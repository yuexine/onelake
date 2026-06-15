/**
 * 状态徽章（§2.3）。
 * 运行中(蓝/动效)、成功(绿)、失败(红)、等待(灰)、告警(橙)、已下线(暗)。
 */
import { Badge, Tag } from 'antd';
import { taskStatusColorMap } from './tokens';

interface Props {
  status: string;
  label?: string;
  pulsing?: boolean;
}

export function StatusBadge({ status, label, pulsing }: Props) {
  const color = taskStatusColorMap[status] || 'default';
  const text = label || status;
  if (status === 'RUNNING' && pulsing !== false) {
    return <Badge status="processing" text={text} />;
  }
  return <Tag color={color} style={{ margin: 0 }}>{text}</Tag>;
}
