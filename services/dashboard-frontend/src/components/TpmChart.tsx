import React from 'react';
import { Chart, ChartArea, ChartAxis, ChartGroup, ChartLine, ChartVoronoiContainer } from '@patternfly/react-charts';
import { TpmPoint } from '../App';
import { ONPREM_CAPACITY_TPS } from '../types/metrics';
import { ONPREM_COLOR, ONPREM_COLOR_ALPHA, CLOUD_COLOR, CLOUD_COLOR_ALPHA, DARK_AXIS } from '../colors';
import { useElementSize } from '../hooks/useElementSize';

interface Props { history: TpmPoint[]; capacityTps?: number; }

export default function TpmChart({ history, capacityTps = ONPREM_CAPACITY_TPS }: Props) {
  const [containerRef, { width: chartWidth, height: rawHeight }] = useElementSize<HTMLDivElement>({ width: 560, height: 210 });
  const chartHeight = Math.max(160, rawHeight);
  const capacityTpm = capacityTps * 60;

  const maxY = history.length > 0
    ? Math.max(capacityTpm, ...history.map(p => Math.max(p.onprem, p.cloud)))
    : capacityTpm;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '16px 8px 4px', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 12px 12px' }}>
        <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 14 }}>TPM Over Time</span>
        <div style={{ display: 'flex', gap: 16, fontSize: 12 }}>
          <span style={{ color: ONPREM_COLOR }}>■ On-Prem</span>
          <span style={{ color: CLOUD_COLOR }}>■ Cloud</span>
          <span style={{ color: '#c9190b' }}>– – Onprem capacity ({capacityTps} TPS)</span>
        </div>
      </div>
      <div ref={containerRef} style={{ flex: 1, minHeight: 0 }}>
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
                data={history.map(p => ({ x: p.ts, y: p.onprem, name: 'On-Prem' }))}
                style={{ data: { fill: ONPREM_COLOR_ALPHA, stroke: ONPREM_COLOR, strokeWidth: 2 } }}
              />
              <ChartArea
                data={history.map(p => ({ x: p.ts, y: p.cloud, name: 'Cloud' }))}
                style={{ data: { fill: CLOUD_COLOR_ALPHA, stroke: CLOUD_COLOR, strokeWidth: 2 } }}
              />
            </ChartGroup>
            <ChartLine
              data={[
                { x: history[0].ts, y: capacityTpm },
                { x: history[history.length - 1].ts, y: capacityTpm },
              ]}
              style={{ data: { stroke: '#c9190b', strokeWidth: 1.5, strokeDasharray: '6,3' } }}
            />
          </Chart>
        )}
      </div>
    </div>
  );
}
