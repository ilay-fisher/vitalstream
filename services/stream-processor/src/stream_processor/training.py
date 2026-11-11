"""Offline training of the ML_ANOMALY model with Spark ML KMeans.

Pipeline: seeded synthetic normal windows -> standardise -> KMeans for k in a range -> pick k by
silhouette -> threshold = 99th percentile of training distances to the nearest centroid.
"""

from __future__ import annotations

import argparse
import os
from collections.abc import Sequence
from pathlib import Path
from typing import TYPE_CHECKING

from stream_processor.anomaly import (
    AnomalyModel,
    distance_to_nearest,
    fit_standardizer,
    percentile,
)
from stream_processor.config import Settings
from stream_processor.logging_setup import configure_logging, get_logger
from stream_processor.synthetic import generate_normal_windows

if TYPE_CHECKING:
    from pyspark.sql import SparkSession

MODEL_FILE = "model.json"
DEFAULT_ROWS = 5000
DEFAULT_SEED = 42
K_RANGE = range(2, 7)


def train_model(
    spark: SparkSession,
    rows: Sequence[Sequence[float]],
    k_values: Sequence[int] = tuple(K_RANGE),
    seed: int = DEFAULT_SEED,
    spark_model_dir: Path | None = None,
) -> AnomalyModel:
    """Fit KMeans over `rows`, choosing k by silhouette, and return the scoring model."""
    from pyspark.ml.clustering import KMeans
    from pyspark.ml.evaluation import ClusteringEvaluator
    from pyspark.ml.linalg import Vectors

    standardizer = fit_standardizer(rows)
    scaled = [standardizer.transform(r) for r in rows]
    df = spark.createDataFrame([(Vectors.dense(v),) for v in scaled], ["features"]).cache()
    evaluator = ClusteringEvaluator(metricName="silhouette", distanceMeasure="squaredEuclidean")

    best = None
    for k in k_values:
        fitted = KMeans(k=k, seed=seed, maxIter=50, featuresCol="features").fit(df)
        score = float(evaluator.evaluate(fitted.transform(df)))
        get_logger().info("kmeans candidate", k=k, silhouette=round(score, 4))
        if best is None or score > best[0]:
            best = (score, k, fitted)
    if best is None:
        raise ValueError("k_values must not be empty")
    silhouette, k, fitted = best

    centroids = [[float(x) for x in c] for c in fitted.clusterCenters()]
    distances = [distance_to_nearest(v, centroids) for v in scaled]
    if spark_model_dir is not None:
        fitted.write().overwrite().save(str(spark_model_dir))
    df.unpersist()
    return AnomalyModel(
        standardizer=standardizer,
        centroids=centroids,
        threshold=percentile(distances, 99),
        k=k,
        silhouette=silhouette,
        training_rows=len(rows),
    )


def train_and_save(
    spark: SparkSession, model_dir: str, rows: int = DEFAULT_ROWS, seed: int = DEFAULT_SEED
) -> AnomalyModel:
    """Train on synthetic data and persist `model.json` (+ the Spark ML model) under `model_dir`."""
    target = Path(model_dir)
    model = train_model(
        spark,
        generate_normal_windows(rows, seed),
        seed=seed,
        spark_model_dir=target / "spark-kmeans",
    )
    model.save(target / MODEL_FILE)
    get_logger().info(
        "anomaly model saved",
        path=str(target / MODEL_FILE),
        k=model.k,
        silhouette=round(model.silhouette, 4),
        threshold=round(model.threshold, 4),
        trainingRows=model.training_rows,
    )
    return model


def ensure_model(spark: SparkSession, model_dir: str) -> AnomalyModel:
    """Load the saved model, training one first if it is missing."""
    existing = AnomalyModel.load(Path(model_dir) / MODEL_FILE)
    if existing is not None:
        get_logger().info("anomaly model loaded", path=model_dir, k=existing.k)
        return existing
    get_logger().warning("anomaly model missing, training at startup", path=model_dir)
    return train_and_save(spark, model_dir)


def main(argv: Sequence[str] | None = None) -> None:
    """CLI entrypoint: `train-anomaly-model --output-dir DIR [--rows N] [--seed S]`."""
    from pyspark.sql import SparkSession

    settings = Settings.from_env(os.environ)
    parser = argparse.ArgumentParser(description="Train the ML_ANOMALY KMeans model")
    parser.add_argument("--output-dir", default=settings.model_dir)
    parser.add_argument("--rows", type=int, default=DEFAULT_ROWS)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args(argv)
    configure_logging(settings.service_name, settings.log_level)
    spark = (
        SparkSession.builder.master(settings.spark_master)
        .appName("train-anomaly-model")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    try:
        train_and_save(spark, args.output_dir, args.rows, args.seed)
    finally:
        spark.stop()
