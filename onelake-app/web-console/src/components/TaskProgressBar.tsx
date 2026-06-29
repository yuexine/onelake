/**
 * 全局任务条（§2.4 长任务 + §1.2 全局任务条）。
 */
import { useMemo } from 'react';
import { Button, Progress, Tooltip, Typography } from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  DownOutlined,
  EyeOutlined,
  LoadingOutlined,
  StopOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import type { RunningTask } from '../types';

const { Text } = Typography;

interface Props {
  tasks: RunningTask[];
  extra?: ReactNode;
  taskError?: string;
  busyTaskId?: string | null;
  onCollapse?: () => void;
  onTaskOpen?: (task: RunningTask) => void;
  onTaskCancel?: (task: RunningTask) => void;
  onTaskDismiss?: (task: RunningTask) => void;
}

interface TriggerProps {
  tasks: RunningTask[];
  onClick: () => void;
}

const taskTypeLabel: Record<string, string> = {
  COLLECT: '采集',
  COMPACTION: '合并',
  DAG: '编排',
  API: '服务',
  QUALITY: '质量',
  SQL: 'SQL',
  ALERT: '告警',
};

const statusMeta: Record<RunningTask['status'], { label: string; color: string; bg: string; icon: ReactNode }> = {
  QUEUED: {
    label: '排队中',
    color: 'var(--ol-ink-3)',
    bg: 'var(--ol-fill)',
    icon: <ClockCircleOutlined />,
  },
  RUNNING: {
    label: '运行中',
    color: 'var(--ol-info)',
    bg: 'var(--ol-info-soft)',
    icon: <LoadingOutlined spin />,
  },
  SUCCEEDED: {
    label: '已完成',
    color: 'var(--ol-success)',
    bg: 'var(--ol-success-soft)',
    icon: <CheckCircleOutlined />,
  },
  FAILED: {
    label: '失败',
    color: 'var(--ol-error)',
    bg: 'var(--ol-error-soft)',
    icon: <CloseCircleOutlined />,
  },
  CANCELLED: {
    label: '已取消',
    color: 'var(--ol-ink-3)',
    bg: 'var(--ol-fill)',
    icon: <StopOutlined />,
  },
};

const activeStatuses = new Set<RunningTask['status']>(['QUEUED', 'RUNNING']);

function getProgress(task: RunningTask) {
  if (typeof task.progress === 'number') return Math.max(0, Math.min(100, task.progress));
  return activeStatuses.has(task.status) ? 0 : 100;
}

function getTaskStats(tasks: RunningTask[]) {
  const runningCount = tasks.filter((t) => activeStatuses.has(t.status)).length;
  const failedCount = tasks.filter((t) => t.status === 'FAILED').length;
  const averageProgress = tasks.length === 0
    ? 0
    : Math.round(tasks.reduce((sum, t) => sum + getProgress(t), 0) / tasks.length);
  return { runningCount, failedCount, averageProgress };
}

function taskDetail(task: RunningTask) {
  if (task.status === 'FAILED' && task.errorMessage) return task.errorMessage;
  return task.detail || task.phase || `${getProgress(task)}%`;
}

function canDismiss(task: RunningTask) {
  return !activeStatuses.has(task.status);
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
          {runningCount > 0 ? `${runningCount} 运行中` : `${averageProgress}%`}
        </span>
      </button>
    </Tooltip>
  );
}

export function TaskProgressBar({
  tasks,
  extra,
  taskError,
  busyTaskId,
  onCollapse,
  onTaskOpen,
  onTaskCancel,
  onTaskDismiss,
}: Props) {
  const { runningCount, failedCount, averageProgress } = useMemo(() => getTaskStats(tasks), [tasks]);

  return (
    <div
      className="global-task-bar"
      style={{
        border: '1px solid var(--ol-line-soft)',
        borderRadius: 'var(--ol-radius-lg)',
        background: 'var(--ol-card)',
        boxShadow: 'var(--ol-shadow-e2)',
        display: 'flex',
        flexDirection: 'column',
        maxHeight: 'min(440px, calc(100vh - 96px))',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      <div
        className="global-task-bar__header"
        style={{
          minHeight: 44,
          padding: '8px 14px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          borderBottom: '1px solid var(--ol-line-soft)',
          flexShrink: 0,
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
          {taskError && (
            <Tooltip title={taskError}>
              <Text type="secondary" style={{ fontSize: 12, color: 'var(--ol-warning)' }}>刷新失败</Text>
            </Tooltip>
          )}
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
        <div
          className="global-task-bar__list"
          style={{
            display: 'grid',
            gap: 0,
            minHeight: 0,
            overflow: 'auto',
            overscrollBehavior: 'contain',
            padding: '6px 14px 10px',
          }}
        >
          {tasks.map((t) => (
            <div
              key={t.id}
              style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(220px, 360px) minmax(160px, 1fr) minmax(90px, 140px) 92px',
                alignItems: 'center',
                gap: 14,
                minHeight: 36,
                minWidth: 620,
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
                  {taskTypeLabel[t.taskType] || t.taskType}
                </span>
                <Tooltip title={t.title}>
                  <Text className="ol-truncate" style={{ color: 'var(--ol-ink)', fontSize: 13, minWidth: 0 }}>
                    {t.title}
                  </Text>
                </Tooltip>
              </div>
              <Progress
                percent={getProgress(t)}
                showInfo={false}
                size="small"
                status={t.status === 'FAILED' ? 'exception' : t.status === 'SUCCEEDED' ? 'success' : 'active'}
                strokeColor={activeStatuses.has(t.status) ? 'var(--ol-info)' : undefined}
                trailColor="var(--ol-line-soft)"
                style={{ margin: 0 }}
              />
              <Tooltip title={taskDetail(t)}>
                <Text className="ol-truncate" style={{ color: 'var(--ol-ink-3)', fontSize: 12, textAlign: 'right' }}>
                  {taskDetail(t)}
                </Text>
              </Tooltip>
              <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 2 }}>
                <Tooltip title="查看">
                  <Button
                    type="text"
                    size="small"
                    icon={<EyeOutlined />}
                    disabled={!t.link}
                    onClick={() => onTaskOpen?.(t)}
                    aria-label="查看任务"
                  />
                </Tooltip>
                {t.cancellable && (
                  <Tooltip title="取消">
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<StopOutlined />}
                      loading={busyTaskId === t.id}
                      onClick={() => onTaskCancel?.(t)}
                      aria-label="取消任务"
                    />
                  </Tooltip>
                )}
                {canDismiss(t) && (
                  <Tooltip title="关闭">
                    <Button
                      type="text"
                      size="small"
                      icon={<CloseOutlined />}
                      loading={busyTaskId === t.id}
                      onClick={() => onTaskDismiss?.(t)}
                      aria-label="关闭任务"
                    />
                  </Tooltip>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
