#!/usr/bin/env bash
# Acceptance test (~30 min): the "is it all good?" verdict for a deployed environment.
#
#   1. smoke        smoke-test.sh — pipeline works end to end on both clusters
#   2. autoscale    100 TPS per cluster: KEDA scales processors out, lag drains, scales back
#   3. chaos        break and restore the interconnect via the dashboard API under load
#   4. consistency  balances, ledger and Kafka accounting agree across the whole run
#   5. logs         log-scan.sh over the whole run window
#
# Generates synthetic transactions and makes onprem unreachable from cloud for a few
# minutes — run it on demo/sandbox environments only.
#
# Usage: acceptance-test.sh [--keep-going] [--skip-autoscale] [--skip-chaos]
#   --keep-going   run the remaining stages after a stage fails (default: stop)
# Exit:  0 every stage passed · 1 at least one stage failed · 2 cannot run
set -uo pipefail

# shellcheck source=lib/test-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test-lib.sh"

KEEP_GOING=0; SKIP_AUTOSCALE=0; SKIP_CHAOS=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --keep-going)     KEEP_GOING=1; shift ;;
    --skip-autoscale) SKIP_AUTOSCALE=1; shift ;;
    --skip-chaos)     SKIP_CHAOS=1; shift ;;
    *) printf 'Usage: %s [--keep-going] [--skip-autoscale] [--skip-chaos]\n' "$(basename "$0")"; exit 2 ;;
  esac
done

AUTOSCALE_TPS=100        # per cluster — inside cloud's known ~150 TPS ceiling (CLAUDE.md)
AUTOSCALE_SECS=240
CHAOS_TPS=20             # per cluster
CHAOS_HOLD_SECS=180

require_tools
require_clusters

REPORT_DIR="$REPO_ROOT/test-reports"
mkdir -p "$REPORT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="$REPORT_DIR/acceptance-$STAMP.log"
REPORT_FILE="$REPORT_DIR/acceptance-$STAMP.md"
exec > >(tee "$LOG_FILE") 2>&1

cleanup() {
  stop_load
  if [[ "$(chaos_listeners_present)" != "3" ]]; then
    info "cleanup: restoring the interconnect listeners"
    link_restore || true
  fi
}
trap cleanup EXIT

START_SECS=$SECONDS
STAGE_NAMES=(); STAGE_RESULTS=(); STAGE_SECS=()
OVERALL=0; ABORTED=0

# run_stage <name> <function> — a stage passes when it adds no failed checks
run_stage() {
  local name=$1 fn=$2 before=$FAIL t0=$SECONDS result
  if (( ABORTED )); then
    STAGE_NAMES+=("$name"); STAGE_RESULTS+=("SKIPPED (earlier stage failed)"); STAGE_SECS+=(0); return
  fi
  printf "\n${BLUE}################ STAGE: %s ################${NC}\n" "$name"
  "$fn"
  if (( FAIL > before )); then
    result=FAIL; OVERALL=1
    (( KEEP_GOING )) || ABORTED=1
  else
    result=PASS
  fi
  STAGE_NAMES+=("$name"); STAGE_RESULTS+=("$result"); STAGE_SECS+=($(( SECONDS - t0 )))
}

# child_script <description> <script> [args...] — counts as one check
child_script() {
  local desc=$1; shift
  if "$@"; then pass_check "$desc"; else fail_check "$desc (see its output above)"; fi
}

# ── Run-wide baseline for the consistency stage ─────────────────────────────
snapshot_counts() { # prints: tx led raw dlq consumed   for one cluster
  local ctx=$1
  printf '%s %s %s %s %s\n' "$(count_tx "$ctx")" "$(count_ledger "$ctx")" \
    "$(topic_end_sum "$ctx" "$RAW_TOPIC" "$(owned_lo "$ctx")" "$(owned_hi "$ctx")")" \
    "$(topic_end_sum "$ctx" "$DLQ_TOPIC" 0 2)" \
    "$(group_consumed "$ctx" "ledger-updaters-$ctx" 0 2)"
}
stop_load
wait_until 120 all_lag_zero || info "note: consumer lag was not 0 when the run started"
BASE_onprem=$(snapshot_counts "$ONPREM")
BASE_cloud=$(snapshot_counts "$CLOUD")
DRIFT_BEFORE="$REPORT_DIR/.drift-before-$STAMP"; DRIFT_AFTER="$REPORT_DIR/.drift-after-$STAMP"
balance_drift > "$DRIFT_BEFORE"
DUP_LEDGER_BEFORE=$(duplicate_ledger_rows)
OUTAGE_FROM=""; OUTAGE_TO=""

