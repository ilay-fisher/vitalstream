import httpx
import pytest
import respx

from vitalstream_e2e.auth import (
    DR_COHEN,
    NURSE_LEVI,
    OBSERVER,
    TokenError,
    bearer,
    fetch_token,
    token_request,
)

BASE = "http://clinical.test"


def test_demo_users_are_the_contract_users():
    assert (NURSE_LEVI.sub, NURSE_LEVI.name, NURSE_LEVI.roles) == (
        "nurse.levi",
        "Nurse Levi",
        ("CLINICIAN",),
    )
    assert (DR_COHEN.sub, DR_COHEN.name, DR_COHEN.roles) == (
        "dr.cohen",
        "Dr. Cohen",
        ("CLINICIAN",),
    )
    assert (OBSERVER.sub, OBSERVER.name, OBSERVER.roles) == (
        "observer",
        "Observer",
        ("VIEWER",),
    )


def test_token_request_is_the_dev_endpoint_body():
    assert token_request(NURSE_LEVI) == {
        "sub": "nurse.levi",
        "name": "Nurse Levi",
        "roles": ["CLINICIAN"],
    }


def test_bearer_header():
    assert bearer("abc") == {"Authorization": "Bearer abc"}


@respx.mock
def test_fetch_token_posts_to_the_dev_endpoint_and_returns_the_token():
    route = respx.post(f"{BASE}/api/v1/dev/token").mock(
        return_value=httpx.Response(200, json={"token": "t-1", "expiresAt": "2026-11-12T16:00:00Z"})
    )

    token = fetch_token(BASE, OBSERVER)

    assert token == "t-1"
    assert route.calls.last.request.read() == (
        b'{"sub":"observer","name":"Observer","roles":["VIEWER"]}'
    )


@respx.mock
@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(404),
        httpx.Response(500),
        httpx.Response(200, json={"nope": 1}),
    ],
)
def test_fetch_token_explains_failures(response):
    respx.post(f"{BASE}/api/v1/dev/token").mock(return_value=response)

    with pytest.raises(TokenError, match="dev token"):
        fetch_token(BASE, NURSE_LEVI)
