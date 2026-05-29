import React, { useRef, useEffect, useState } from 'react';
import { Chart, ChartLine, ChartAxis, ChartGroup, ChartVoronoiContainer } from '@patternfly/react-charts';
import { AutoscalePoint } from '../App';
import { MetricsPayload } from '../types/metrics';

interface Props {
  history: AutoscalePoint[];
  payload: MetricsPayload | null;
}

const AWS_COLOR  = '#06c';
const GCP_COLOR  = '#4cb140';
const DARK_AXIS  = {
  axis:       { stroke: '#3c3f42' },
  tickLabels: { fill: '#6a6e73', fontSize: 10 },
  grid:       { stroke: '#2a2d32', strokeDasharray: '2,4' },
};

const SCALER_CONFIG = {
  processor: {
    label: 'KEDA',
    labelColor: '#f4c145',
    title: 'transaction-processor',
    description: 'Scales on transactions-raw consumer lag. When lag exceeds 100 messages KEDA adds replicas.',
    trigger: 'Kafka lag threshold: 100 messages',
    onpremMin: 1, onpremMax: 10,
    cloudMin: 0,  cloudMax: 20,
  },
  account: {
    label: 'HPA',
    labelColor: '#06c',
    title: 'account-service',
    description: 'Scales when average CPU exceeds 60%. Responds to rising request rate as more processors call the balance API.',
    trigger: 'CPU utilisation target: 60%',
    onpremMin: 2, onpremMax: 10,
    cloudMin: 1,  cloudMax: 5,
  },
};

function replicaAccent(current: number, min: number, max: number): string {
  if (current < 0) return '#6a6e73';
  if (current >= max) return '#c9190b';
  if (current > min) return '#f4c145';
  return '#4cb140';
}

function ReplicaKpi({ label, clusterLabel, current, min, max, accent }: {
  label: string; clusterLabel: string; current: number; min: number; max: number; accent: string;
}) {
  return (
    <div style={{
      background: '#212427',
      border: `1px solid ${accent}55`,
      borderTop: `3px solid ${accent}`,
      borderRadius: 8,
      padding: '10px 14px',
    }}>
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>{clusterLabel}</div>
      <div style={{ fontSize: 11, color: '#8a8d90', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 26, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums', lineHeight: 1 }}>
        {current >= 0 ? current : '—'}
      </div>
      <div style={{ fontSize: 11, color: '#6a6e73', marginTop: 4 }}>min {min} / max {max}</div>
    </div>
  );
}

function ScalerBreakdownCard() {
  const { processor, account } = SCALER_CONFIG;
  const badge = (text: string, color: string) => (
    <span style={{
      fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 10,
      background: `${color}22`, color, border: `1px solid ${color}55`,
      marginRight: 8,
    }}>{text}</span>
  );
  const row = (cluster: string, min: number, max: number, accent: string) => (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, marginBottom: 2 }}>
      <span style={{ color: '#8a8d90' }}>{cluster}</span>
      <span style={{ color: accent, fontWeight: 600 }}>{min} – {max} replicas</span>
    </div>
  );

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16 }}>
      <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 14 }}>
        Autoscaling Breakdown
      </div>

      {/* KEDA */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
          {badge(processor.label, processor.labelColor)}
          <span style={{ fontSize: 12, fontWeight: 600, color: '#f0f0f0' }}>{processor.title}</span>
        </div>
        <div style={{ fontSize: 11, color: '#8a8d90', lineHeight: 1.6, marginBottom: 8 }}>
          {processor.description}
        </div>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 6 }}>
          Trigger: <span style={{ color: processor.labelColor }}>{processor.trigger}</span>
        </div>
        <div style={{ background: '#151515', borderRadius: 6, padding: '8px 10px' }}>
          {row('AWS (on-prem)', processor.onpremMin, processor.onpremMax, AWS_COLOR)}
          {row('GCP (cloud burst)', processor.cloudMin, processor.cloudMax, GCP_COLOR)}
        </div>
      </div>

      <div style={{ borderTop: '1px solid #2a2d32', paddingTop: 14 }}>
        {/* HPA */}
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
          {badge(account.label, account.labelColor)}
          <span style={{ fontSize: 12, fontWeight: 600, color: '#f0f0f0' }}>{account.title}</span>
        </div>
        <div style={{ fontSize: 11, color: '#8a8d90', lineHeight: 1.6, marginBottom: 8 }}>
          {account.description}
        </div>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 6 }}>
          Trigger: <span style={{ color: account.labelColor }}>{account.trigger}</span>
        </div>
        <div style={{ background: '#151515', borderRadius: 6, padding: '8px 10px' }}>
          {row('AWS (on-prem)', account.onpremMin, account.onpremMax, AWS_COLOR)}
          {row('GCP (cloud burst)', account.cloudMin, account.cloudMax, GCP_COLOR)}
        </div>
      </div>
    </div>
  );
}

