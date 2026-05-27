import React from 'react';
import { Card, CardTitle, CardBody } from '@patternfly/react-core';
import { MetricsPayload, ClusterMetrics } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

function ClusterBox({ m }: { m: ClusterMetrics }) {
  const label = m.cluster === 'onprem' ? 'AWS (on-prem)' : 'GCP (cloud burst)';
  const color = m.healthy ? '#92d400' : '#c9190b';
  return (
    <div style={{
      border: `2px solid ${color}`, borderRadius: 8, padding: 16,
      minWidth: 200, background: '#1b1d21', color: 'white'
    }}>
      <div style={{ fontWeight: 700, marginBottom: 8 }}>{label}</div>
      <div>TPS: <b>{m.tps.toFixed(1)}</b></div>
      <div>Traffic weight: <b>{m.trafficWeight}%</b></div>
      <div>Ledger entries: <b>{m.totalLedgerEntries.toLocaleString()}</b></div>
      <div style={{ marginTop: 6, fontSize: 12, color }}>
        {m.healthy ? '● Healthy' : '○ Degraded'}
      </div>
    </div>
  );
}

export default function ClusterMap({ payload }: Props) {
  return (
    <Card>
      <CardTitle>Cluster Topology</CardTitle>
      <CardBody>
        <div style={{ display: 'flex', gap: 32, alignItems: 'center', flexWrap: 'wrap' }}>
          {payload?.clusters.map(m => <ClusterBox key={m.cluster} m={m} />) ?? (
            <span style={{ color: '#6a6e73' }}>Waiting for data…</span>
          )}
          {payload && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ width: 80, height: 2, background: '#4cb140', position: 'relative' }}>
                <span style={{
                  position: 'absolute', top: -10, left: '50%', transform: 'translateX(-50%)',
                  fontSize: 10, color: '#4cb140', whiteSpace: 'nowrap'
                }}>RHSI mTLS</span>
              </div>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
