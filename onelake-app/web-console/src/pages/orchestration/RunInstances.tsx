/**
 * 运行实例（对应原型 §4.4.1 升级版）。
 *
 * <p>P4: supports {@code ?pipelineId=} (alias of {@code ?dagId=}) per design doc §4.1,
 * plus expandable row showing per-task task_run status from PipelineAPI.listTaskRuns.
 */
import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { Alert, App as AntApp, Table, Tag, Space, Button, Typography } from 'antd';
import { ReloadOutlined, HistoryOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard, StateView } from '../../components';
import { OrchestrationAPI, PipelineAPI } from '../../api';
import type { JobRun, PipelineTask, PipelineTaskEdge, PipelineTaskType, TaskRun } from '../../types';

const { Text } = Typography;

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function formatDuration(run: JobRun) {
  if (!run.startedAt || !run.finishedAt) return '-';
  const durationMs = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
  if (!Number.isFinite(durationMs) || durationMs < 0) return '-';
  if (durationMs < 60_000) return `${Math.max(1, Math.round(durationMs / 1000))}s`;
  return `${(durationMs / 60_000).toFixed(1)}m`;
}

type RunDisplayStatus = TaskRun['status'] | 'NOT_STARTED' | 'BLOCKED';

interface TaskRunDisplayRow {
  id: string;
  taskKey: string;
  status: RunDisplayStatus;
  run?: TaskRun;
  task?: PipelineTask;
  synthetic: boolean;
  blockedBy: string[];
}

const RUN_STATUS_LABEL: Record<RunDisplayStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  UPSTREAM_FAILED: '上游失败',
  SKIPPED: '已跳过',
  NOT_STARTED: '未开始',
  BLOCKED: '已阻断',
};

function formatTaskDuration(run?: TaskRun) {
  if (!run) return '-';
  if (!run.startedAt || !run.finishedAt) return '-';
  const durationMs = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
  if (!Number.isFinite(durationMs) || durationMs < 0) return '-';
  if (durationMs < 60_000) return `${Math.max(1, Math.round(durationMs / 1000))}s`;
  return `${(durationMs / 60_000).toFixed(1)}m`;
}

const TASK_TYPE_LABEL: Record<PipelineTaskType, string> = {
  SYNC_REF: '采集引用',
  QUALITY_GATE: '质量检查',
  SPARK_SQL: 'Spark SQL',
  PYSPARK: 'PySpark',
};

function taskTypeLabel(type?: PipelineTaskType) {
  return type ? TASK_TYPE_LABEL[type] : '任务';
}

function taskLevels(tasks: PipelineTask[], edges: PipelineTaskEdge[]) {
  const incoming = new Map<string, Set<string>>();
  tasks.forEach((task) => incoming.set(task.taskKey, new Set()));
  edges
    .filter((edge) => edge.edgeLayer === 'PIPELINE')
    .forEach((edge) => incoming.get(edge.targetKey)?.add(edge.sourceKey));

  const levels = new Map<string, number>();
  let changed = true;
  while (changed) {
    changed = false;
    for (const task of tasks) {
      const upstream = incoming.get(task.taskKey) ?? new Set();
      const upstreamMax = upstream.size === 0
        ? -1
        : Math.max(...[...upstream].map((key) => levels.get(key) ?? -1));
      const nextLevel = upstreamMax + 1;
      if ((levels.get(task.taskKey) ?? -1) !== nextLevel) {
        levels.set(task.taskKey, nextLevel);
        changed = true;
      }
    }
  }
  return levels;
}

const GRAPH_NODE_WIDTH = 260;
const GRAPH_NODE_HEIGHT = 104;
const GRAPH_COLUMN_GAP = 128;
const GRAPH_ROW_GAP = 16;
const GRAPH_PADDING = 16;
const GRAPH_HEADER_HEIGHT = 28;

