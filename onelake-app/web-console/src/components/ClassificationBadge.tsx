/**
 * 密级徽章（§2.2 跨模块强约定）。
 *   采集层字段、目录资产、血缘节点、脱敏策略、API 返回字段，必须使用同一套 L1~L4 颜色。
 */
import { Tooltip } from 'antd';
import { classifications } from './tokens';

type Level = 'L1' | 'L2' | 'L3' | 'L4';

interface Props {
  level?: Level | string;
  showName?: boolean;
  size?: 'small' | 'default';
  only?: 'dot' | 'chip';
}

export function ClassificationBadge({ level, showName = true, size = 'default' }: Props) {
  if (!level) return null;
  const meta = classifications[(level as Level) ?? 'L1'];
  if (!meta) return null;
  const sm = size === 'small';
  return (
    <Tooltip title={`${meta.name} · ${meta.label}`}>
      <span
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 4,
          padding: sm ? '0 5px' : '1px 6px',
          borderRadius: 4,
          fontSize: sm ? 11 : 12,
          fontWeight: 500,
          lineHeight: sm ? '16px' : '18px',
          background: meta.bg,
          color: meta.color,
          border: `1px solid ${meta.border}`,
          whiteSpace: 'nowrap',
        }}
      >
        <span
          style={{
            width: 6, height: 6, borderRadius: '50%', background: meta.dot, display: 'inline-block',
          }}
        />
        <span>{meta.label}</span>
        {showName && <span style={{ opacity: 0.85 }}>{meta.name}</span>}
      </span>
    </Tooltip>
  );
}