# ── Stage 1: smoke ──────────────────────────────────────────────────────────
stage_smoke() { child_script "smoke test" "$SCRIPTS_DIR/smoke-test.sh"; }

# ── Stage 2: autoscale ──────────────────────────────────────────────────────
stage_autoscale() {
  local start_onprem start_cloud peak_onprem peak_cloud r t0 ctx
  start_onprem=$(processor_replicas "$ONPREM"); start_cloud=$(processor_replicas "$CLOUD")
  peak_onprem=$start_onprem; peak_cloud=$start_cloud
  log "Load: ${AUTOSCALE_TPS} TPS per cluster for ${AUTOSCALE_SECS}s (processors: onprem=$start_onprem cloud=$start_cloud)"
  set_cluster_tps "$ONPREM" "$AUTOSCALE_TPS"; set_cluster_tps "$CLOUD" "$AUTOSCALE_TPS"
  t0=$SECONDS
  while (( SECONDS - t0 < AUTOSCALE_SECS )); do
    sleep 20
    r=$(processor_replicas "$ONPREM"); (( r > peak_onprem )) && peak_onprem=$r
    r=$(processor_replicas "$CLOUD");  (( r > peak_cloud ))  && peak_cloud=$r
    info "t+$(( SECONDS - t0 ))s  processors ready: onprem=$(processor_replicas "$ONPREM") cloud=$(processor_replicas "$CLOUD")"
  done
  stop_load
  for ctx in "$ONPREM" "$CLOUD"; do
    eval "r=\$peak_$ctx; s=\$start_$ctx"
    if (( r > s && r >= 3 )); then
      pass_check "$ctx: KEDA scaled transaction-processor out ($s → $r replicas)"
    else
      fail_check "$ctx: transaction-processor did not scale out under load ($s → $r replicas)"
    fi
    expect "$ctx: replicas stayed within maxReplicaCount" "$r" -le 20
  done

  info "load stopped; waiting for the backlog to drain (up to 10 min)"
  if wait_until 600 all_lag_zero; then
    pass_check "backlog drained to 0 lag on both clusters"
  else
    fail_check "backlog did not drain within 10 min"
    for ctx in "$ONPREM" "$CLOUD"; do
      info "$ctx: processors lag=$(group_lag "$ctx" "transaction-processors-$ctx" "$(owned_lo "$ctx")" "$(owned_hi "$ctx")") ledger lag=$(group_lag "$ctx" "ledger-updaters-$ctx" 0 2)"
    done
  fi

  info "waiting for scale-down (HPA stabilisation, up to 8 min)"
  scaled_down() { [[ "$(processor_replicas "$ONPREM")" -lt "$peak_onprem" && "$(processor_replicas "$CLOUD")" -lt "$peak_cloud" ]]; }
  if wait_until 480 scaled_down; then
    pass_check "processors scaled back down (onprem=$(processor_replicas "$ONPREM") cloud=$(processor_replicas "$CLOUD"))"
  else
    fail_check "processors did not scale down within 8 min (onprem=$(processor_replicas "$ONPREM") cloud=$(processor_replicas "$CLOUD"))"
  fi
}

