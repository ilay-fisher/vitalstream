"""PHI minimisation: pseudonymous patient references for logs (CONTRACTS 7)."""

from __future__ import annotations

import hashlib
import hmac


def patient_ref(patient_id: str, key: str) -> str:
    """Return the first 12 hex chars of HMAC-SHA256(patientId, key).

    Raw patient ids are medical record identifiers and must never reach the logs; the ref is
    stable per key so log lines of one patient still correlate across services.
    """
    digest = hmac.new(key.encode(), patient_id.encode(), hashlib.sha256).hexdigest()
    return digest[:12]
