#!/usr/bin/env bash
# Smoke test (~3 min): proves the transaction pipeline works end to end on BOTH
# clusters and that the dashboard can see it. Safe to run any time on a demo
# environment — it generates a short, low-rate burst of synthetic transactions.
#
# Usage: smoke-test.sh [--tps <per-cluster rate, default 20>] [--duration <seconds, default 30>]
# Exit:  0 all checks passed · 1 at least one check failed · 2 cannot run
set -uo pipefail

# shellcheck source=lib/test-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test-lib.sh"

TPS=20
DURATION=30
while [[ $# -gt 0 ]]; do
  case $1 in
    --tps)      TPS=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    *) printf 'Usage: %s [--tps N] [--duration SECONDS]\n' "$(basename "$0")"; exit 2 ;;
  esac
done

require_tools
require_clusters
trap stop_load EXIT

# ── 1. Readiness ────────────────────────────────────────────────────────────
log "1/5 Readiness"
cond() { # cond <ctx> <kind> <name> <namespace> <condition-type>
  oc --context "$1" get "$2" "$3" -n "$4" -o jsonpath="{.status.conditions[?(@.type==\"$5\")].status}" | grep -q '^True$'
}
for ctx in "$ONPREM" "$CLOUD"; do
  check "$ctx: Kafka Ready" cond "$ctx" kafka banking-kafka "$INFRA_NS" Ready
  check "$ctx: KEDA ScaledObject Ready" cond "$ctx" scaledobject transaction-processor "$APP_NS" Ready
done
check "onprem: PostgreSQL 3/3 Ready" bash -c \
  "oc --context $ONPREM get postgrescluster postgres -n $INFRA_NS -o jsonpath='{.status.instances[0].readyReplicas}' | grep -q '^3$'"
check "onprem: Apicurio Registry Ready" bash -c \
  "oc --context $ONPREM get deploy apicurio-registry -n $INFRA_NS -o jsonpath='{.status.readyReplicas}' | grep -q '^1$'"
check "cloud: Skupper link Ready" cond "$CLOUD" links.skupper.io onprem-link-token "$INFRA_NS" Ready
expect "cloud: Skupper Listeners Ready" \
  "$(oc --context "$CLOUD" get listeners.skupper.io -n "$INFRA_NS" --no-headers 2>/dev/null | grep -c 'Ready')" -ge 7
check "cloud: MirrorMaker 2 Ready" cond "$CLOUD" kafkamirrormaker2 banking-mirror "$INFRA_NS" Ready

for dep in transaction-generator transaction-processor account-service ledger-service cluster-gateway dashboard-backend dashboard-frontend; do
  check "onprem: $dep has ready replicas" bash -c \
    "oc --context $ONPREM get deploy $dep -n $APP_NS -o jsonpath='{.status.readyReplicas}' | grep -qE '^[1-9]'"
done
# transaction-processor on cloud is KEDA-managed and may legitimately sit at its minimum
for dep in transaction-generator account-service ledger-service cluster-gateway; do
  check "cloud: $dep has ready replicas" bash -c \
    "oc --context $CLOUD get deploy $dep -n $APP_NS -o jsonpath='{.status.readyReplicas}' | grep -qE '^[1-9]'"
done

consumer_channel_up() { # a Running pod can still have a dead Kafka consumer — see CLAUDE.md findings 6/7
  svc_health "$1" "$2" /health/live | jq -e '.checks[] | select(.name=="kafka-consumer-channel") | .status=="UP"'
}
for ctx in "$ONPREM" "$CLOUD"; do
  check "$ctx: ledger-service kafka-consumer-channel UP" consumer_channel_up "$ctx" ledger-service
  if [[ "$(processor_replicas "$ctx")" -gt 0 ]]; then
    check "$ctx: transaction-processor kafka-consumer-channel UP" consumer_channel_up "$ctx" transaction-processor
  fi
  check "$ctx: account-service readiness UP (database reachable)" service_ready "$ctx" account-service
  check "$ctx: $APP_NS has a ResourceQuota" quota_present "$ctx" "$APP_NS"
  expect "$ctx: no pod creation refused by the $APP_NS quota" "$(quota_rejections "$ctx" "$APP_NS")" -eq 0
done

if (( FAIL > 0 )); then
  bad "Readiness failed — skipping the load stage (results would be meaningless)"
  summary "SMOKE TEST"; exit 1
fi

# ── 2. Baseline ─────────────────────────────────────────────────────────────
log "2/5 Baseline snapshot"
stop_load
wait_until 60 all_lag_zero || info "note: consumer lag was not 0 before the test started"

for ctx in "$ONPREM" "$CLOUD"; do
  eval "TX0_$ctx=\$(count_tx $ctx)"
  eval "LED0_$ctx=\$(count_ledger $ctx)"
  eval "RAW0_$ctx=\$(topic_end_sum $ctx $RAW_TOPIC \$(owned_lo $ctx) \$(owned_hi $ctx))"
  eval "DLQ0_$ctx=\$(topic_end_sum $ctx $DLQ_TOPIC 0 2)"
done
MIRROR0=$(topic_end_sum "$CLOUD" "$RAW_TOPIC" 0 11)
info "transactions: onprem=${TX0_onprem} cloud=${TX0_cloud} · ledger: onprem=${LED0_onprem} cloud=${LED0_cloud}"

