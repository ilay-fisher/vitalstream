import json
import random
from pathlib import Path

import pytest

from stream_processor.anomaly import (
    FEATURE_ORDER,
    AnomalyModel,
    Standardizer,
    distance_to_nearest,
    feature_vector,
    fit_standardizer,
    percentile,
)
from stream_processor.vitals import Metric

MEANS = {
    Metric.HEART_RATE: 72.0,
    Metric.SPO2: 98.0,
    Metric.RESP_RATE: 16.0,
    Metric.SYSTOLIC_BP: 120.0,
    Metric.DIASTOLIC_BP: 75.0,
    Metric.TEMPERATURE: 36.8,
}


def test_feature_order_covers_all_metrics_once() -> None:
    assert sorted(FEATURE_ORDER, key=str) == sorted(Metric, key=str)
    assert len(set(FEATURE_ORDER)) == 6


def test_feature_vector_follows_feature_order() -> None:
    vec = feature_vector(MEANS)
    assert vec == [MEANS[m] for m in FEATURE_ORDER]


def test_feature_vector_none_when_metric_missing() -> None:
    partial = dict(MEANS)
    del partial[Metric.SPO2]
    assert feature_vector(partial) is None


def test_feature_vector_none_when_value_none() -> None:
    with_none: dict[Metric, float | None] = {**MEANS, Metric.HEART_RATE: None}
    assert feature_vector(with_none) is None


def test_fit_standardizer_computes_mean_and_population_std() -> None:
    rows = [[1.0, 10.0], [3.0, 10.0], [5.0, 10.0]]
    std = fit_standardizer(rows)
    assert std.means == pytest.approx([3.0, 10.0])
    assert std.stds[0] == pytest.approx((8 / 3) ** 0.5)
    assert std.stds[1] == 1.0  # zero variance column falls back to 1 to avoid division by zero


def test_standardizer_transform() -> None:
    std = Standardizer(means=[10.0, 0.0], stds=[2.0, 4.0])
    assert std.transform([14.0, -4.0]) == [2.0, -1.0]


def test_fit_standardizer_rejects_empty_input() -> None:
    with pytest.raises(ValueError, match="at least one"):
        fit_standardizer([])


def test_distance_to_nearest_picks_closest_centroid() -> None:
    centroids = [[0.0, 0.0], [10.0, 0.0]]
    assert distance_to_nearest([3.0, 4.0], centroids) == pytest.approx(5.0)
    assert distance_to_nearest([9.0, 0.0], centroids) == pytest.approx(1.0)


def test_distance_to_nearest_requires_centroids() -> None:
    with pytest.raises(ValueError, match="centroid"):
        distance_to_nearest([1.0], [])


@pytest.mark.parametrize(
    ("q", "expected"),
    [(0, 1.0), (50, 3.0), (100, 5.0), (25, 2.0), (75, 4.0), (99, 4.96)],
)
def test_percentile_linear_interpolation(q: float, expected: float) -> None:
    assert percentile([5.0, 1.0, 3.0, 2.0, 4.0], q) == pytest.approx(expected)


def test_percentile_single_value_and_validation() -> None:
    assert percentile([7.0], 99) == 7.0
    with pytest.raises(ValueError, match="empty"):
        percentile([], 50)
    with pytest.raises(ValueError, match="between"):
        percentile([1.0], 101)


def _model() -> AnomalyModel:
    std = fit_standardizer([[1.0, 1.0], [-1.0, -1.0], [1.0, -1.0], [-1.0, 1.0]])
    return AnomalyModel(
        standardizer=std,
        centroids=[[0.0, 0.0]],
        threshold=2.0,
        k=1,
        silhouette=0.0,
        training_rows=4,
    )


def test_model_score_is_distance_in_standardized_space() -> None:
    assert _model().score([3.0, 4.0]) == pytest.approx(5.0)


def test_model_flags_only_above_threshold() -> None:
    model = _model()
    assert model.is_anomalous([1.0, 1.0]) is False
    assert model.is_anomalous([2.0, 0.0]) is False  # exactly at threshold: not above
    assert model.is_anomalous([2.1, 0.0]) is True


def test_model_json_roundtrip() -> None:
    model = _model()
    restored = AnomalyModel.from_json(model.to_json())
    assert restored == model
    assert json.loads(model.to_json())["threshold"] == 2.0


def test_model_save_and_load(tmp_path: Path) -> None:
    model = _model()
    path = tmp_path / "nested" / "model.json"
    model.save(path)
    assert AnomalyModel.load(path) == model


def test_model_load_missing_returns_none(tmp_path: Path) -> None:
    assert AnomalyModel.load(tmp_path / "nope.json") is None


def test_model_rejects_dimension_mismatch() -> None:
    with pytest.raises(ValueError, match="dimension"):
        _model().score([1.0, 2.0, 3.0])


def test_random_normal_windows_mostly_inside_percentile_threshold() -> None:
    rng = random.Random(1)
    rows = [[rng.gauss(0, 1), rng.gauss(0, 1)] for _ in range(2000)]
    std = fit_standardizer(rows)
    centroids = [[0.0, 0.0]]
    dists = [distance_to_nearest(std.transform(r), centroids) for r in rows]
    threshold = percentile(dists, 99)
    flagged = sum(d > threshold for d in dists)
    assert flagged == pytest.approx(len(rows) * 0.01, abs=2)