function statusTone(status: RunDisplayStatus) {
  switch (status) {
    case 'SUCCEEDED': return '#16a34a';
    case 'FAILED': return '#dc2626';
    case 'RUNNING': return '#2563eb';
    case 'CANCELLED': return '#f97316';
    case 'UPSTREAM_FAILED': return '#f59e0b';
    case 'BLOCKED': return '#f59e0b';
    default: return '#94a3b8';
  }
}

function artifactText(path?: string) {
  if (!path) return '-';
  return path.replace(/^table:/, '').replace(/^quality:/, '');
}

function edgeNodeName(key: string, taskByKey: Map<string, PipelineTask>) {
  return taskByKey.get(key)?.name || key;
}

function edgeCaption(edge: PipelineTaskEdge) {
  const input = edge.targetInput || edge.targetPort;
  const alias = edge.inputAlias ? ` as ${edge.inputAlias}` : '';
  const asset = edge.assetFqn ? ` · ${edge.assetFqn}` : '';
  return input ? `${input}${alias}${asset}` : edge.assetFqn || '依赖';
}

function DependencyChain({
  edges,
  taskByKey,
}: {
  edges: PipelineTaskEdge[];
  taskByKey: Map<string, PipelineTask>;
}) {
  if (edges.length === 0) {
    return null;
  }
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 8,
        padding: '10px 12px',
        borderBottom: '1px solid var(--ol-border)',
        background: '#f8fafc',
      }}
    >
      <Text type="secondary" style={{ fontSize: 12 }}>依赖链</Text>
      {edges.map((edge, index) => (
        <Space key={edge.id} size={8} align="center" wrap={false}>
          <Tag color="blue" style={{ margin: 0, maxWidth: 220 }}>
            <span style={{ display: 'inline-block', maxWidth: 190, overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'bottom' }}>
              {edgeNodeName(edge.sourceKey, taskByKey)}
            </span>
          </Tag>
          <span
            aria-hidden
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              color: '#2563eb',
              fontWeight: 700,
              letterSpacing: 0,
            }}
          >
            →
          </span>
          <Tag color="blue" style={{ margin: 0, maxWidth: 220 }}>
            <span style={{ display: 'inline-block', maxWidth: 190, overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'bottom' }}>
              {edgeNodeName(edge.targetKey, taskByKey)}
            </span>
          </Tag>
          <Tag style={{ margin: 0, maxWidth: 260 }}>
            <span style={{ display: 'inline-block', maxWidth: 230, overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'bottom' }}>
              {edgeCaption(edge)}
            </span>
          </Tag>
          {index < edges.length - 1 && (
            <span style={{ width: 16, height: 2, background: '#bfdbfe', display: 'inline-block' }} />
          )}
        </Space>
      ))}
    </div>
  );
}

function taskDegree(tasks: PipelineTask[], edges: PipelineTaskEdge[]) {
  const result = new Map<string, { in: number; out: number }>();
  tasks.forEach((task) => result.set(task.taskKey, { in: 0, out: 0 }));
  edges.forEach((edge) => {
    const source = result.get(edge.sourceKey);
    if (source) source.out += 1;
    const target = result.get(edge.targetKey);
    if (target) target.in += 1;
  });
  return result;
}

function buildDisplayRows(
  taskRuns: TaskRun[],
  tasks: PipelineTask[],
  edges: PipelineTaskEdge[],
): TaskRunDisplayRow[] {
  const runByKey = new Map(taskRuns.map((run) => [run.taskKey, run]));
  const taskByKey = new Map(tasks.map((task) => [task.taskKey, task]));
  if (tasks.length === 0) {
    return taskRuns.map((run) => ({
      id: run.id,
      taskKey: run.taskKey,
      status: run.status,
      run,
      task: undefined,
      synthetic: false,
      blockedBy: [],
    }));
  }
  const incoming = new Map<string, PipelineTaskEdge[]>();
  tasks.forEach((task) => incoming.set(task.taskKey, []));
  edges
    .filter((edge) => edge.edgeLayer === 'PIPELINE')
    .forEach((edge) => incoming.get(edge.targetKey)?.push(edge));

  return tasks.map((task) => {
    const run = runByKey.get(task.taskKey);
    if (run) {
      return {
        id: run.id,
        taskKey: task.taskKey,
        status: run.status,
        run,
        task,
        synthetic: false,
        blockedBy: [],
      };
    }
    const blockedBy = (incoming.get(task.taskKey) || [])
      .map((edge) => runByKey.get(edge.sourceKey))
      .filter((upstreamRun): upstreamRun is TaskRun => upstreamRun?.status === 'FAILED')
      .map((upstreamRun) => taskByKey.get(upstreamRun.taskKey)?.name || upstreamRun.taskKey);
    return {
      id: `definition-${task.taskKey}`,
      taskKey: task.taskKey,
      status: blockedBy.length > 0 ? 'BLOCKED' : 'NOT_STARTED',
      run: undefined,
      task,
      synthetic: true,
      blockedBy,
    };
  });
}