# ── 3. Load ─────────────────────────────────────────────────────────────────
log "3/5 Load: ${TPS} TPS per cluster for ${DURATION}s"
set_cluster_tps "$ONPREM" "$TPS"
set_cluster_tps "$CLOUD" "$TPS"
sleep "$DURATION"
stop_load
info "load stopped; waiting for consumers to drain (up to 180s)"
if wait_until 180 all_lag_zero; then
  pass_check "all consumer groups drained to 0 lag on owned partitions"
else
  fail_check "consumer lag did not drain within 180s"
  for ctx in "$ONPREM" "$CLOUD"; do
    info "$ctx: processors lag=$(group_lag "$ctx" "transaction-processors-$ctx" "$(owned_lo "$ctx")" "$(owned_hi "$ctx")") ledger lag=$(group_lag "$ctx" "ledger-updaters-$ctx" 0 2)"
  done
fi
sleep 5  # let the last ledger batch commit

# ── 4. Assertions ───────────────────────────────────────────────────────────
log "4/5 Pipeline assertions"
for ctx in "$ONPREM" "$CLOUD"; do
  eval "tx0=\$TX0_$ctx; led0=\$LED0_$ctx; raw0=\$RAW0_$ctx; dlq0=\$DLQ0_$ctx"
  produced=$(( $(topic_end_sum "$ctx" "$RAW_TOPIC" "$(owned_lo "$ctx")" "$(owned_hi "$ctx")") - raw0 ))
  committed=$(( $(count_tx "$ctx") - tx0 ))
  ledgered=$(( $(count_ledger "$ctx") - led0 ))
  rejected=$(( $(topic_end_sum "$ctx" "$DLQ_TOPIC" 0 2) - dlq0 ))
  info "$ctx: produced=$produced committed=$committed ledger=$ledgered rejected(DLQ)=$rejected"

  expect "$ctx: generator produced to its owned partitions" "$produced" -gt 0
  expect "$ctx: transactions committed to PostgreSQL" "$committed" -gt 0
  expect "$ctx: ledger entries written" "$ledgered" -gt 0
  assert_accounting "$ctx" "$produced" "$committed" "$rejected"
  # version conflicts are expected when both clusters update the same accounts (CLAUDE.md)
  if (( rejected * 100 <= produced * 5 )); then
    pass_check "$ctx: rejected share is at most 5% of produced ($rejected of $produced)"
  else
    fail_check "$ctx: rejected share above 5% of produced ($rejected of $produced)"
  fi
  if (( ledgered != committed )); then
    finding "$ctx: ledger entries ($ledgered) differ from committed transactions ($committed)"
  fi
  expect "$ctx: transaction-processors group has live members" "$(group_members "$ctx" "transaction-processors-$ctx")" -ge 1
  expect "$ctx: ledger-updaters group has live members" "$(group_members "$ctx" "ledger-updaters-$ctx")" -ge 1
done

# MirrorMaker 2: cloud's partitions 0-11 are written only by the mirror, so their
# growth must equal what onprem produced.
onprem_produced=$(( $(topic_end_sum "$ONPREM" "$RAW_TOPIC" 0 11) - RAW0_onprem ))
mirror_caught_up() { [[ $(( $(topic_end_sum "$CLOUD" "$RAW_TOPIC" 0 11) - MIRROR0 )) -eq "$onprem_produced" ]]; }
wait_until 60 mirror_caught_up || true
expect "MirrorMaker 2 mirrored onprem's messages to cloud one-for-one" \
  "$(( $(topic_end_sum "$CLOUD" "$RAW_TOPIC" 0 11) - MIRROR0 ))" -eq "$onprem_produced"

# ── 5. Dashboard ────────────────────────────────────────────────────────────
log "5/5 Dashboard"
HOST=$(dashboard_host)
if [[ -z "$HOST" ]]; then
  fail_check "dashboard Route not found"
else
  http() { curl -sk -m 10 -o /dev/null -w '%{http_code}' "https://$HOST$1"; }
  expect "dashboard page" "$(http /)" -eq 200
  expect "dashboard → cluster-gateway proxy (/api/gateway/health)" "$(http /api/gateway/health)" -eq 200
  expect "dashboard → dashboard-backend proxy (/api/backend/compliance)" "$(http /api/backend/compliance)" -eq 200
  check "RHACS compliance snapshot available" bash -c \
    "curl -sk -m 10 https://$HOST/api/backend/compliance | jq -e '.available==true'"
  # .available alone stays true when an individual Central endpoint fails (each
  # count degrades to -1 on its own) — that hid a removed RHACS endpoint (issue #22).
  check "RHACS compliance posture counts populated" bash -c \
    "curl -sk -m 10 https://$HOST/api/backend/compliance | jq -e '[.numDeployments,.numSecrets,.numNodes,.imagesScanned,.imagesWithCriticalVulns] | all(. >= 0)'"

  WS=$(curl -sk -i -N --http1.1 --max-time 5 \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "https://$HOST/ws/metrics" 2>/dev/null | LC_ALL=C tr -d '\000')
  check "WebSocket /ws/metrics upgrades (101)" env LC_ALL=C grep -qa '^HTTP/1.1 101' <<<"$WS"
  for ctx in "$ONPREM" "$CLOUD"; do
    check "WebSocket payload reports $ctx healthy" \
      env LC_ALL=C grep -qaE "\"cluster\":\"$ctx\"[^}]*\"healthy\":true" <<<"$WS"
  done
  check "cloud gateway reachable from dashboard-backend (RHSI control channel)" backend_reaches_cloud_gateway
fi

summary "SMOKE TEST"
