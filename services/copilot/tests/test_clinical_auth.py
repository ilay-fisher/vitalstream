"""clinical-api authentication (CONTRACTS §4.2): service token sourcing, refresh and use."""

import asyncio
import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx
from pydantic import SecretStr

from copilot.app import create_app
from copilot.clinical import ClinicalApiClient, ClinicalApiError
from copilot.clinical_auth import (
    REFRESH_MARGIN,
    DevTokenProvider,
    StaticTokenProvider,
    UserTokenProvider,
    build_token_provider,
)
from copilot.config import Settings
from copilot.user_context import set_user_token

BASE = "http://clinical.test"
TOKEN_URL = f"{BASE}/api/v1/dev/token"
STRONG = "x" * 32
PG = f"postgresql://vitalstream:{STRONG}@db:5432/vitalstream"
JWT = "eyJhbGciOiJIUzI1NiJ9." + "a" * 40 + "." + "b" * 20


class Clock:
    """A settable clock so expiry can be crossed without sleeping."""

    def __init__(self) -> None:
        self.now = datetime(2026, 11, 12, 8, 0, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now


def issued(token: str, clock: Clock, ttl: timedelta = timedelta(hours=8)) -> httpx.Response:
    expires = (clock.now + ttl).isoformat().replace("+00:00", "Z")
    return httpx.Response(200, json={"token": token, "expiresAt": expires})


def prod(**overrides: object) -> Settings:
    base: dict[str, object] = {
        "app_env": "prod",
        "copilot_admin_token": SecretStr(STRONG),
        "mcp_token": SecretStr(STRONG + "1"),
        "log_pseudonym_key": SecretStr(STRONG + "2"),
        "postgres_url": PG,
        "clinical_api_token": SecretStr(JWT),
    }
    return Settings(**{**base, **overrides})  # type: ignore[arg-type]


# ---- static token ------------------------------------------------------------------------------


async def test_static_provider_returns_the_configured_token() -> None:
    provider = StaticTokenProvider("tok-1")
    assert await provider.token() == "tok-1"
    provider.invalidate()
    assert await provider.token() == "tok-1"


# ---- dev token provider ------------------------------------------------------------------------


async def test_dev_provider_requests_a_service_token_for_copilot_mcp() -> None:
    clock = Clock()
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            route = router.post(TOKEN_URL).mock(return_value=issued("dev-tok", clock))
            provider = DevTokenProvider(http, BASE, clock=clock)
            token = await provider.token()
    assert token == "dev-tok"
    body = json.loads(route.calls.last.request.content)
    assert body == {"sub": "copilot-mcp", "name": "Copilot MCP", "roles": ["SERVICE"]}


async def test_dev_provider_caches_until_sixty_seconds_before_expiry() -> None:
    clock = Clock()
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            route = router.post(TOKEN_URL).mock(
                side_effect=[issued("t1", clock), issued("t2", clock)]
            )
            provider = DevTokenProvider(http, BASE, clock=clock)
            assert await provider.token() == "t1"
            clock.now += timedelta(hours=8) - REFRESH_MARGIN - timedelta(seconds=1)
            assert await provider.token() == "t1"
            assert route.call_count == 1
            clock.now += timedelta(seconds=2)
            assert await provider.token() == "t2"
            assert route.call_count == 2


def test_refresh_margin_is_sixty_seconds() -> None:
    assert timedelta(seconds=60) == REFRESH_MARGIN


async def test_dev_provider_fetches_once_for_concurrent_callers() -> None:
    clock = Clock()
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            route = router.post(TOKEN_URL).mock(return_value=issued("t1", clock))
            provider = DevTokenProvider(http, BASE, clock=clock)
            tokens = await asyncio.gather(*(provider.token() for _ in range(5)))
    assert tokens == ["t1"] * 5
    assert route.call_count == 1


async def test_invalidate_forces_a_new_token() -> None:
    clock = Clock()
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            router.post(TOKEN_URL).mock(side_effect=[issued("t1", clock), issued("t2", clock)])
            provider = DevTokenProvider(http, BASE, clock=clock)
            assert await provider.token() == "t1"
            provider.invalidate()
            assert await provider.token() == "t2"


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(404, json={"status": 404}),
        httpx.Response(500),
        httpx.Response(200, json={"nope": 1}),
        httpx.Response(200, json={"token": "t", "expiresAt": "garbage"}),
        httpx.Response(200, content=b"not json"),
    ],
)
async def test_dev_provider_failures_become_clinical_api_errors(response: httpx.Response) -> None:
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            router.post(TOKEN_URL).mock(return_value=response)
            with pytest.raises(ClinicalApiError):
                await DevTokenProvider(http, BASE, clock=Clock()).token()


async def test_dev_provider_reports_unreachable_clinical_api() -> None:
    async with httpx.AsyncClient() as http:
        with respx.mock(assert_all_called=False) as router:
            router.post(TOKEN_URL).mock(side_effect=httpx.ConnectError("down"))
            with pytest.raises(ClinicalApiError, match="unavailable"):
                await DevTokenProvider(http, BASE, clock=Clock()).token()


# ---- client use of the token -------------------------------------------------------------------


async def test_client_sends_bearer_on_vitals_and_alerts() -> None:
    client = ClinicalApiClient(BASE, StaticTokenProvider("svc-tok"), timeout=2.0)
    with respx.mock(assert_all_called=False) as router:
        vitals = router.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(
            return_value=httpx.Response(200, json=[])
        )
        alerts = router.get(f"{BASE}/api/v1/alerts").mock(return_value=httpx.Response(200, json=[]))
        await client.vitals("P-0001", 30)
        await client.alerts("P-0001", None)
    await client.aclose()
    assert vitals.calls.last.request.headers["Authorization"] == "Bearer svc-tok"
    assert alerts.calls.last.request.headers["Authorization"] == "Bearer svc-tok"


