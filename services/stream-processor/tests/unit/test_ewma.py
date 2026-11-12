import pytest

from stream_processor.ewma import EwmaConfig, EwmaState, TrendHit, score_window, z_score

CFG = EwmaConfig()
MIN = 60_000  # one window in epoch milliseconds


def _state(mean: float = 75.0, var: float = 1.0, n: int = 20, ts: int = 0, hist: str = "") -> EwmaState:
    return EwmaState(mean=mean, var=var, n=n, last_ts=ts, hist=hist)


def test_defaults_match_contract() -> None:
    assert CFG.alpha == 0.2
    assert CFG.z_threshold == 3.0
    assert CFG.warmup_windows == 5
    assert (CFG.persistence_required, CFG.persistence_windows) == (3, 5)


def test_first_window_seeds_state_without_a_hit() -> None:
    state, hit = score_window(None, 80.0, MIN, min_delta=10.0, cfg=CFG)
    assert state == EwmaState(mean=80.0, var=0.0, n=1, last_ts=MIN, hist=".")
    assert hit is None


def test_fold_follows_incremental_formula() -> None:
    state, _ = score_window(_state(mean=100.0, var=0.0, n=2), 110.0, MIN, 10.0, CFG)
    # diff = 10, incr = 2 -> mean 102; var = 0.8 * (0 + 10 * 2) = 16 (warm-up windows always fold)
    assert state.mean == pytest.approx(102.0)
    assert state.var == pytest.approx(16.0)
    assert state.n == 3


def test_z_score_none_during_warmup() -> None:
    assert z_score(_state(n=4), 100.0, CFG) is None
    assert z_score(None, 100.0, CFG) is None
    assert z_score(_state(n=5), 100.0, CFG) is not None


def test_z_score_basic_and_std_floor() -> None:
    assert z_score(_state(mean=70.0, var=4.0), 76.0, CFG) == pytest.approx(3.0)
    # flat signal: std floor = max(0.02 * 98, 0.1) = 1.96
    assert z_score(_state(mean=98.0, var=0.0), 98.0 - 3 * 1.96, CFG) == pytest.approx(-3.0)
    # near-zero mean falls back to the absolute floor
    assert z_score(_state(mean=0.0, var=0.0), 0.3, CFG) == pytest.approx(3.0)


def test_no_hit_during_warmup_even_for_a_huge_jump() -> None:
    state = _state(n=4)
    new_state, hit = score_window(state, 200.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None
    assert new_state.hist == "."


def test_breach_needs_both_z_and_clinical_delta() -> None:
    # std floor 1.5: +6 is z = 4 but below the clinical delta of 10 -> not a breach
    state, hit = score_window(_state(), 81.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None and state.hist == "."
    # +12 clears both gates, but a single breach is not persistent
    state, hit = score_window(_state(), 87.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None and state.hist == "+"
    # large delta but the signal is noisy: z < 3 -> not a breach
    state, hit = score_window(_state(var=100.0), 90.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None and state.hist == "."


def test_breach_direction_is_recorded() -> None:
    state, _ = score_window(_state(), 60.0, MIN, min_delta=10.0, cfg=CFG)
    assert state.hist == "-"


def test_hist_keeps_only_last_five_windows() -> None:
    state, _ = score_window(_state(hist="+.-.+"), 75.0, MIN, 10.0, CFG)
    assert state.hist == ".-.+."


def test_three_same_direction_breaches_in_five_emit_a_hit() -> None:
    state = _state(hist="+.+")
    new_state, hit = score_window(state, 90.0, MIN, min_delta=10.0, cfg=CFG)
    assert new_state.hist == "+.++"
    assert isinstance(hit, TrendHit)
    assert hit.rising is True and hit.direction == "rising"
    assert hit.breaches == 3
    assert hit.trend_mean == 75.0
    assert hit.value == 90.0
    assert hit.delta == pytest.approx(15.0)
    assert hit.z == pytest.approx(10.0)


def test_mixed_directions_do_not_count_together() -> None:
    _, hit = score_window(_state(hist="+-"), 90.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None


def test_falling_hit_has_negative_delta() -> None:
    _, hit = score_window(_state(hist="--"), 60.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is not None
    assert hit.direction == "falling" and hit.delta < 0 and hit.z < 0


def test_old_breaches_outside_the_last_five_do_not_count() -> None:
    _, hit = score_window(_state(hist="++..."), 90.0, MIN, min_delta=10.0, cfg=CFG)
    assert hit is None


def test_breach_windows_do_not_inflate_the_variance() -> None:
    """Else a real shift would raise its own std and mask the persistence it needs."""
    state, _ = score_window(_state(var=1.0), 90.0, MIN, min_delta=10.0, cfg=CFG)
    assert state.var == 1.0
    assert state.mean == pytest.approx(75.0 + 0.2 * 15.0)


def test_non_breach_windows_update_the_variance() -> None:
    state, _ = score_window(_state(var=1.0), 76.0, MIN, min_delta=10.0, cfg=CFG)
    assert state.var == pytest.approx(0.8 * (1.0 + 1.0 * 0.2))


def test_replayed_window_is_ignored() -> None:
    state, _ = score_window(_state(ts=5 * MIN), 90.0, 5 * MIN, min_delta=10.0, cfg=CFG)
    assert state == _state(ts=5 * MIN)
    older, hit = score_window(_state(ts=5 * MIN), 90.0, 4 * MIN, min_delta=10.0, cfg=CFG)
    assert older == _state(ts=5 * MIN) and hit is None


def test_state_ts_is_last_applied_window_start() -> None:
    state, _ = score_window(_state(ts=MIN), 75.0, 2 * MIN, 10.0, CFG)
    assert state.last_ts == 2 * MIN


def test_hash_roundtrip_includes_hist() -> None:
    state = _state(mean=71.5, var=2.25, n=12, ts=1_700_000_000_000, hist="+.+-.")
    data = state.to_hash()
    assert data["hist"] == "+.+-."
    assert EwmaState.from_hash(data) == state


def test_empty_hist_roundtrips() -> None:
    state = _state(hist="")
    assert EwmaState.from_hash(state.to_hash()) == state


def test_from_hash_missing_key_is_none() -> None:
    assert EwmaState.from_hash({}) is None


def test_v1_hash_without_hist_resets_state() -> None:
    v1 = {"mean": "71.5", "var": "0.5", "n": "300", "ts": "1700000000123"}
    assert EwmaState.from_hash(v1) is None
