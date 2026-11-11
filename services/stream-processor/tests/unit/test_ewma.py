import math

import pytest

from stream_processor.ewma import (
    EwmaConfig,
    EwmaState,
    TrendHit,
    scan_series,
    update,
    z_score,
)

CFG = EwmaConfig(alpha=0.1, z_threshold=3.0, min_samples=10, min_rel_std=0.02, min_abs_std=0.1)


def test_first_sample_initialises_state() -> None:
    state = update(None, 100.0, 1_000, alpha=0.1)
    assert state == EwmaState(mean=100.0, var=0.0, n=1, last_ts=1_000)


def test_second_sample_follows_incremental_formula() -> None:
    state = update(None, 100.0, 1, alpha=0.1)
    state = update(state, 110.0, 2, alpha=0.1)
    # diff = 10, incr = 1 -> mean 101, var = 0.9 * (0 + 10 * 1) = 9
    assert state.mean == pytest.approx(101.0)
    assert state.var == pytest.approx(9.0)
    assert state.n == 2
    assert state.last_ts == 2


def test_constant_series_keeps_zero_variance() -> None:
    state: EwmaState | None = None
    for ts in range(50):
        state = update(state, 72.0, ts, alpha=0.1)
    assert state is not None
    assert state.mean == pytest.approx(72.0)
    assert state.var == pytest.approx(0.0, abs=1e-12)


def test_incremental_matches_batch_definition() -> None:
    """EWMA mean = sum w_i x_i; variance via Finch's recursion, checked against a direct loop."""
    xs = [70.0, 72.0, 71.0, 75.0, 69.0, 80.0, 74.0]
    alpha = 0.1
    mean, var = xs[0], 0.0
    for x in xs[1:]:
        diff = x - mean
        incr = alpha * diff
        mean += incr
        var = (1 - alpha) * (var + diff * incr)
    state: EwmaState | None = None
    for i, x in enumerate(xs):
        state = update(state, x, i, alpha=alpha)
    assert state is not None
    assert state.mean == pytest.approx(mean)
    assert state.var == pytest.approx(var)


def test_z_score_none_until_min_samples() -> None:
    state = EwmaState(mean=70.0, var=4.0, n=9, last_ts=0)
    assert z_score(state, 100.0, CFG) is None
    assert z_score(None, 100.0, CFG) is None


def test_z_score_basic() -> None:
    state = EwmaState(mean=70.0, var=4.0, n=50, last_ts=0)
    assert z_score(state, 76.0, CFG) == pytest.approx(3.0)
    assert z_score(state, 64.0, CFG) == pytest.approx(-3.0)


def test_z_score_uses_std_floor_for_flat_signal() -> None:
    state = EwmaState(mean=98.0, var=0.0, n=50, last_ts=0)
    # floor = max(0.02 * 98, 0.1) = 1.96
    assert z_score(state, 98.0 - 3 * 1.96, CFG) == pytest.approx(-3.0)


def test_z_score_floor_uses_absolute_minimum_near_zero_mean() -> None:
    state = EwmaState(mean=0.0, var=0.0, n=50, last_ts=0)
    assert z_score(state, 0.3, CFG) == pytest.approx(3.0)


def test_scan_series_flags_sudden_jump_and_updates_state() -> None:
    state: EwmaState | None = None
    series = [(i, 72.0 + (i % 3) * 0.5) for i in range(30)]
    state, hits = scan_series(state, series, CFG)
    assert hits == []
    state, hits = scan_series(state, [(100, 120.0)], CFG)
    assert len(hits) == 1
    hit = hits[0]
    assert isinstance(hit, TrendHit)
    assert hit.ts == 100
    assert hit.value == 120.0
    assert hit.z > 3.0
    assert state.n == 31


def test_scan_series_flags_drop_with_negative_z() -> None:
    series = [(i, 98.0 + (i % 2) * 0.4) for i in range(30)]
    state, _ = scan_series(None, series, CFG)
    _, hits = scan_series(state, [(100, 88.0)], CFG)
    assert [h.z < -3 for h in hits] == [True]


def test_scan_series_is_idempotent_for_replayed_samples() -> None:
    series = [(i, 72.0 + (i % 3)) for i in range(30)]
    state, _ = scan_series(None, series, CFG)
    replay_state, hits = scan_series(state, series, CFG)
    assert replay_state == state
    assert hits == []


def test_scan_series_sorts_samples_and_skips_stale_ones() -> None:
    state, _ = scan_series(None, [(5, 70.0), (3, 71.0), (4, 69.0)], CFG)
    assert state.last_ts == 5
    assert state.n == 3


def test_scan_series_empty_keeps_state() -> None:
    state = EwmaState(mean=1.0, var=1.0, n=20, last_ts=9)
    new_state, hits = scan_series(state, [], CFG)
    assert new_state == state
    assert hits == []


def test_scan_series_none_state_and_empty_series_creates_nothing() -> None:
    new_state, hits = scan_series(None, [], CFG)
    assert new_state is None
    assert hits == []


def test_warmup_suppresses_hits() -> None:
    series = [(0, 70.0), (1, 200.0)]
    _, hits = scan_series(None, series, CFG)
    assert hits == []


def test_slow_ramp_eventually_trips_when_slope_outpaces_variance() -> None:
    series = [(i, 72.0 + (i % 3) * 0.5) for i in range(40)]
    state, _ = scan_series(None, series, CFG)
    ramp = [(100 + i, 74.0 + 2.5 * i) for i in range(20)]
    _, hits = scan_series(state, ramp, CFG)
    assert hits, "a steep monotonic ramp must trip the z-score at some point"
    assert all(math.isfinite(h.z) for h in hits)


def test_state_hash_roundtrip() -> None:
    state = EwmaState(mean=71.5, var=2.25, n=12, last_ts=1_700_000_000_123)
    assert EwmaState.from_hash(state.to_hash()) == state


def test_from_hash_missing_returns_none() -> None:
    assert EwmaState.from_hash({}) is None


def test_from_hash_without_ts_defaults_to_zero() -> None:
    state = EwmaState.from_hash({"mean": "1.5", "var": "0.5", "n": "3"})
    assert state == EwmaState(mean=1.5, var=0.5, n=3, last_ts=0)
