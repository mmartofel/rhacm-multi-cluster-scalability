#!/usr/bin/env bash
# bootstrap-phase2.sh — Phase 2: application services deployment
# Requires: Phase 1 checkpoint passed, oc CLI, quay.sh credentials
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ─── Logging helpers (same style as bootstrap-phase1.sh) ─────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${YELLOW}[$(date +'%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✓${NC} $*"; }
info() { echo -e "    $*"; }
fail() { echo -e "${RED}  ✗${NC} $*"; exit 1; }

# ─── KUBECONFIG ──────────────────────────────────────────────────────────────
if [[ -z "${KUBECONFIG:-}" ]]; then
  export KUBECONFIG="$REPO_ROOT/kubeconfig-onprem:$REPO_ROOT/kubeconfig-cloud"
fi

# ─── Verify both contexts ────────────────────────────────────────────────────
log "Step 1 — Verifying cluster access"
oc config use-context onprem >/dev/null 2>&1 || fail "Cannot switch to 'onprem' context"
oc whoami >/dev/null 2>&1 || fail "Not logged in to onprem cluster"
ok "onprem cluster accessible"

oc config use-context cloud >/dev/null 2>&1 || fail "Cannot switch to 'cloud' context"
oc whoami >/dev/null 2>&1 || fail "Not logged in to cloud cluster"
ok "cloud cluster accessible"

oc config use-context onprem >/dev/null 2>&1

# ─── Load Quay credentials ───────────────────────────────────────────────────
if [[ -f "$REPO_ROOT/quay.sh" ]]; then
  # shellcheck source=/dev/null
  source "$REPO_ROOT/quay.sh"
fi
: "${QUAY_ORG:?Please set QUAY_ORG or source quay.sh}"

# ─── Build images via Tekton Pipelines on onprem ─────────────────────────────
log "Step 2 — Building images via Tekton Pipelines on onprem"

: "${QUAY_USER:?Please set QUAY_USER or source quay.sh}"
: "${QUAY_TOKEN:?Please set QUAY_TOKEN or source quay.sh}"

# Apply build infrastructure (idempotent)
oc apply -f "$REPO_ROOT/pipelines/namespace.yaml" --context onprem
oc apply -f "$REPO_ROOT/pipelines/rbac.yaml" --context onprem
oc apply -f "$REPO_ROOT/pipelines/pipeline.yaml" --context onprem

# Create quay.io push secret and link to pipeline SA
oc create secret docker-registry quay-push-secret \
  --docker-server=quay.io \
  --docker-username="$QUAY_USER" \
  --docker-password="$QUAY_TOKEN" \
  -n banking-build --context onprem \
  --dry-run=client -o yaml | oc apply -f - --context onprem

oc secrets link pipeline-build quay-push-secret \
  --for=mount -n banking-build --context onprem 2>/dev/null || true

# Trigger one PipelineRun per service (all in parallel)
BUILD_SERVICES=(
  transaction-generator
  transaction-processor
  account-service
  ledger-service
  cluster-gateway
  dashboard-backend
  dashboard-frontend
)
REGISTRY="quay.io/${QUAY_ORG}"

TRIGGERED_RUNS=()
for svc in "${BUILD_SERVICES[@]}"; do
  RUN_NAME=$(oc create -n banking-build --context onprem -o jsonpath='{.metadata.name}' -f - <<EOF
apiVersion: tekton.dev/v1
kind: PipelineRun
metadata:
  generateName: build-${svc}-
  labels:
    banking-demo/service: ${svc}
spec:
  pipelineRef:
    name: build-banking-image
  taskRunTemplate:
    serviceAccountName: pipeline-build
  params:
  - name: service
    value: ${svc}
  - name: image
    value: ${REGISTRY}/banking-demo-${svc}:latest
  workspaces:
  - name: source
    volumeClaimTemplate:
      spec:
        accessModes: [ReadWriteOnce]
        resources:
          requests:
            storage: 2Gi
EOF
)
  TRIGGERED_RUNS+=("$RUN_NAME")
  ok "PipelineRun triggered for ${svc}: $RUN_NAME"
done

# Wait only for the PipelineRuns triggered in this run (up to 45 min)
log "Waiting for all Tekton builds to complete (up to 45 min)..."
DEADLINE=$((SECONDS + 2700))
while [[ $SECONDS -lt $DEADLINE ]]; do
  BUILD_DONE=0
  BUILD_FAILED=()
  for run in "${TRIGGERED_RUNS[@]}"; do
    status=$(oc get pipelinerun "$run" -n banking-build --context onprem \
      -o jsonpath='{.status.conditions[0].status}' 2>/dev/null || echo "Unknown")
    reason=$(oc get pipelinerun "$run" -n banking-build --context onprem \
      -o jsonpath='{.status.conditions[0].reason}' 2>/dev/null || echo "Unknown")
    if [[ "$status" == "True" ]]; then
      ((BUILD_DONE++)) || true
    elif [[ "$status" == "False" ]]; then
      BUILD_FAILED+=("$run ($reason)")
    fi
  done
  if [[ "${#BUILD_FAILED[@]}" -gt 0 ]]; then
    fail "${#BUILD_FAILED[@]} PipelineRun(s) failed: ${BUILD_FAILED[*]}"
  fi
  if [[ "$BUILD_DONE" -eq "${#TRIGGERED_RUNS[@]}" ]]; then
    break
  fi
  info "Builds: $BUILD_DONE/${#TRIGGERED_RUNS[@]} done..."
  sleep 30
done
if [[ "$BUILD_DONE" -lt "${#TRIGGERED_RUNS[@]}" ]]; then
  fail "Build timeout — $BUILD_DONE/${#TRIGGERED_RUNS[@]} completed. Check: oc get pipelinerun -n banking-build --context onprem"
fi
ok "All 7 images built and pushed to quay.io/$QUAY_ORG/ via Tekton"

# ─── Apply Skupper extensions ────────────────────────────────────────────────
log "Step 3 — Applying Skupper connector/listener extensions (apicurio-registry + cloud reverse access)"

oc apply -f "$REPO_ROOT/infra/skupper/onprem/connectors.yaml" --context onprem
oc apply -f "$REPO_ROOT/infra/skupper/cloud/listeners.yaml" --context cloud
oc apply -f "$REPO_ROOT/infra/skupper/cloud/connectors.yaml" --context cloud
oc apply -f "$REPO_ROOT/infra/skupper/onprem/listeners.yaml" --context onprem
ok "Skupper connectors/listeners applied"

log "Waiting 30s for Skupper to propagate new routes..."
sleep 30

# ─── PostgreSQL schema init ───────────────────────────────────────────────────
log "Step 4 — Initialising PostgreSQL schema"

# Find the PGO primary pod
PG_POD=$(oc get pods -n banking-infra --context onprem \
  -l "postgres-operator.crunchydata.com/role=master" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)

if [[ -z "$PG_POD" ]]; then
  PG_POD=$(oc get pods -n banking-infra --context onprem \
    -l "postgres-operator.crunchydata.com/cluster=postgres,postgres-operator.crunchydata.com/instance" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
fi

if [[ -z "$PG_POD" ]]; then
  fail "Could not find PostgreSQL primary pod in banking-infra on onprem"
fi
ok "Found PostgreSQL pod: $PG_POD"

oc exec -n banking-infra --context onprem "$PG_POD" -- psql -U postgres postgres <<'SQL'
CREATE TABLE IF NOT EXISTS accounts (
  account_id   VARCHAR(20)    PRIMARY KEY,
  balance      NUMERIC(15,2)  NOT NULL DEFAULT 1000000.00,
  last_updated TIMESTAMPTZ    DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transactions (
  transaction_id  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id      VARCHAR(20)    NOT NULL,
  type            VARCHAR(6)     NOT NULL,
  amount          NUMERIC(15,2)  NOT NULL,
  balance_after   NUMERIC(15,2),
  processed_at    TIMESTAMPTZ    DEFAULT now(),
  source_cluster  VARCHAR(10)    NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger_entries (
  entry_id        BIGSERIAL      PRIMARY KEY,
  account_id      VARCHAR(20)    NOT NULL,
  running_balance NUMERIC(15,2)  NOT NULL,
  as_of           TIMESTAMPTZ    DEFAULT now(),
  source_cluster  VARCHAR(10)
);

-- Hibernate 6 sequence (Panache SequenceStyleGenerator, allocationSize=50)
CREATE SEQUENCE IF NOT EXISTS "ledger_entries_SEQ" START 1 INCREMENT BY 50;

-- Seed 100 test accounts (idempotent)
INSERT INTO accounts (account_id, balance)
SELECT 'ACC' || LPAD(i::text, 5, '0'), 1000000.00
FROM generate_series(1, 100) AS i
ON CONFLICT (account_id) DO NOTHING;

SELECT 'Schema OK: accounts=' || (SELECT COUNT(*) FROM accounts)::text rows;
SQL
ok "PostgreSQL schema initialised (100 accounts seeded)"

# ─── Propagate DB credentials ─────────────────────────────────────────────────
log "Step 5 — Propagating PostgreSQL credentials to banking-demo namespace"

# PGO secret name pattern: postgres-pguser-<username>
# Default username = cluster name = 'postgres'
PGO_SECRET=$(oc get secret -n banking-infra --context onprem \
  -l "postgres-operator.crunchydata.com/pguser" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "postgres-pguser-postgres")

ok "Using PGO secret: $PGO_SECRET"

PG_USER=$(oc get secret "$PGO_SECRET" -n banking-infra --context onprem -o jsonpath='{.data.user}' | base64 -d)
PG_PASS=$(oc get secret "$PGO_SECRET" -n banking-infra --context onprem -o jsonpath='{.data.password}' | base64 -d)

# Create credentials Secret on onprem (banking-demo namespace)
oc create secret generic postgresql-credentials \
  --from-literal=user="$PG_USER" \
  --from-literal=password="$PG_PASS" \
  -n banking-demo --context onprem \
  --dry-run=client -o yaml | oc apply -f - --context onprem

# Create the same Secret on cloud (transaction-processor writes to onprem PG via Skupper)
oc create secret generic postgresql-credentials \
  --from-literal=user="$PG_USER" \
  --from-literal=password="$PG_PASS" \
  -n banking-demo --context cloud \
  --dry-run=client -o yaml | oc apply -f - --context cloud

ok "postgresql-credentials secret created on both clusters"

# ─── Copy quay pull secret ────────────────────────────────────────────────────
log "Step 6 — Ensuring quay-pull-secret exists in banking-demo"

for ctx in onprem cloud; do
  if oc get secret quay-pull-secret -n banking-demo --context "$ctx" &>/dev/null; then
    ok "quay-pull-secret already exists on $ctx"
  else
    # Try to copy from banking-infra where Phase 1 placed it
    if oc get secret quay-pull-secret -n banking-infra --context "$ctx" &>/dev/null; then
      oc get secret quay-pull-secret -n banking-infra --context "$ctx" -o json \
        | jq 'del(.metadata.namespace,.metadata.resourceVersion,.metadata.uid,.metadata.creationTimestamp)' \
        | oc apply -n banking-demo --context "$ctx" -f -
      ok "quay-pull-secret copied to banking-demo on $ctx"
    else
      # Create from quay credentials
      oc create secret docker-registry quay-pull-secret \
        --docker-server=quay.io \
        --docker-username="$QUAY_USER" \
        --docker-password="$QUAY_TOKEN" \
        -n banking-demo --context "$ctx" \
        --dry-run=client -o yaml | oc apply -f - --context "$ctx"
      ok "quay-pull-secret created in banking-demo on $ctx"
    fi
  fi
done

# ─── Register Avro schemas with Apicurio ─────────────────────────────────────
log "Step 7 — Registering Avro schemas with Apicurio Registry"

APICURIO_ROUTE=$(oc get route apicurio-registry -n banking-infra --context onprem \
  -o jsonpath='{.spec.host}' 2>/dev/null || true)

if [[ -n "$APICURIO_ROUTE" ]]; then
  APICURIO_BASE="https://$APICURIO_ROUTE/apis/registry/v2"
  info "Apicurio route: $APICURIO_ROUTE"

  for schema in TransactionEvent TransactionCommitted; do
    AVSC="$REPO_ROOT/services/avro-schemas/${schema}.avsc"
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      -X POST "$APICURIO_BASE/groups/default/artifacts" \
      -H "Content-Type: application/json; artifactType=AVRO" \
      -H "X-Registry-ArtifactId: com.redhat.banking.${schema}" \
      --data-binary "@$AVSC" 2>/dev/null || echo "000")
    if [[ "$HTTP_CODE" =~ ^(200|201|409)$ ]]; then
      ok "Schema $schema registered (HTTP $HTTP_CODE)"
    else
      info "WARN: Schema $schema registration returned HTTP $HTTP_CODE (may retry on startup)"
    fi
  done
else
  info "WARN: Apicurio route not found — schemas will be auto-registered on first message"
fi

# ─── Apply Argo CD RBAC ───────────────────────────────────────────────────────
log "Step 8 — Applying Argo CD RBAC for banking-demo namespace"

# Apply on onprem (Argo CD runs here, needs local permissions)
oc apply -f "$REPO_ROOT/infra/argocd/banking-demo-rbac.yaml" --context onprem

# Apply on cloud (Argo CD pushes manifests to cloud banking-demo namespace)
oc apply -f "$REPO_ROOT/infra/argocd/banking-demo-rbac.yaml" --context cloud

ok "Argo CD admin RoleBinding applied on both clusters"

# ─── Apply banking-demo ApplicationSet ────────────────────────────────────────
log "Step 9 — Applying banking-demo ApplicationSet"

oc apply -f "$REPO_ROOT/gitops/applicationsets/banking-demo-appset.yaml" --context onprem
ok "banking-demo-apps ApplicationSet created"

# ─── Wait for Argo CD to sync ─────────────────────────────────────────────────
log "Waiting 60s for Argo CD to process the ApplicationSet..."
sleep 60

# ─── Wait for all deployments ─────────────────────────────────────────────────
log "Step 10 — Waiting for application pods to become ready (up to 15 min)"

ONPREM_DEPLOYMENTS=(
  transaction-generator
  transaction-processor
  account-service
  ledger-service
  cluster-gateway
  dashboard-backend
  dashboard-frontend
)

CLOUD_DEPLOYMENTS=(
  transaction-generator
  account-service
  ledger-service
  cluster-gateway
)
# transaction-processor is intentionally excluded: KEDA manages it with minReplicaCount=0,
# so it correctly runs 0 replicas when no load is present. Readiness is verified via
# the KEDA ScaledObject check below.

wait_deployment() {
  local name=$1 ctx=$2 ns=banking-demo
  local deadline=$((SECONDS + 600))
  while [[ $SECONDS -lt $deadline ]]; do
    local ready
    ready=$(oc get deployment "$name" -n "$ns" --context "$ctx" \
      -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
    local desired
    desired=$(oc get deployment "$name" -n "$ns" --context "$ctx" \
      -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "1")
    if [[ "${ready:-0}" -ge "${desired:-1}" ]]; then
      ok "$name ready on $ctx ($ready/$desired)"
      return 0
    fi
    sleep 15
  done
  echo "  TIMEOUT: $name on $ctx did not become ready within 10 min"
  return 1
}

FAILED_DEPS=()

for dep in "${ONPREM_DEPLOYMENTS[@]}"; do
  wait_deployment "$dep" onprem || FAILED_DEPS+=("onprem/$dep")
done

for dep in "${CLOUD_DEPLOYMENTS[@]}"; do
  wait_deployment "$dep" cloud || FAILED_DEPS+=("cloud/$dep")
done

# ─── Phase 2 Checkpoint ───────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "        PHASE 2 CHECKPOINT"
echo "═══════════════════════════════════════════════════════"

PASS=0; FAIL=0
check() {
  local label=$1; local cmd=$2
  if eval "$cmd" &>/dev/null; then
    ok "$label"
    ((PASS++)) || true
  else
    echo -e "${RED}  ✗${NC} $label"
    ((FAIL++)) || true
  fi
}

# Onprem pods
for dep in "${ONPREM_DEPLOYMENTS[@]}"; do
  check "onprem: $dep running" \
    "oc get deployment $dep -n banking-demo --context onprem -o jsonpath='{.status.readyReplicas}' | grep -qE '[1-9]'"
done

# Cloud pods
for dep in "${CLOUD_DEPLOYMENTS[@]}"; do
  check "cloud: $dep running" \
    "oc get deployment $dep -n banking-demo --context cloud -o jsonpath='{.status.readyReplicas}' | grep -qE '[1-9]'"
done

# KEDA ScaledObjects
check "onprem: KEDA ScaledObject ready" \
  "oc get scaledobject transaction-processor -n banking-demo --context onprem -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' | grep -q True"
check "cloud: KEDA ScaledObject ready" \
  "oc get scaledobject transaction-processor -n banking-demo --context cloud -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' | grep -q True"

info "NOTE: cloud transaction-processor runs 0 replicas at idle (minReplicaCount=0)."
info "      KEDA scales it up automatically when load exceeds lagThreshold=500 messages."
info "      This is expected — not a failure."

# Dashboard route
DASH_HOST="dashboard.apps.zenek.sandbox3454.opentlc.com"
check "dashboard Route accessible (HTTP 200/302)" \
  "curl -s -o /dev/null -w '%{http_code}' -k --max-time 10 https://$DASH_HOST | grep -qE '^(200|302)'"

# PostgreSQL has rows
check "PostgreSQL: transactions table has rows" \
  "oc exec -n banking-infra --context onprem $PG_POD -- psql -U postgres postgres -t -c 'SELECT COUNT(*) FROM transactions' 2>/dev/null | grep -qE '[1-9]'"

echo ""
echo "─────────────────────────────────────────────────────"
echo "  Result: $PASS passed / $FAIL failed"
echo "─────────────────────────────────────────────────────"

if [[ $FAIL -eq 0 ]]; then
  echo -e "${GREEN}  PHASE 2 CHECKPOINT PASSED${NC}"
else
  echo -e "${YELLOW}  PHASE 2 CHECKPOINT: $FAIL check(s) failed${NC}"
  echo ""
  echo "Quick debug commands:"
  echo "  oc get pods -n banking-demo --context onprem"
  echo "  oc get pods -n banking-demo --context cloud"
  echo "  oc describe deployment transaction-processor -n banking-demo --context onprem"
  echo "  oc logs -n banking-demo -l app=transaction-generator --context onprem"
fi

echo ""
echo "Dashboard:  https://$DASH_HOST"
echo "WS metrics: wss://$DASH_HOST/ws/metrics"
echo ""
