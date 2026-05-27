import React, { useEffect, useState } from 'react';
import {
  Page, PageSection, Title, Grid, GridItem, Alert
} from '@patternfly/react-core';
import { MetricsPayload } from './types/metrics';
import ClusterMap from './components/ClusterMap';
import TpsGauges from './components/TpsGauges';
import ChaosPanel from './components/ChaosPanel';
import ComplianceWidget from './components/ComplianceWidget';

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/metrics`;

export default function App() {
  const [payload, setPayload] = useState<MetricsPayload | null>(null);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ws: WebSocket;
    let reconnectTimer: ReturnType<typeof setTimeout>;

    const connect = () => {
      ws = new WebSocket(WS_URL);
      ws.onopen = () => { setConnected(true); setError(null); };
      ws.onmessage = (e) => {
        try { setPayload(JSON.parse(e.data)); } catch { /* ignore malformed */ }
      };
      ws.onerror = () => setError('WebSocket error — retrying…');
      ws.onclose = () => {
        setConnected(false);
        reconnectTimer = setTimeout(connect, 3000);
      };
    };

    connect();
    return () => { ws?.close(); clearTimeout(reconnectTimer); };
  }, []);

  return (
    <Page header={
      <div style={{ background: '#151515', padding: '12px 24px', display: 'flex', alignItems: 'center', gap: 16 }}>
        <img src="https://upload.wikimedia.org/wikipedia/commons/d/d8/Red_Hat_logo.svg"
             alt="Red Hat" height={32} style={{ filter: 'brightness(0) invert(1)' }} />
        <Title headingLevel="h1" size="xl" style={{ color: 'white' }}>
          Banking Demo — Multi-Cluster Dashboard
        </Title>
        <span style={{ marginLeft: 'auto', color: connected ? '#92d400' : '#f4c145', fontSize: 14 }}>
          {connected ? '● Live' : '○ Reconnecting…'}
        </span>
      </div>
    }>
      <PageSection>
        {error && <Alert variant="warning" title={error} style={{ marginBottom: 16 }} />}
        <Grid hasGutter>
          <GridItem span={12}>
            <ClusterMap payload={payload} />
          </GridItem>
          <GridItem span={8}>
            <TpsGauges payload={payload} />
          </GridItem>
          <GridItem span={4}>
            <ChaosPanel />
          </GridItem>
          <GridItem span={12}>
            <ComplianceWidget />
          </GridItem>
        </Grid>
      </PageSection>
    </Page>
  );
}
