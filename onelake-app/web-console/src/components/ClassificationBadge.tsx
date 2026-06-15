/**
 * 密级徽章（§2.2 跨模块强约定）。
 * 采集层字段、目录资产、血缘节点、脱敏策略、API 返回字段，必须使用同一套 L1~L4 颜色。
 */
import { Tag, Tooltip } from 'antd';
import { classifications, ClassificationMeta } from './tokens';
import type { Classification } from '../types';

interface Props {
  level?: Classification;
  showName?: boolean;
  size?: 'small' | 'default';
}

export function ClassificationBadge({ level, showName = true, size }: Props) {
  if (!level) return null;
  const meta: ClassificationMeta = classifications[level];
  if (!meta) return null;
  return (
    <Tooltip title={`${meta.name} · ${meta.label}`}>
      <Tag
        color={undefined}
        style={{
          color: meta.color,
          background: meta.bg,
          border: `1px solid ${meta.border}`,
          fontSize: size === 'small' ? 11 : 12,
          margin: 0,
        }}
      >
        {meta.label} {showName && meta.name}
      </Tag>
    </Tooltip>
  );
}