# ── Stage 3: chaos — interconnect break/restore ─────────────────────────────
stage_chaos() {
  local led0 dlq0 before after grown new_restarts
  log "Background load: ${CHAOS_TPS} TPS per cluster"
  set_cluster_tps "$ONPREM" "$CHAOS_TPS"; set_cluster_tps "$CLOUD" "$CHAOS_TPS"
  sleep 20
  check "before break: cloud account-service ready" service_ready "$CLOUD" account-service
  before=$(restart_snapshot)
  led0=$(count_ledger "$ONPREM"); dlq0=$(topic_end_sum "$CLOUD" "$DLQ_TOPIC" 0 2)

  log "Breaking the interconnect (PUT /api/backend/link/break)"
  OUTAGE_FROM=$(date -u +'%Y-%m-%d %H:%M:%S')
  check "break accepted by the dashboard API" link_call break broken
  expect "the three banking-infra listeners are gone on cloud" "$(chaos_listeners_present)" -eq 0
  cloud_db_down() { ! service_ready "$CLOUD" account-service; }
  if wait_until 90 cloud_db_down; then
    pass_check "cloud account-service readiness went DOWN (onprem PostgreSQL unreachable)"
  else
    fail_check "cloud account-service stayed ready — the break had no effect"
  fi

  info "holding the outage for ${CHAOS_HOLD_SECS}s"
  sleep "$CHAOS_HOLD_SECS"

  check "Skupper Link CR still Ready (only the Listeners were removed)" bash -c \
    "oc --context $CLOUD get links.skupper.io onprem-link-token -n $INFRA_NS -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' | grep -q '^True$'"
  check "dashboard-backend still reaches the cloud gateway during the outage" backend_reaches_cloud_gateway
  check "onprem account-service stayed ready throughout" service_ready "$ONPREM" account-service
  grown=$(( $(count_ledger "$ONPREM") - led0 ))
  expect "onprem kept committing during the outage (new ledger entries)" "$grown" -gt 0
  info "cloud DLQ grew by $(( $(topic_end_sum "$CLOUD" "$DLQ_TOPIC" 0 2) - dlq0 )) message(s) during the outage"
  check "MirrorMaker 2 unaffected (own tunnel)" bash -c \
    "oc --context $CLOUD get kafkamirrormaker2 banking-mirror -n $INFRA_NS -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}' | grep -q '^True$'"

  log "Restoring the interconnect (PUT /api/backend/link/restore)"
  check "restore accepted by the dashboard API" link_call restore active
  listeners_ready() { [[ "$(oc --context "$CLOUD" get listeners.skupper.io -n "$INFRA_NS" --no-headers 2>/dev/null | grep -c 'Ready')" -ge 7 ]]; }
  if wait_until 120 listeners_ready; then pass_check "all 7 cloud listeners Ready again"; else fail_check "cloud listeners not Ready 120s after restore"; fi
  cloud_recovered() { service_ready "$CLOUD" account-service && service_ready "$CLOUD" ledger-service; }
  if wait_until 180 cloud_recovered; then
    pass_check "cloud account-service and ledger-service ready again without intervention"
  else
    fail_check "cloud services did not recover within 180s of restore"
  fi

  sleep 30   # some post-restore traffic before stopping the load
  OUTAGE_TO=$(date -u +'%Y-%m-%d %H:%M:%S')   # restore + recovery + 30s grace for reconnects
  stop_load
  info "load stopped; waiting for the backlog to drain (up to 10 min)"
  if wait_until 600 all_lag_zero; then pass_check "backlog drained to 0 lag after restore"; else fail_check "backlog did not drain within 10 min of restore"; fi

  for group in "transaction-processors-$CLOUD" "ledger-updaters-$CLOUD"; do
    expect "cloud: consumer group $group has live members (no dead consumer)" "$(group_members "$CLOUD" "$group")" -ge 1
  done
  after=$(restart_snapshot)
  # same pod+container present before and after with a higher restart count
  new_restarts=$(awk 'NR==FNR { b[$1" "$2" "$3" "$4]=$5; next } ($1" "$2" "$3" "$4) in b && $5 > b[$1" "$2" "$3" "$4] { print $1"/"$3" ("$4"): "b[$1" "$2" "$3" "$4]" → "$5 }' \
    <(printf '%s\n' "$before") <(printf '%s\n' "$after"))
  if [[ -z "$new_restarts" ]]; then
    pass_check "zero pod restarts across the break/restore cycle"
  else
    fail_check "pods restarted during the break/restore cycle:"
    printf '%s\n' "$new_restarts" | sed 's/^/     /'
  fi
}

# ── Stage 4: data consistency over the whole run ────────────────────────────
stage_consistency() {
  local ctx base now tx led raw dlq cons
  wait_until 120 all_lag_zero || true
  sleep 5
  expect "accounts table still has 100 accounts" "$(psql_onprem 'SELECT count(*) FROM accounts')" -eq 100
  expect "no account has a negative balance" "$(psql_onprem 'SELECT count(*) FROM accounts WHERE balance < 0')" -eq 0
  # Every account's balance must equal its seed plus the signed sum of its committed
  # transactions. Compared against the drift recorded when the run started, so damage
  # left behind by an earlier run does not fail this one.
  balance_drift > "$DRIFT_AFTER"
  local drifted
  drifted=$(awk -F'|' 'NR==FNR { b[$1]=$2; next } b[$1] != $2 { printf "%s: balance is off by %.2f versus its transactions (was %.2f before the run)\n", $1, $2, b[$1] }' "$DRIFT_BEFORE" "$DRIFT_AFTER")
  if [[ -z "$drifted" ]]; then
    pass_check "every account balance still matches its committed transactions (no double-apply, no lost update)"
  else
    fail_check "$(printf '%s\n' "$drifted" | grep -c .) account(s) whose balance no longer matches its committed transactions:"
    printf '%s\n' "$drifted" | sed 's/^/     /'
  fi
  local preexisting
  preexisting=$(awk -F'|' '$2+0 != 0' "$DRIFT_BEFORE" | grep -c . || true)
  (( preexisting == 0 )) || finding "$preexisting account(s) already had a balance/transaction mismatch before this run started"
  # A ledger batch retried after an ambiguous commit (connection lost mid-COMMIT) is written twice
  local dups; dups=$(duplicate_ledger_rows)
  expect "no new exact-duplicate ledger entries during the run" "$(( dups - DUP_LEDGER_BEFORE ))" -eq 0
  (( DUP_LEDGER_BEFORE == 0 )) || finding "$DUP_LEDGER_BEFORE exact-duplicate ledger entr(y/ies) already existed before this run started"

  for ctx in "$ONPREM" "$CLOUD"; do
    eval "base=\$BASE_$ctx"
    now=$(snapshot_counts "$ctx")
    set -- $base; local tx0=$1 led0=$2 raw0=$3 dlq0=$4 cons0=$5
    set -- $now;  tx=$(( $1 - tx0 )); led=$(( $2 - led0 )); raw=$(( $3 - raw0 )); dlq=$(( $4 - dlq0 )); cons=$(( $5 - cons0 ))
    info "$ctx over the run: produced=$raw committed=$tx rejected(DLQ)=$dlq ledger=$led ledger-consumed=$cons"
    assert_accounting "$ctx" "$raw" "$tx" "$dlq"
    (( led == tx ))   || finding "$ctx: ledger entries written ($led) differ from committed transactions ($tx)"
    (( led == cons )) || finding "$ctx: ledger entries written ($led) differ from messages the ledger consumer read ($cons)"
  done
}

