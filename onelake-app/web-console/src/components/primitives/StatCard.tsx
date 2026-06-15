/**
 * KPI 指标卡 StatCard
 *   - 主数值 + 标签
 *   - 可选趋势（▲/▼ + 百分比）
 *   - 可选 sparkline
 *   - 可选角标（如左下角的状态徽标）
 */
import type { ReactNode } from 'react';
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';

interface Props {
  label: ReactNode;
  value: ReactNode;
  suffix?: ReactNode;
  icon?: ReactNode;
  intent?: 'neutral' | 'success' | 'warning' | 'error' | 'info' | 'brand';
  delta?: { value: string; direction: 'up' | 'down'; good?: 'up' | 'down' };
  hint?: ReactNode;
  footer?: ReactNode;
  /** sparkline 数据点 — 简化为 SVG 折线 */
  spark?: number[];
  style?: React.CSSProperties;
  loading?: boolean;
}

const intentColors: Record<NonNullable<Props['intent']>, { fg: string; bg: string }> = {
  neutral: { fg: 'var(--ol-ink)',  bg: 'var(--ol-fill-soft)' },
  brand:   { fg: 'var(--ol-brand)',bg: 'var(--ol-brand-soft)' },
  success: { fg: 'var(--ol-success)', bg: 'var(--ol-success-soft)' },
  warning: { fg: 'var(--ol-warning)', bg: 'var(--ol-warning-soft)' },
  error:   { fg: 'var(--ol-error)',   bg: 'var(--ol-error-soft)' },
  info:    { fg: 'var(--ol-info)',    bg: 'var(--ol-info-soft)' },
};

function Sparkline({ data, color }: { data: number[]; color: string }) {
  if (data.length < 2) return null;
  const w = 80, h = 24;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const step = w / (data.length - 1);
  const pts = data.map((v, i) => `${i * step},${h - ((v - min) / range) * h}`).join(' ');
  const area = `0,${h} ${pts} ${w},${h}`;
  return (
    <svg width={w} height={h} style={{ display: 'block' }}>
      <polygon points={area} fill={color} opacity={0.10} />
      <polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

export function StatCard({
  label, value, suffix, icon, intent = 'neutral', delta, hint, footer, spark, style, loading,
}: Props) {
  const c = intentColors[intent];
  return (
    <div
      className="ol-section ol-anim-fade"
      style={{
        padding: 16,
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        position: 'relative',
        height: '100%',
        minHeight: 88,
        marginTop: 0,
        minWidth: 0,
        ...style,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', minHeight: 28 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          {icon && (
            <div
              style={{
                width: 28, height: 28, borderRadius: 8,
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                background: c.bg, color: c.fg, fontSize: 14, flexShrink: 0,
              }}
            >
              {icon}
            </div>
          )}
          <span style={{ fontSize: 12, color: 'var(--ol-ink-3)', fontWeight: 500 }} className="ol-truncate">{label}</span>
        </div>
        {spark && <Sparkline data={spark} color={c.fg} />}
      </div>

      {loading ? (
        <div className="ol-shimmer" style={{ width: 80, height: 26, borderRadius: 4, marginTop: 2 }} />
      ) : (
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginTop: 'auto', flexWrap: 'wrap' }}>
          <span
            className="tnum"
            style={{ fontSize: 24, fontWeight: 600, color: 'var(--ol-ink)', lineHeight: 1.2 }}
          >
            {value}
          </span>
          {suffix && <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{suffix}</span>}
          {delta && (
            <span
              style={{
                marginLeft: 'auto',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 2,
                fontSize: 12,
                fontWeight: 600,
                color: delta.good === delta.direction
                  ? 'var(--ol-success)'
                  : 'var(--ol-error)',
              }}
            >
              {delta.direction === 'up' ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
              {delta.value}
            </span>
          )}
        </div>
      )}

      {hint && (
        <div style={{ fontSize: 11, color: 'var(--ol-ink-4)', lineHeight: 1.4, minHeight: 15 }} className="ol-truncate">
          {hint}
        </div>
      )}
      {footer && <div style={{ marginTop: 4, fontSize: 12 }}>{footer}</div>}
    </div>
  );
}
