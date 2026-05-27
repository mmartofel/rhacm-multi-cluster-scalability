#!/usr/bin/env bash
# Verifies required OLM operators are installed (CSV exists + Succeeded) on both clusters.
# Exit 1 if any operator is missing or degraded.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-${REPO_ROOT}/kubeconfig-onprem:${REPO_ROOT}/kubeconfig-cloud}"

CONTEXTS=(onprem cloud)

# Operators installed on every cluster (hub + spoke)
SHARED_OPS=(
  amqstreams
  cert-manager
  custom-metrics-autoscaler
  openshift-pipelines-operator-rh
  postgresoperator
  rhacs-operator
  skupper-operator
)

# Operators installed on the hub (onprem) only
HUB_OPS=(
  advanced-cluster-management
  openshift-gitops-operator
)

any_missing=0

for ctx in "${CONTEXTS[@]}"; do
  printf '\n=== Context: %s ===\n' "$ctx"

  if ! oc --context "$ctx" cluster-info &>/dev/null; then
    printf '  ERROR   cannot reach cluster — skipping context\n'
    any_missing=1
    continue
  fi

  # Fetch all CSV names + phases across every namespace in one call.
  # Output format (tab-separated): <csv-name>\t<phase>
  csv_data=$(
    oc --context "$ctx" get csv -A \
      -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
      2>/dev/null || true
  )

  # Hub context gets all operators; spoke contexts get shared only
  if [[ "$ctx" == "onprem" ]]; then
    ops=("${SHARED_OPS[@]}" "${HUB_OPS[@]}")
  else
    ops=("${SHARED_OPS[@]}")
  fi

  for op in "${ops[@]}"; do
    # Match CSVs whose name starts with the operator prefix (e.g. "amqstreams.v3.2.0")
    match=$(printf '%s\n' "$csv_data" | grep -E "^${op}[.\-]" | head -1 || true)

    if [[ -z "$match" ]]; then
      printf '  MISSING  %s\n' "$op"
      any_missing=1
    else
      phase=$(printf '%s\n' "$match" | cut -f2)
      if [[ "$phase" == "Succeeded" ]]; then
        csv_name=$(printf '%s\n' "$match" | cut -f1)
        printf '  OK       %-45s  (%s)\n' "$op" "$csv_name"
      else
        printf '  DEGRADED %s  (phase: %s)\n' "$op" "$phase"
        any_missing=1
      fi
    fi
  done
done

printf '\n'
if (( any_missing )); then
  printf 'RESULT: one or more operators missing or degraded — resolve before Phase 1\n'
  exit 1
fi
printf 'RESULT: all operators OK on both contexts\n'
