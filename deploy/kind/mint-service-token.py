#!/usr/bin/env python3
"""Mints the HS256 SERVICE token the copilot's /mcp tools use against clinical-api (CONTRACTS 4.2).

Used by up.sh for the kind cluster only. The signing secret and the issuer come from the
environment (JWT_SECRET, JWT_ISSUER), never from arguments, so they do not show up in `ps`. The
token goes to stdout: capture it in a variable and never echo it.

Claims: iss, aud clinical-api, sub copilot-mcp, roles [SERVICE], iat, exp (30 days).
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time

AUDIENCE = "clinical-api"
SUBJECT = "copilot-mcp"
TTL_SECONDS = 30 * 24 * 3600
MIN_SECRET_BYTES = 32


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint(secret: str, issuer: str, now: int | None = None) -> str:
    """HS256 JWT for the copilot-mcp SERVICE identity."""
    if len(secret.encode()) < MIN_SECRET_BYTES:
        raise ValueError(f"the JWT secret must be at least {MIN_SECRET_BYTES} bytes")
    issued = int(time.time()) if now is None else now
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": issuer,
        "aud": [AUDIENCE],
        "sub": SUBJECT,
        "roles": ["SERVICE"],
        "iat": issued,
        "exp": issued + TTL_SECONDS,
    }
    signing_input = ".".join(
        _b64(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims)
    )
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{_b64(signature)}"


def main() -> int:
    secret = os.environ.get("JWT_SECRET", "")
    if not secret:
        print("JWT_SECRET is not set", file=sys.stderr)
        return 2
    try:
        print(mint(secret, os.environ.get("JWT_ISSUER") or "vitalstream-dev"))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
