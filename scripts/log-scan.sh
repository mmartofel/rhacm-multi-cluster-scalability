#!/usr/bin/env bash
# Log and health gate (~1-2 min): scans every pod in banking-demo and banking-infra
# on both clusters over a time window and fails on
#   - any known-bad signature (log-scan-signatures.txt)
#   - any ERROR line that is not on the allowlist (log-scan-allowlist.txt)
#   - unpaired "Database connectivity LOST" lines
#   - container restarts / OOMKills inside the window, pods not Ready,
#     bad Warning events, consumer groups with no members
# WARN lines, other Warning events and CPU throttling are reported but never fail.
#
# Usage: log-scan.sh [--since <30s|15m|2h>] [--expect-outage]
#   --expect-outage  the window contains a deliberate interconnect break: cloud pods
#                    may log the connection errors in log-scan-outage-allowlist.txt,
#                    and each LOST must have a matching RESTORED
# Exit:  0 clean · 1 problems found · 2 cannot run
set -uo pipefail

# shellcheck source=lib/test-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test-lib.sh"

SINCE=15m
EXPECT_OUTAGE=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --since)         SINCE=$2; shift 2 ;;
    --expect-outage) EXPECT_OUTAGE=1; shift ;;
    *) printf 'Usage: %s [--since <duration>] [--expect-outage]\n' "$(basename "$0")"; exit 2 ;;
  esac
done

case $SINCE in
  *s) WINDOW_SECS=${SINCE%s} ;;
  *m) WINDOW_SECS=$(( ${SINCE%m} * 60 )) ;;
  *h) WINDOW_SECS=$(( ${SINCE%h} * 3600 )) ;;
  *)  printf 'ERROR: --since must look like 90s, 15m or 2h\n'; exit 2 ;;
esac
# ISO-8601 UTC cutoff (BSD date first, GNU date as fallback); ISO strings compare lexically
CUTOFF=$(date -u -v-"${WINDOW_SECS}"S +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
      || date -u -d "@$(( $(date +%s) - WINDOW_SECS ))" +%Y-%m-%dT%H:%M:%SZ)

require_tools
require_clusters
export LC_ALL=C   # logs contain arbitrary bytes; BSD grep/sed choke on them otherwise

