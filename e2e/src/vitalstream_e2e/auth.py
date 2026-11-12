"""Bearer tokens for clinical-api from its dev token endpoint (CONTRACTS section 4.2).

The compose stack runs with ``APP_ENV=dev``, so ``POST /api/v1/dev/token`` hands out HS256 tokens
for the three demo users; no secret is needed in the test code.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx


@dataclass(frozen=True)
class DemoUser:
    """A demo identity of the dashboard sign-in screen."""

    sub: str
    name: str
    roles: tuple[str, ...]


NURSE_LEVI = DemoUser("nurse.levi", "Nurse Levi", ("CLINICIAN",))
DR_COHEN = DemoUser("dr.cohen", "Dr. Cohen", ("CLINICIAN",))
OBSERVER = DemoUser("observer", "Observer", ("VIEWER",))


class TokenError(Exception):
    """The dev token endpoint did not issue a token."""


def token_request(user: DemoUser) -> dict[str, Any]:
    """Body of ``POST /api/v1/dev/token`` for ``user``."""
    return {"sub": user.sub, "name": user.name, "roles": list(user.roles)}


def bearer(token: str) -> dict[str, str]:
    """``Authorization`` header carrying ``token``."""
    return {"Authorization": f"Bearer {token}"}


def fetch_token(base_url: str, user: DemoUser) -> str:
    """Ask clinical-api's dev endpoint for a token for ``user``."""
    response = httpx.post(f"{base_url}/api/v1/dev/token", json=token_request(user), timeout=10)
    if response.status_code != 200:
        raise TokenError(f"dev token request for {user.sub} failed: HTTP {response.status_code}")
    token = response.json().get("token")
    if not isinstance(token, str) or not token:
        raise TokenError(f"dev token response for {user.sub} has no token")
    return token
