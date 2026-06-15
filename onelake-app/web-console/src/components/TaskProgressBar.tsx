/**
 * 模拟长任务进度（§2.4 长任务 + §1.2 全局任务条）。
 */
import { Card, Progress, Tag, Typography } from 'antd';
import { CheckCircleTwoTone, LoadingOutlined, CloseCircleTwoTone } from '@ant-design/icons';
import type { ReactNode } from 'react';

const { Text } = Typography;

export interface RunningTask {
  id: string;
  category: 'COLLECT' | 'COMPACTION' | 'DAG' | 'API' | 'QUALITY';
  name: string;
  progress: number;
  status: 'running' | 'success' | 'failed';
  detail?: string;
}

interface Props {
  tasks: RunningTask[];
  extra?: ReactNode;
}

export function TaskProgressBar({ tasks, extra }: Props) {
  return (
    <Card size="small" title={<><Text strong>全局任务条</Text><Tag color="processing" style={{ marginLeft: 8 }}>{tasks.length}</Tag></>} extra={extra}>
      {tasks.length === 0 ? (
        <Text type="secondary">暂无运行中任务</Text>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {tasks.map((t) => (
            <div key={t.id} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              {t.status === 'running' && <LoadingOutlined spin style={{ color: '#1677ff' }} />}
              {t.status === 'success' && <CheckCircleTwoTone twoToneColor="#52c41a" />}
              {t.status === 'failed' && <CloseCircleTwoTone twoToneColor="#ff4d4f" />}
              <Text style={{ width: 200, flexShrink: 0 }}>{t.name}</Text>
              <Progress percent={t.progress} size="small" style={{ flex: 1, margin: 0 }} />
              <Text type="secondary" style={{ width: 80, textAlign: 'right' }}>{t.detail}</Text>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
