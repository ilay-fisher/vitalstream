#!/usr/bin/env bash
# Deletes the kind cluster (and with it all infra and app state).
set -euo pipefail
kind delete cluster --name "${CLUSTER:-vitalstream-platform}"