WORK=$(mktemp -d "${TMPDIR:-/tmp}/log-scan.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
ALL="$WORK/all.log"

SIGNATURES="$SCRIPTS_DIR/log-scan-signatures.txt"
ALLOWLIST="$SCRIPTS_DIR/log-scan-allowlist.txt"
OUTAGE_ALLOWLIST="$SCRIPTS_DIR/log-scan-outage-allowlist.txt"
patterns() { grep -vE '^[[:space:]]*(#|$)' "$1" 2>/dev/null || true; }

ERROR_RE=' ERROR |\[error\]|ERROR:|FATAL|SEVERE|level=error|"level":"error"'
WARN_RE=' WARN |\[warn\]|WARNING:|level=warn'
# strip the volatile parts so identical problems collapse into one line
normalise() {
  sed -E 's#^([a-z]+/[a-z0-9-]+)-[a-z0-9]{6,10}-[a-z0-9]{5} \|#\1 |#; s/[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9:.,]+(Z|[+-][0-9:]+)?//g; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{22}/<id>/g; s/[0-9]+/N/g' \
    | cut -c1-240
}

# ── Collect ─────────────────────────────────────────────────────────────────
log "Collecting logs since ${SINCE} (cutoff ${CUTOFF})"
PODS=0
for ctx in "$ONPREM" "$CLOUD"; do
  for ns in "$APP_NS" "$INFRA_NS"; do
    for pod in $(oc --context "$ctx" get pods -n "$ns" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null); do
      PODS=$(( PODS + 1 ))
      (
        oc --context "$ctx" logs -n "$ns" "$pod" --all-containers --since="$SINCE" 2>/dev/null \
          | sed "s#^#${ctx}/${pod} | #" > "$WORK/${ctx}_${ns}_${pod}.log"
      ) &
      # keep the number of concurrent oc processes bounded
      (( PODS % 12 == 0 )) && wait
    done
  done
done
wait
cat "$WORK"/*.log > "$ALL" 2>/dev/null || true
info "$PODS pods, $(wc -l < "$ALL" | tr -d ' ') log lines"

# ── 1. Known-bad signatures ─────────────────────────────────────────────────
log "1/5 Known-bad signatures"
SIG_HITS=0
: > "$WORK/sig.regex"
while IFS= read -r line; do
  regex=${line%% ## *}; meaning=${line#* ## }
  printf '%s\n' "$regex" >> "$WORK/sig.regex"
  n=$(grep -cE -- "$regex" "$ALL" || true)
  if (( n > 0 )); then
    SIG_HITS=$(( SIG_HITS + 1 ))
    fail_check "'$regex' × $n — $meaning"
    grep -E -- "$regex" "$ALL" | cut -d'|' -f1 | sort | uniq -c | sort -rn | head -5 | sed 's/^/        /'
    info "sample: $(grep -m1 -E -- "$regex" "$ALL" | cut -c1-300)"
  fi
done < <(patterns "$SIGNATURES")
(( SIG_HITS == 0 )) && pass_check "none of the $(patterns "$SIGNATURES" | wc -l | tr -d ' ') known-bad signatures found"

# ── 2. Unknown ERROR lines ──────────────────────────────────────────────────
log "2/5 Unrecognised ERROR lines"
grep -E -- "$ERROR_RE" "$ALL" | grep -vE -f "$WORK/sig.regex" > "$WORK/errors.all" || true
patterns "$ALLOWLIST" > "$WORK/allow.regex"
if [[ -s "$WORK/allow.regex" ]]; then
  grep -vE -f "$WORK/allow.regex" "$WORK/errors.all" > "$WORK/errors.1" || true
else
  cp "$WORK/errors.all" "$WORK/errors.1"
fi
patterns "$OUTAGE_ALLOWLIST" > "$WORK/outage.regex"
if (( EXPECT_OUTAGE )) && [[ -s "$WORK/outage.regex" ]]; then
  # tolerated on cloud pods only — onprem must stay clean through the outage
  { grep -E "^${ONPREM}/" "$WORK/errors.1" || true
    grep -E "^${CLOUD}/" "$WORK/errors.1" | grep -vE -f "$WORK/outage.regex" || true
  } > "$WORK/errors.unknown"
  tolerated=$(( $(wc -l < "$WORK/errors.1") - $(wc -l < "$WORK/errors.unknown") ))
  info "tolerated $tolerated outage-related ERROR line(s) on cloud pods"
else
  cp "$WORK/errors.1" "$WORK/errors.unknown"
fi
if [[ -s "$WORK/errors.unknown" ]]; then
  fail_check "$(wc -l < "$WORK/errors.unknown" | tr -d ' ') ERROR line(s) not on the allowlist — distinct patterns:"
  normalise < "$WORK/errors.unknown" | sort | uniq -c | sort -rn | head -25 | sed 's/^/     /'
  info "if a pattern is confirmed harmless, add it to scripts/log-scan-allowlist.txt with the reason"
else
  pass_check "no unrecognised ERROR lines"
fi

# ── 3. Database connectivity transitions ────────────────────────────────────
log "3/5 Database connectivity (DatabaseConnectivityMonitor)"
lost_total=$(grep -c 'Database connectivity LOST' "$ALL" || true)
if (( lost_total == 0 )); then
  pass_check "no database connectivity loss logged"
elif (( ! EXPECT_OUTAGE )); then
  fail_check "database connectivity LOST logged $lost_total time(s) with no outage expected"
  grep 'Database connectivity' "$ALL" | cut -c1-200 | head -10 | sed 's/^/     /'
else
  unpaired=0
  for pod in $(grep 'Database connectivity LOST' "$ALL" | cut -d' ' -f1 | sort -u); do
    l=$(grep -F "$pod |" "$ALL" | grep -c 'Database connectivity LOST' || true)
    r=$(grep -F "$pod |" "$ALL" | grep -c 'Database connectivity RESTORED' || true)
    if (( l != r )); then unpaired=$(( unpaired + 1 )); info "$pod: LOST=$l RESTORED=$r"; fi
    case $pod in "$ONPREM"/*) unpaired=$(( unpaired + 1 )); info "$pod: onprem lost its database during a cloud-side outage" ;; esac
  done
  if (( unpaired == 0 )); then
    pass_check "every connectivity LOST has a matching RESTORED ($lost_total transitions, cloud only)"
  else
    fail_check "$unpaired pod(s) with unexpected or unrecovered database connectivity loss"
  fi
fi

# ── 4. Pod and cluster health ───────────────────────────────────────────────
log "4/5 Pod health"
: > "$WORK/restarts"; : > "$WORK/notready"; : > "$WORK/events.bad"; : > "$WORK/events.other"
# Warning event reasons that always indicate a real problem
BAD_EVENTS='^(OOMKilling|BackOff|CrashLoopBackOff|FailedScheduling|Evicted|FailedMount|FailedAttachVolume|FailedCreatePodSandBox|ErrImagePull|ImagePullBackOff|Failed)$'
for ctx in "$ONPREM" "$CLOUD"; do
  for ns in "$APP_NS" "$INFRA_NS"; do
    oc --context "$ctx" get pods -n "$ns" -o json 2>/dev/null > "$WORK/pods.json" || continue
    jq -r --arg c "$ctx" --arg cut "$CUTOFF" '.items[] | .metadata.name as $p
        | (.status.containerStatuses // [])[]
        | select(.lastState.terminated != null and .lastState.terminated.finishedAt >= $cut)
        | "\($c)/\($p) container=\(.name) restarts=\(.restartCount) reason=\(.lastState.terminated.reason) exit=\(.lastState.terminated.exitCode)"' \
        "$WORK/pods.json" >> "$WORK/restarts"
    jq -r --arg c "$ctx" '.items[] | select(.status.phase != "Succeeded" and .metadata.deletionTimestamp == null)
        | select(any(.status.containerStatuses[]?; .ready == false) or .status.phase != "Running")
        | "\($c)/\(.metadata.name) phase=\(.status.phase)"' \
        "$WORK/pods.json" >> "$WORK/notready"
    oc --context "$ctx" get events -n "$ns" --field-selector type=Warning -o json 2>/dev/null \
      | jq -r --arg c "$ctx" --arg cut "$CUTOFF" '.items[]
          | ((.lastTimestamp // .eventTime // .metadata.creationTimestamp) | tostring | .[0:19] + "Z") as $t
          | select($t >= $cut)
          | "\(.reason)\t\($c)/\(.involvedObject.name)\t\(.message | gsub("\n";" ") | .[0:140])"' > "$WORK/ev" || true
    awk -F'\t' -v re="$BAD_EVENTS" '$1 ~ re' "$WORK/ev" >> "$WORK/events.bad"
    awk -F'\t' -v re="$BAD_EVENTS" '$1 !~ re' "$WORK/ev" >> "$WORK/events.other"
  done
done

if [[ -s "$WORK/restarts" ]]; then
  fail_check "$(wc -l < "$WORK/restarts" | tr -d ' ') container restart(s) inside the window"
  sed 's/^/     /' "$WORK/restarts"
else
  pass_check "no container restarts or OOMKills inside the window"
fi
if [[ -s "$WORK/notready" ]]; then
  fail_check "$(wc -l < "$WORK/notready" | tr -d ' ') pod(s) not Ready"
  sed 's/^/     /' "$WORK/notready"
else
  pass_check "all pods Ready"
fi
if [[ -s "$WORK/events.bad" ]]; then
  fail_check "Warning events that indicate a real problem:"
  cut -f1,2 "$WORK/events.bad" | sort | uniq -c | sort -rn | head -15 | sed 's/^/     /'
else
  pass_check "no crash, OOM, scheduling, image or volume Warning events"
fi

for ctx in "$ONPREM" "$CLOUD"; do
  for group in "transaction-processors-$ctx" "ledger-updaters-$ctx"; do
    expect "$ctx: consumer group $group has live members" "$(group_members "$ctx" "$group")" -ge 1
  done
done

# ── 5. Informational (never fails) ──────────────────────────────────────────
log "5/5 Informational"
if [[ -s "$WORK/events.other" ]]; then
  info "other Warning events in the window (usually probe failures while pods scale):"
  cut -f1,2 "$WORK/events.other" | sed -E 's/-[a-z0-9]{8,10}-[a-z0-9]{5}$//' | sort | uniq -c | sort -rn | head -10 | sed 's/^/       /'
fi
warns=$(grep -cE -- "$WARN_RE" "$ALL" || true)
info "WARN lines: $warns"
if (( warns > 0 )); then
  grep -E -- "$WARN_RE" "$ALL" | normalise | sort | uniq -c | sort -rn | head -8 | sed 's/^/       /'
fi
# CPU throttling masquerades as several unrelated startup bugs (see CLAUDE.md, issue #17)
for ctx in "$ONPREM" "$CLOUD"; do
  for pod in $(oc --context "$ctx" get pods -n "$APP_NS" --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null); do
    stat=$(oc_exec --context "$ctx" exec -n "$APP_NS" "$pod" -- cat /sys/fs/cgroup/cpu.stat 2>/dev/null) || continue
    pct=$(awk '$1=="nr_periods"{p=$2} $1=="nr_throttled"{t=$2} END{ if (p>0) printf "%d", t*100/p; else print 0 }' <<<"$stat")
    (( pct > 10 )) && info "CPU throttling: $ctx/$pod throttled in ${pct}% of scheduling periods since start"
  done
done

summary "LOG AND HEALTH GATE"
