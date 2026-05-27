#!/usr/bin/env bash
# Phase 1 bootstrap: Kafka, PostgreSQL, Apicurio Registry, Skupper cross-cluster
# mesh, and MirrorMaker 2. Deploys via Argo CD ApplicationSets (GitOps) plus
# imperative Skupper token exchange.
# Requires: kubeconfig with 'onprem' + 'cloud' contexts; git push of Phase 1
# manifests to the repo before running.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ONPREM=onprem
CLOUD=cloud
INFRA_NS=banking-infra
GITOPS_NS=openshift-gitops

export KUBECONFIG="${KUBECONFIG:-${REPO_ROOT}/kubeconfig-onprem:${REPO_ROOT}/kubeconfig-cloud}"

log()  { printf '\n\033[1;34m=== %s ===\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m  %s\n' "$*"; }
info() { printf '  \033[1;33m→\033[0m  %s\n' "$*"; }
fail() { printf '  \033[1;31m✗\033[0m  %s\n' "$*"; }

wait_for() {
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

wait_for_jsonpath() {
  # wait_for_jsonpath <desc> <max_seconds> <context> <resource> <namespace> <jsonpath> <expected>
  local desc=$1 max=$2 ctx=$3 res=$4 ns=$5 jpath=$6 want=$7
  local elapsed=0
  until [[ "$(oc --context "${ctx}" get ${res} -n "${ns}" \
             -o jsonpath="${jpath}" 2>/dev/null)" == "${want}" ]]; do
    printf '.'
    sleep 10
    elapsed=$(( elapsed + 10 ))
    if (( elapsed >= max )); then
      printf '\nERROR: timed out waiting for %s\n' "$desc" >&2
      exit 1
    fi
  done
  printf ' %s\n' "$desc"
}

# ── Step 1: Register GCP cluster with Argo CD ──────────────────────────────
log "Step 1: Register cloud cluster with Argo CD (RHACM GitOpsCluster)"

# Check if already registered
if oc --context "${ONPREM}" get secret cloud-cluster-secret \
    -n "${GITOPS_NS}" &>/dev/null; then
  ok "cloud cluster already registered with Argo CD — skipping"
else
  # Create a ServiceAccount + token on the cloud cluster for Argo CD
  oc --context "${CLOUD}" create serviceaccount argocd-manager \
    -n kube-system --dry-run=client -o yaml \
    | oc --context "${CLOUD}" apply -f -

  oc --context "${CLOUD}" create clusterrolebinding argocd-manager-binding \
    --clusterrole=cluster-admin \
    --serviceaccount=kube-system:argocd-manager \
    --dry-run=client -o yaml \
    | oc --context "${CLOUD}" apply -f -

  CLOUD_TOKEN=$(oc --context "${CLOUD}" create token argocd-manager \
    -n kube-system --duration=8760h)

  CLOUD_CA=$(oc --context "${CLOUD}" get secret \
    -n kube-system -l kubernetes.io/legacy-token-type=secret \
    --field-selector type=kubernetes.io/service-account-token \
    -o jsonpath='{.items[0].data.ca\.crt}' 2>/dev/null \
    || oc --context "${CLOUD}" config view --raw \
       -o jsonpath='{.clusters[?(@.name=="cloud")].cluster.certificate-authority-data}')

  oc --context "${ONPREM}" apply -n "${GITOPS_NS}" -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: cloud-cluster-secret
  namespace: ${GITOPS_NS}
  labels:
    argocd.argoproj.io/secret-type: cluster
type: Opaque
stringData:
  name: cloud
  server: https://api.zenek.ln6np.gcp.redhatworkshops.io:6443
  config: |
    {
      "bearerToken": "${CLOUD_TOKEN}",
      "tlsClientConfig": {
        "insecure": false,
        "caData": "${CLOUD_CA}"
      }
    }
EOF
  ok "cloud cluster registered with Argo CD"
fi

# ── Step 2: Apply Argo CD ApplicationSets ─────────────────────────────────
log "Step 2: Apply Argo CD ApplicationSets (Kafka, PostgreSQL, Apicurio, MM2)"
oc --context "${ONPREM}" apply \
  -f "${REPO_ROOT}/gitops/applicationsets/" \
  -n "${GITOPS_NS}"
ok "ApplicationSets applied"
info "Argo CD will sync Kafka → PostgreSQL → Apicurio → MirrorMaker2"
info "Monitor: oc --context onprem get applications -n openshift-gitops"

# ── Step 3: Wait for Kafka ready on both clusters ─────────────────────────
log "Step 3: Waiting for Kafka clusters (up to 15 min each)"

printf 'Waiting for Kafka ready on onprem'
wait_for_jsonpath "banking-kafka (onprem)" 900 \
  "${ONPREM}" "kafka/banking-kafka" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
ok "Kafka ready on onprem"

printf 'Waiting for Kafka ready on cloud'
wait_for_jsonpath "banking-kafka (cloud)" 900 \
  "${CLOUD}" "kafka/banking-kafka" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
ok "Kafka ready on cloud"

# ── Step 4: Wait for PostgreSQL primary on onprem ─────────────────────────
log "Step 4: Waiting for PostgreSQL primary on onprem (up to 15 min)"

printf 'Waiting for PostgreSQL 3 instances ready on onprem'
wait_for_jsonpath "postgres (onprem)" 900 \
  "${ONPREM}" "postgrescluster/postgres" "${INFRA_NS}" \
  '{.status.instances[0].readyReplicas}' "3"
ok "PostgreSQL primary (3/3 instances) ready on onprem"

# ── Step 5: Deploy Skupper sites ──────────────────────────────────────────
log "Step 5: Deploy Skupper sites on both clusters"

oc --context "${ONPREM}" apply \
  -f "${REPO_ROOT}/infra/skupper/onprem/site.yaml" \
  -n "${INFRA_NS}"
ok "Skupper site deployed on onprem"

oc --context "${CLOUD}" apply \
  -f "${REPO_ROOT}/infra/skupper/cloud/site.yaml" \
  -n "${INFRA_NS}"
ok "Skupper site deployed on cloud"

printf 'Waiting for Skupper sites to be Ready'
wait_for_jsonpath "skupper site (onprem)" 300 \
  "${ONPREM}" "sites.skupper.io/banking-onprem" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
wait_for_jsonpath "skupper site (cloud)" 300 \
  "${CLOUD}" "sites.skupper.io/banking-cloud" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
ok "Both Skupper sites Ready"

# ── Step 6: Create AccessGrant and exchange token ─────────────────────────
log "Step 6: Skupper cross-cluster link (AccessGrant → AccessToken)"

oc --context "${ONPREM}" apply \
  -f "${REPO_ROOT}/infra/skupper/onprem/access-grant.yaml" \
  -n "${INFRA_NS}"

printf 'Waiting for AccessGrant URL'
grant_elapsed=0
until GRANT_URL=$(oc --context "${ONPREM}" get accessgrant cloud-link-grant \
    -n "${INFRA_NS}" \
    -o jsonpath='{.status.url}' 2>/dev/null) && [[ -n "${GRANT_URL}" ]]; do
  printf '.'
  sleep 10
  grant_elapsed=$(( grant_elapsed + 10 ))
  if (( grant_elapsed >= 180 )); then
    printf '\nERROR: timed out waiting for AccessGrant URL\n' >&2
    exit 1
  fi
done
printf ' URL ready\n'

GRANT_CODE=$(oc --context "${ONPREM}" get accessgrant cloud-link-grant \
  -n "${INFRA_NS}" -o jsonpath='{.status.code}')
GRANT_CA=$(oc --context "${ONPREM}" get accessgrant cloud-link-grant \
  -n "${INFRA_NS}" -o jsonpath='{.status.ca}')

# Write to temp file: GRANT_CA is a PEM cert whose "-----END CERTIFICATE-----"
# lines start with dashes that YAML parses as document separators in a heredoc.
_TMPTOKEN=$(mktemp /tmp/skupper-token.XXXXXX.yaml)
cat > "${_TMPTOKEN}" <<YAMLEOF
apiVersion: skupper.io/v2alpha1
kind: AccessToken
metadata:
  name: onprem-link-token
  namespace: ${INFRA_NS}
spec:
  url: "${GRANT_URL}"
  code: "${GRANT_CODE}"
  ca: |
$(printf '%s\n' "${GRANT_CA}" | sed 's/^/    /')
YAMLEOF
oc --context "${CLOUD}" apply -f "${_TMPTOKEN}" -n "${INFRA_NS}"
rm -f "${_TMPTOKEN}"
ok "AccessToken applied on cloud"

printf 'Waiting for Skupper link to be established'
wait_for_jsonpath "skupper link (cloud)" 300 \
  "${CLOUD}" "links.skupper.io/onprem-link-token" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
ok "Skupper cross-cluster link operational"

# ── Step 7: Apply Skupper connectors and listeners ────────────────────────
log "Step 7: Expose kafka-bootstrap and postgresql-primary via Skupper"

oc --context "${ONPREM}" apply \
  -f "${REPO_ROOT}/infra/skupper/onprem/connectors.yaml" \
  -n "${INFRA_NS}"
ok "Skupper Connectors applied on onprem (kafka-bootstrap, postgresql-primary)"

oc --context "${CLOUD}" apply \
  -f "${REPO_ROOT}/infra/skupper/cloud/listeners.yaml" \
  -n "${INFRA_NS}"
ok "Skupper Listeners applied on cloud (kafka-bootstrap, postgresql-primary)"

# ── Step 8: Wait for PostgreSQL on cloud ─────────────────────────────────
log "Step 8: Waiting for PostgreSQL on cloud (up to 10 min)"

printf 'Waiting for PostgreSQL instance ready on cloud'
wait_for_jsonpath "postgres (cloud)" 600 \
  "${CLOUD}" "postgrescluster/postgres" "${INFRA_NS}" \
  '{.status.instances[0].readyReplicas}' "1"
ok "PostgreSQL ready on cloud"

# ── Step 9: Wait for Apicurio Registry ────────────────────────────────────
log "Step 9: Waiting for Apicurio Registry on onprem (up to 5 min)"

printf 'Waiting for apicurio-registry deployment'
wait_for "apicurio-registry" 300 \
  oc --context "${ONPREM}" rollout status deployment/apicurio-registry \
    -n "${INFRA_NS}" --timeout=10s
ok "Apicurio Registry ready"

# ── Step 10: Wait for MirrorMaker 2 ───────────────────────────────────────
log "Step 10: Waiting for MirrorMaker 2 on cloud (up to 10 min)"

printf 'Waiting for KafkaMirrorMaker2 ready on cloud'
wait_for_jsonpath "banking-mirror (cloud)" 600 \
  "${CLOUD}" "kafkamirrormaker2/banking-mirror" "${INFRA_NS}" \
  '{.status.conditions[?(@.type=="Ready")].status}' "True"
ok "MirrorMaker 2 ready"

# ── Phase 1 Checkpoint ────────────────────────────────────────────────────
log "Phase 1 Checkpoint"

PASS=0
FAIL=0

check() {
  local desc=$1; shift
  if "$@" &>/dev/null; then
    ok "${desc}"
    PASS=$(( PASS + 1 ))
  else
    fail "${desc}"
    FAIL=$(( FAIL + 1 ))
  fi
}

check "Kafka Ready (onprem)" bash -c \
  "oc --context ${ONPREM} get kafka banking-kafka -n ${INFRA_NS} \
   -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' \
   | grep -q '^True$'"

check "Kafka Ready (cloud)" bash -c \
  "oc --context ${CLOUD} get kafka banking-kafka -n ${INFRA_NS} \
   -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' \
   | grep -q '^True$'"

check "Kafka topics exist (onprem, count=3)" bash -c \
  "test \$(oc --context ${ONPREM} get kafkatopic -n ${INFRA_NS} \
     --no-headers 2>/dev/null | wc -l) -ge 3"

check "PostgreSQL 3/3 Ready (onprem)" bash -c \
  "oc --context ${ONPREM} get postgrescluster postgres -n ${INFRA_NS} \
   -o jsonpath='{.status.instances[0].readyReplicas}' \
   | grep -q '^3$'"

check "PostgreSQL Ready (cloud)" bash -c \
  "oc --context ${CLOUD} get postgrescluster postgres -n ${INFRA_NS} \
   -o jsonpath='{.status.instances[0].readyReplicas}' \
   | grep -Eq '^[1-9]'"

check "Skupper link Ready (cloud)" bash -c \
  "oc --context ${CLOUD} get links.skupper.io onprem-link-token \
   -n ${INFRA_NS} \
   -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' \
   | grep -q '^True$'"

check "Skupper Listeners present (cloud, count=2)" bash -c \
  "test \$(oc --context ${CLOUD} get listeners.skupper.io -n ${INFRA_NS} \
     --no-headers 2>/dev/null | wc -l) -ge 2"

check "Apicurio Registry Ready (onprem)" bash -c \
  "oc --context ${ONPREM} get deployment apicurio-registry -n ${INFRA_NS} \
   -o jsonpath='{.status.readyReplicas}' | grep -q '^1$'"

check "MirrorMaker2 Ready (cloud)" bash -c \
  "oc --context ${CLOUD} get kafkamirrormaker2 banking-mirror -n ${INFRA_NS} \
   -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' \
   | grep -q '^True$'"

check "Mirror topic replicated (cloud)" bash -c \
  "oc --context ${CLOUD} get kafkatopic -n ${INFRA_NS} \
   --no-headers 2>/dev/null | grep -q 'transactions-raw'"

printf '\n'
if (( FAIL == 0 )); then
  printf '\033[1;32m=== Phase 1 checkpoint PASSED (%d/%d) ===\033[0m\n\n' \
    "${PASS}" "$(( PASS + FAIL ))"
else
  printf '\033[1;31m=== Phase 1 checkpoint FAILED (%d passed, %d failed) ===\033[0m\n\n' \
    "${PASS}" "${FAIL}"
  exit 1
fi

printf 'Quick reference:\n'
printf '  oc --context onprem get kafka,postgrescluster,deployment -n banking-infra\n'
printf '  oc --context cloud  get kafka,postgrescluster,kafkamirrormaker2 -n banking-infra\n'
printf '  oc --context cloud  get links.skupper.io,listeners.skupper.io -n banking-infra\n'
