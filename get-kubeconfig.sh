#!/usr/bin/env bash
# Exports the current oc login credentials to the per-cluster kubeconfig file.
# Usage: get-kubeconfig.sh onprem|cloud
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "${1:-}" in
  onprem) OUT="${SCRIPT_DIR}/kubeconfig-onprem" ;;
  cloud)  OUT="${SCRIPT_DIR}/kubeconfig-cloud"  ;;
  *)
    printf 'Usage: %s onprem|cloud\n' "$(basename "$0")" >&2
    exit 1
    ;;
esac

oc config view --flatten --minify > "${OUT}"
printf 'Wrote kubeconfig to %s\n' "${OUT}"
