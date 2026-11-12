"""Identities for calling clinical-api (CONTRACTS §4.2).

* Chat: the end user's own token (:class:`UserTokenProvider`), so clinical-api enforces that user's
  access and the copilot is never a confused deputy.
* ``/mcp`` (no end user): a ``SERVICE`` token. ``CLINICAL_API_TOKEN`` when set (production); in
  ``dev`` only, one for ``sub`` ``copilot-mcp`` from the dev token endpoint, renewed shortly before
  it expires. The SERVICE identity is used by the MCP tools only.

Tokens are never logged.
"""

import asyncio
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import httpx
import structlog

from copilot.clinical import ClinicalApiError, TokenProvider
from copilot.config import Settings
from copilot.user_context import get_user_token

log = structlog.get_logger()

#: A token is renewed this long before it expires, so a request never carries a dying token.
REFRESH_MARGIN = timedelta(seconds=60)
_DEV_TOKEN_PATH = "/api/v1/dev/token"
_SERVICE_IDENTITY = {"sub": "copilot-mcp", "name": "Copilot MCP", "roles": ["SERVICE"]}


class StaticTokenProvider:
    """A fixed token from ``CLINICAL_API_TOKEN``; rotating it means restarting the service."""

    def __init__(self, token: str) -> None:
        self._token = token

    async def token(self) -> str:
        """The configured token."""
        return self._token

    def invalidate(self) -> None:
        """Nothing to refresh: a static token cannot be renewed."""


class UserTokenProvider:
    """The end user's token of the chat request in progress (see :mod:`copilot.user_context`).

    Fails closed: with no user token in context there is no fallback to a service identity, so a
    tool call outside an authenticated chat cannot read anything.
    """

    async def token(self) -> str:
        """The current user's token."""
        token = get_user_token()
        if not token:
            raise ClinicalApiError("no user token in the request context")
        return token

    def invalidate(self) -> None:
        """Nothing to refresh: the user's own token cannot be renewed by the copilot."""


class DevTokenProvider:
    """Fetches and caches a ``SERVICE`` token from clinical-api's dev endpoint (dev only)."""

    def __init__(
        self,
        http: httpx.AsyncClient,
        base_url: str,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._http = http
        self._url = base_url.rstrip("/") + _DEV_TOKEN_PATH
        self._clock = clock
        self._token: str | None = None
        self._expires_at = datetime.min.replace(tzinfo=UTC)
        self._lock = asyncio.Lock()

    async def token(self) -> str:
        """The cached token, or a new one when it is missing or within the refresh margin."""
        if (cached := self._valid_token()) is not None:
            return cached
        async with self._lock:
            if (cached := self._valid_token()) is not None:
                return cached
            return await self._fetch()

    def invalidate(self) -> None:
        """Drop the cached token."""
        self._token = None

    def _valid_token(self) -> str | None:
        if self._token is not None and self._clock() < self._expires_at - REFRESH_MARGIN:
            return self._token
        return None

    async def _fetch(self) -> str:
        try:
            response = await self._http.post(self._url, json=_SERVICE_IDENTITY)
        except httpx.HTTPError as exc:
            raise ClinicalApiError("clinical-api unavailable") from exc
        if response.status_code == 404:
            raise ClinicalApiError("clinical-api has no dev token endpoint; set CLINICAL_API_TOKEN")
        if response.status_code >= 400:
            raise ClinicalApiError(
                f"clinical-api token request failed (HTTP {response.status_code})"
            )
        try:
            body = response.json()
            token = str(body["token"])
            expires_at = datetime.fromisoformat(str(body["expiresAt"]).replace("Z", "+00:00"))
        except (ValueError, KeyError, TypeError) as exc:
            raise ClinicalApiError("clinical-api returned an unusable token response") from exc
        self._token, self._expires_at = token, expires_at
        log.info("clinical_api_token_issued", expiresAt=expires_at.isoformat())
        return token


def build_token_provider(settings: Settings, http: httpx.AsyncClient) -> TokenProvider:
    """``CLINICAL_API_TOKEN`` if set, else the dev endpoint in dev, else a startup error."""
    if settings.clinical_api_token is not None and settings.clinical_api_token.get_secret_value():
        return StaticTokenProvider(settings.clinical_api_token.get_secret_value())
    if settings.app_env == "dev":
        return DevTokenProvider(http, settings.clinical_api_url)
    raise ValueError(f"APP_ENV={settings.app_env}: CLINICAL_API_TOKEN is required")
