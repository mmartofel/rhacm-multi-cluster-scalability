import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ComplianceSnapshot, ClusterSecurityHealth, Violation, ViolationDetail } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR, HEALTHY_COLOR, CAPACITY_COLOR, GEN_COLOR } from '../colors';

const UNKNOWN_COLOR = '#6a6e73';
const AUTO_REFRESH_MS = 30000;

function healthColor(status: string): string {
  if (status === 'HEALTHY') return HEALTHY_COLOR;
  if (status === 'UNINITIALIZED' || status === 'UNKNOWN' || !status) return UNKNOWN_COLOR;
  return CAPACITY_COLOR;
}

function severityColor(severity: string): string {
  if (severity === 'CRITICAL_SEVERITY') return CAPACITY_COLOR;
  if (severity === 'HIGH_SEVERITY') return ONPREM_COLOR;
  if (severity === 'MEDIUM_SEVERITY') return GEN_COLOR;
  return HEALTHY_COLOR;
}

function severityLabel(severity: string): string {
  return severity.replace('_SEVERITY', '').replace(/^\w/, c => c.toUpperCase());
}

function fmtCount(n: number): string {
  return n < 0 ? '—' : n.toLocaleString('en');
}

function fmtAge(ms: number): string {
  if (!ms) return '—';
  const deltaMin = Math.max(0, Math.round((Date.now() - ms) / 60000));
  if (deltaMin < 60) return `${deltaMin}m ago`;
  const deltaHr = Math.round(deltaMin / 60);
  if (deltaHr < 24) return `${deltaHr}h ago`;
  return `${Math.round(deltaHr / 24)}d ago`;
}

function fmtTimestamp(ms: number): string {
  if (!ms) return 'never';
  return new Date(ms).toLocaleTimeString();
}

function fmtDateTime(ms: number): string {
  if (!ms) return '—';
  return new Date(ms).toLocaleString();
}

// RUNTIME -> Runtime, FAIL_BUILD_ENFORCEMENT -> Fail build enforcement
function fmtEnum(value: string): string {
  const s = value.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function clusterColor(cluster: string): string {
  if (cluster === 'onprem') return ONPREM_COLOR;
  if (cluster === 'cloud') return CLOUD_COLOR;
  return UNKNOWN_COLOR;
}

const VIOLATION_COLUMNS = '90px minmax(0, 1.5fr) minmax(0, 1.1fr) 84px 76px';
const SEVERITIES = ['CRITICAL_SEVERITY', 'HIGH_SEVERITY', 'MEDIUM_SEVERITY', 'LOW_SEVERITY'];
type ClusterFilter = 'all' | 'onprem' | 'cloud';

function Pill({ label, color }: { label: string; color: string }) {
  return (
    <span style={{
      color, fontWeight: 700, fontSize: 11, background: `${color}18`,
      border: `1px solid ${color}44`, borderRadius: 10, padding: '2px 8px',
      textAlign: 'center', whiteSpace: 'nowrap',
    }}>
      {label}
    </span>
  );
}

function ClusterHealthCard({ health, accent }: { health: ClusterSecurityHealth; accent: string }) {
  const label = health.cluster === 'onprem' ? 'On-Prem' : 'Cloud';
  const overall = healthColor(health.healthStatus);
  const sensor = healthColor(health.sensorHealthStatus);
  return (
    <div style={{
      background: '#212427', border: `1px solid ${accent}33`, borderTop: `3px solid ${accent}`,
      borderRadius: 8, padding: '14px 16px', flex: 1, minWidth: 160,
    }}>
      <div style={{ fontWeight: 700, color: '#f0f0f0', fontSize: 14, marginBottom: 8 }}>{label}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: 11, color: '#8a8d90' }}>Cluster health</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: overall }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: overall, display: 'inline-block' }} />
            {health.healthStatus || 'UNKNOWN'}
          </span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: 11, color: '#8a8d90' }}>Sensor</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: sensor }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: sensor, display: 'inline-block' }} />
            {health.sensorHealthStatus || 'UNKNOWN'}
          </span>
        </div>
      </div>
    </div>
  );
}

