/**
 * 模拟长任务进度（§2.4 长任务 + §1.2 全局任务条）。
 */
import { useMemo } from 'react';
import { Button, Progress, Tooltip, Typography } from 'antd';
import {
  CheckCircleOutlined, CloseCircleOutlined, DownOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
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
  onCollapse?: () => void;
}

interface TriggerProps {
  tasks: RunningTask[];
  onClick: () => void;
}

const categoryLabel: Record<RunningTask['category'], string> = {
  COLLECT: '采集',
  COMPACTION: '合并',
  DAG: '编排',
  API: '服务',
  QUALITY: '质量',
};

const statusMeta: Record<RunningTask['status'], { label: string; color: string; bg: string; icon: ReactNode }> = {
  running: {
    label: '运行中',
    color: 'var(--ol-info)',
    bg: 'var(--ol-info-soft)',
    icon: <LoadingOutlined spin />,
  },
  success: {
    label: '已完成',
    color: 'var(--ol-success)',
    bg: 'var(--ol-success-soft)',
    icon: <CheckCircleOutlined />,
  },
  failed: {
    label: '失败',
    color: 'var(--ol-error)',
    bg: 'var(--ol-error-soft)',
    icon: <CloseCircleOutlined />,
  },
};

function getTaskStats(tasks: RunningTask[]) {
  const runningCount = tasks.filter((t) => t.status === 'running').length;
  const failedCount = tasks.filter((t) => t.status === 'failed').length;
  const averageProgress = tasks.length === 0
    ? 0
    : Math.round(tasks.reduce((sum, t) => sum + t.progress, 0) / tasks.length);
  return { runningCount, failedCount, averageProgress };
}

export function TaskProgressTrigger({ tasks, onClick }: TriggerProps) {
  const { runningCount, failedCount, averageProgress } = useMemo(() => getTaskStats(tasks), [tasks]);
  const dotColor = runningCount > 0 ? 'var(--ol-info)' : failedCount > 0 ? 'var(--ol-error)' : 'var(--ol-success)';

  return (
    <Tooltip title="展开全局任务条">
      <button
        type="button"
        onClick={onClick}
        aria-label="展开全局任务条"
        style={{
          height: 34,
          minWidth: 138,
          border: '1px solid var(--ol-line-soft)',
          borderRadius: 'var(--ol-radius-md)',
          background: 'var(--ol-fill)',
          color: 'var(--ol-ink)',
          padding: '0 10px',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 8,
          cursor: 'pointer',
        }}
      >
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: dotColor,
            boxShadow: runningCount > 0 ? '0 0 0 4px rgba(14, 165, 233, 0.12)' : 'none',
            flexShrink: 0,
          }}
        />
        <span style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap' }}>任务</span>
        <span className="ol-chip" style={{ padding: '0 6px', lineHeight: '18px', background: 'var(--ol-card)' }}>
          {tasks.length}
        </span>
        <span className="tnum" style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginLeft: 2 }}>
          {averageProgress}%
        </span>
      </button>
    </Tooltip>
  );
}

export function TaskProgressBar({ tasks, extra, onCollapse }: Props) {
  const { runningCount, failedCount, averageProgress } = useMemo(() => getTaskStats(tasks), [tasks]);

  return (
    <div
      style={{
        border: '1px solid var(--ol-line-soft)',
        borderRadius: 'var(--ol-radius-lg)',
        background: 'var(--ol-card)',
        boxShadow: 'var(--ol-shadow-e2)',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          minHeight: 44,
          padding: '8px 14px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          borderBottom: '1px solid var(--ol-line-soft)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
          <Text strong style={{ color: 'var(--ol-ink)', fontSize: 14 }}>全局任务条</Text>
          <span className="ol-chip" style={{ background: 'var(--ol-info-soft)', color: 'var(--ol-info)', border: 'none' }}>
            {tasks.length}
          </span>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {runningCount > 0 ? `${runningCount} 个运行中` : failedCount > 0 ? `${failedCount} 个失败` : '无运行中任务'}
          </Text>
          <Text type="secondary" className="tnum" style={{ fontSize: 12 }}>
            平均 {averageProgress}%
          </Text>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
          {extra}
          <Tooltip title="收起">
            <Button
              type="text"
              size="small"
              icon={<DownOutlined />}
              onClick={onCollapse}
              aria-label="收起全局任务条"
              style={{ color: 'var(--ol-ink-3)' }}
            />
          </Tooltip>
        </div>
      </div>

      {tasks.length === 0 ? (
        <div style={{ padding: '14px 16px' }}>
          <Text type="secondary" style={{ fontSize: 13 }}>暂无运行中任务</Text>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 0, padding: '6px 14px 10px' }}>
          {tasks.map((t) => (
            <div
              key={t.id}
              style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(220px, 320px) minmax(180px, 1fr) 48px',
                alignItems: 'center',
                gap: 14,
                minHeight: 34,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                <span
                  style={{
                    width: 20,
                    height: 20,
                    borderRadius: 6,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: statusMeta[t.status].bg,
                    color: statusMeta[t.status].color,
                    fontSize: 12,
                    flexShrink: 0,
                  }}
                >
                  {statusMeta[t.status].icon}
                </span>
                <span className="ol-chip" style={{ padding: '0 6px', lineHeight: '18px', flexShrink: 0 }}>
                  {categoryLabel[t.category]}
                </span>
                <Text className="ol-truncate" style={{ color: 'var(--ol-ink)', fontSize: 13, minWidth: 0 }}>
                  {t.name}
                </Text>
              </div>
              <Progress
                percent={t.progress}
                showInfo={false}
                size="small"
                status={t.status === 'failed' ? 'exception' : t.status === 'success' ? 'success' : 'active'}
                strokeColor={t.status === 'running' ? 'var(--ol-info)' : undefined}
                trailColor="var(--ol-line-soft)"
                style={{ margin: 0 }}
              />
              <Text className="tnum" style={{ color: 'var(--ol-ink-3)', fontSize: 12, textAlign: 'right' }}>
                {t.detail || `${t.progress}%`}
              </Text>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
