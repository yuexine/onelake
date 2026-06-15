/**
 * 状态徽章（§2.3 增强版）。
 *   - 色点 + 文本两段式，比 antd Tag 更轻量
 *   - running 自带脉冲动画
 *   - 全站语义一致：success / processing / warning / error / default
 */
import { Tooltip } from 'antd';
import { taskStatusColorMap } from './tokens';

const INTENT_MAP: Record<string, { dot: string; text: string }> = {
  success: { dot: 'var(--ol-success)', text: 'var(--ol-success)' },
  processing: { dot: 'var(--ol-info)', text: 'var(--ol-info)' },
  warning: { dot: 'var(--ol-warning)', text: 'var(--ol-warning)' },
  error: { dot: 'var(--ol-error)', text: 'var(--ol-error)' },
  default: { dot: 'var(--ol-ink-4)', text: 'var(--ol-ink-2)' },
};

interface Props {
  status: string;
  label?: string;
  pulsing?: boolean;
  tooltip?: string;
  size?: 'sm' | 'md';
}

export function StatusBadge({ status, label, pulsing, tooltip, size = 'sm' }: Props) {
  const intent = taskStatusColorMap[status] || 'default';
  const c = INTENT_MAP[intent] || INTENT_MAP.default;
  const isRunning = (status === 'RUNNING' || intent === 'processing') && pulsing !== false;

  const content = (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        fontSize: size === 'sm' ? 12 : 13,
        lineHeight: 1,
        whiteSpace: 'nowrap',
      }}
    >
      <span
        className={`ol-status-dot ${isRunning ? 'is-running' : ''}`}
        style={{
          background: c.dot,
          width: 7, height: 7,
        }}
      />
      <span style={{ color: c.text, fontWeight: 500 }}>{label || status}</span>
    </span>
  );

  return tooltip ? <Tooltip title={tooltip}>{content}</Tooltip> : content;
}
