#!/usr/bin/env bash
# Installs all required OLM operators for the banking demo.
# Run with --role hub on the onprem cluster, --role spoke on the cloud cluster.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OPERATORS_DIR="${REPO_ROOT}/infra/operators"
export KUBECONFIG="${KUBECONFIG:-${REPO_ROOT}/kubeconfig-onprem:${REPO_ROOT}/kubeconfig-cloud}"

# ── Helpers ────────────────────────────────────────────────────────────────
log()  { printf '\n\033[1;34m=== %s ===\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m  %s\n' "$*"; }
err()  { printf '  \033[1;31m✗\033[0m  %s\n' "$*" >&2; }

wait_for_csv() {
  # wait_for_csv <context> <csv-prefix> <max_seconds>
  local ctx=$1 prefix=$2 max=$3 elapsed=0
  printf '    waiting for %s' "$prefix"
  until oc --context "$ctx" get csv -A \
      -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
      2>/dev/null \
    | grep -E "^${prefix}[.\-]" \
    | grep -q "Succeeded"; do
    printf '.'
    sleep 15
    elapsed=$(( elapsed + 15 ))
    if (( elapsed >= max )); then
      printf ' TIMEOUT\n'
      return 1
    fi
  done
  local csv_name
  csv_name=$(oc --context "$ctx" get csv -A \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
    2>/dev/null | grep -E "^${prefix}[.\-]" | grep "Succeeded" | cut -f1 | head -1)
  printf ' %s\n' "$csv_name"
}

already_succeeded() {
  # already_succeeded <context> <csv-prefix>
  # Returns 0 and prints CSV name if a Succeeded CSV exists; 1 otherwise.
  local ctx=$1 prefix=$2
  local csv_name
  csv_name=$(oc --context "$ctx" get csv -A \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
    2>/dev/null | grep -E "^${prefix}[.\-]" | grep "Succeeded" | cut -f1 | head -1 || true)
  if [[ -n "$csv_name" ]]; then
    printf '%s\n' "$csv_name"
    return 0
  fi
  return 1
}

# ── Argument parsing ───────────────────────────────────────────────────────
ROLE=""
CTX=""

usage() {
  printf 'Usage: %s --role hub|spoke [--context <name>]\n\n' "$(basename "$0")"
  printf '  --role hub    Install all 9 operators (RHACM + GitOps + 7 shared)\n'
  printf '  --role spoke  Install 7 shared operators only (skip RHACM + GitOps)\n'
  printf '  --context     Override oc context (default: onprem for hub, cloud for spoke)\n'
  exit 1
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --role)    ROLE=$2;    shift 2 ;;
    --context) CTX=$2;     shift 2 ;;
    -h|--help) usage ;;
    *) printf 'Unknown argument: %s\n' "$1"; usage ;;
  esac
done

[[ -z "$ROLE" ]] && { printf 'ERROR: --role is required\n\n'; usage; }
[[ "$ROLE" != "hub" && "$ROLE" != "spoke" ]] && \
  { printf 'ERROR: --role must be "hub" or "spoke"\n\n'; usage; }

[[ -z "$CTX" ]] && CTX=$( [[ "$ROLE" == "hub" ]] && echo "onprem" || echo "cloud" )

# ── Operator lists ─────────────────────────────────────────────────────────
# Parallel arrays: index N in PREFIXES corresponds to index N in MANIFESTS.
# Associative arrays (declare -A) require bash 4+; macOS ships bash 3.2.
SHARED_PREFIXES=(
  cert-manager
  custom-metrics-autoscaler
  rhacs-operator
  amqstreams
  postgresoperator
  openshift-pipelines-operator-rh
  skupper-operator
)
SHARED_MANIFESTS=(
  cert-manager.yaml
  custom-metrics-autoscaler.yaml
  rhacs.yaml
  amq-streams.yaml
  crunchy-postgres.yaml
  pipelines.yaml
  skupper.yaml
)

HUB_PREFIXES=(
  advanced-cluster-management
  openshift-gitops-operator
)
HUB_MANIFESTS=(
  rhacm.yaml
  gitops.yaml
)

# ── Pre-flight ─────────────────────────────────────────────────────────────
log "Pre-flight: checking cluster access (context: ${CTX}, role: ${ROLE})"
if ! oc --context "$CTX" cluster-info &>/dev/null; then
  err "Cannot reach cluster context '${CTX}'"
  exit 1
fi
ok "Cluster reachable"

# ── Ensure operators (apply only if not already Succeeded) ─────────────────
log "Ensuring shared operators"
any_failed=0
MAX=900  # 15 minutes per operator

for i in "${!SHARED_PREFIXES[@]}"; do
  prefix="${SHARED_PREFIXES[$i]}"
  manifest="${OPERATORS_DIR}/${SHARED_MANIFESTS[$i]}"
  if csv_name=$(already_succeeded "$CTX" "$prefix"); then
    ok "${prefix} (${csv_name})"
  else
    oc --context "$CTX" apply -f "$manifest" &>/dev/null
    wait_for_csv "$CTX" "$prefix" $MAX || { err "${prefix} timed out"; any_failed=1; }
  fi
done

if [[ "$ROLE" == "hub" ]]; then
  log "Ensuring hub-only operators"
  for i in "${!HUB_PREFIXES[@]}"; do
    prefix="${HUB_PREFIXES[$i]}"
    manifest="${OPERATORS_DIR}/${HUB_MANIFESTS[$i]}"
    if csv_name=$(already_succeeded "$CTX" "$prefix"); then
      ok "${prefix} (${csv_name})"
    else
      oc --context "$CTX" apply -f "$manifest" &>/dev/null
      wait_for_csv "$CTX" "$prefix" $MAX || { err "${prefix} timed out"; any_failed=1; }
    fi
  done
fi

# ── Result ─────────────────────────────────────────────────────────────────
printf '\n'
if (( any_failed )); then
  err "One or more operators did not reach Succeeded — run operator-check.sh for details"
  exit 1
fi
printf '\033[1;32m=== All operators installed successfully ===\033[0m\n\n'
printf 'Verify with:\n'
printf '  ./scripts/operator-check.sh\n'
