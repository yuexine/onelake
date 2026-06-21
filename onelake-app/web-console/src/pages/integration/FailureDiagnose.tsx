/**
 * 采集失败诊断（对应原型 §8.2.10 升级版）。
 *   - 顶部错误横幅 + 错误分类卡
 *   - 影响分析（卡内）
 *   - 处置方案按钮组
 */
import { useParams, useNavigate } from 'react-router-dom';
import {
  Space, Button, Typography, message, Steps, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, BranchesOutlined, ReloadOutlined,
  StepForwardOutlined, PauseCircleOutlined, WarningOutlined,
  ExclamationCircleOutlined, CheckCircleOutlined, FieldTimeOutlined,
  CopyOutlined, DatabaseOutlined, CodeOutlined, NodeIndexOutlined,
} from '@ant-design/icons';
import {
  PageHeader, StatusBadge, ImpactAnalysis, SectionCard,
} from '../../components';
import { syncRuns, syncTasks } from '../../mock';
import { IntegrationAPI } from '../../api';
import type { SyncRun, SyncTask } from '../../types';
import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';

const { Text } = Typography;

const statusLabel: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function formatDuration(ms?: number) {
  if (!ms) return '-';
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatRows(value?: number) {
  return (value ?? 0).toLocaleString();
}

function formatThroughput(run: SyncRun) {
  if (run.throughputRows && run.throughputRows > 0) {
    return `${run.throughputRows.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}/s`;
  }
  if ((run.rowsWritten ?? 0) > 0 && (run.durationMs ?? 0) > 0) return '<1/s';
  return '-';
}

function parseCheckpoint(value?: string): Record<string, unknown> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : { value: parsed };
  } catch {
    return { raw: value };
  }
}

function formatBytes(value?: unknown) {
  const bytes = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(bytes) || bytes <= 0) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }
  return `${size >= 10 || unitIndex === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unitIndex]}`;
}

function formatScalar(value?: unknown) {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'number') return Number.isFinite(value) ? value.toLocaleString() : '-';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}

function buildRunSnapshot(
  task: SyncTask,
  run: SyncRun,
  checkpoint: Record<string, unknown>,
  failedShards: number,
  totalShards: number,
) {
  return {
    run: {
      id: run.id,
      taskId: run.taskId,
      externalJobId: run.externalJobId,
      status: run.status,
      startedAt: run.startedAt,
      finishedAt: run.finishedAt,
      durationMs: run.durationMs,
      errorCode: run.errorCode,
      errorMsg: run.errorMsg,
    },
    metrics: {
      rowsRead: run.rowsRead,
      rowsWritten: run.rowsWritten,
      throughputRows: run.throughputRows,
      throughputText: formatThroughput(run),
      failedShards,
      totalShards,
      shardProgress: run.shardProgress,
      bytesSynced: checkpoint.bytesSynced,
      bytesSyncedText: formatBytes(checkpoint.bytesSynced),
    },
    task: {
      id: task.id,
      name: task.name,
      status: task.status,
      sourceId: task.sourceId,
      sourceName: task.sourceName,
      sourceTable: task.sourceTable,
      targetTable: task.targetTable,
      mode: task.mode,
      scheduleCron: task.scheduleCron,
      rateLimit: task.rateLimit,
      dirtyThreshold: task.dirtyThreshold,
      airbyteConnectionId: task.airbyteConnectionId,
      fieldMappingCount: task.fieldMapping?.length ?? 0,
      fieldMapping: task.fieldMapping,
    },
    checkpoint,
  };
}

interface SnapshotRow {
  label: string;
  value: ReactNode;
  mono?: boolean;
}

