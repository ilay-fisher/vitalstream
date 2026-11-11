"""Pseudonymous patient references for logs (CONTRACTS §7): never log the raw patient id."""

import hashlib
import hmac


def patient_ref(patient_id: str, key: str) -> str:
    """First 12 hex chars of HMAC-SHA256(patient_id, key); stable across services/languages."""
    return hmac.new(key.encode(), patient_id.encode(), hashlib.sha256).hexdigest()[:12]
