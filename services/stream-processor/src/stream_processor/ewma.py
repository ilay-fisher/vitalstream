"""EWMA over 1-minute window means and the TREND_ZSCORE v2 decision (CONTRACTS 3.3 / 5).

Why windows and persistence: scoring every 1 Hz sample ran ~60 tests per metric per minute, so
noise alone crossed |z| >= 3 several times an hour. A closed window mean averages that noise away,
the clinical-delta gate drops statistically significant but clinically meaningless shifts, and
requiring 3 of the last 5 windows to breach in the same direction drops one-minute artefacts.

State per patient+metric: EW mean/variance over window means, the number of windows folded in, the
start of the last applied window (`last_ts`, makes replays idempotent) and `hist`, the outcome of
the last windows as `+` (breach up), `-` (breach down) or `.` (none), newest last.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class EwmaConfig:
    """Detector parameters (CONTRACTS 3.3: alpha 0.2, |z| >= 3, warm-up 5, 3 of 5 windows)."""

    alpha: float = 0.2
    z_threshold: float = 3.0
    warmup_windows: int = 5
    persistence_windows: int = 5
    persistence_required: int = 3
    min_rel_std: float = 0.02
    min_abs_std: float = 0.1


@dataclass(frozen=True)
class EwmaState:
    """Window-mean EWMA state; `last_ts` is the last applied windowStart (epoch milliseconds)."""

    mean: float
    var: float
    n: int
    last_ts: int
    hist: str

    def to_hash(self) -> dict[str, str]:
        """Serialise for the Redis hash `mean,var,n,ts,hist` (CONTRACTS 5)."""
        return {
            "mean": repr(self.mean),
            "var": repr(self.var),
            "n": str(self.n),
            "ts": str(self.last_ts),
            "hist": self.hist,
        }

    @classmethod
    def from_hash(cls, data: Mapping[str, str]) -> EwmaState | None:
        """Parse a Redis hash; None when absent or a v1 raw-sample hash (no `hist`) to reset."""
        if not data or "hist" not in data:
            return None
        return cls(
            mean=float(data["mean"]),
            var=float(data["var"]),
            n=int(data["n"]),
            last_ts=int(data["ts"]),
            hist=data["hist"],
        )


@dataclass(frozen=True)
class TrendHit:
    """A window that completed a persistent trend: what to say about it in the alert."""

    value: float
    trend_mean: float
    z: float
    delta: float
    breaches: int
    windows: int = 5

    @property
    def rising(self) -> bool:
        """True when the window mean is above the trend."""
        return self.delta > 0

    @property
    def direction(self) -> str:
        """`rising` or `falling`."""
        return "rising" if self.rising else "falling"


def z_score(state: EwmaState | None, x: float, cfg: EwmaConfig) -> float | None:
    """Return (x - mean) / std against the *prior* state, or None while warming up.

    The standard deviation is floored at max(min_rel_std * |mean|, min_abs_std): a perfectly flat
    signal has zero variance, which would make the first tiny wiggle look infinitely anomalous.
    """
    if state is None or state.n < cfg.warmup_windows:
        return None
    std = max(math.sqrt(max(state.var, 0.0)), cfg.min_rel_std * abs(state.mean), cfg.min_abs_std)
    return (x - state.mean) / std


def score_window(
    state: EwmaState | None,
    window_mean: float,
    window_ts: int,
    min_delta: float,
    cfg: EwmaConfig,
) -> tuple[EwmaState, TrendHit | None]:
    """Score one closed window against the state *before* it, then fold it in.

    A window at or before `state.last_ts` is a replay and changes nothing. A window is a breach
    when |z| >= z_threshold and |mean - trend| >= min_delta; a hit needs `persistence_required`
    breaches in the same direction among the last `persistence_windows` (this one included).

    Windows with |z| >= z_threshold move the mean but not the variance. Folding a real shift into
    the variance inflates the std within one or two windows, so z falls back under the threshold
    and the persistence rule could never be met (a +20 bpm step yields at most two breaches).
    """
    if state is None:
        return EwmaState(mean=window_mean, var=0.0, n=1, last_ts=window_ts, hist="."), None
    if window_ts <= state.last_ts:
        return state, None
    z = z_score(state, window_mean, cfg)
    delta = window_mean - state.mean
    outcome = "."
    if z is not None and abs(z) >= cfg.z_threshold and abs(delta) >= min_delta:
        outcome = "+" if z > 0 else "-"
    hist = (state.hist + outcome)[-cfg.persistence_windows :]
    hit = None
    if z is not None and outcome != "." and hist.count(outcome) >= cfg.persistence_required:
        hit = TrendHit(
            value=window_mean,
            trend_mean=state.mean,
            z=z,
            delta=delta,
            breaches=hist.count(outcome),
            windows=cfg.persistence_windows,
        )
    return _fold(state, window_mean, window_ts, hist, z, cfg), hit


def _fold(
    state: EwmaState,
    x: float,
    ts: int,
    hist: str,
    z: float | None,
    cfg: EwmaConfig,
) -> EwmaState:
    """Incremental EW mean/variance (Finch); the variance skips |z| >= z_threshold windows.

    With diff = x - mean and incr = alpha * diff:  mean' = mean + incr,
    var' = (1 - alpha) * (var + diff * incr).
    """
    diff = x - state.mean
    incr = cfg.alpha * diff
    outlier = z is not None and abs(z) >= cfg.z_threshold
    var = state.var if outlier else (1.0 - cfg.alpha) * (state.var + diff * incr)
    return EwmaState(mean=state.mean + incr, var=var, n=state.n + 1, last_ts=ts, hist=hist)
