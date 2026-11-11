import statistics

from stream_processor.anomaly import FEATURE_ORDER
from stream_processor.synthetic import generate_normal_windows
from stream_processor.vitals import SPECS


def test_same_seed_same_data() -> None:
    assert generate_normal_windows(50, seed=7) == generate_normal_windows(50, seed=7)


def test_different_seed_different_data() -> None:
    assert generate_normal_windows(50, seed=7) != generate_normal_windows(50, seed=8)


def test_shape_and_count() -> None:
    rows = generate_normal_windows(120, seed=1)
    assert len(rows) == 120
    assert all(len(r) == len(FEATURE_ORDER) for r in rows)


def test_every_window_is_inside_the_normal_range() -> None:
    for row in generate_normal_windows(500, seed=3):
        for metric, value in zip(FEATURE_ORDER, row, strict=True):
            spec = SPECS[metric]
            assert spec.normal_min <= value <= spec.normal_max


def test_values_are_centred_in_the_normal_range() -> None:
    rows = generate_normal_windows(1000, seed=5)
    hr = statistics.mean(r[0] for r in rows)
    assert 65 < hr < 80
