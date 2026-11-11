# Trivy ignore policy for this image (TRIVY_IGNORE_POLICY in ci.yml / release.yml).
# Nothing is ignored: any CRITICAL finding with an available fix fails the build.
package trivy

import rego.v1

default ignore := false
