import React, { useState } from 'react';
import { Chart, ChartLine, ChartAxis, ChartGroup, ChartVoronoiContainer } from '@patternfly/react-charts';
import { ResourcePoint } from '../App';
import { MetricsPayload, NamespaceResources, QuotaItem } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR, CAPACITY_COLOR, GEN_COLOR, HEALTHY_COLOR, DARK_AXIS } from '../colors';
import { useElementSize } from '../hooks/useElementSize';

interface Props {
  history: ResourcePoint[];
  payload: MetricsPayload | null;
}

type ClusterId = 'onprem' | 'cloud';
type Kind = 'cpu' | 'bytes' | 'count';
type Metric = 'cpu' | 'memory' | 'pods';

const NAMESPACES = ['banking-demo', 'banking-infra'];
const CLUSTERS: { id: ClusterId; label: string; color: string }[] = [
  { id: 'onprem', label: 'On-Prem', color: ONPREM_COLOR },
  { id: 'cloud',  label: 'Cloud',   color: CLOUD_COLOR },
];

// Display order and labels for the quota keys in infra/namespaces/*-limits.yaml.
// A key that is not listed here is still shown, after these, under its raw name.
const RESOURCES: { key: string; label: string; kind: Kind }[] = [
  { key: 'requests.cpu',           label: 'CPU requests',    kind: 'cpu' },
  { key: 'limits.cpu',             label: 'CPU limits',      kind: 'cpu' },
  { key: 'requests.memory',        label: 'Memory requests', kind: 'bytes' },
  { key: 'limits.memory',          label: 'Memory limits',   kind: 'bytes' },
  { key: 'pods',                   label: 'Pods',            kind: 'count' },
  { key: 'persistentvolumeclaims', label: 'PVCs',            kind: 'count' },
  { key: 'requests.storage',       label: 'Storage',         kind: 'bytes' },
];

const METRICS: { id: Metric; label: string; quotaKey: string; unit: string }[] = [
  { id: 'cpu',    label: 'CPU',    quotaKey: 'requests.cpu',    unit: 'cores' },
  { id: 'memory', label: 'Memory', quotaKey: 'requests.memory', unit: 'GiB' },
  { id: 'pods',   label: 'Pods',   quotaKey: 'pods',            unit: 'pods' },
];

const GIB = 1024 * 1024 * 1024;

function formatValue(v: number, kind: Kind): string {
  if (kind === 'count') return String(Math.round(v));
  if (kind === 'bytes') {
    const gi = v / GIB;
    return `${gi >= 100 ? gi.toFixed(0) : gi.toFixed(1)}Gi`;
  }
  return v >= 10 ? v.toFixed(1) : v.toFixed(2);
}

// Same thresholds the OpenShift console uses on its quota charts.
function utilisationColor(pct: number): string {
  if (pct >= 90) return CAPACITY_COLOR;
  if (pct >= 75) return GEN_COLOR;
  return HEALTHY_COLOR;
}

function findNs(list: NamespaceResources[] | undefined, ns: string): NamespaceResources | undefined {
  return list?.find(r => r.namespace === ns);
}

function findItem(r: NamespaceResources | undefined, key: string): QuotaItem | undefined {
  return r?.items.find(i => i.resource === key);
}

function orderedItems(r: NamespaceResources): { item: QuotaItem; label: string; kind: Kind }[] {
  const known = RESOURCES
    .map(def => ({ def, item: findItem(r, def.key) }))
    .filter((x): x is { def: typeof RESOURCES[number]; item: QuotaItem } => x.item !== undefined)
    .map(x => ({ item: x.item, label: x.def.label, kind: x.def.kind }));
  const extra = r.items
    .filter(i => !RESOURCES.some(def => def.key === i.resource))
    .map(i => ({ item: i, label: i.resource, kind: 'count' as Kind }));
  return [...known, ...extra];
}

