import { clockTime, formatValue, isOutOfNormal } from '../lib/format';
import { METRICS, type Metric, type VitalPoint } from '../lib/types';

const W = 480;
const H = 168;
const PAD = { left: 40, right: 12, top: 10, bottom: 24 };

interface Props {
  metric: Metric;
  points: VitalPoint[];
  from: number;
  to: number;
  loading?: boolean;
}

/** Hand-rolled SVG line chart: normal range as a band, minute resolution adds a min/max ribbon. */
export function VitalChart({ metric, points, from, to, loading }: Props) {
  const info = METRICS[metric];
  const last = points[points.length - 1];
  const values = points.flatMap((p) => [p.min ?? p.value, p.max ?? p.value]);
  const dataMin = values.length ? Math.min(...values) : info.normalMin;
  const dataMax = values.length ? Math.max(...values) : info.normalMax;
  const span = Math.max(dataMax - dataMin, (info.normalMax - info.normalMin) * 0.25, 1);
  const yMin = dataMin - span * 0.15;
  const yMax = dataMax + span * 0.15;

  const x = (t: number) =>
    PAD.left + ((t - from) / Math.max(to - from, 1)) * (W - PAD.left - PAD.right);
  const y = (v: number) => PAD.top + (1 - (v - yMin) / (yMax - yMin)) * (H - PAD.top - PAD.bottom);
  const clampY = (v: number) => Math.min(Math.max(y(v), PAD.top), H - PAD.bottom);

  const line = points
    .map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)} ${y(p.value).toFixed(1)}`)
    .join('');
  const ribbon =
    points.length > 1 && points.every((p) => p.min !== undefined && p.max !== undefined)
      ? `${points.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)} ${y(p.max as number).toFixed(1)}`).join('')}${[
          ...points,
        ]
          .reverse()
          .map((p) => `L${x(p.t).toFixed(1)} ${y(p.min as number).toFixed(1)}`)
          .join('')}Z`
      : null;

  const ticks = [0, 1, 2, 3].map((i) => yMin + ((yMax - yMin) * i) / 3);
  const out = last ? isOutOfNormal(metric, last.value) : false;
  const direction = last && last.value < info.normalMin ? 'below' : 'above';
  const summary = last
    ? `${info.label}: latest ${formatValue(metric, last.value)} ${info.unit}, ${out ? `${direction} normal range` : 'within normal range'}`
    : `${info.label}: no data in this range`;

  return (
    <figure className={`chart${out ? ' chart-out' : ''}`} data-testid={`chart-${metric}`}>
      <figcaption>
        <span className="chart-name">{info.label}</span>
        <span className="chart-value">
          {last ? formatValue(metric, last.value) : '–'}
          <small>{info.unit}</small>
        </span>
        <span className="chart-state">
          {last
            ? out
              ? `${direction === 'below' ? 'Below' : 'Above'} normal`
              : 'Normal'
            : loading
              ? 'Loading'
              : 'No data'}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} preserveAspectRatio="none">
        <rect
          className="band"
          x={PAD.left}
          width={W - PAD.left - PAD.right}
          y={clampY(Math.min(info.normalMax, yMax))}
          height={Math.max(
            clampY(Math.max(info.normalMin, yMin)) - clampY(Math.min(info.normalMax, yMax)),
            0,
          )}
        />
        {ticks.map((v) => (
          <g key={v}>
            <line className="grid-line" x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} />
            <text className="axis" x={PAD.left - 6} y={y(v) + 3} textAnchor="end">
              {v.toFixed(info.decimals)}
            </text>
          </g>
        ))}
        {[from, (from + to) / 2, to].map((t, i) => (
          <text
            key={t}
            className="axis"
            x={x(t)}
            y={H - 6}
            textAnchor={i === 0 ? 'start' : i === 2 ? 'end' : 'middle'}
          >
            {clockTime(t)}
          </text>
        ))}
        {ribbon && <path className="ribbon" d={ribbon} />}
        {points.length > 0 && <path className="series" d={line} />}
        {last && <circle className="last" cx={x(last.t)} cy={y(last.value)} r="3.5" />}
      </svg>
    </figure>
  );
}