function RunTaskGraph({
  rows,
  tasks,
  edges,
}: {
  rows: TaskRunDisplayRow[];
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
}) {
  const taskByKey = useMemo(() => new Map(tasks.map((task) => [task.taskKey, task])), [tasks]);
  const levels = useMemo(() => taskLevels(tasks, edges), [tasks, edges]);
  const degreeByKey = useMemo(() => taskDegree(tasks, edges), [tasks, edges]);
  const displayKeySet = useMemo(() => new Set(rows.map((row) => row.taskKey)), [rows]);
  const graphEdges = useMemo(
    () => edges.filter((edge) => displayKeySet.has(edge.sourceKey) && displayKeySet.has(edge.targetKey)),
    [edges, displayKeySet],
  );

  const nodes = useMemo(() => rows.map((row, index) => {
    const task = row.task || taskByKey.get(row.taskKey);
    return {
      row,
      task,
      key: row.taskKey,
      level: levels.get(row.taskKey) ?? index,
      index,
    };
  }), [rows, taskByKey, levels]);

  const columns = useMemo(() => {
    const map = new Map<number, typeof nodes>();
    nodes.forEach((node) => {
      if (!map.has(node.level)) map.set(node.level, []);
      map.get(node.level)!.push(node);
    });
    return [...map.entries()].sort((a, b) => a[0] - b[0]);
  }, [nodes]);

  const columnIndex = useMemo(
    () => new Map(columns.map(([level], index) => [level, index])),
    [columns],
  );

  const positions = useMemo(() => {
    const map = new Map<string, { x: number; y: number }>();
    columns.forEach(([level, columnNodes]) => {
      const x = GRAPH_PADDING + (columnIndex.get(level) ?? 0) * (GRAPH_NODE_WIDTH + GRAPH_COLUMN_GAP);
      columnNodes
        .sort((a, b) => a.index - b.index)
        .forEach((node, rowIndex) => {
          map.set(node.key, {
            x,
            y: GRAPH_PADDING + GRAPH_HEADER_HEIGHT + rowIndex * (GRAPH_NODE_HEIGHT + GRAPH_ROW_GAP),
          });
        });
    });
    return map;
  }, [columns, columnIndex]);

  const maxRows = Math.max(1, ...columns.map(([, columnNodes]) => columnNodes.length));
  const graphWidth = GRAPH_PADDING * 2
    + columns.length * GRAPH_NODE_WIDTH
    + Math.max(0, columns.length - 1) * GRAPH_COLUMN_GAP;
  const graphHeight = GRAPH_PADDING * 2
    + GRAPH_HEADER_HEIGHT
    + maxRows * GRAPH_NODE_HEIGHT
    + Math.max(0, maxRows - 1) * GRAPH_ROW_GAP;

  return (
    <div
      style={{
        border: '1px solid var(--ol-border)',
        borderRadius: 8,
        background: '#fff',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '10px 12px',
          borderBottom: '1px solid var(--ol-border)',
        }}
      >
        <Space size={8}>
          <Text strong>节点拓扑</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {nodes.length} 个节点 · {graphEdges.length} 条依赖边
          </Text>
        </Space>
        {graphEdges.length === 0 && nodes.length > 1 && (
          <Text type="secondary" style={{ fontSize: 12 }}>当前运行未记录节点依赖边</Text>
        )}
      </div>
      <DependencyChain edges={graphEdges} taskByKey={taskByKey} />
      <div style={{ overflowX: 'auto', padding: 12, background: 'var(--ol-fill-soft)' }}>
        <div style={{ position: 'relative', width: graphWidth, height: graphHeight, minHeight: 160 }}>
          <svg
            width={graphWidth}
            height={graphHeight}
            style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 1 }}
          >
            <defs>
              <marker
                id="run-edge-arrow"
                markerWidth="8"
                markerHeight="8"
                refX="7"
                refY="4"
                orient="auto"
                markerUnits="strokeWidth"
              >
                <path d="M 0 0 L 8 4 L 0 8 z" fill="#2563eb" />
              </marker>
            </defs>
            {graphEdges.map((edge) => {
              const source = positions.get(edge.sourceKey);
              const target = positions.get(edge.targetKey);
              if (!source || !target) return null;
              const sx = source.x + GRAPH_NODE_WIDTH;
              const sy = source.y + GRAPH_NODE_HEIGHT / 2;
              const tx = target.x;
              const ty = target.y + GRAPH_NODE_HEIGHT / 2;
              const mid = sx + Math.max(36, (tx - sx) / 2);
              const dashed = edge.edgeLayer === 'CROSS_ENGINE';
              return (
                <path
                  key={edge.id}
                  d={`M ${sx} ${sy} C ${mid} ${sy}, ${mid} ${ty}, ${tx - 8} ${ty}`}
                  fill="none"
                  stroke={dashed ? '#f59e0b' : '#2563eb'}
                  strokeWidth={3}
                  strokeDasharray={dashed ? '6 4' : undefined}
                  strokeLinecap="round"
                  markerEnd="url(#run-edge-arrow)"
                />
              );
            })}
          </svg>
          {graphEdges.map((edge) => {
            const source = positions.get(edge.sourceKey);
            const target = positions.get(edge.targetKey);
            if (!source || !target) return null;
            const sx = source.x + GRAPH_NODE_WIDTH;
            const sy = source.y + GRAPH_NODE_HEIGHT / 2;
            const tx = target.x;
            const ty = target.y + GRAPH_NODE_HEIGHT / 2;
            const left = sx + Math.max(24, (tx - sx) / 2) - 56;
            const top = (sy + ty) / 2 - 12;
            return (
              <div
                key={`${edge.id}-label`}
                style={{
                  position: 'absolute',
                  left,
                  top,
                  zIndex: 4,
                  maxWidth: 160,
                  padding: '2px 6px',
                  border: '1px solid #bfdbfe',
                  borderRadius: 4,
                  background: '#fff',
                  color: '#2563eb',
                  fontSize: 11,
                  lineHeight: '18px',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  boxShadow: '0 1px 3px rgba(15, 23, 42, 0.08)',
                }}
                title={edgeCaption(edge)}
              >
                {edgeCaption(edge)}
              </div>
            );
          })}
          {columns.map(([level, columnNodes]) => {
            const firstNode = columnNodes[0];
            const firstPos = firstNode ? positions.get(firstNode.key) : undefined;
            const left = firstPos?.x ?? GRAPH_PADDING;
            return (
              <Text
                key={level}
                type="secondary"
                style={{
                  position: 'absolute',
                  left,
                  top: GRAPH_PADDING,
                  fontSize: 11,
                  zIndex: 2,
                }}
              >
                层级 {level + 1}
              </Text>
            );
          })}
          {nodes.map((node) => {
            const pos = positions.get(node.key);
            if (!pos) return null;
            const borderColor = statusTone(node.row.status);
            const target = node.task?.targetFqn || artifactText(node.row.run?.artifactPath);
            const degree = degreeByKey.get(node.key) || { in: 0, out: 0 };
            const nodeStyle: CSSProperties = {
              position: 'absolute',
              left: pos.x,
              top: pos.y,
              width: GRAPH_NODE_WIDTH,
              height: GRAPH_NODE_HEIGHT,
              zIndex: 3,
              border: `1px solid ${borderColor}`,
              borderLeft: `4px solid ${borderColor}`,
              borderRadius: 8,
              background: '#fff',
              padding: 10,
              boxShadow: '0 2px 8px rgba(15, 23, 42, 0.06)',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
            };
            return (
              <div key={node.key} style={nodeStyle}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Text strong ellipsis style={{ flex: 1, fontSize: 13 }}>
                      {node.task?.name || node.key}
                    </Text>
                    <StatusBadge
                      status={node.row.status}
                      label={RUN_STATUS_LABEL[node.row.status]}
                      tooltip={node.row.blockedBy.length > 0 ? `上游失败：${node.row.blockedBy.join('、')}` : undefined}
                    />
                  </div>
                  <Space size={6} wrap style={{ marginTop: 6 }}>
                    <Tag style={{ margin: 0 }}>{taskTypeLabel(node.task?.taskType)}</Tag>
                    {node.task?.engine && <Tag style={{ margin: 0 }}>{node.task.engine}</Tag>}
                  </Space>
                </div>
                <div>
                  <Text code ellipsis style={{ display: 'block', fontSize: 11 }}>
                    {target}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    输入 {degree.in} · 输出 {degree.out} · 行数 {node.row.run?.rowsWritten == null ? '-' : node.row.run.rowsWritten.toLocaleString()}
                  </Text>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export default function RunInstances() {
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const dagIdFilter = searchParams.get('dagId') || searchParams.get('pipelineId') || '';
  const [runs, setRuns] = useState<JobRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const loadRuns = (nextPage = page, nextSize = pageSize) => {
    setLoading(true);
    setError(null);
    const request = dagIdFilter
      ? OrchestrationAPI.listDagRuns(dagIdFilter, nextPage, nextSize)
      : OrchestrationAPI.listRuns(nextPage, nextSize);
    request
      .then((result) => {
        setRuns(result.content || []);
        setPage(result.number ?? nextPage);
        setPageSize(result.size ?? nextSize);
        setTotal(result.totalElements ?? 0);
      })
      .catch((e) => {
        const msg = e.message || '运行实例加载失败';
        setError(msg);
        message.error(msg);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadRuns(0, pageSize);
  }, [dagIdFilter]);

  const visibleRuns = useMemo(
    () => (dagIdFilter ? runs.filter((run) => run.dagId === dagIdFilter) : runs),
    [dagIdFilter, runs],
  );

  const counts = useMemo(() => ({
    total: visibleRuns.length,
    success: visibleRuns.filter((r) => r.status === 'SUCCESS' || r.status === 'SUCCEEDED').length,
    failed: visibleRuns.filter((r) => r.status === 'FAILED').length,
    cron: visibleRuns.filter((r) => r.triggerType === 'CRON').length,
  }), [visibleRuns]);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<HistoryOutlined />}
        title="运行实例"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description={dagIdFilter ? '查看当前流水线运行历史，含触发方式、耗时、责任人' : '查看所有流水线运行历史，含触发方式、耗时、责任人'}
        meta={[
          { label: dagIdFilter ? '当前流水线' : '当前页', value: counts.total },
          { label: '成功', value: counts.success },
          { label: '失败', value: counts.failed },
          { label: '调度触发', value: counts.cron },
        ]}
        actions={(
          <Space size={8} wrap>
            {dagIdFilter && <Button onClick={() => navigate('/orchestration/runs')}>查看全部</Button>}
            <Button icon={<ReloadOutlined />} onClick={() => loadRuns()} loading={loading}>刷新</Button>
          </Space>
        )}
      />

      <SectionCard title="运行历史" icon={<HistoryOutlined />} flatBody>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            action={<Button size="small" onClick={() => loadRuns()}>重试</Button>}
            style={{ margin: 12 }}
          />
        )}
        <Table
          rowKey="id"
          dataSource={visibleRuns}
          loading={loading}
          expandable={{
            expandedRowRender: (run: JobRun) => <TaskRunsPanel runId={run.id} dagId={run.dagId} />,
            rowExpandable: (record) => Boolean(record.dagId),
          }}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无运行记录"
                description="触发流水线后，运行历史将自动出现"
              />
            ),
          }}
          size="middle"
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (value) => `共 ${value} 条`,
            onChange: (nextPage, nextPageSize) => loadRuns(nextPage - 1, nextPageSize),
          }}
          columns={[
            { title: 'Run ID', render: (_: unknown, r: JobRun) => <Text code style={{ fontSize: 12 }}>{r.dagsterRunId || r.id}</Text> },
            { title: '流水线', render: (_: unknown, r: JobRun) => (
              <div>
                <a className="ol-link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>{r.dagName || r.dagId}</a>
                {r.dagsterJob && <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{r.dagsterJob}</div>}
              </div>
            ) },
            { title: '触发方式', dataIndex: 'triggerType', width: 110, render: (t: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: t === 'CRON' ? 'var(--ol-brand-soft)' : 'var(--ol-fill-soft)',
                color: t === 'CRON' ? 'var(--ol-brand)' : 'var(--ol-ink-2)',
              }}>{t}</span>
            ) },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: '开始', dataIndex: 'startedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatDate(t)}</span> },
            { title: '结束', dataIndex: 'finishedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatDate(t)}</span> },
            { title: '耗时', width: 90, render: (_: unknown, r: JobRun) => <Tag style={{ margin: 0 }}>{formatDuration(r)}</Tag> },
            { title: '触发人', dataIndex: 'triggeredBy', width: 140, render: (b?: string) => (
              <span style={{ fontSize: 12, color: b ? 'var(--ol-ink)' : 'var(--ol-ink-3)' }}>{b || 'system'}</span>
            ) },
            { title: '操作', width: 120, render: (_: unknown, r: JobRun) => (
              <Space>
                <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>打开流水线</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}

/**
 * Per-task status panel (P4). Loads via PipelineAPI.listTaskRuns for v2 pipelines.
 * Falls back to "no task_run data" when a run has no task-level rows.
 */
function TaskRunsPanel({ runId, dagId }: { runId: string; dagId: string }) {
  const [taskRuns, setTaskRuns] = useState<TaskRun[] | null>(null);
  const [tasks, setTasks] = useState<PipelineTask[]>([]);
  const [edges, setEdges] = useState<PipelineTaskEdge[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      PipelineAPI.listTaskRuns(dagId, runId),
      PipelineAPI.listTasks(dagId),
      PipelineAPI.listEdges(dagId),
    ])
      .then(([runs, taskDefs, edgeDefs]) => {
        if (cancelled) return;
        setTaskRuns(runs);
        setTasks(taskDefs);
        setEdges(edgeDefs);
      })
      .catch((e) => !cancelled && setError(e.message || 'task_run 加载失败'));
    return () => {
      cancelled = true;
    };
  }, [runId, dagId]);

  if (error) {
    return <Alert type="info" showIcon message={error} />;
  }
  if (!taskRuns) {
    return <Text type="secondary">加载中…</Text>;
  }
  if (taskRuns.length === 0 && tasks.length === 0) {
    return (
      <Alert
        type="info"
        showIcon
        message="无 task_run 记录"
        description="可能是 v1 路径的运行（未走 pipeline v2 节点级状态）。"
      />
    );
  }

  const taskByKey = new Map(tasks.map((task) => [task.taskKey, task]));
  const levels = taskLevels(tasks, edges);
  const degreeByKey = taskDegree(tasks, edges);
  const rows = buildDisplayRows(taskRuns, tasks, edges).sort((a, b) => {
    const levelDelta = (levels.get(a.taskKey) ?? Number.MAX_SAFE_INTEGER)
      - (levels.get(b.taskKey) ?? Number.MAX_SAFE_INTEGER);
    if (levelDelta !== 0) return levelDelta;
    const taskIndexA = tasks.findIndex((task) => task.taskKey === a.taskKey);
    const taskIndexB = tasks.findIndex((task) => task.taskKey === b.taskKey);
    return (taskIndexA < 0 ? Number.MAX_SAFE_INTEGER : taskIndexA)
      - (taskIndexB < 0 ? Number.MAX_SAFE_INTEGER : taskIndexB);
  });

  return (
    <div style={{ display: 'grid', gap: 12 }}>
      {taskRuns.length === 0 && tasks.length > 0 && (
        <Alert
          type="info"
          showIcon
          message="当前运行尚未写入节点级状态"
          description="下方仍按流水线定义展示完整拓扑，便于确认上游、下游和产物链路。"
        />
      )}
      <RunTaskGraph rows={rows} tasks={tasks} edges={edges} />
      <Table<TaskRunDisplayRow>
        rowKey="id"
        dataSource={rows}
        pagination={false}
        size="small"
        columns={[
        {
          title: '任务节点',
          dataIndex: 'taskKey',
          render: (_taskKey: string, row: TaskRunDisplayRow) => {
            const task = row.task || taskByKey.get(row.taskKey);
            const level = levels.get(row.taskKey);
            return (
              <Space direction="vertical" size={2}>
                <Space size={8} wrap>
                  <Text strong>{task?.name || row.taskKey}</Text>
                  {level != null && <Tag style={{ margin: 0 }}>层级 {level + 1}</Tag>}
                </Space>
                <Text code style={{ fontSize: 11 }}>{row.taskKey}</Text>
              </Space>
            );
          },
        },
        {
          title: '类型',
          width: 120,
          render: (_: unknown, row: TaskRunDisplayRow) => {
            const task = row.task || taskByKey.get(row.taskKey);
            return <Tag style={{ margin: 0 }}>{taskTypeLabel(task?.taskType)}</Tag>;
          },
        },
        {
          title: '状态',
          dataIndex: 'status',
          width: 110,
          render: (s: RunDisplayStatus, row: TaskRunDisplayRow) => (
            <StatusBadge
              status={s}
              label={RUN_STATUS_LABEL[s]}
              tooltip={row.synthetic && s === 'NOT_STARTED' ? '该节点没有对应 task_run 记录' : undefined}
            />
          ),
        },
        {
          title: '输入/输出',
          width: 110,
          render: (_: unknown, row: TaskRunDisplayRow) => {
            const degree = degreeByKey.get(row.taskKey) || { in: 0, out: 0 };
            return <Tag style={{ margin: 0 }}>{degree.in} 入 · {degree.out} 出</Tag>;
          },
        },
        {
          title: '目标表',
          width: 210,
          render: (_: unknown, row: TaskRunDisplayRow) => {
            const task = row.task || taskByKey.get(row.taskKey);
            return task?.targetFqn ? <Text code style={{ fontSize: 11 }}>{task.targetFqn}</Text> : '-';
          },
        },
        {
          title: '耗时',
          width: 90,
          render: (_: unknown, row: TaskRunDisplayRow) => <Tag style={{ margin: 0 }}>{formatTaskDuration(row.run)}</Tag>,
        },
        {
          title: '行数',
          width: 100,
          render: (_: unknown, row: TaskRunDisplayRow) => (row.run?.rowsWritten == null ? '-' : row.run.rowsWritten.toLocaleString()),
        },
        {
          title: '扫描字节',
          width: 110,
          render: (_: unknown, row: TaskRunDisplayRow) => (row.run?.scanBytes == null ? '-' : `${(row.run.scanBytes / 1024 / 1024).toFixed(1)} MB`),
        },
        {
          title: '产物',
          render: (_: unknown, row: TaskRunDisplayRow) =>
            row.run?.artifactPath ? (
              <Text code style={{ fontSize: 11 }}>
                {row.run.artifactPath}
              </Text>
            ) : (
              '-'
            ),
        },
        {
          title: '错误',
          render: (_: unknown, row: TaskRunDisplayRow) =>
            row.run?.errorMsg ? (
              <Text type="danger" style={{ fontSize: 12 }} ellipsis>
                {row.run.errorMsg}
              </Text>
            ) : (
              '-'
            ),
        },
        ]}
      />
    </div>
  );
}