// viewBox-scaled ring: it takes whatever height the card can spare, so nothing here
// has a fixed pixel size that could push the pane into scrolling.
function Donut({ item, label, kind }: { item: QuotaItem; label: string; kind: Kind }) {
  const pct = item.hard > 0 ? (item.used / item.hard) * 100 : 0;
  const color = utilisationColor(pct);
  const R = 40;
  const C = 2 * Math.PI * R;
  const filled = Math.min(100, Math.max(0, pct)) / 100 * C;
  return (
    <div
      title={`${item.resource}: ${formatValue(item.used, kind)} of ${formatValue(item.hard, kind)} used`}
      style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}
    >
      <div style={{ fontSize: 10, color: '#8a8d90', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }}>{label}</div>
      <div style={{ flex: 1, minHeight: 0, width: '100%', position: 'relative' }}>
        <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
          <circle cx="50" cy="50" r={R} fill="none" stroke="#2a2d32" strokeWidth="11" />
          <circle
            cx="50" cy="50" r={R} fill="none" stroke={color} strokeWidth="11" strokeLinecap="butt"
            strokeDasharray={`${filled} ${C - filled}`}
            transform="rotate(-90 50 50)"
            style={{ transition: 'stroke-dasharray 0.4s ease, stroke 0.3s ease' }}
          />
          <text x="50" y="56" textAnchor="middle" fontSize="19" fontWeight="700" fill="#f0f0f0">{Math.round(pct)}%</text>
        </svg>
      </div>
      <div style={{ fontSize: 10, color: '#c0c2c5', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
        {formatValue(item.used, kind)} <span style={{ color: '#6a6e73' }}>/ {formatValue(item.hard, kind)}</span>
      </div>
    </div>
  );
}

// Live usage (metrics API) against what the pods reserve (requests) and may use (limits).
// The bar's full width is the sum of limits; the marker is the sum of requests.
function UsageBar({ label, kind, usage, requested, limit, color }: {
  label: string; kind: Kind; usage: number; requested: number | undefined; limit: number | undefined; color: string;
}) {
  const known = usage >= 0;
  const scale = Math.max(limit ?? 0, requested ?? 0, known ? usage : 0, 1e-9);
  const usagePct = known ? Math.min(100, (usage / scale) * 100) : 0;
  const reqPct = requested !== undefined ? Math.min(100, (requested / scale) * 100) : -1;
  const unit = kind === 'cpu' ? ' cores' : '';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 10 }}>
      <span style={{ color: '#8a8d90', width: 46, flexShrink: 0 }}>{label}</span>
      <div style={{ flex: 1, height: 6, background: '#2a2d32', borderRadius: 3, position: 'relative' }}>
        <div style={{ width: `${usagePct}%`, height: '100%', background: color, borderRadius: 3, transition: 'width 0.4s ease' }} />
        {reqPct >= 0 && (
          <div style={{ position: 'absolute', left: `${reqPct}%`, top: -2, width: 2, height: 10, background: '#c0c2c5' }} />
        )}
      </div>
      <span style={{ color: '#c0c2c5', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', flexShrink: 0 }}>
        <span style={{ color, fontWeight: 700 }}>{known ? formatValue(usage, kind) : '—'}</span>{unit} used
        {requested !== undefined && <span style={{ color: '#8a8d90' }}> · {formatValue(requested, kind)} requested</span>}
        {limit !== undefined && <span style={{ color: '#6a6e73' }}> · {formatValue(limit, kind)} limits</span>}
      </span>
    </div>
  );
}

