import React, { useRef, useEffect } from 'react';
import { Card, CardTitle, CardBody } from '@patternfly/react-core';
import { MetricsPayload } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

const MAX_POINTS = 60;

export default function TpsGauges({ payload }: Props) {
  const historyRef = useRef<Record<string, number[]>>({ onprem: [], cloud: [] });

  useEffect(() => {
    if (!payload) return;
    payload.clusters.forEach(m => {
      const h = historyRef.current[m.cluster] ?? [];
      h.push(m.tps);
      if (h.length > MAX_POINTS) h.shift();
      historyRef.current[m.cluster] = h;
    });
  }, [payload]);

  const renderBar = (cluster: string, tps: number) => {
    const pct = Math.min(100, (tps / 500) * 100);
    const color = cluster === 'onprem' ? '#06c' : '#4cb140';
    return (
      <div key={cluster} style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
          <span>{cluster === 'onprem' ? 'AWS (on-prem)' : 'GCP (cloud burst)'}</span>
          <b>{tps.toFixed(1)} TPS</b>
        </div>
        <div style={{ height: 24, background: '#1b1d21', borderRadius: 4, overflow: 'hidden' }}>
          <div style={{
            height: '100%', width: `${pct}%`, background: color,
            transition: 'width 0.4s ease', borderRadius: 4
          }} />
        </div>
      </div>
    );
  };

  return (
    <Card>
      <CardTitle>Transactions Per Second</CardTitle>
      <CardBody>
        {payload
          ? payload.clusters.map(m => renderBar(m.cluster, m.tps))
          : <span style={{ color: '#6a6e73' }}>Waiting for data…</span>
        }
      </CardBody>
    </Card>
  );
}