function SeverityBadge({ label, count, color, active, dimmed, onClick }: {
  label: string; count: number; color: string; active: boolean; dimmed: boolean; onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      title={active ? `Showing ${label} only — click to clear` : `Show only ${label} violations`}
      style={{
        display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', textAlign: 'left',
        background: `${color}${active ? '33' : '18'}`, border: `1px solid ${color}${active ? '' : '44'}`,
        borderRadius: 8, padding: '8px 16px', flex: 1, minWidth: 100, opacity: dimmed ? 0.45 : 1,
      }}
    >
      <span style={{ fontSize: 22, fontWeight: 700, color, fontVariantNumeric: 'tabular-nums' }}>{count}</span>
      <span style={{ fontSize: 11, color: '#8a8d90' }}>{label}</span>
    </button>
  );
}

function FilterChip({ label, count, color, active, onClick }: {
  label: string; count: number; color: string; active: boolean; onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      style={{
        cursor: 'pointer', fontSize: 11, fontWeight: 700, borderRadius: 10, padding: '3px 10px',
        color: active ? color : '#8a8d90',
        background: active ? `${color}22` : 'transparent',
        border: `1px solid ${active ? color : '#3c3f42'}`,
      }}
    >
      {label} <span style={{ fontVariantNumeric: 'tabular-nums', opacity: 0.8 }}>{count}</span>
    </button>
  );
}

function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>{label}</div>
      <div style={{ fontSize: 17, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>{value}</div>
    </div>
  );
}

function ViolationRow({ v, onSelect }: { v: Violation; onSelect: (v: Violation) => void }) {
  const [hover, setHover] = useState(false);
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: VIOLATION_COLUMNS, gap: 10, alignItems: 'center',
      padding: '7px 10px', borderBottom: '1px solid #2a2d32', fontSize: 12,
    }}>
      <Pill label={severityLabel(v.severity)} color={severityColor(v.severity)} />
      <button
        onClick={() => onSelect(v)}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        title={`${v.policyName} — click for details`}
        style={{
          background: 'none', border: 'none', padding: 0, cursor: 'pointer', textAlign: 'left',
          color: '#f0f0f0', fontSize: 12, textDecoration: hover ? 'underline' : 'none',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}
      >
        {v.policyName}
      </button>
      <span
        title={`${v.namespace}/${v.deploymentName}`}
        style={{ color: '#e0e0e0', fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
      >
        {v.deploymentName}
      </span>
      <Pill label={v.cluster} color={clusterColor(v.cluster)} />
      <span style={{ color: '#8a8d90', textAlign: 'right' }}>{fmtAge(v.lastOccurred)}</span>
    </div>
  );
}

function DetailSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div style={{ fontSize: 10, color: '#8a8d90', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 13, color: '#d2d2d2', lineHeight: 1.6 }}>{children}</div>
    </div>
  );
}

function mitreUrl(id: string): string {
  return id.startsWith('TA')
    ? `https://attack.mitre.org/tactics/${id}/`
    : `https://attack.mitre.org/techniques/${id.replace('.', '/')}/`;
}

