#!/usr/bin/env bash
# Shared helpers for smoke-test.sh, log-scan.sh and acceptance-test.sh.
# Source this file; do not execute it. Compatible with macOS bash 3.2 and BSD grep.

TEST_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "${TEST_LIB_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SCRIPTS_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-${REPO_ROOT}/kubeconfig-onprem:${REPO_ROOT}/kubeconfig-cloud}"
# Numbers from psql/Kafka use "." decimals; a comma-decimal locale (e.g. pl_PL) makes awk
# silently truncate them, and logs contain bytes BSD grep/sed reject outside the C locale.
export LC_ALL=C

ONPREM=onprem
CLOUD=cloud
APP_NS=banking-demo
INFRA_NS=banking-infra
RAW_TOPIC=transactions-raw
COMMITTED_TOPIC=transactions-committed
DLQ_TOPIC=transactions-dlq

# ── Output ──────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[1;34m'; NC='\033[0m'
log()     { printf "\n${BLUE}=== %s ===${NC}\n" "$*"; }
ok()      { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
bad()     { printf "  ${RED}✗${NC}  %s\n" "$*"; }
info()    { printf "     %s\n" "$*"; }
PASS=0; FAIL=0; FINDINGS=0

pass_check() { ok "$1"; PASS=$(( PASS + 1 )); }
fail_check() { bad "$1"; FAIL=$(( FAIL + 1 )); }
# finding <text> — reported, never fails the run (see "report only" items in CLAUDE.md Testing)
FINDING_TEXT=""
finding() {
  printf "  ${YELLOW}!${NC}  FINDING (report only): %s\n" "$*"
  FINDINGS=$(( FINDINGS + 1 ))
  FINDING_TEXT="${FINDING_TEXT}- $*"$'\n'
}

# check <description> <command...> — command output is discarded
check() {
  local desc=$1; shift
  if "$@" &>/dev/null; then pass_check "$desc"; else fail_check "$desc"; fi
}

# expect <description> <actual> <op> <expected> — numeric comparison, prints the values
expect() {
  local desc=$1 actual=$2 op=$3 expected=$4
  if [[ "$actual" =~ ^-?[0-9]+$ ]] && test "$actual" "$op" "$expected"; then
    pass_check "${desc} (${actual})"
  else
    fail_check "${desc} — got '${actual}', wanted ${op} ${expected}"
  fi
}

summary() {
  local name=$1
  printf '\n─────────────────────────────────────────────────────\n'
  printf '  %s: %d passed / %d failed / %d finding(s)\n' "$name" "$PASS" "$FAIL" "$FINDINGS"
  printf '─────────────────────────────────────────────────────\n'
  if (( FAIL == 0 )); then
    printf "${GREEN}  %s PASSED${NC}\n\n" "$name"; return 0
  fi
  printf "${RED}  %s FAILED${NC}\n\n" "$name"; return 1
}

# wait_until <max_seconds> <command...> — polls every 5s, returns 0 as soon as it succeeds
wait_until() {
  local max=$1; shift
  local deadline=$(( SECONDS + max ))
  until "$@" &>/dev/null; do
    (( SECONDS >= deadline )) && return 1
    sleep 5
  done
  return 0
}

# ── Cluster access ──────────────────────────────────────────────────────────
require_tools() {
  local t
  for t in oc jq curl perl; do
    command -v "$t" >/dev/null 2>&1 || { bad "required tool not found: $t"; exit 2; }
  done
}

require_clusters() {
  local ctx
  for ctx in "$ONPREM" "$CLOUD"; do
    oc --context "$ctx" whoami >/dev/null 2>&1 || { bad "cannot reach context '$ctx' (token expired? see get-kubeconfig.sh)"; exit 2; }
  done
}

# oc_exec <oc exec arguments...> — "oc exec" with a hard 60s limit. An exec stream can
# hang indefinitely when the API connection stalls (seen live: 37 min), and neither
# --request-timeout nor curl's own -m bounds that. perl's alarm is used because macOS
# ships no timeout(1); the exec'd oc inherits the pending alarm and is killed by it.
oc_exec() {
  perl -e 'alarm 60; exec @ARGV or die "exec failed: $!"' oc "$@"
}

# gw <ctx> <METHOD> <path> — call that cluster's own cluster-gateway from inside its pod
gw() {
  oc_exec --context "$1" exec -n "$APP_NS" deploy/cluster-gateway -- \
    curl -s -m 10 -X "$2" "http://localhost:8080$3" 2>/dev/null
}

# backend <METHOD> <path> — call dashboard-backend (onprem only)
backend() {
  oc_exec --context "$ONPREM" exec -n "$APP_NS" deploy/dashboard-backend -- \
    curl -s -m 15 -X "$1" "http://localhost:8080$2" 2>/dev/null
}

# svc_health <ctx> <deployment> <path> — raw body of a service's own health endpoint
svc_health() {
  oc_exec --context "$1" exec -n "$APP_NS" "deploy/$2" -- curl -s -m 10 "http://localhost:8080$3" 2>/dev/null
}

# ── PostgreSQL (onprem primary — the single record of truth) ────────────────
PG_POD=""
pg_pod() {
  if [[ -z "$PG_POD" ]]; then
    PG_POD=$(oc --context "$ONPREM" get pods -n "$INFRA_NS" \
      -l postgres-operator.crunchydata.com/role=master -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  fi
  printf '%s' "$PG_POD"
}
# psql_onprem <sql> — unaligned, tuples only
psql_onprem() {
  oc_exec --context "$ONPREM" exec -n "$INFRA_NS" "$(pg_pod)" -c database -- \
    psql -U postgres postgres -tA -c "$1" 2>/dev/null
}
count_tx()     { psql_onprem "SELECT count(*) FROM transactions WHERE source_cluster='$1'"; }
count_ledger() { psql_onprem "SELECT count(*) FROM ledger_entries WHERE source_cluster='$1'"; }

# ── Kafka (each cluster has its own physical banking-kafka) ─────────────────
kafka_exec() {
  local ctx=$1; shift
  oc_exec --context "$ctx" exec -n "$INFRA_NS" banking-kafka-broker-0 -- "$@" 2>/dev/null
}

# topic_end_sum <ctx> <topic> <first_partition> <last_partition> — sum of end offsets
topic_end_sum() {
  kafka_exec "$1" bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$2" \
    | awk -F: -v lo="$3" -v hi="$4" '$2+0>=lo && $2+0<=hi {s+=$3} END {print s+0}'
}

# group_describe <ctx> <group> — data rows only
group_describe() {
  kafka_exec "$1" bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$2" \
    | awk -v g="$2" '$1==g'
}
# group_lag <ctx> <group> <first_partition> <last_partition> — summed lag ("-" counts as 0)
group_lag() {
  group_describe "$1" "$2" | awk -v lo="$3" -v hi="$4" '$3+0>=lo && $3+0<=hi && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}'
}
# group_consumed <ctx> <group> <first_partition> <last_partition> — summed committed offsets
group_consumed() {
  group_describe "$1" "$2" | awk -v lo="$3" -v hi="$4" '$3+0>=lo && $3+0<=hi && $4 ~ /^[0-9]+$/ {s+=$4} END {print s+0}'
}
# group_members <ctx> <group> — number of distinct live consumers
group_members() {
  group_describe "$1" "$2" | awk '$7 != "-" {print $7}' | sort -u | grep -c . || true
}

# owned partition range per cluster (see OWNED_PARTITIONS in the Kustomize overlays)
owned_lo() { [[ "$1" == "$ONPREM" ]] && echo 0  || echo 12; }
owned_hi() { [[ "$1" == "$ONPREM" ]] && echo 11 || echo 23; }

# all four consumer groups drained on the partitions they actually process
all_lag_zero() {
  local ctx
  for ctx in "$ONPREM" "$CLOUD"; do
    [[ "$(group_lag "$ctx" "transaction-processors-$ctx" "$(owned_lo "$ctx")" "$(owned_hi "$ctx")")" == "0" ]] || return 1
    [[ "$(group_lag "$ctx" "ledger-updaters-$ctx" 0 2)" == "0" ]] || return 1
  done
}

# ── Load control ────────────────────────────────────────────────────────────
# set_cluster_tps <ctx> <rate> — drive one cluster's generator directly (no traffic-weight change)
set_cluster_tps() { gw "$1" PUT "/api/gateway/generator/tps/$2" >/dev/null; }
stop_load() {
  set_cluster_tps "$ONPREM" 0 || true
  set_cluster_tps "$CLOUD" 0 || true
}

processor_replicas() {
  oc --context "$1" get deploy transaction-processor -n "$APP_NS" -o jsonpath='{.status.readyReplicas}' 2>/dev/null | grep -E '^[0-9]+$' || echo 0
}

# restart_snapshot — "<ctx> <namespace> <pod> <container> <restartCount>" for every app/infra container
restart_snapshot() {
  local ctx ns
  for ctx in "$ONPREM" "$CLOUD"; do
    for ns in "$APP_NS" "$INFRA_NS"; do
      oc --context "$ctx" get pods -n "$ns" -o json 2>/dev/null | jq -r --arg c "$ctx" --arg n "$ns" \
        '.items[] | .metadata.name as $p | (.status.containerStatuses // [])[] | "\($c) \($n) \($p) \(.name) \(.restartCount)"'
    done
  done
}

dashboard_host() {
  oc --context "$ONPREM" get route dashboard -n "$APP_NS" -o jsonpath='{.spec.host}' 2>/dev/null
}

# ── Skupper Listeners toggled by the interconnect chaos feature (cloud) ─────
CHAOS_LISTENERS="kafka-bootstrap postgresql-primary apicurio-registry"
chaos_listeners_present() {
  local n=0 l
  for l in $CHAOS_LISTENERS; do
    oc --context "$CLOUD" get listeners.skupper.io "$l" -n "$INFRA_NS" >/dev/null 2>&1 && n=$(( n + 1 ))
  done
  echo "$n"
}
link_restore() { backend PUT /api/backend/link/restore >/dev/null; }

# quota_present <ctx> <namespace> — the namespace has a ResourceQuota (infra/namespaces/)
quota_present() {
  [[ "$(oc --context "$1" get resourcequota -n "$2" --no-headers 2>/dev/null | wc -l)" -gt 0 ]]
}
# quota_rejections <ctx> <namespace> — number of pod creations the quota is refusing
quota_rejections() {
  oc --context "$1" get events -n "$2" --field-selector reason=FailedCreate --no-headers 2>/dev/null \
    | grep -c 'exceeded quota' || true
}

# quota_reported <ctx> — cluster-gateway serves used/hard for both namespace quotas
# (Resource Consumption tab). Live usage is not asserted: it is legitimately -1
# while metrics-server rolls.
quota_reported() {
  gw "$1" GET /api/gateway/resources/summary | jq -e --arg a "$APP_NS" --arg b "$INFRA_NS" '
    ([.[].namespace] | index($a) != null and index($b) != null)
    and all(.[]; (.items | length) > 0 and all(.items[]; .hard > 0))' >/dev/null 2>&1
}

# service_ready <ctx> <deployment> — the service's own readiness (includes its database check)
service_ready() { svc_health "$1" "$2" /health/ready | jq -e '.status=="UP"' >/dev/null 2>&1; }

# balance_drift — "<account_id>|<balance minus (seed + signed sum of its committed transactions)>"
# for every account. 0.00 everywhere means balances and the transactions table agree; a
# non-zero value is a balance update that was applied without (or more often than) its
# transaction row — i.e. a double-applied or lost update.
balance_drift() {
  psql_onprem "SELECT a.account_id || '|' || (a.balance - 1000000 - COALESCE(sum(CASE WHEN t.type='CREDIT' THEN t.amount ELSE -t.amount END), 0)) FROM accounts a LEFT JOIN transactions t ON t.account_id = a.account_id GROUP BY a.account_id, a.balance ORDER BY a.account_id"
}

# assert_accounting <ctx> <produced> <committed> <rejected>
# Every produced message must end up committed or in the DLQ. Fewer means messages were
# lost (fail). More means some were handled twice after a redelivery — at-least-once
# delivery allows that, so it is reported as a finding; whether it also corrupted a
# balance is what balance_drift checks.
assert_accounting() {
  local ctx=$1 produced=$2 committed=$3 rejected=$4 handled=$(( $3 + $4 ))
  if (( handled == produced )); then
    pass_check "$ctx: every produced message was committed or sent to the DLQ ($produced)"
  elif (( handled < produced )); then
    fail_check "$ctx: $(( produced - handled )) of $produced produced messages were neither committed nor sent to the DLQ"
  else
    pass_check "$ctx: no produced message was lost ($produced produced, $handled handled)"
    finding "$ctx: $(( handled - produced )) message(s) were handled more than once (redelivered after a rebalance or outage)"
  fi
}

# link_call <break|restore> <expected status> — drive the chaos feature through the dashboard API
link_call() { backend PUT "/api/backend/link/$1" | jq -e --arg s "$2" '.status==$s' >/dev/null; }

# the dashboard's own control channel to cloud (separate Skupper Listener/Connector pair)
backend_reaches_cloud_gateway() {
  [[ "$(oc_exec --context "$ONPREM" exec -n "$APP_NS" deploy/dashboard-backend -- curl -s -m 10 -o /dev/null -w '%{http_code}' \
        "http://cloud-cluster-gateway.$INFRA_NS.svc.cluster.local:8080/api/gateway/health" 2>/dev/null)" == "200" ]]
}

duplicate_ledger_rows() {
  psql_onprem 'SELECT count(*) FROM (SELECT 1 FROM ledger_entries GROUP BY account_id, running_balance, as_of, source_cluster HAVING count(*) > 1) d'
}