function ReplicaChart({ history, title, subtitle, awsKey, gcpKey, minRef, maxRef }: {
  history: AutoscalePoint[];
  title: string;
  subtitle: string;
  awsKey: keyof AutoscalePoint;
  gcpKey: keyof AutoscalePoint;
  minRef: number;
  maxRef: number;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [chartWidth, setChartWidth] = useState(600);

  useEffect(() => {
    if (!containerRef.current) return;
    const ro = new ResizeObserver(entries =>
      setChartWidth(Math.floor(entries[0].contentRect.width) - 2));
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, []);

  // Filter out -1 (unknown) points — show as gap
  const awsData = history
    .filter(p => (p[awsKey] as number) >= 0)
    .map(p => ({ x: p.ts, y: p[awsKey] as number, name: 'AWS (on-prem)' }));
  const gcpData = history
    .filter(p => (p[gcpKey] as number) >= 0)
    .map(p => ({ x: p.ts, y: p[gcpKey] as number, name: 'GCP (cloud)' }));

  const maxY = history.length > 0
    ? Math.max(maxRef, ...history.map(p => Math.max(
        (p[awsKey] as number) >= 0 ? p[awsKey] as number : 0,
        (p[gcpKey] as number) >= 0 ? p[gcpKey] as number : 0,
      )))
    : maxRef;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '14px 8px 4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 12px 2px' }}>
        <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 13 }}>{title}</span>
        <div style={{ display: 'flex', gap: 12, fontSize: 11 }}>
          <span style={{ color: AWS_COLOR }}>— AWS (on-prem)</span>
          <span style={{ color: GCP_COLOR }}>— GCP (cloud burst)</span>
        </div>
      </div>
      <div style={{ padding: '0 12px 8px', fontSize: 11, color: '#6a6e73' }}>{subtitle}</div>
      <div ref={containerRef}>
        {history.length < 2 ? (
          <div style={{ height: 185, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
            Collecting data…
          </div>
        ) : (
          <Chart
            width={chartWidth}
            height={185}
            padding={{ bottom: 38, left: 48, right: 16, top: 6 }}
            minDomain={{ y: 0 }}
            maxDomain={{ y: maxY + 1 }}
            containerComponent={
              <ChartVoronoiContainer
                labels={({ datum }) => datum.name ? `${datum.name}: ${datum.y} replicas` : ''}
                constrainToVisibleArea
              />
            }
            style={{ parent: { background: 'transparent' } }}
          >
            <ChartAxis
              tickFormat={(t: number) =>
                new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
              tickCount={5}
              style={DARK_AXIS}
            />
            <ChartAxis dependentAxis tickFormat={(t: number) => `${Math.round(t)}`} style={DARK_AXIS} />
            <ChartGroup>
              {awsData.length > 0 && (
                <ChartLine
                  data={awsData}
                  style={{ data: { stroke: AWS_COLOR, strokeWidth: 2 } }}
                />
              )}
              {gcpData.length > 0 && (
                <ChartLine
                  data={gcpData}
                  style={{ data: { stroke: GCP_COLOR, strokeWidth: 2 } }}
                />
              )}
            </ChartGroup>
            {/* min reference line */}
            {history.length >= 2 && (
              <ChartLine
                data={[
                  { x: history[0].ts, y: minRef },
                  { x: history[history.length - 1].ts, y: minRef },
                ]}
                style={{ data: { stroke: '#3c3f42', strokeWidth: 1, strokeDasharray: '4,4' } }}
              />
            )}
            {/* max reference line */}
            {history.length >= 2 && (
              <ChartLine
                data={[
                  { x: history[0].ts, y: maxRef },
                  { x: history[history.length - 1].ts, y: maxRef },
                ]}
                style={{ data: { stroke: '#c9190b44', strokeWidth: 1, strokeDasharray: '4,4' } }}
              />
            )}
          </Chart>
        )}
      </div>
    </div>
  );
}

export default function AutoscaleWatchPanel({ history, payload }: Props) {
  const onprem = payload?.clusters.find(c => c.cluster === 'onprem');
  const cloud  = payload?.clusters.find(c => c.cluster === 'cloud');

  const procOnprem = onprem?.processorReplicas ?? -1;
  const procCloud  = cloud?.processorReplicas  ?? -1;
  const acctOnprem = onprem?.accountReplicas   ?? -1;
  const acctCloud  = cloud?.accountReplicas    ?? -1;

  const { processor, account } = SCALER_CONFIG;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16, alignItems: 'start' }}>
      {/* Left: charts */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <ReplicaChart
          history={history}
          title="Transaction Processor Replicas"
          subtitle={`KEDA · Kafka lag threshold ${processor.trigger.split(': ')[1]}`}
          awsKey="onpremProcessor"
          gcpKey="cloudProcessor"
          minRef={0}
          maxRef={processor.cloudMax}
        />
        <ReplicaChart
          history={history}
          title="Account Service Replicas"
          subtitle={`HPA · ${account.trigger}`}
          awsKey="onpremAccount"
          gcpKey="cloudAccount"
          minRef={account.cloudMin}
          maxRef={account.onpremMax}
        />
      </div>

      {/* Right: KPIs + breakdown */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {/* Live replica KPI tiles */}
        <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 14 }}>
          <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 12 }}>Live Replica Counts</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <ReplicaKpi
              label="transaction-processor"
              clusterLabel="GCP (cloud burst)"
              current={procCloud}
              min={processor.cloudMin}
              max={processor.cloudMax}
              accent={replicaAccent(procCloud, processor.cloudMin, processor.cloudMax)}
            />
            <ReplicaKpi
              label="transaction-processor"
              clusterLabel="AWS (on-prem)"
              current={procOnprem}
              min={processor.onpremMin}
              max={processor.onpremMax}
              accent={replicaAccent(procOnprem, processor.onpremMin, processor.onpremMax)}
            />
            <ReplicaKpi
              label="account-service"
              clusterLabel="GCP (cloud burst)"
              current={acctCloud}
              min={account.cloudMin}
              max={account.cloudMax}
              accent={replicaAccent(acctCloud, account.cloudMin, account.cloudMax)}
            />
            <ReplicaKpi
              label="account-service"
              clusterLabel="AWS (on-prem)"
              current={acctOnprem}
              min={account.onpremMin}
              max={account.onpremMax}
              accent={replicaAccent(acctOnprem, account.onpremMin, account.onpremMax)}
            />
          </div>
        </div>

        <ScalerBreakdownCard />
      </div>
    </div>
  );
}