function SnapshotGroup({
  title,
  icon,
  rows,
  style,
}: {
  title: string;
  icon: ReactNode;
  rows: SnapshotRow[];
  style?: CSSProperties;
}) {
  return (
    <div style={{
      border: '1px solid var(--ol-line-soft)',
      borderRadius: 8,
      background: 'var(--ol-fill-soft)',
      overflow: 'hidden',
      minWidth: 0,
      ...style,
    }}
    >
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '10px 12px',
        borderBottom: '1px solid var(--ol-line-soft)',
        background: 'var(--ol-card)',
        fontSize: 13,
        fontWeight: 600,
        color: 'var(--ol-ink)',
      }}
      >
        <span style={{ color: 'var(--ol-brand)', fontSize: 14 }}>{icon}</span>
        {title}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(96px, 0.36fr) minmax(0, 1fr)' }}>
        {rows.map((row) => (
          <div key={row.label} style={{ display: 'contents' }}>
            <div style={{
              padding: '9px 12px',
              borderTop: '1px solid var(--ol-line-soft)',
              color: 'var(--ol-ink-3)',
              fontSize: 12,
              lineHeight: 1.5,
            }}
            >
              {row.label}
            </div>
            <div style={{
              padding: '9px 12px',
              borderTop: '1px solid var(--ol-line-soft)',
              color: 'var(--ol-ink)',
              fontSize: 12,
              lineHeight: 1.5,
              minWidth: 0,
              overflowWrap: 'anywhere',
            }}
            >
              {row.mono ? <Text code style={{ fontSize: 12 }}>{row.value}</Text> : row.value}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function RunSnapshotPanel({
  task,
  run,
  failedShards,
  totalShards,
}: {
  task: SyncTask;
  run: SyncRun;
  failedShards: number;
  totalShards: number;
}) {
  const checkpoint = useMemo(() => parseCheckpoint(run.checkpoint), [run.checkpoint]);
  const snapshot = useMemo(
    () => buildRunSnapshot(task, run, checkpoint, failedShards, totalShards),
    [task, run, checkpoint, failedShards, totalShards],
  );
  const rawJson = useMemo(() => JSON.stringify(snapshot, null, 2), [snapshot]);
  const checkpointRows = Object.entries(checkpoint).length
    ? Object.entries(checkpoint).map(([key, value]) => ({
      label: key,
      value: key.toLowerCase().includes('bytes') ? formatBytes(value) : formatScalar(value),
      mono: true,
    }))
    : [{ label: 'checkpoint', value: '暂无 checkpoint 参数', mono: false }];

  const copySnapshot = async () => {
    await navigator.clipboard.writeText(rawJson);
    message.success('运行快照参数已复制');
  };

  return (
    <SectionCard
      title="运行快照"
      subtitle="按运行、任务、指标和 checkpoint 还原本次执行参数"
      icon={<FieldTimeOutlined />}
      extra={<Button size="small" icon={<CopyOutlined />} onClick={copySnapshot}>复制参数</Button>}
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 12, alignItems: 'start' }}>
        <SnapshotGroup
          title="运行身份"
          icon={<NodeIndexOutlined />}
          rows={[
            { label: 'Run ID', value: run.id, mono: true },
            { label: 'Task ID', value: run.taskId, mono: true },
            { label: 'Airbyte Job', value: run.externalJobId || '-', mono: true },
            { label: '状态', value: <StatusBadge status={run.status} label={statusLabel[run.status] || run.status} /> },
            { label: '开始时间', value: formatDate(run.startedAt), mono: true },
            { label: '结束时间', value: formatDate(run.finishedAt), mono: true },
            { label: '耗时', value: formatDuration(run.durationMs) },
            ...(run.errorCode || run.errorMsg
              ? [
                { label: '错误码', value: run.errorCode || '-', mono: true },
                { label: '错误信息', value: run.errorMsg || '-' },
              ]
              : []),
          ]}
        />
        <SnapshotGroup
          title="数据与指标"
          icon={<DatabaseOutlined />}
          rows={[
            { label: '读取行数', value: formatRows(run.rowsRead), mono: true },
            { label: '写入行数', value: formatRows(run.rowsWritten), mono: true },
            { label: '吞吐', value: formatThroughput(run), mono: true },
            { label: '同步字节', value: formatBytes(checkpoint.bytesSynced), mono: true },
            { label: '分片进度', value: totalShards > 0 ? `${failedShards}/${totalShards} 失败` : '未返回分片信息' },
            { label: '分片原始值', value: run.shardProgress?.length ? run.shardProgress.join(', ') : '-', mono: true },
          ]}
        />
        <SnapshotGroup
          title="任务上下文"
          icon={<BranchesOutlined />}
          rows={[
            { label: '任务名称', value: task.name },
            { label: '任务状态', value: task.status, mono: true },
            { label: '源名称', value: task.sourceName },
            { label: '源表', value: task.sourceTable, mono: true },
            { label: '目标表', value: task.targetTable, mono: true },
            { label: '同步模式', value: task.mode, mono: true },
            { label: '调度 Cron', value: task.scheduleCron || '-', mono: true },
            { label: '限速', value: task.rateLimit ? `${task.rateLimit.toLocaleString()} rows/s` : '-' },
            { label: '脏数据阈值', value: task.dirtyThreshold ?? '-', mono: true },
            { label: '字段映射', value: `${task.fieldMapping?.length ?? 0} 个字段` },
            { label: 'Airbyte 连接', value: task.airbyteConnectionId || '-', mono: true },
          ]}
        />
        <SnapshotGroup
          title="Checkpoint 参数"
          icon={<CodeOutlined />}
          rows={checkpointRows}
          style={{ gridColumn: '1 / -1' }}
        />
      </div>
    </SectionCard>
  );
}

export default function FailureDiagnose() {
  const { id, runId } = useParams();
  const navigate = useNavigate();
  const [task, setTask] = useState<SyncTask>(syncTasks.find((t) => t.id === id) || syncTasks[0]);
  const [run, setRun] = useState<SyncRun>(syncRuns.find((r) => r.id === runId) || syncRuns[1]);

  useEffect(() => {
    if (!id || !runId) return;
    Promise.all([
      IntegrationAPI.getSyncTask(id),
      IntegrationAPI.getSyncRun(runId),
    ])
      .then(([nextTask, nextRun]) => {
        setTask(nextTask);
        setRun(nextRun);
      })
      .catch((e) => message.error(e.message || '运行诊断加载失败'));
  }, [id, runId]);

  const failedShards = run.shardProgress?.filter((p: number) => p < 100).length ?? 0;
  const totalShards = run.shardProgress?.length ?? 0;
  const isFailed = run.status === 'FAILED';
  const isSucceeded = run.status === 'SUCCEEDED';
  const pageTitle = isFailed ? '失败诊断' : '运行详情';
  const alertType = isFailed ? 'error' : isSucceeded ? 'success' : run.status === 'CANCELLED' ? 'warning' : 'info';
  const alertMessage = isFailed
    ? (run.errorMsg || '运行失败，暂无错误详情')
    : isSucceeded
      ? '运行已成功完成'
      : run.status === 'CANCELLED'
        ? '运行已取消'
        : '运行尚未结束，状态会随刷新更新';

  return (
    <div className="ol-page">
      <PageHeader
        icon={isFailed ? <WarningOutlined /> : <FieldTimeOutlined />}
        title={
          <Space size={8}>
            {pageTitle}
            <Text code style={{ fontSize: 13 }}>{run.id}</Text>
          </Space>
        }
        subtitle={<span style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>{task.name} · <Text code style={{ fontSize: 12 }}>{task.targetTable}</Text></span>}
        breadcrumb={[
          { path: '/integration/sync-tasks', label: '采集任务' },
          { path: `/integration/sync-tasks/${task.id}`, label: task.name },
          { label: `${pageTitle} · ${run.id}` },
        ]}
        actions={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/integration/sync-tasks/${task.id}`)}>返回任务</Button>}
      />

      <Alert
        type={alertType} showIcon
        icon={isFailed ? <ExclamationCircleOutlined style={{ fontSize: 18 }} /> : isSucceeded ? <CheckCircleOutlined style={{ fontSize: 18 }} /> : undefined}
        style={{ borderRadius: 10, padding: '14px 16px' }}
        message={
          <Space size={8}>
            <StatusBadge status={run.status} label={statusLabel[run.status] || run.status} pulsing={run.status === 'RUNNING'} />
            <Text strong>{alertMessage}</Text>
          </Space>
        }
        description={
          <Space split={<span className="ol-divider-v" />} wrap>
            {isFailed && <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>错误码：<Text code>{run.errorCode || '-'}</Text></Text>}
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>Airbyte Job：<Text code>{run.externalJobId || '-'}</Text></Text>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>开始：<Text code>{formatDate(run.startedAt)}</Text></Text>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>结束：<Text code>{formatDate(run.finishedAt)}</Text></Text>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>耗时：{formatDuration(run.durationMs)}</Text>
          </Space>
        }
      />

      <SectionCard title="运行指标" icon={<FieldTimeOutlined />}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
          {[
            { label: '读取行数', value: formatRows(run.rowsRead) },
            { label: '写入行数', value: formatRows(run.rowsWritten) },
            { label: '吞吐', value: formatThroughput(run) },
            { label: '失败分片', value: totalShards > 0 ? `${failedShards}/${totalShards}` : '-' },
          ].map((item) => (
            <div key={item.label} style={{ padding: 12, background: 'var(--ol-fill-soft)', border: '1px solid var(--ol-line-soft)', borderRadius: 8 }}>
              <div style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginBottom: 6 }}>{item.label}</div>
              <div className="mono tnum" style={{ fontSize: 20, fontWeight: 700, color: 'var(--ol-ink)' }}>{item.value}</div>
            </div>
          ))}
        </div>
      </SectionCard>

      <RunSnapshotPanel task={task} run={run} failedShards={failedShards} totalShards={totalShards} />

      {isFailed && (
        <>
          <SectionCard title="处置建议（自动诊断）" icon={<StepForwardOutlined />}>
            <Steps
              size="small"
              direction="vertical"
              current={-1}
              items={[
                { title: '轮换源库密码', description: '通过 KMS 触发密钥轮换，热更新不中断其他任务', status: 'wait' },
                { title: '从 checkpoint 恢复', description: '基于最近位点 binlog.000128:4456 (02:00) 回放', status: 'wait' },
                { title: '重跑该任务', description: '从恢复点继续抽取，不重复处理已提交数据', status: 'wait' },
                { title: '验证下游', description: '检查 dwd.dwd_order_df 数据新鲜度与质量门禁', status: 'wait' },
              ]}
            />
          </SectionCard>

          <SectionCard title="下游影响分析" icon={<BranchesOutlined />}>
            <ImpactAnalysis impact={{
              assets: ['dwd.dwd_order_df'],
              tasks: [task.name],
              apis: ['/api/order/detail'],
              subscribers: 18,
              blocking: true,
              suggestion: '先轮换源库密码 → 从 checkpoint 恢复 → 重跑',
            }} />
          </SectionCard>

          <div className="ol-section" style={{ padding: '12px 16px' }}>
            <Space size={8} wrap>
              <Button type="primary" icon={<ReloadOutlined />} onClick={() => message.warning({ content: '重试功能待接入：后端 sync-tasks/runs/{runId}/retry API 尚未实现', duration: 4 })}>重试</Button>
              <Button icon={<StepForwardOutlined />} onClick={() => message.warning({ content: '从 checkpoint 恢复待接入：需 Flink CDC savepoint 集成', duration: 4 })}>从 checkpoint 恢复</Button>
              <Button onClick={() => message.warning({ content: '跳过坏记录待接入：需 Airbyte error-handling 配置', duration: 4 })}>跳过坏记录</Button>
              <Button danger icon={<PauseCircleOutlined />} onClick={() => message.warning({ content: '管道暂停待接入：需 Airbyte connection cancel API', duration: 4 })}>暂停管道</Button>
              <Button icon={<BranchesOutlined />} onClick={() => navigate(`/catalog/lineage?focus=${encodeURIComponent(task.targetTable)}&from=diagnose&runId=${run.id}`)}>查看血缘影响</Button>
            </Space>
          </div>
        </>
      )}
    </div>
  );
}