function NamespaceCard({ namespace, clusterLabel, color, data }: {
  namespace: string; clusterLabel: string; color: string; data: NamespaceResources | undefined;
}) {
  const items = data ? orderedItems(data) : [];
  return (
    <div style={{
      background: '#1b1d21', border: '1px solid #2a2d32', borderTop: `3px solid ${color}`, borderRadius: 8,
      padding: '10px 14px', flex: 1, minHeight: 0, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8, overflow: 'hidden',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', flexShrink: 0 }}>
        <span style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0' }}>
          <span style={{ color }}>{clusterLabel}</span> · {namespace}
        </span>
        <span style={{ fontSize: 11, color: '#6a6e73' }}>{data?.quota ?? ''}</span>
      </div>
      {items.length === 0 ? (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
          {data ? 'No ResourceQuota in this namespace' : 'Waiting for data…'}
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', gap: 8, flex: 1, minHeight: 0 }}>
            {items.map(i => <Donut key={i.item.resource} item={i.item} label={i.label} kind={i.kind} />)}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5, flexShrink: 0, borderTop: '1px solid #2a2d32', paddingTop: 7 }}>
            <UsageBar
              label="CPU" kind="cpu" color={color}
              usage={data?.usageCpuCores ?? -1}
              requested={findItem(data, 'requests.cpu')?.used}
              limit={findItem(data, 'limits.cpu')?.used}
            />
            <UsageBar
              label="Memory" kind="bytes" color={color}
              usage={data?.usageMemoryBytes ?? -1}
              requested={findItem(data, 'requests.memory')?.used}
              limit={findItem(data, 'limits.memory')?.used}
            />
          </div>
        </>
      )}
    </div>
  );
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      style={{
        fontSize: 11, fontWeight: 600, padding: '2px 10px', borderRadius: 10, cursor: 'pointer',
        background: active ? '#06c' : 'transparent',
        color: active ? '#fff' : '#8a8d90',
        border: `1px solid ${active ? '#06c' : '#3c3f42'}`,
      }}
    >{children}</button>
  );
}

type Datum = { x: number; y: number; name: string };

