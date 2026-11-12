# Trivy ignore policy for the stream-processor image (TRIVY_IGNORE_POLICY in ci.yml / release.yml).
# Only exact CVE IDs, each time-boxed. Once the date passes the rule stops matching and the scan fails again.
package trivy

import rego.v1

default ignore := false

# Reason: jars bundled inside the apache/spark 3.5.8 base image / hadoop-aws; not patchable without moving Spark.
# Review: 2026-12-15 (renew only if Spark has no 3.5.x release containing the fixed library).
# EXPIRES 2026-12-15T00:00:00Z
ignore if {
	time.now_ns() < 1797292800000000000
	input.VulnerabilityID in {
		"CVE-2026-75595", # io.netty:netty-handler 4.1.77/4.1.96 in aws-java-sdk-bundle and spark jars
		"CVE-2024-47561", # org.apache.avro:avro 1.7.7 in hadoop-client-runtime
		"CVE-2022-46337", # org.apache.derby:derby 10.14.2.0 (Spark's Hive metastore support)
		"CVE-2023-44981", # org.apache.zookeeper:zookeeper 3.6.3 (hadoop client)
	}
}
