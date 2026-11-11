from stream_processor.privacy import patient_ref


def test_pinned_cross_service_test_vector() -> None:
    assert patient_ref("P-0001", "dev-only-pseudonym-key") == "26043831372c"


def test_ref_is_12_hex_chars_and_key_dependent() -> None:
    ref = patient_ref("P-0001", "other-key")
    assert len(ref) == 12 and int(ref, 16) >= 0
    assert ref != patient_ref("P-0001", "dev-only-pseudonym-key")
    assert patient_ref("P-0002", "other-key") != ref