function TrendCard({ history }: { history: ResourcePoint[] }) {
  const [namespace, setNamespace] = useState(NAMESPACES[0]);
  const [metricId, setMetricId] = useState<Metric>('cpu');
  const [containerRef, { width: chartWidth, height: rawHeight }] = useElementSize<HTMLDivElement>({ width: 900, height: 180 });
  const chartHeight = Math.max(120, rawHeight);
  const metric = METRICS.find(m => m.id === metricId)!;
  const divisor = metricId === 'memory' ? GIB : 1;

  const series = (cluster: ClusterId, label: string, pick: (r: NamespaceResources) => number | undefined): Datum[] => {
    const out: Datum[] = [];
    for (const p of history) {
      const r = findNs(p[cluster], namespace);
      const v = r ? pick(r) : undefined;
      if (v !== undefined && v >= 0) out.push({ x: p.ts, y: v / divisor, name: label });
    }
    return out;
  };
  const reserved = (r: NamespaceResources) => findItem(r, metric.quotaKey)?.used;
  const live = (r: NamespaceResources) => metricId === 'cpu' ? r.usageCpuCores : r.usageMemoryBytes;
  const reservedWord = metricId === 'pods' ? 'pods' : 'requested';

  const lines = CLUSTERS.map(c => ({
    ...c,
    reserved: series(c.id, `${c.label} ${reservedWord}`, reserved),
    live: metricId === 'pods' ? [] : series(c.id, `${c.label} live usage`, live),
  }));

  // Quota ceiling: the hard value is the same manifest on both clusters.
  const last = history.length > 0 ? history[history.length - 1] : undefined;
  const hardRaw = findItem(findNs(last?.onprem, namespace), metric.quotaKey)?.hard
    ?? findItem(findNs(last?.cloud, namespace), metric.quotaKey)?.hard;
  const hard = hardRaw !== undefined ? hardRaw / divisor : undefined;
  const points = lines.reduce((n, l) => Math.max(n, l.reserved.length), 0);
  const maxSeen = Math.max(0, ...lines.flatMap(l => [...l.reserved, ...l.live]).map(d => d.y));
  const maxY = Math.max(hard ?? 0, maxSeen, 1);
  const fmt = (v: number) => metricId === 'pods' ? String(Math.round(v)) : v.toFixed(v >= 10 ? 1 : 2);

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '10px 8px 4px', flex: 2, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 8px 6px', gap: 12, flexWrap: 'wrap', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: '#f0f0f0', fontWeight: 600, fontSize: 13, marginRight: 4 }}>Trend <span style={{ color: '#6a6e73', fontWeight: 400, fontSize: 11 }}>({metric.unit})</span></span>
          {NAMESPACES.map(ns => <Chip key={ns} active={ns === namespace} onClick={() => setNamespace(ns)}>{ns}</Chip>)}
          <span style={{ width: 1, height: 14, background: '#2a2d32' }} />
          {METRICS.map(m => <Chip key={m.id} active={m.id === metricId} onClick={() => setMetricId(m.id)}>{m.label}</Chip>)}
        </div>
        <div style={{ display: 'flex', gap: 12, fontSize: 11, color: '#8a8d90' }}>
          <span style={{ color: ONPREM_COLOR }}>— On-Prem</span>
          <span style={{ color: CLOUD_COLOR }}>— Cloud</span>
          <span>solid: {reservedWord}</span>
          {metricId !== 'pods' && <span>dashed: live usage</span>}
          <span style={{ color: CAPACITY_COLOR }}>- - quota</span>
        </div>
      </div>
      <div ref={containerRef} style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', inset: 0 }}>
          {points < 2 ? (
            <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6a6e73', fontSize: 13 }}>
              Collecting data…
            </div>
          ) : (
            <Chart
              width={chartWidth}
              height={chartHeight}
              padding={{ bottom: 34, left: 52, right: 16, top: 8 }}
              minDomain={{ y: 0 }}
              maxDomain={{ y: maxY * 1.08 }}
              containerComponent={
                <ChartVoronoiContainer
                  labels={({ datum }) => datum.name ? `${datum.name}: ${fmt(datum.y)} ${metric.unit}` : ''}
                  constrainToVisibleArea
                />
              }
              style={{ parent: { background: 'transparent' } }}
            >
              <ChartAxis
                tickFormat={(t: number) =>
                  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                tickCount={6}
                style={DARK_AXIS}
              />
              <ChartAxis dependentAxis tickFormat={(t: number) => fmt(t)} style={DARK_AXIS} />
              <ChartGroup>
                {lines.filter(l => l.reserved.length > 0).map(l => (
                  <ChartLine key={`${l.id}-reserved`} data={l.reserved} style={{ data: { stroke: l.color, strokeWidth: 2 } }} />
                ))}
                {lines.filter(l => l.live.length > 0).map(l => (
                  <ChartLine key={`${l.id}-live`} data={l.live} style={{ data: { stroke: l.color, strokeWidth: 2, strokeDasharray: '5,4' } }} />
                ))}
              </ChartGroup>
              {hard !== undefined && (
                <ChartLine
                  data={[
                    { x: history[0].ts, y: hard },
                    { x: history[history.length - 1].ts, y: hard },
                  ]}
                  style={{ data: { stroke: CAPACITY_COLOR, strokeWidth: 1, strokeDasharray: '4,4' } }}
                />
              )}
            </Chart>
          )}
        </div>
      </div>
    </div>
  );
}

export default function ResourceConsumptionPanel({ history, payload }: Props) {
  const byCluster = (id: ClusterId) => payload?.clusters.find(c => c.cluster === id)?.resources;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, height: '100%', minHeight: 0 }}>
      {NAMESPACES.map(ns => (
        <div key={ns} style={{ display: 'flex', gap: 14, flex: 3, minHeight: 0 }}>
          {CLUSTERS.map(c => (
            <NamespaceCard
              key={c.id}
              namespace={ns}
              clusterLabel={c.label}
              color={c.color}
              data={payload ? findNs(byCluster(c.id), ns) : undefined}
            />
          ))}
        </div>
      ))}
      <TrendCard history={history} />
    </div>
  );
}
