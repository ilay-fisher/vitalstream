# syntax=docker/dockerfile:1
# Stage 1: fetch connector jars at build time so the runtime image never downloads anything.
FROM curlimages/curl:8.11.1 AS jars
ARG SPARK_VERSION=3.5.8
ARG MAVEN=https://repo1.maven.org/maven2
WORKDIR /jars
RUN set -eux; \
    fetch() { curl -fsSL --retry 3 -O "$MAVEN/$1"; }; \
    fetch org/apache/spark/spark-sql-kafka-0-10_2.12/${SPARK_VERSION}/spark-sql-kafka-0-10_2.12-${SPARK_VERSION}.jar; \
    fetch org/apache/spark/spark-token-provider-kafka-0-10_2.12/${SPARK_VERSION}/spark-token-provider-kafka-0-10_2.12-${SPARK_VERSION}.jar; \
    fetch org/apache/kafka/kafka-clients/3.4.1/kafka-clients-3.4.1.jar; \
    fetch org/apache/commons/commons-pool2/2.11.1/commons-pool2-2.11.1.jar; \
    fetch com/datastax/spark/spark-cassandra-connector-assembly_2.12/3.5.1/spark-cassandra-connector-assembly_2.12-3.5.1.jar; \
    fetch org/apache/hadoop/hadoop-aws/3.3.4/hadoop-aws-3.3.4.jar; \
    fetch com/amazonaws/aws-java-sdk-bundle/1.12.262/aws-java-sdk-bundle-1.12.262.jar

# Stage 2: runtime on the official Spark image (Spark 3.5.8, Java 17, Python 3).
FROM apache/spark:3.5.8-scala2.12-java17-python3-ubuntu
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-pip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jars /jars/*.jar /opt/spark/jars/

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip3 install --no-cache-dir "numpy>=1.26,<2.3" "structlog>=24.4" "redis>=5.0" "prometheus-client>=0.20" \
    && pip3 install --no-cache-dir --no-deps .

ENV TZ=UTC \
    SPARK_PACKAGES="" \
    PYSPARK_PYTHON=/usr/bin/python3 \
    PYTHONPATH=/opt/spark/python:/opt/spark/python/lib/py4j-0.10.9.7-src.zip \
    CHECKPOINT_DIR=/data/checkpoints \
    ANOMALY_MODEL_DIR=/data/models/anomaly \
    METRICS_PORT=9404
RUN mkdir -p /data && chown -R spark:spark /data /app
VOLUME /data
USER spark
EXPOSE 4040 9404
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9404/health', timeout=3).status == 200 else 1)"
ENTRYPOINT ["python3", "-m", "stream_processor.main"]
