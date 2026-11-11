import os
import sys
import time
from collections.abc import Iterator

import pytest

os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
os.environ["TZ"] = "UTC"  # Spark timestamps surface as naive local datetimes in Python
time.tzset()


@pytest.fixture(scope="session")
def spark() -> Iterator["SparkSession"]:  # noqa: F821
    from pyspark.sql import SparkSession

    session = (
        SparkSession.builder.master("local[2]")
        .appName("stream-processor-tests")
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.ui.enabled", "false")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()
