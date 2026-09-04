import React from 'react';
import { Chart, ChartLine, ChartAxis, ChartGroup, ChartVoronoiContainer } from '@patternfly/react-charts';
import { ThroughputPoint } from '../App';
import { ONPREM_CAPACITY_TPS } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR, GEN_COLOR, DARK_AXIS } from '../colors';
import { useElementSize } from '../hooks/useElementSize';

interface Props { history: ThroughputPoint[]; capacityTps?: number; }

export default function ThroughputChart({ history, capacityTps = ONPREM_CAPACITY_TPS }: Props) {
  const [containerRef, { width: chartWidth, height: rawHeight }] = useElementSize<HTMLDivElement>({ width: 560, height: 195 });
  const chartHeight = Math.max(150, rawHeight);

  const isEstimated = history.length > 0 && history[history.length - 1].estimated;
  const maxY = history.length > 0
    ? Math.max(capacityTps, ...history.map(p => Math.max(p.genRate, p.onpremCommit, p.cloudCommit)))
    : capacityTps;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '16px 8px 4px', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 12px 4px' }}>
        <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 14 }}>Processing Throughput Analysis</span>
        <div style={{ display: 'flex', gap: 14, fontSize: 12 }}>
          <span style={{ color: GEN_COLOR }}>— Generator</span>
          <span style={{ color: ONPREM_COLOR }}>— On-Prem commit</span>
          <span style={{ color: CLOUD_COLOR }}>— Cloud commit</span>
          <span style={{ color: '#c9190b' }}>– – Onprem cap. ({capacityTps} TPS)</span>
        </div>
      </div>
      <div style={{ padding: '0 12px 8px', fontSize: 12, color: '#6a6e73', display: 'flex', justifyContent: 'space-between' }}>
        <span>Gap between generator and commit lines = processing backlog. Gap closes as cloud scales up.</span>
        {isEstimated && (
          <span style={{ color: '#f4c14580', fontStyle: 'italic' }}>Commit lines estimated from traffic weights · real data when ledger active</span>
        )}
      </div>
      <div ref={containerRef} style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', inset: 0 }}>
        {history.length < 2 ? (
          <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
            Collecting data…
          </div>
        ) : (
          <Chart
            width={chartWidth}
            height={chartHeight}
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
                data={history.map(p => ({ x: p.ts, y: p.onpremCommit, name: 'On-Prem commit' }))}
                style={{ data: { stroke: ONPREM_COLOR, strokeWidth: 2 } }}
              />
              <ChartLine
                data={history.map(p => ({ x: p.ts, y: p.cloudCommit, name: 'Cloud commit' }))}
                style={{ data: { stroke: CLOUD_COLOR, strokeWidth: 2 } }}
              />
            </ChartGroup>
            <ChartLine
              data={[
                { x: history[0].ts, y: capacityTps },
                { x: history[history.length - 1].ts, y: capacityTps },
              ]}
              style={{ data: { stroke: '#c9190b', strokeWidth: 1.5, strokeDasharray: '6,3' } }}
            />
          </Chart>
        )}
        </div>
      </div>
    </div>
  );
}
