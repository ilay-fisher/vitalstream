from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from stream_processor.anomaly import FEATURE_ORDER, AnomalyModel, feature_vector
from stream_processor.synthetic import generate_normal_windows
from stream_processor.training import ensure_model, train_and_save, train_model
from stream_processor.vitals import Metric

pytestmark = pytest.mark.spark


def test_train_model_picks_k_and_threshold(spark: SparkSession) -> None:
    rows = generate_normal_windows(600, seed=1)
    model = train_model(spark, rows, k_values=(2, 3), seed=1)
    assert model.k in (2, 3)
    assert len(model.centroids) == model.k
    assert -1.0 <= model.silhouette <= 1.0
    flagged = sum(model.is_anomalous(r) for r in rows)
    assert flagged <= 0.02 * len(rows)  # ~1 % by construction of the 99th percentile
    assert flagged >= 1


def test_model_flags_clinically_abnormal_window_but_not_normal(spark: SparkSession) -> None:
    model = train_model(spark, generate_normal_windows(600, seed=2), k_values=(2,), seed=2)
    normal = feature_vector(dict(zip(FEATURE_ORDER, [72, 98, 16, 120, 75, 36.8], strict=True)))
    sick = feature_vector(
        {
            Metric.HEART_RATE: 130.0,
            Metric.SPO2: 88.0,
            Metric.RESP_RATE: 28.0,
            Metric.SYSTOLIC_BP: 95.0,
            Metric.DIASTOLIC_BP: 55.0,
            Metric.TEMPERATURE: 38.9,
        }
    )
    assert normal is not None and sick is not None
    assert not model.is_anomalous(normal)
    assert model.is_anomalous(sick)


def test_train_and_save_then_ensure_model_loads_without_retraining(
    spark: SparkSession, tmp_path: Path
) -> None:
    trained = train_and_save(spark, str(tmp_path), rows=300, seed=3)
    assert (tmp_path / "model.json").exists()
    assert (tmp_path / "spark-kmeans").exists()
    assert ensure_model(spark, str(tmp_path)) == trained


def test_ensure_model_trains_when_missing(spark: SparkSession, tmp_path: Path) -> None:
    target = tmp_path / "fresh"
    model = ensure_model(spark, str(target))
    assert AnomalyModel.load(target / "model.json") == model


def test_train_model_requires_k_values(spark: SparkSession) -> None:
    with pytest.raises(ValueError, match="k_values"):
        train_model(spark, generate_normal_windows(50, seed=1), k_values=())
