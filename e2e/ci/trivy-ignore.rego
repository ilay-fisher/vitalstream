# Trivy ignore policy for the CI image scan (TRIVY_IGNORE_POLICY in .github/workflows/ci.yml and release.yml).
#
# The gate is "no CRITICAL with a fix". These findings are CRITICAL with a fix but sit in base images
# and bundled third-party jars owned by other components, so this workstream cannot patch them.
# Every entry is time-boxed: after EXPIRES the policy stops matching and the scan fails again, which
# forces the owning component to patch (or to renew the exception with a reason).
package trivy

import rego.v1

# 2026-12-15T00:00:00Z
EXPIRES := 1797292800000000000

default ignore := false

active if time.now_ns() < EXPIRES

# stream-processor (Ubuntu base of apache/spark): kernel *headers*, never a running kernel in a container.
# Owner fix: `apt-get purge -y linux-libc-dev` in services/stream-processor/Dockerfile.
ignore if {
	active
	input.PkgName == "linux-libc-dev"
}

# stream-processor: jars shipped inside Spark 3.5.8 / hadoop-aws, upgraded only by moving Spark.
ignore if {
	active
	input.VulnerabilityID in {"CVE-2026-75595", "CVE-2024-47561", "CVE-2022-46337", "CVE-2023-44981"}
}

# dashboard (nginx-unprivileged:1.27-alpine, alpine 3.21.3): fixed by `apk upgrade` in
# web/dashboard/Dockerfile or a newer base tag.
ignore if {
	active
	input.PkgName in {"brotli-libs", "libcrypto3", "libssl3"}
}