async def test_client_refreshes_the_token_once_after_a_401() -> None:
    clock = Clock()
    http = httpx.AsyncClient(base_url=BASE)
    provider = DevTokenProvider(http, BASE, clock=clock)
    client = ClinicalApiClient(BASE, provider, timeout=2.0, http_client=http)
    with respx.mock(assert_all_called=False) as router:
        router.post(TOKEN_URL).mock(side_effect=[issued("stale", clock), issued("fresh", clock)])
        route = router.get(f"{BASE}/api/v1/alerts").mock(
            side_effect=lambda request: httpx.Response(
                200 if request.headers["Authorization"] == "Bearer fresh" else 401,
                json=[] if request.headers["Authorization"] == "Bearer fresh" else {},
            )
        )
        assert await client.alerts(None, None) == []
    await client.aclose()
    assert route.call_count == 2


async def test_client_gives_up_after_a_second_401_without_leaking_the_token(
    capsys: pytest.CaptureFixture[str],
) -> None:
    client = ClinicalApiClient(BASE, StaticTokenProvider("super-secret-token"), timeout=2.0)
    with respx.mock(assert_all_called=False) as router:
        route = router.get(f"{BASE}/api/v1/alerts").mock(return_value=httpx.Response(401))
        with pytest.raises(ClinicalApiError, match="401") as error:
            await client.alerts(None, None)
    await client.aclose()
    assert route.call_count == 2
    assert "super-secret-token" not in str(error.value)
    assert "super-secret-token" not in capsys.readouterr().out


async def test_client_reports_a_403_as_unavailable() -> None:
    client = ClinicalApiClient(BASE, StaticTokenProvider("t"), timeout=2.0)
    with respx.mock(assert_all_called=False) as router:
        router.get(f"{BASE}/api/v1/alerts").mock(return_value=httpx.Response(403))
        with pytest.raises(ClinicalApiError, match="403"):
            await client.alerts(None, None)
    await client.aclose()


# ---- configuration -----------------------------------------------------------------------------


async def test_explicit_token_wins_over_the_dev_endpoint() -> None:
    async with httpx.AsyncClient() as http:
        provider = build_token_provider(
            Settings(clinical_api_token=SecretStr("explicit")),
            http,  # type: ignore[arg-type]
        )
    assert isinstance(provider, StaticTokenProvider)
    assert await provider.token() == "explicit"


async def test_dev_without_a_token_fetches_from_the_dev_endpoint() -> None:
    async with httpx.AsyncClient() as http:
        provider = build_token_provider(Settings(), http)
    assert isinstance(provider, DevTokenProvider)


async def test_outside_dev_without_a_token_there_is_no_provider() -> None:
    async with httpx.AsyncClient() as http:
        with pytest.raises(ValueError, match="CLINICAL_API_TOKEN"):
            build_token_provider(prod(clinical_api_token=None), http)


def test_dev_does_not_need_a_token() -> None:
    Settings().validate_secrets()


def test_prod_with_a_token_passes_the_guard() -> None:
    prod().validate_secrets()


@pytest.mark.parametrize("token", [None, SecretStr(""), SecretStr("short")])
def test_prod_without_a_usable_token_fails_startup(token: SecretStr | None) -> None:
    with pytest.raises(ValueError, match="CLINICAL_API_TOKEN") as error:
        prod(clinical_api_token=token).validate_secrets()
    assert "short" not in str(error.value)


def test_create_app_refuses_prod_without_clinical_api_token() -> None:
    with pytest.raises(ValueError, match="CLINICAL_API_TOKEN"):
        create_app(prod(clinical_api_token=None))


# ---- user token (chat) -------------------------------------------------------------------------


async def test_user_provider_returns_the_token_of_the_current_request() -> None:
    set_user_token("tok-user")
    try:
        assert await UserTokenProvider().token() == "tok-user"
    finally:
        set_user_token(None)


async def test_user_provider_refuses_to_run_without_a_user_token() -> None:
    set_user_token(None)
    with pytest.raises(ClinicalApiError, match="no user token"):
        await UserTokenProvider().token()


async def test_client_me_returns_the_identity_and_reports_the_status() -> None:
    client = ClinicalApiClient(BASE, UserTokenProvider(), timeout=2.0)
    with respx.mock(assert_all_called=False) as router:
        route = router.get(f"{BASE}/api/v1/me")
        route.mock(
            return_value=httpx.Response(
                200, json={"sub": "observer", "name": "O", "roles": ["VIEWER"]}
            )
        )
        assert (await client.me("tok-1"))["sub"] == "observer"
        assert route.calls.last.request.headers["Authorization"] == "Bearer tok-1"
        route.mock(return_value=httpx.Response(403))
        with pytest.raises(ClinicalApiError) as denied:
            await client.me("tok-1")
        assert denied.value.status == 403
        route.mock(return_value=httpx.Response(200, json=["not", "an", "object"]))
        with pytest.raises(ClinicalApiError):
            await client.me("tok-1")
        route.mock(side_effect=httpx.ConnectError("down"))
        with pytest.raises(ClinicalApiError) as down:
            await client.me("tok-1")
        assert down.value.status is None
    await client.aclose()