// Drill-down for one violation row: what the policy means (description, rationale,
// remediation) and what this deployment actually did to trip it. Fetched on open.
function ViolationModal({ violation, onClose }: { violation: Violation; onClose: () => void }) {
  const [detail, setDetail] = useState<ViolationDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/backend/compliance/violations/${violation.alertId}`)
      .then(res => {
        if (!res.ok) throw new Error(`Request failed (${res.status})`);
        return res.json();
      })
      .then((json: ViolationDetail) => { if (!cancelled) setDetail(json); })
      .catch((e: any) => { if (!cancelled) setError(e.message ?? 'Failed to load violation details'); });
    return () => { cancelled = true; };
  }, [violation.alertId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const mitreIds = detail
    ? Array.from(new Set(detail.mitre.flatMap(m => [m.tactic, ...m.techniques]).filter(Boolean)))
    : [];

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,0.65)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={violation.policyName}
        onClick={e => e.stopPropagation()}
        style={{
          background: '#1b1d21', border: '1px solid #3c3f42', borderRadius: 8,
          width: 'min(760px, 100%)', maxHeight: '100%', display: 'flex', flexDirection: 'column',
          boxShadow: '0 12px 40px rgba(0,0,0,0.6)',
        }}
      >
        <div style={{ padding: '16px 20px', borderBottom: '1px solid #2a2d32', display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
              <Pill label={severityLabel(violation.severity)} color={severityColor(violation.severity)} />
              <span style={{ fontSize: 16, fontWeight: 700, color: '#f0f0f0' }}>{violation.policyName}</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 12, color: '#8a8d90' }}>
              <Pill label={violation.cluster} color={clusterColor(violation.cluster)} />
              <span>{violation.namespace} /</span>
              <span style={{ color: '#e0e0e0', fontWeight: 700 }}>{violation.deploymentName}</span>
            </div>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ background: 'none', border: 'none', color: '#8a8d90', fontSize: 20, lineHeight: 1, cursor: 'pointer', padding: 4 }}
          >
            ✕
          </button>
        </div>

        <div style={{ padding: 20, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          {error ? (
            <div style={{
              padding: '8px 12px', borderRadius: 6, fontSize: 12,
              background: '#c9190b22', border: '1px solid #c9190b66', color: '#e57979',
            }}>
              Could not load details from RHACS Central: {error}
            </div>
          ) : !detail ? (
            <div style={{ color: '#6a6e73', fontSize: 13, textAlign: 'center', padding: '24px 0' }}>Loading…</div>
          ) : (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 16 }}>
                <DetailSection title="Lifecycle">{detail.lifecycleStages.map(fmtEnum).join(', ') || '—'}</DetailSection>
                <DetailSection title="Categories">{detail.categories.join(', ') || '—'}</DetailSection>
                <DetailSection title="Enforcement">
                  {detail.enforcementActions.length ? detail.enforcementActions.map(fmtEnum).join(', ') : 'Inform only'}
                </DetailSection>
                <DetailSection title="First seen">{fmtDateTime(detail.firstOccurred)}</DetailSection>
                <DetailSection title="Last seen">{fmtDateTime(detail.lastOccurred)}</DetailSection>
              </div>

              {detail.description && <DetailSection title="Description">{detail.description}</DetailSection>}
              {detail.rationale && <DetailSection title="Why it matters">{detail.rationale}</DetailSection>}
              {detail.remediation && <DetailSection title="Remediation">{detail.remediation}</DetailSection>}

              <DetailSection
                title={detail.totalMessages > detail.messages.length
                  ? `What triggered it (latest ${detail.messages.length} of ${detail.totalMessages})`
                  : 'What triggered it'}
              >
                {detail.messages.length === 0 ? '—' : (
                  <div style={{ background: '#151515', border: '1px solid #2a2d32', borderRadius: 6, maxHeight: 200, overflowY: 'auto' }}>
                    {detail.messages.map((m, i) => (
                      <div key={i} style={{ padding: '6px 10px', borderBottom: '1px solid #2a2d32', fontSize: 12, lineHeight: 1.5 }}>
                        <div style={{ color: '#d2d2d2', wordBreak: 'break-word' }}>{m.message}</div>
                        {m.time > 0 && <div style={{ color: '#6a6e73', fontSize: 11 }}>{fmtDateTime(m.time)}</div>}
                      </div>
                    ))}
                  </div>
                )}
              </DetailSection>

              {mitreIds.length > 0 && (
                <DetailSection title="MITRE ATT&CK">
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {mitreIds.map(id => (
                      <a key={id} href={mitreUrl(id)} target="_blank" rel="noopener noreferrer" style={{ color: CLOUD_COLOR, fontSize: 12 }}>
                        {id}
                      </a>
                    ))}
                  </div>
                </DetailSection>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default function ComplianceWidget() {
  const [snapshot, setSnapshot] = useState<ComplianceSnapshot | null>(null);
  const [pending, setPending] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [clusterFilter, setClusterFilter] = useState<ClusterFilter>('all');
  const [severityFilter, setSeverityFilter] = useState<string | null>(null);
  const [selected, setSelected] = useState<Violation | null>(null);
  const closeModal = useCallback(() => setSelected(null), []);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async (path: string, method: 'GET' | 'POST') => {
    try {
      const res = await fetch(path, { method });
      if (!res.ok) throw new Error(`Request failed (${res.status})`);
      const json: ComplianceSnapshot = await res.json();
      setSnapshot(json);
      setFetchError(null);
    } catch (e: any) {
      setFetchError(e.message ?? 'Failed to load compliance data');
    }
  }, []);

  useEffect(() => {
    load('/api/backend/compliance', 'GET');
    intervalRef.current = setInterval(() => load('/api/backend/compliance', 'GET'), AUTO_REFRESH_MS);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [load]);

  const handleRefresh = async () => {
    if (pending) return;
    setPending(true);
    await load('/api/backend/compliance/refresh', 'POST');
    setPending(false);
  };

  const findCluster = (cluster: 'onprem' | 'cloud') =>
    snapshot?.clusters.find(c => c.cluster === cluster) ?? { cluster, healthStatus: 'UNKNOWN', sensorHealthStatus: 'UNKNOWN' };

  const violations = snapshot?.violations ?? [];
  const bySeverity = severityFilter ? violations.filter(v => v.severity === severityFilter) : violations;
  const shown = clusterFilter === 'all' ? bySeverity : bySeverity.filter(v => v.cluster === clusterFilter);
  const clusterCount = (c: string) => bySeverity.filter(v => v.cluster === c).length;
  const toggleSeverity = (sev: string) => setSeverityFilter(cur => (cur === sev ? null : sev));

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 20, height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6, flexShrink: 0 }}>
        <div style={{ fontWeight: 600, fontSize: 16, color: '#f0f0f0' }}>RHACS Compliance</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: 11, color: '#6a6e73' }}>
            {snapshot ? `Updated ${fmtTimestamp(snapshot.lastUpdated)}` : 'Loading…'}
          </span>
          <button
            onClick={handleRefresh}
            disabled={pending}
            style={{
              background: '#4285F41a', border: '1px solid #4285F466', color: CLOUD_COLOR,
              padding: '6px 14px', borderRadius: 6, fontSize: 12, fontWeight: 700,
              cursor: pending ? 'wait' : 'pointer',
            }}
          >
            {pending ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      <div style={{ fontSize: 12, color: '#8a8d90', marginBottom: 14, flexShrink: 0 }}>
        Live from RHACS Central, scoped to <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>banking-demo</code> and <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>banking-infra</code> on both clusters. Auto-refreshes every 30s.
      </div>

      {(fetchError || (snapshot && !snapshot.available)) && (
        <div style={{
          padding: '8px 12px', borderRadius: 6, fontSize: 12, marginBottom: 14, flexShrink: 0,
          background: '#c9190b22', border: '1px solid #c9190b66', color: '#e57979',
        }}>
          {fetchError
            ? `Error: ${fetchError}`
            : `RHACS Central unreachable${snapshot?.error ? ` (${snapshot.error})` : ''} — showing last known data${snapshot?.lastUpdated ? ` as of ${fmtTimestamp(snapshot.lastUpdated)}` : ''}.`}
        </div>
      )}

      {!snapshot ? (
        <div style={{ color: '#6a6e73', fontSize: 13, textAlign: 'center', padding: '40px 0' }}>
          Waiting for data…
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14, flex: 1, minHeight: 0 }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', flexShrink: 0 }}>
            <ClusterHealthCard health={findCluster('onprem')} accent={ONPREM_COLOR} />
            <ClusterHealthCard health={findCluster('cloud')} accent={CLOUD_COLOR} />
          </div>

          <div style={{ background: '#212427', border: '1px solid #2a2d32', borderRadius: 8, padding: '12px 16px', flexShrink: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 10 }}>
              Posture <span style={{ fontWeight: 400, color: '#8a8d90' }}>— banking-demo + banking-infra, both clusters</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 16 }}>
              <StatTile label="Deployments" value={fmtCount(snapshot.numDeployments)} />
              <StatTile label="Images scanned" value={fmtCount(snapshot.imagesScanned)} />
              <StatTile label="Images w/ Critical CVE" value={fmtCount(snapshot.imagesWithCriticalVulns)} />
              <StatTile label="Secrets tracked" value={fmtCount(snapshot.numSecrets)} />
              <StatTile label="Nodes (cluster-wide)" value={fmtCount(snapshot.numNodes)} />
            </div>
          </div>

          <div style={{ flexShrink: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 8 }}>
              Active policy violations <span style={{ fontWeight: 400, color: '#6a6e73', fontSize: 11 }}>— click a severity to filter the list</span>
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              {SEVERITIES.map(sev => (
                <SeverityBadge
                  key={sev}
                  label={severityLabel(sev)}
                  count={snapshot.severity[severityLabel(sev).toLowerCase() as keyof typeof snapshot.severity]}
                  color={severityColor(sev)}
                  active={severityFilter === sev}
                  dimmed={severityFilter !== null && severityFilter !== sev}
                  onClick={() => toggleSeverity(sev)}
                />
              ))}
            </div>
          </div>

          <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap', flexShrink: 0 }}>
              <span style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginRight: 6 }}>
                Violations{' '}
                <span style={{ fontWeight: 400, color: '#8a8d90', fontVariantNumeric: 'tabular-nums' }}>
                  {shown.length === violations.length ? violations.length : `${shown.length} of ${violations.length}`}
                </span>
              </span>
              <FilterChip label="All" count={bySeverity.length} color="#f0f0f0" active={clusterFilter === 'all'} onClick={() => setClusterFilter('all')} />
              <FilterChip label="onprem" count={clusterCount('onprem')} color={ONPREM_COLOR} active={clusterFilter === 'onprem'} onClick={() => setClusterFilter('onprem')} />
              <FilterChip label="cloud" count={clusterCount('cloud')} color={CLOUD_COLOR} active={clusterFilter === 'cloud'} onClick={() => setClusterFilter('cloud')} />
              {(severityFilter || clusterFilter !== 'all') && (
                <button
                  onClick={() => { setSeverityFilter(null); setClusterFilter('all'); }}
                  style={{ background: 'none', border: 'none', color: CLOUD_COLOR, fontSize: 11, cursor: 'pointer', padding: '3px 4px' }}
                >
                  Clear filters
                </button>
              )}
            </div>
            <div style={{ background: '#212427', border: '1px solid #2a2d32', borderRadius: 8, overflow: 'hidden', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
              <div style={{
                display: 'grid', gridTemplateColumns: VIOLATION_COLUMNS, gap: 10, flexShrink: 0,
                padding: '8px 10px', fontSize: 10, color: '#8a8d90', textTransform: 'uppercase',
                borderBottom: '1px solid #2a2d32',
              }}>
                <span>Severity</span><span>Policy</span><span>Deployment</span><span>Cluster</span><span style={{ textAlign: 'right' }}>Last seen</span>
              </div>
              <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                {shown.length === 0 ? (
                  <div style={{ color: '#6a6e73', fontSize: 12, padding: '12px 10px' }}>
                    {violations.length === 0 ? 'No active violations for our namespaces.' : 'No violations match the current filters.'}
                  </div>
                ) : (
                  shown.map(v => <ViolationRow key={v.alertId} v={v} onSelect={setSelected} />)
                )}
              </div>
            </div>
          </div>
        </div>
      )}
      {selected && <ViolationModal violation={selected} onClose={closeModal} />}
    </div>
  );
}
