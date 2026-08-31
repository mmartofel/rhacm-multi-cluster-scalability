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
NAME="$1"

oc config view --flatten --minify > "${OUT}"

# Rename the current context to 'onprem'/'cloud' so scripts can target it
# with `oc --context onprem|cloud` regardless of the cluster's real name.
CURRENT_CTX="$(oc --kubeconfig "${OUT}" config current-context)"
oc --kubeconfig "${OUT}" config rename-context "${CURRENT_CTX}" "${NAME}" > /dev/null

printf 'Wrote kubeconfig to %s (context renamed to %s)\n' "${OUT}" "${NAME}"