# ── Stage 5: logs ───────────────────────────────────────────────────────────
stage_logs() {
  local window=$(( SECONDS - START_SECS + 60 ))
  if [[ -n "$OUTAGE_FROM" && -n "$OUTAGE_TO" ]]; then
    # outage symptoms are tolerated on cloud pods only, and only inside the break window
    child_script "log and health gate" "$SCRIPTS_DIR/log-scan.sh" --since "${window}s" \
      --expect-outage --outage-from "$OUTAGE_FROM" --outage-to "$OUTAGE_TO"
  else
    child_script "log and health gate" "$SCRIPTS_DIR/log-scan.sh" --since "${window}s"
  fi
}

run_stage smoke stage_smoke
(( SKIP_AUTOSCALE )) || run_stage autoscale stage_autoscale
(( SKIP_CHAOS ))     || run_stage chaos stage_chaos
run_stage consistency stage_consistency
run_stage logs stage_logs
rm -f "$DRIFT_BEFORE" "$DRIFT_AFTER"

# ── Report ──────────────────────────────────────────────────────────────────
TOTAL=$(( SECONDS - START_SECS ))
VERDICT=$( (( OVERALL == 0 )) && echo PASSED || echo FAILED )
{
  printf '# Acceptance test — %s\n\n' "$VERDICT"
  printf -- '- Run: %s (%dm %ds)\n' "$STAMP" $(( TOTAL / 60 )) $(( TOTAL % 60 ))
  printf -- '- onprem: %s\n- cloud: %s\n' "$(oc --context "$ONPREM" whoami --show-server)" "$(oc --context "$CLOUD" whoami --show-server)"
  printf -- '- Commit: %s\n' "$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)"
  printf -- '- Checks: %d passed, %d failed, %d finding(s)\n\n' "$PASS" "$FAIL" "$FINDINGS"
  printf '| Stage | Result | Duration |\n|---|---|---|\n'
  for i in "${!STAGE_NAMES[@]}"; do
    printf '| %s | %s | %ds |\n' "${STAGE_NAMES[$i]}" "${STAGE_RESULTS[$i]}" "${STAGE_SECS[$i]}"
  done
  if [[ -n "$FINDING_TEXT" ]]; then printf '\n## Findings (report only)\n\n%s' "$FINDING_TEXT"; fi
  printf '\nFull output: `%s`\n' "$(basename "$LOG_FILE")"
} > "$REPORT_FILE"

printf '\n═══════════════════════════════════════════════════════\n'
printf '  ACCEPTANCE TEST — %dm %ds\n' $(( TOTAL / 60 )) $(( TOTAL % 60 ))
printf '═══════════════════════════════════════════════════════\n'
for i in "${!STAGE_NAMES[@]}"; do
  printf '  %-12s %s\n' "${STAGE_NAMES[$i]}" "${STAGE_RESULTS[$i]}"
done
[[ -n "$FINDING_TEXT" ]] && printf '\n  Findings (report only):\n%s' "$(printf '%s' "$FINDING_TEXT" | sed 's/^/    /')"
printf '\n  Report: %s\n' "$REPORT_FILE"
if (( OVERALL == 0 )); then
  printf "\n${GREEN}  ALL GOOD — ACCEPTANCE TEST PASSED${NC}\n\n"
else
  printf "\n${RED}  ACCEPTANCE TEST FAILED${NC}\n\n"
fi
exit "$OVERALL"
