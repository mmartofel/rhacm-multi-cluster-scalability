#!/usr/bin/env bash
# Phase 0 bootstrap: operator validation, RHACM hub, managed-cluster import,
# GitOps readiness, pull secrets, namespaces, and cert-manager ClusterIssuer.
# Requires: QUAY_USER and QUAY_TOKEN env vars; kubeconfig with 'onprem' + 'cloud' contexts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ONPREM=onprem
CLOUD=cloud
NAMESPACES=(banking-demo banking-infra banking-monitoring)
export KUBECONFIG="${KUBECONFIG:-${REPO_ROOT}/kubeconfig-onprem:${REPO_ROOT}/kubeconfig-cloud}"

# ── Validate environment ────────────────────────────────────────────────────
: "${QUAY_USER:?QUAY_USER must be exported before running this script}"
: "${QUAY_TOKEN:?QUAY_TOKEN must be exported before running this script}"

log()  { printf '\n\033[1;34m=== %s ===\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m  %s\n' "$*"; }
wait_for() {
  # wait_for <description> <max_seconds> <check_command...>
  local desc=$1 max=$2; shift 2
  local elapsed=0
  until "$@" &>/dev/null; do
    printf '.'
    sleep 10
    elapsed=$(( elapsed + 10 ))
    if (( elapsed >= max )); then
      printf '\nERROR: timed out waiting for %s\n' "$desc" >&2
      exit 1
    fi
  done
  printf ' %s ready\n' "$desc"
}

# ── Step a: Operator check ──────────────────────────────────────────────────
log "Step a: Operator check"
"${SCRIPT_DIR}/operator-check.sh"
ok "All operators present on both contexts"

# ── Step b: RHACM MultiClusterHub on onprem ────────────────────────────────
log "Step b: MultiClusterHub (context: ${ONPREM})"
oc --context "${ONPREM}" apply -f "${REPO_ROOT}/infra/rhacm/multiclusterhub.yaml"

printf 'Waiting for MultiClusterHub phase=Running (up to 20 min)'
wait_for "MultiClusterHub" 1200 \
  bash -c "oc --context ${ONPREM} get multiclusterhub multiclusterhub \
    -n open-cluster-management \
    -o jsonpath='{.status.phase}' 2>/dev/null | grep -q '^Running$'"
ok "MultiClusterHub running"

# ── Step c: Import GCP cluster ─────────────────────────────────────────────
log "Step c: ManagedCluster import (hub: ${ONPREM}, spoke: ${CLOUD})"

if oc --context "${ONPREM}" get managedcluster cloud \
    -o jsonpath='{.status.conditions[?(@.type=="ManagedClusterJoined")].status}' \
    2>/dev/null | grep -q '^True$'; then
  ok "ManagedCluster cloud already joined — skipping import"
else
  # Pre-create the managed-cluster namespace on the hub so ManagedCluster and
  # KlusterletAddonConfig can be applied in a single oc call.
  oc --context "${ONPREM}" create namespace cloud --dry-run=client -o yaml \
    | oc --context "${ONPREM}" apply -f -

  oc --context "${ONPREM}" apply -f "${REPO_ROOT}/infra/rhacm/managedcluster-cloud.yaml"
  ok "ManagedCluster + KlusterletAddonConfig applied"

  # Wait for RHACM to generate the import secret (named '<cluster>-import' in the cluster namespace)
  printf 'Waiting for import secret cloud/cloud-import'
  wait_for "import secret" 300 \
    oc --context "${ONPREM}" get secret cloud-import -n cloud

  # Apply CRDs to the spoke cluster first, then the klusterlet import manifest.
  printf 'Applying import CRDs to spoke cluster... '
  oc --context "${ONPREM}" get secret cloud-import -n cloud \
    -o jsonpath='{.data.crds\.yaml}' | base64 -d \
    | oc --context "${CLOUD}" apply -f -
  ok "CRDs applied to ${CLOUD}"

  printf 'Applying klusterlet import manifest to spoke cluster... '
  oc --context "${ONPREM}" get secret cloud-import -n cloud \
    -o jsonpath='{.data.import\.yaml}' | base64 -d \
    | oc --context "${CLOUD}" apply -f -
  ok "Import manifest applied to ${CLOUD}"

  printf 'Waiting for ManagedCluster cloud to join hub'
  wait_for "ManagedCluster join" 600 \
    bash -c "oc --context ${ONPREM} get managedcluster cloud \
      -o jsonpath='{.status.conditions[?(@.type==\"ManagedClusterJoined\")].status}' \
      2>/dev/null | grep -q '^True$'"
  ok "Cluster '${CLOUD}' joined hub"
fi

# ── Step d: OpenShift GitOps readiness ─────────────────────────────────────
log "Step d: OpenShift GitOps (context: ${ONPREM})"
# The openshift-gitops-operator auto-creates an ArgoCD instance; wait for it.
printf 'Waiting for openshift-gitops-server deployment'
wait_for "openshift-gitops-server" 600 \
  oc --context "${ONPREM}" rollout status deployment/openshift-gitops-server \
    -n openshift-gitops --timeout=10s
ok "ArgoCD (openshift-gitops-server) ready"

log "Step e: Namespaces on both contexts"
for ctx in "${ONPREM}" "${CLOUD}"; do
  for ns in "${NAMESPACES[@]}"; do
    oc --context "${ctx}" create namespace "${ns}" \
      --dry-run=client -o yaml | oc --context "${ctx}" apply -f -
    ok "${ctx}/${ns}"
  done
done

log "Step f: Quay.io pull secret on both contexts"
for ctx in "${ONPREM}" "${CLOUD}"; do
  for ns in "${NAMESPACES[@]}"; do
    oc --context "${ctx}" create secret docker-registry quay-pull-secret \
      --docker-server=quay.io \
      --docker-username="${QUAY_USER}" \
      --docker-password="${QUAY_TOKEN}" \
      --namespace "${ns}" \
      --dry-run=client -o yaml \
      | oc --context "${ctx}" apply -f -
    ok "${ctx}/${ns}/quay-pull-secret"
  done
done

# ── Step g: cert-manager ClusterIssuer ────────────────────────────────────
log "Step g: cert-manager ClusterIssuer on both contexts"
for ctx in "${ONPREM}" "${CLOUD}"; do
  oc --context "${ctx}" apply -f "${REPO_ROOT}/infra/cert-manager/cluster-issuer.yaml"
  ok "${ctx}/ClusterIssuer demo-issuer"
done

# ── Done ───────────────────────────────────────────────────────────────────
printf '\n\033[1;32m=== Phase 0 complete ===\033[0m\n\n'
printf 'Verify:\n'
printf '  oc --context onprem get multiclusterhub -n open-cluster-management\n'
printf '  oc --context onprem get managedcluster cloud\n'
printf '  oc --context onprem get argocd -n openshift-gitops\n'
printf '  oc --context onprem get clusterissuer demo-issuer\n'
printf '  oc --context cloud   get clusterissuer demo-issuer\n'
