import React, { useRef, useEffect, useState } from 'react';
import { Chart, ChartLine, ChartAxis, ChartGroup, ChartVoronoiContainer } from '@patternfly/react-charts';
import { ThroughputPoint } from '../App';
import { ONPREM_CAPACITY_TPS } from '../types/metrics';

interface Props { history: ThroughputPoint[]; }

const GEN_COLOR  = '#f4c145';
const AWS_COLOR  = '#06c';
const GCP_COLOR  = '#4cb140';
const DARK_AXIS  = {
  axis: { stroke: '#3c3f42' },
  tickLabels: { fill: '#6a6e73', fontSize: 10 },
  grid: { stroke: '#2a2d32', strokeDasharray: '2,4' },
};

export default function ThroughputChart({ history }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [chartWidth, setChartWidth] = useState(560);

  useEffect(() => {
    if (!containerRef.current) return;
    const ro = new ResizeObserver(entries => setChartWidth(Math.floor(entries[0].contentRect.width) - 2));
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, []);

  const isEstimated = history.length > 0 && history[history.length - 1].estimated;
  const maxY = history.length > 0
    ? Math.max(ONPREM_CAPACITY_TPS, ...history.map(p => Math.max(p.genRate, p.onpremCommit, p.cloudCommit)))
    : ONPREM_CAPACITY_TPS;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '16px 8px 4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 12px 4px' }}>
        <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 14 }}>Processing Throughput Analysis</span>
        <div style={{ display: 'flex', gap: 14, fontSize: 12 }}>
          <span style={{ color: GEN_COLOR }}>— Generator</span>
          <span style={{ color: AWS_COLOR }}>— AWS commit</span>
          <span style={{ color: GCP_COLOR }}>— GCP commit</span>
          <span style={{ color: '#c9190b' }}>– – Onprem cap. ({ONPREM_CAPACITY_TPS} TPS)</span>
        </div>
      </div>
      <div style={{ padding: '0 12px 8px', fontSize: 12, color: '#6a6e73', display: 'flex', justifyContent: 'space-between' }}>
        <span>Gap between generator and commit lines = processing backlog. Gap closes as cloud scales up.</span>
        {isEstimated && (
          <span style={{ color: '#f4c14580', fontStyle: 'italic' }}>Commit lines estimated from traffic weights · real data when ledger active</span>
        )}
      </div>
      <div ref={containerRef}>
        {history.length < 2 ? (
          <div style={{ height: 195, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
            Collecting data…
          </div>
        ) : (
          <Chart
            width={chartWidth}
            height={195}
            padding={{ bottom: 40, left: 62, right: 16, top: 6 }}
            minDomain={{ y: 0 }}
            maxDomain={{ y: maxY * 1.15 }}
            domainPadding={{ y: [20, 10] }}
            containerComponent={<ChartVoronoiContainer labels={({ datum }) => datum.name ? `${datum.name}: ${datum.y.toFixed(1)} TPS` : ''} constrainToVisibleArea />}
            style={{ parent: { background: 'transparent' } }}
          >
            <ChartAxis
              tickFormat={(t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
              tickCount={5}
              style={DARK_AXIS}
            />
            <ChartAxis dependentAxis tickFormat={(t: number) => `${t.toFixed(0)}`} style={DARK_AXIS} />
            <ChartGroup>
              <ChartLine
                data={history.map(p => ({ x: p.ts, y: p.genRate, name: 'Generator' }))}
                style={{ data: { stroke: GEN_COLOR, strokeWidth: 2, strokeDasharray: '6,3' } }}
              />
              <ChartLine
                data={history.map(p => ({ x: p.ts, y: p.onpremCommit, name: 'AWS commit' }))}
                style={{ data: { stroke: AWS_COLOR, strokeWidth: 2 } }}
              />
              <ChartLine
                data={history.map(p => ({ x: p.ts, y: p.cloudCommit, name: 'GCP commit' }))}
                style={{ data: { stroke: GCP_COLOR, strokeWidth: 2 } }}
              />
            </ChartGroup>
            <ChartLine
              data={[
                { x: history[0].ts, y: ONPREM_CAPACITY_TPS },
                { x: history[history.length - 1].ts, y: ONPREM_CAPACITY_TPS },
              ]}
              style={{ data: { stroke: '#c9190b', strokeWidth: 1.5, strokeDasharray: '6,3' } }}
            />
          </Chart>
        )}
      </div>
    </div>
  );
}
