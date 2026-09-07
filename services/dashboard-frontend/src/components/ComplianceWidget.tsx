import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ComplianceSnapshot, ClusterSecurityHealth, TopViolation } from '../types/metrics';
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

function SeverityBadge({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      background: `${color}18`, border: `1px solid ${color}44`,
      borderRadius: 8, padding: '10px 16px', flex: 1, minWidth: 100,
    }}>
      <span style={{ fontSize: 22, fontWeight: 700, color, fontVariantNumeric: 'tabular-nums' }}>{count}</span>
      <span style={{ fontSize: 11, color: '#8a8d90' }}>{label}</span>
    </div>
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

function ViolationRow({ v }: { v: TopViolation }) {
  const color = severityColor(v.severity);
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: '90px 1fr 90px 90px 80px', gap: 10, alignItems: 'center',
      padding: '8px 10px', borderBottom: '1px solid #2a2d32', fontSize: 12,
    }}>
      <span style={{
        color, fontWeight: 700, fontSize: 11, background: `${color}18`,
        border: `1px solid ${color}44`, borderRadius: 10, padding: '2px 8px', textAlign: 'center',
      }}>
        {severityLabel(v.severity)}
      </span>
      <span style={{ color: '#f0f0f0' }}>{v.policyName}</span>
      <span style={{ color: '#8a8d90' }}>{v.deploymentName}</span>
      <span style={{ color: '#8a8d90' }}>{v.cluster}</span>
      <span style={{ color: '#6a6e73', textAlign: 'right' }}>{fmtAge(v.firstOccurred)}</span>
    </div>
  );
}

export default function ComplianceWidget() {
  const [snapshot, setSnapshot] = useState<ComplianceSnapshot | null>(null);
  const [pending, setPending] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
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

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 20, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
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

      <div style={{ fontSize: 13, color: '#8a8d90', marginBottom: 18, lineHeight: 1.7 }}>
        Live security and risk posture from RHACS Central, scoped to the <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>banking-demo</code> and <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>banking-infra</code> namespaces on both clusters. Auto-refreshes every 30s.
      </div>

      {(fetchError || (snapshot && !snapshot.available)) && (
        <div style={{
          padding: '8px 12px', borderRadius: 6, fontSize: 12, marginBottom: 14,
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
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <ClusterHealthCard health={findCluster('onprem')} accent={ONPREM_COLOR} />
            <ClusterHealthCard health={findCluster('cloud')} accent={CLOUD_COLOR} />
          </div>

          <div>
            <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 10 }}>Active policy violations</div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <SeverityBadge label="Critical" count={snapshot.severity.critical} color={CAPACITY_COLOR} />
              <SeverityBadge label="High" count={snapshot.severity.high} color={ONPREM_COLOR} />
              <SeverityBadge label="Medium" count={snapshot.severity.medium} color={GEN_COLOR} />
              <SeverityBadge label="Low" count={snapshot.severity.low} color={HEALTHY_COLOR} />
            </div>
          </div>

          <div style={{ background: '#212427', border: '1px solid #2a2d32', borderRadius: 8, padding: 16 }}>
            <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 12 }}>Fleet posture</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 16 }}>
              <StatTile label="Deployments" value={fmtCount(snapshot.numDeployments)} />
              <StatTile label="Images scanned" value={fmtCount(snapshot.imagesScanned >= 0 ? snapshot.imagesScanned : snapshot.numImages)} />
              <StatTile label="Images w/ Critical CVE" value={fmtCount(snapshot.imagesWithCriticalVulns)} />
              <StatTile label="Nodes" value={fmtCount(snapshot.numNodes)} />
              <StatTile label="Secrets tracked" value={fmtCount(snapshot.numSecrets)} />
            </div>
          </div>

          <div>
            <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 10 }}>Top violations</div>
            {snapshot.topViolations.length === 0 ? (
              <div style={{ color: '#6a6e73', fontSize: 12, padding: '12px 0' }}>No active violations for our namespaces.</div>
            ) : (
              <div style={{ background: '#212427', border: '1px solid #2a2d32', borderRadius: 8, overflow: 'hidden' }}>
                <div style={{
                  display: 'grid', gridTemplateColumns: '90px 1fr 90px 90px 80px', gap: 10,
                  padding: '8px 10px', fontSize: 10, color: '#6a6e73', textTransform: 'uppercase',
                  borderBottom: '1px solid #2a2d32',
                }}>
                  <span>Severity</span><span>Policy</span><span>Deployment</span><span>Cluster</span><span style={{ textAlign: 'right' }}>Age</span>
                </div>
                {snapshot.topViolations.map((v, i) => <ViolationRow key={`${v.policyName}-${v.deploymentName}-${i}`} v={v} />)}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
