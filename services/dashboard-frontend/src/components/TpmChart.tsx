import React, { useRef, useEffect, useState } from 'react';
import { Chart, ChartArea, ChartAxis, ChartGroup, ChartLine, ChartVoronoiContainer } from '@patternfly/react-charts';
import { TpmPoint } from '../App';
import { ONPREM_CAPACITY_TPS } from '../types/metrics';

interface Props { history: TpmPoint[]; }

const AWS_COLOR = '#06c';
const GCP_COLOR = '#4cb140';
const CAPACITY_TPM = ONPREM_CAPACITY_TPS * 60;
const DARK_AXIS = {
  axis: { stroke: '#3c3f42' },
  tickLabels: { fill: '#6a6e73', fontSize: 10 },
  grid: { stroke: '#2a2d32', strokeDasharray: '2,4' },
};

export default function TpmChart({ history }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [chartWidth, setChartWidth] = useState(560);

  useEffect(() => {
    if (!containerRef.current) return;
    const ro = new ResizeObserver(entries => setChartWidth(Math.floor(entries[0].contentRect.width) - 2));
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, []);

  const maxY = history.length > 0
    ? Math.max(CAPACITY_TPM, ...history.map(p => Math.max(p.onprem, p.cloud)))
    : CAPACITY_TPM;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '16px 8px 4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 12px 12px' }}>
        <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 14 }}>TPM Over Time</span>
        <div style={{ display: 'flex', gap: 16, fontSize: 12 }}>
          <span style={{ color: AWS_COLOR }}>■ AWS (on-prem)</span>
          <span style={{ color: GCP_COLOR }}>■ GCP (cloud burst)</span>
          <span style={{ color: '#c9190b' }}>– – Onprem capacity ({ONPREM_CAPACITY_TPS} TPS)</span>
        </div>
      </div>
      <div ref={containerRef}>
        {history.length < 2 ? (
          <div style={{ height: 210, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
            Collecting data…
          </div>
        ) : (
          <Chart
            width={chartWidth}
            height={210}
            padding={{ bottom: 40, left: 62, right: 16, top: 6 }}
            minDomain={{ y: 0 }}
            maxDomain={{ y: maxY * 1.15 }}
            domainPadding={{ y: [20, 10] }}
            containerComponent={<ChartVoronoiContainer labels={({ datum }) => datum.name ? `${datum.name}: ${Math.round(datum.y)} TPM` : ''} constrainToVisibleArea />}
            style={{ parent: { background: 'transparent' } }}
          >
            <ChartAxis
              tickFormat={(t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
              tickCount={5}
              style={DARK_AXIS}
            />
            <ChartAxis dependentAxis tickFormat={(t: number) => `${Math.round(t)}`} style={DARK_AXIS} />
            <ChartGroup>
              <ChartArea
                data={history.map(p => ({ x: p.ts, y: p.onprem, name: 'AWS' }))}
                style={{ data: { fill: 'rgba(0,102,204,0.2)', stroke: AWS_COLOR, strokeWidth: 2 } }}
              />
              <ChartArea
                data={history.map(p => ({ x: p.ts, y: p.cloud, name: 'GCP' }))}
                style={{ data: { fill: 'rgba(76,177,64,0.2)', stroke: GCP_COLOR, strokeWidth: 2 } }}
              />
            </ChartGroup>
            <ChartLine
              data={[
                { x: history[0].ts, y: CAPACITY_TPM },
                { x: history[history.length - 1].ts, y: CAPACITY_TPM },
              ]}
              style={{ data: { stroke: '#c9190b', strokeWidth: 1.5, strokeDasharray: '6,3' } }}
            />
          </Chart>
        )}
      </div>
    </div>
  );
}
