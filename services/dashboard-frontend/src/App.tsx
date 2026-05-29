import React, { useEffect, useState, useRef } from 'react';
import {
  Page, PageSection, PageSidebar, PageSidebarBody,
  Masthead, MastheadMain, MastheadBrand, MastheadContent,
  Grid, GridItem,
} from '@patternfly/react-core';
import { MetricsPayload, ONPREM_CAPACITY_TPS } from './types/metrics';
import AppHeader from './components/AppHeader';
import AppNav from './components/AppNav';
import AppFooter from './components/AppFooter';
import KpiStrip from './components/KpiStrip';
import TpmChart from './components/TpmChart';
import ThroughputChart from './components/ThroughputChart';
import ClusterCards from './components/ClusterCards';
import LoadControlPanel from './components/LoadControlPanel';
import ChaosPanel from './components/ChaosPanel';
import ComplianceWidget from './components/ComplianceWidget';

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/metrics`;
const MAX_HISTORY = 360;

export type TpmPoint = { ts: number; onprem: number; cloud: number };
export type ThroughputPoint = { ts: number; genRate: number; onpremCommit: number; cloudCommit: number };
export type View = 'overview' | 'load-control' | 'chaos' | 'compliance' | 'autoscale' | 'about';

export default function App() {
  const [payload, setPayload] = useState<MetricsPayload | null>(null);
  const [connected, setConnected] = useState(false);
  const [activeView, setActiveView] = useState<View>('overview');

  const tpmHistory = useRef<TpmPoint[]>([]);
  const throughputHistory = useRef<ThroughputPoint[]>([]);
  const lastAutoWeight = useRef<number>(-1);

  useEffect(() => {
    let ws: WebSocket;
    let reconnectTimer: ReturnType<typeof setTimeout>;

    const connect = () => {
      ws = new WebSocket(WS_URL);
      ws.onopen = () => setConnected(true);
      ws.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data) as MetricsPayload;
          const onprem = data.clusters.find(c => c.cluster === 'onprem');
          const cloud = data.clusters.find(c => c.cluster === 'cloud');
          if (onprem && cloud) {
            const ts = Date.now();
            tpmHistory.current = [...tpmHistory.current, {
              ts,
              onprem: (onprem.committedTps ?? 0) * 60,
              cloud: (cloud.committedTps ?? 0) * 60,
            }].slice(-MAX_HISTORY);
            throughputHistory.current = [...throughputHistory.current, {
              ts,
              genRate: onprem.generatorTps ?? 0,
              onpremCommit: onprem.committedTps ?? 0,
              cloudCommit: cloud.committedTps ?? 0,
            }].slice(-MAX_HISTORY);
          }
          setPayload(data);
        } catch { /* ignore malformed */ }
      };
      ws.onerror = () => {};
      ws.onclose = () => { setConnected(false); reconnectTimer = setTimeout(connect, 3000); };
    };

    connect();
    return () => { ws?.close(); clearTimeout(reconnectTimer); };
  }, []);

  useEffect(() => {
    if (!payload) return;
    const genTps = payload.clusters.find(c => c.cluster === 'onprem')?.generatorTps ?? 0;
    if (genTps <= ONPREM_CAPACITY_TPS) return;
    const targetWeight = Math.max(1, Math.round(ONPREM_CAPACITY_TPS / genTps * 100));
    if (targetWeight === lastAutoWeight.current) return;
    lastAutoWeight.current = targetWeight;
    fetch('/api/gateway/traffic-weight', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trafficWeight: targetWeight }),
    }).catch(() => {});
  }, [payload]);

  const masthead = (
    <Masthead style={{ background: '#151515', borderBottom: '1px solid #2a2d32' }}>
      <MastheadMain>
        <MastheadBrand style={{ padding: '0 20px' }}>
          <AppHeader connected={connected} />
        </MastheadBrand>
      </MastheadMain>
      <MastheadContent />
    </Masthead>
  );

  const sidebar = (
    <PageSidebar style={{ background: '#1b1d21', borderRight: '1px solid #2a2d32' }}>
      <PageSidebarBody>
        <AppNav active={activeView} onSelect={setActiveView} />
      </PageSidebarBody>
    </PageSidebar>
  );

  const renderView = () => {
    switch (activeView) {
      case 'overview':
        return (
          <Grid hasGutter>
            <GridItem span={12}>
              <KpiStrip payload={payload} />
            </GridItem>
            <GridItem lg={7} span={12}>
              <TpmChart history={tpmHistory.current} />
            </GridItem>
            <GridItem lg={5} span={12}>
              <ClusterCards payload={payload} />
            </GridItem>
            <GridItem span={12}>
              <ThroughputChart history={throughputHistory.current} />
            </GridItem>
          </Grid>
        );
      case 'load-control':
        return <LoadControlPanel payload={payload} />;
      case 'chaos':
        return <ChaosPanel payload={payload} />;
      case 'compliance':
        return <ComplianceWidget />;
      case 'autoscale':
        return <Placeholder title="Autoscale Watch" body={'Real-time KEDA scaler activity — coming soon.\n\nThis view will show pod replica counts over time for\ntransaction-processor on both clusters as Kafka consumer\nlag drives horizontal scale-out and scale-in.'} />;
      case 'about':
        return <Placeholder title="Banking Demo — Multi-Cluster Scalability" body={'AWS (on-prem sim)  ·  GCP (cloud burst)  ·  Red Hat Service Interconnect mTLS\n\nKafka 4.2 (KRaft)  ·  PostgreSQL HA  ·  KEDA autoscaling  ·  Argo CD GitOps  ·  RHACM 2.16\n\nTransactions generated on AWS → replicated to GCP via MirrorMaker 2\nProcessors on both clusters write commits back to AWS PostgreSQL via RHSI\nKEDA scales cloud processors 0 → 20 replicas based on consumer lag'} />;
      default:
        return null;
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Page header={masthead} sidebar={sidebar} style={{ flex: 1, minHeight: 0 }}>
        <PageSection style={{ background: '#151515', padding: '20px 24px' }} isFilled>
          {renderView()}
        </PageSection>
      </Page>
      <footer style={{ background: '#0f1214', padding: '10px 24px', borderTop: '1px solid #2a2d32', flexShrink: 0 }}>
        <AppFooter />
      </footer>
    </div>
  );
}

function Placeholder({ title, body }: { title: string; body: string }) {
  return (
    <div style={{
      background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8,
      padding: '48px 32px', textAlign: 'center',
    }}>
      <div style={{ fontSize: 20, fontWeight: 700, color: '#f0f0f0', marginBottom: 16 }}>{title}</div>
      <div style={{ whiteSpace: 'pre-line', color: '#8a8d90', lineHeight: 1.8 }}>{body}</div>
    </div>
  );
}
