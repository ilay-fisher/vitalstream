"""Exponentially weighted mean/variance and the TREND_ZSCORE detector.

State per patient+metric is three numbers plus the timestamp of the last sample folded in. The
timestamp makes `scan_series` idempotent: when Spark replays a micro-batch after a failure the
samples at or before `last_ts` are skipped instead of being folded in twice.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class EwmaConfig:
    """Detector parameters (CONTRACTS 3.3: alpha 0.1, |z| >= 3)."""

    alpha: float = 0.1
    z_threshold: float = 3.0
    min_samples: int = 10
    min_rel_std: float = 0.02
    min_abs_std: float = 0.1


@dataclass(frozen=True)
class EwmaState:
    """Mean, variance, sample count and last folded-in timestamp (epoch milliseconds)."""

    mean: float
    var: float
    n: int
    last_ts: int

    def to_hash(self) -> dict[str, str]:
        """Serialise for a Redis hash (`mean`, `var`, `n` per CONTRACTS plus `ts`)."""
        return {
            "mean": repr(self.mean),
            "var": repr(self.var),
            "n": str(self.n),
            "ts": str(self.last_ts),
        }

    @classmethod
    def from_hash(cls, data: Mapping[str, str]) -> EwmaState | None:
        """Parse a Redis hash; None when the key does not exist yet."""
        if not data:
            return None
        return cls(
            mean=float(data["mean"]),
            var=float(data["var"]),
            n=int(data["n"]),
            last_ts=int(data.get("ts", 0)),
        )


@dataclass(frozen=True)
class TrendHit:
    """A sample whose z-score crossed the threshold."""

    ts: int
    value: float
    z: float


def update(state: EwmaState | None, x: float, ts: int, alpha: float) -> EwmaState:
    """Fold one sample into the state using the incremental EW mean/variance recursion.

    With diff = x - mean and incr = alpha * diff:
        mean' = mean + incr
        var'  = (1 - alpha) * (var + diff * incr)
    (Finch, "Incremental calculation of weighted mean and variance"). The first sample seeds the
    mean with zero variance.
    """
    if state is None:
        return EwmaState(mean=x, var=0.0, n=1, last_ts=ts)
    diff = x - state.mean
    incr = alpha * diff
    return EwmaState(
        mean=state.mean + incr,
        var=(1.0 - alpha) * (state.var + diff * incr),
        n=state.n + 1,
        last_ts=ts,
    )


def z_score(state: EwmaState | None, x: float, cfg: EwmaConfig) -> float | None:
    """Return (x - mean) / std against the *prior* state, or None while warming up.

    The standard deviation is floored at max(min_rel_std * |mean|, min_abs_std): a perfectly flat
    signal has zero variance, which would make the first tiny wiggle look infinitely anomalous.
    """
    if state is None or state.n < cfg.min_samples:
        return None
    std = max(math.sqrt(max(state.var, 0.0)), cfg.min_rel_std * abs(state.mean), cfg.min_abs_std)
    return (x - state.mean) / std


def scan_series(
    state: EwmaState | None,
    samples: Iterable[tuple[int, float]],
    cfg: EwmaConfig,
) -> tuple[EwmaState | None, list[TrendHit]]:
    """Process (timestamp, value) samples in time order, returning new state and threshold hits.

    Samples with a timestamp at or before `state.last_ts` are ignored (replay / out-of-order
    protection). Each remaining sample is scored against the state *before* it is folded in, so an
    outlier is judged against history rather than against itself.
    """
    hits: list[TrendHit] = []
    for ts, value in sorted(samples):
        if state is not None and ts <= state.last_ts:
            continue
        z = z_score(state, value, cfg)
        if z is not None and abs(z) >= cfg.z_threshold:
            hits.append(TrendHit(ts=ts, value=value, z=z))
        state = update(state, value, ts, cfg.alpha)
    return state, hits
