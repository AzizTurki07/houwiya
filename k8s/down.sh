#!/usr/bin/env bash
# Deletes the local kind cluster, with everything in it (including the database volume).
set -euo pipefail
kind delete cluster --name "${CLUSTER:-houwiya}"
