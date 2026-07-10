/**
 * 运行实例（对应原型 §4.4.1 升级版）。
 *
 * <p>P4: supports {@code ?pipelineId=} (alias of {@code ?dagId=}) per design doc §4.1,
 * plus expandable row showing per-task task_run status from PipelineAPI.listTaskRuns.
 */
import { useCallback, useEffect, useMemo, useState, type CSSProperties, type KeyboardEvent, type MouseEvent } from 'react';
import { Alert, App as AntApp, Descriptions, Drawer, Dropdown, Select, Space, Button, Popconfirm, Table, Tabs, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, DownOutlined, DownloadOutlined, EyeOutlined, FileTextOutlined, HistoryOutlined, RedoOutlined, ReloadOutlined, StopOutlined, UpOutlined } from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard, StateView } from '../../components';
import { BackfillAPI, CatalogAPI, OrchestrationAPI, PipelineAPI } from '../../api';
import { BizError } from '../../api/http';
import { getAuthUser } from '../../auth/oidc';
import type { Asset, Backfill, JobRun, PipelineTask, PipelineTaskEdge, PipelineTaskType, TaskRerunMode, TaskRun } from '../../types';
import { normalizeCatalogAssets } from '../lakehouse/assetAdapter';

const { Text } = Typography;
const DEFAULT_LOG_TAIL_LINES = 300;
const LOG_TAIL_OPTIONS = [100, 300, 500, 1000];
const RUN_LIST_POLL_INTERVAL_MS = 4000;
const RUN_DETAIL_POLL_INTERVAL_MS = 2500;
const TASK_RUN_POLL_INTERVAL_MS = 2500;

interface UiError {
  message: string;
  noPermission: boolean;
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function formatBusinessDate(value?: string) {
  if (!value) return '-';
  return `${new Date(value).toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

function formatDuration(run: JobRun) {
  if (!run.startedAt || !run.finishedAt) return '-';
  const durationMs = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
  if (!Number.isFinite(durationMs) || durationMs < 0) return '-';
  if (durationMs < 60_000) return `${Math.max(1, Math.round(durationMs / 1000))}s`;
  return `${(durationMs / 60_000).toFixed(1)}m`;
}

function displayRunId(run: JobRun) {
  return run.dagsterRunId || run.id;
}

function triggerActorName(run: JobRun) {
  return run.triggeredByName || (run.triggeredBy ? '未知用户' : 'system');
}

function triggerActorTitle(run: JobRun) {
  if (!run.triggeredBy) return triggerActorName(run);
  return `${triggerActorName(run)} · ${run.triggeredBy}`;
}

function isRunTerminal(status?: string) {
  return ['SUCCEEDED', 'SUCCESS', 'FAILED', 'CANCELLED'].includes((status || '').toUpperCase());
}

function isBackfillTerminal(status?: string) {
  return ['SUCCEEDED', 'FAILED', 'PARTIAL', 'CANCELLED'].includes((status || '').toUpperCase());
}

function shortId(value: string) {
  return value.length > 12 ? `${value.slice(0, 8)}...` : value;
}

function groupRunsByBackfill(runs: JobRun[]) {
  const groups = new Map<string, JobRun[]>();
  runs.forEach((run) => {
    const key = run.backfillId ? `backfill:${run.backfillId}` : `run:${run.id}`;
    const group = groups.get(key) ?? [];
    group.push(run);
    groups.set(key, group);
  });
  return [...groups.values()].flat();
}

function toNumberCode(value: unknown) {
  if (typeof value === 'number') return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function toUiError(error: unknown, fallback: string): UiError {
  const shape = error as {
    code?: unknown;
    message?: unknown;
    response?: {
      status?: number;
      data?: {
        code?: unknown;
        message?: unknown;
      };
    };
  };
  const code = error instanceof BizError
    ? error.code
    : toNumberCode(shape?.response?.data?.code ?? shape?.code);
  const status = shape?.response?.status;
  const message = (
    error instanceof Error && error.message
      ? error.message
      : typeof shape?.response?.data?.message === 'string' && shape.response.data.message.trim()
        ? shape.response.data.message
        : fallback
  );
  return {
    message,
    noPermission: status === 403 || code === 403 || code === 40300,
  };
}

function filenameFromContentDisposition(header?: string) {
  if (!header) return undefined;
  const encoded = header.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded.replace(/^"|"$/g, ''));
    } catch {
      return encoded.replace(/^"|"$/g, '');
    }
  }
  return header.match(/filename="?([^";]+)"?/i)?.[1];
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
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
const RERUNNABLE_TASK_STATUSES = new Set<RunDisplayStatus>(['FAILED', 'UPSTREAM_FAILED']);

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

function artifactTableFqn(path?: string) {
  const match = path?.trim().match(/^table:(.+)$/i);
  return match?.[1]?.trim();
}

function normalizeFqnKey(fqn?: string) {
  return (fqn || '').trim().toLowerCase();
}

function fqnLookupKeys(fqn?: string) {
  const key = normalizeFqnKey(fqn);
  if (!key) return [];
  const parts = key.split('.').filter(Boolean);
  const shortKey = parts.length > 2 ? parts.slice(-2).join('.') : key;
  return [...new Set([key, shortKey])];
}

type AssetByFqn = Map<string, Asset>;

function buildAssetByFqn(assets: Asset[]) {
  const map: AssetByFqn = new Map();
  assets.forEach((asset) => {
    fqnLookupKeys(asset.fqn).forEach((key) => {
      if (!map.has(key)) map.set(key, asset);
    });
  });
  return map;
}

function findAssetByFqn(assetByFqn: AssetByFqn, fqn: string) {
  for (const key of fqnLookupKeys(fqn)) {
    const asset = assetByFqn.get(key);
    if (asset) return asset;
  }
  return undefined;
}

function TableFqnLink({
  fqn,
  assetByFqn,
  compact = false,
}: {
  fqn: string;
  assetByFqn: AssetByFqn;
  compact?: boolean;
}) {
  const navigate = useNavigate();
  const asset = findAssetByFqn(assetByFqn, fqn);
  const textStyle: CSSProperties = {
    display: compact ? 'block' : 'inline-block',
    maxWidth: compact ? '100%' : 260,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize: 11,
    verticalAlign: 'bottom',
  };

  if (!asset) {
    return (
      <Text code ellipsis={{ tooltip: fqn }} style={textStyle}>
        {fqn}
      </Text>
    );
  }

  const path = `/lakehouse/tables/${encodeURIComponent(asset.id)}`;
  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    event.stopPropagation();
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.altKey || event.ctrlKey || event.shiftKey) {
      return;
    }
    event.preventDefault();
    navigate(path);
  };

  return (
    <a
      className="ol-link"
      href={path}
      title={`打开分层表详情：${asset.fqn}`}
      onClick={handleClick}
      style={textStyle}
    >
      {fqn}
    </a>
  );
}

function ArtifactPathValue({
  path,
  assetByFqn,
  compact = false,
  stripPrefix = false,
}: {
  path?: string;
  assetByFqn: AssetByFqn;
  compact?: boolean;
  stripPrefix?: boolean;
}) {
  const tableFqn = artifactTableFqn(path);
  if (tableFqn) {
    return <TableFqnLink fqn={tableFqn} assetByFqn={assetByFqn} compact={compact} />;
  }

  const text = stripPrefix ? artifactText(path) : path;
  return (
    <Text
      code
      ellipsis={{ tooltip: text || '-' }}
      style={{
        display: compact ? 'block' : 'inline-block',
        maxWidth: compact ? '100%' : 320,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
        fontSize: 11,
      }}
    >
      {text || '-'}
    </Text>
  );
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
  assetByFqn,
  onOpenTask,
}: {
  rows: TaskRunDisplayRow[];
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  assetByFqn: AssetByFqn;
  onOpenTask?: (row: TaskRunDisplayRow) => void;
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
            const targetFqn = node.task?.targetFqn || artifactTableFqn(node.row.run?.artifactPath);
            const degree = degreeByKey.get(node.key) || { in: 0, out: 0 };
            const openNode = () => onOpenTask?.(node.row);
            const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
              if (!onOpenTask) return;
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openNode();
              }
            };
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
              cursor: onOpenTask ? 'pointer' : 'default',
            };
            return (
              <div
                key={node.key}
                role={onOpenTask ? 'button' : undefined}
                tabIndex={onOpenTask ? 0 : undefined}
                onClick={openNode}
                onKeyDown={handleKeyDown}
                style={nodeStyle}
              >
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
                  {targetFqn ? (
                    <TableFqnLink fqn={targetFqn} assetByFqn={assetByFqn} compact />
                  ) : (
                    <ArtifactPathValue
                      path={node.row.run?.artifactPath}
                      assetByFqn={assetByFqn}
                      compact
                      stripPrefix
                    />
                  )}
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
  const canManageRuns = getAuthUser()?.roles.includes('DE') ?? false;
  const navigate = useNavigate();
  const { runId: routeRunId } = useParams<{ runId: string }>();
  const [searchParams] = useSearchParams();
  const dagIdFilter = searchParams.get('dagId') || searchParams.get('pipelineId') || '';
  const backfillIdFilter = searchParams.get('backfill_id') || searchParams.get('backfillId') || '';
  const [runs, setRuns] = useState<JobRun[]>([]);
  const [backfillGroup, setBackfillGroup] = useState<Backfill | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<UiError | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [detailRun, setDetailRun] = useState<JobRun | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<UiError | null>(null);
  const [cancelingRun, setCancelingRun] = useState(false);
  const [taskRefreshVersion, setTaskRefreshVersion] = useState(0);
  const [expandedRunIds, setExpandedRunIds] = useState<string[]>([]);

  const loadRuns = (nextPage = page, nextSize = pageSize, options?: { silent?: boolean }) => {
    if (!options?.silent) setLoading(true);
    setError(null);
    const request = backfillIdFilter
      ? Promise.all([
          BackfillAPI.get(backfillIdFilter),
          BackfillAPI.listRuns(backfillIdFilter, nextPage, nextSize),
        ]).then(([backfill, result]) => {
          setBackfillGroup(backfill);
          return result;
        })
      : dagIdFilter
        ? OrchestrationAPI.listDagRuns(dagIdFilter, nextPage, nextSize)
        : OrchestrationAPI.listRuns(nextPage, nextSize);
    if (!backfillIdFilter) setBackfillGroup(null);
    request
      .then((result) => {
        setRuns(result.content || []);
        setPage(result.number ?? nextPage);
        setPageSize(result.size ?? nextSize);
        setTotal(result.totalElements ?? 0);
      })
      .catch((e) => {
        const nextError = toUiError(e, '运行实例加载失败');
        // Silent polling should keep the last good table visible; transient
        // backend/network failures are surfaced only on explicit loads.
        if (options?.silent && runs.length > 0) {
          return;
        }
        setError(nextError);
        if (!options?.silent && !nextError.noPermission) {
          message.error(nextError.message);
        }
      })
      .finally(() => {
        if (!options?.silent) setLoading(false);
      });
  };

  const requestRunDetail = (runId: string) => backfillIdFilter
    ? BackfillAPI.getRun(backfillIdFilter, runId)
    : OrchestrationAPI.getRun(runId);

  const loadRunDetail = (options?: { silent?: boolean }) => {
    if (!routeRunId) return;
    if (!options?.silent) setDetailLoading(true);
    setDetailError(null);
    requestRunDetail(routeRunId)
      .then(setDetailRun)
      .catch((e) => {
        const nextError = toUiError(e, '运行详情加载失败');
        setDetailError(nextError);
        if (!options?.silent && !nextError.noPermission) {
          message.error(nextError.message);
        }
      })
      .finally(() => {
        if (!options?.silent) setDetailLoading(false);
      });
  };

  useEffect(() => {
    if (routeRunId) return;
    loadRuns(0, pageSize);
  }, [backfillIdFilter, dagIdFilter, routeRunId]);

  useEffect(() => {
    if (!routeRunId) return;
    loadRunDetail();
  }, [backfillIdFilter, routeRunId]);

  const visibleRuns = useMemo(
    () => groupRunsByBackfill(runs.filter((run) => (
      (!dagIdFilter || run.dagId === dagIdFilter)
      && (!backfillIdFilter || run.backfillId === backfillIdFilter)
    ))),
    [backfillIdFilter, dagIdFilter, runs],
  );
  const hasNonTerminalVisibleRun = visibleRuns.some((run) => !isRunTerminal(run.status));
  const hasActiveBackfill = Boolean(backfillGroup && !isBackfillTerminal(backfillGroup.status));
  const detailRunIsActive = Boolean(detailRun && !isRunTerminal(detailRun.status));

  useEffect(() => {
    if (routeRunId || (!hasNonTerminalVisibleRun && !hasActiveBackfill)) return undefined;
    const timer = window.setInterval(() => {
      loadRuns(page, pageSize, { silent: true });
    }, RUN_LIST_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [routeRunId, hasNonTerminalVisibleRun, hasActiveBackfill, page, pageSize, dagIdFilter, backfillIdFilter]);

  useEffect(() => {
    if (!routeRunId || !detailRunIsActive) return undefined;
    const timer = window.setInterval(() => {
      requestRunDetail(routeRunId)
        .then((nextRun) => {
          const reachedTerminal = detailRun && !isRunTerminal(detailRun.status) && isRunTerminal(nextRun.status);
          setDetailRun(nextRun);
          setDetailError(null);
          // TaskRunsPanel has its own lightweight task_run polling; refresh the
          // static topology/catalog bundle only once when the run settles.
          if (reachedTerminal) {
            setTaskRefreshVersion((version) => version + 1);
          }
        })
        .catch((e) => setDetailError(toUiError(e, '运行详情刷新失败')));
    }, RUN_DETAIL_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [backfillIdFilter, routeRunId, detailRunIsActive]);

  const handleCancelRun = async () => {
    if (!routeRunId || !detailRun) return;
    setCancelingRun(true);
    try {
      const previousStatus = detailRun.status;
      const nextRun = await OrchestrationAPI.cancelRun(routeRunId);
      setDetailRun(nextRun);
      setTaskRefreshVersion((version) => version + 1);
      if (nextRun.status === 'CANCELLED') {
        message.success('已取消运行');
      } else if (isRunTerminal(previousStatus) && nextRun.status === previousStatus) {
        message.info('运行已终态，状态未变');
      } else {
        message.success(`运行状态：${nextRun.status}`);
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '取消运行失败');
    } finally {
      setCancelingRun(false);
    }
  };

  useEffect(() => {
    const visibleIds = new Set(visibleRuns.map((run) => run.id));
    setExpandedRunIds((keys) => keys.filter((key) => visibleIds.has(key)));
  }, [visibleRuns]);

  const counts = useMemo(() => ({
    total: visibleRuns.length,
    success: visibleRuns.filter((r) => r.status === 'SUCCESS' || r.status === 'SUCCEEDED').length,
    failed: visibleRuns.filter((r) => r.status === 'FAILED').length,
    cron: visibleRuns.filter((r) => r.triggerType === 'CRON').length,
    backfills: new Set(visibleRuns.flatMap((run) => run.backfillId ? [run.backfillId] : [])).size,
  }), [visibleRuns]);

  const backfillRowSpans = useMemo(() => {
    const spans = new Map<string, number>();
    let index = 0;
    while (index < visibleRuns.length) {
      const run = visibleRuns[index];
      if (!run.backfillId) {
        spans.set(run.id, 1);
        index += 1;
        continue;
      }
      let end = index + 1;
      while (end < visibleRuns.length && visibleRuns[end].backfillId === run.backfillId) end += 1;
      spans.set(run.id, end - index);
      for (let hiddenIndex = index + 1; hiddenIndex < end; hiddenIndex += 1) {
        spans.set(visibleRuns[hiddenIndex].id, 0);
      }
      index = end;
    }
    return spans;
  }, [visibleRuns]);

  if (routeRunId) {
    return (
      <div className="ol-page">
        <PageHeader
          icon={<HistoryOutlined />}
          title="运行详情"
          subtitle={<span className="ol-chip">编排 · 运行实例</span>}
          description={detailRun ? `${detailRun.dagName || detailRun.dagId} 的单次运行观测` : '查看单次运行的概览、任务拓扑和节点状态'}
        meta={detailRun ? [
            { label: '状态', value: detailRun.status },
            { label: '触发方式', value: detailRun.triggerType },
            ...(detailRun.backfillId ? [{ label: '回填批次', value: shortId(detailRun.backfillId) }] : []),
            { label: '触发人', value: triggerActorName(detailRun) },
            { label: '耗时', value: formatDuration(detailRun) },
          ] : []}
          actions={(
            <Space size={8} wrap>
              <Button
                icon={<ArrowLeftOutlined />}
                onClick={() => navigate(backfillIdFilter
                  ? `/orchestration/runs?backfill_id=${encodeURIComponent(backfillIdFilter)}`
                  : '/orchestration/runs')}
              >
                返回列表
              </Button>
              {detailRun?.dagId && (
                <Button onClick={() => navigate(`/orchestration/pipelines/${detailRun.dagId}`)}>打开流水线</Button>
              )}
              {canManageRuns && detailRun && !isRunTerminal(detailRun.status) && (
                <Popconfirm
                  title="取消运行"
                  description="确认取消当前运行？"
                  okText="取消运行"
                  cancelText="保留"
                  okButtonProps={{ danger: true, loading: cancelingRun }}
                  onConfirm={handleCancelRun}
                >
                  <Button
                    danger
                    icon={<StopOutlined />}
                    loading={cancelingRun}
                  >
                    取消运行
                  </Button>
                </Popconfirm>
              )}
              <Button icon={<ReloadOutlined />} onClick={() => loadRunDetail()} loading={detailLoading}>刷新</Button>
            </Space>
          )}
        />

        {detailLoading && !detailRun && (
          <SectionCard title="运行概览" icon={<HistoryOutlined />}>
            <StateView state="loading" rows={4} />
          </SectionCard>
        )}
        {detailError && (
          <SectionCard title="运行概览" icon={<HistoryOutlined />}>
            <StateView
              state={detailError.noPermission ? 'no-permission' : 'error'}
              title={detailError.noPermission ? '无权查看运行详情' : '运行详情加载失败'}
              description={detailError.message}
              onRetry={loadRunDetail}
            />
          </SectionCard>
        )}
        {detailRun && (
          <>
            <SectionCard title="运行概览" icon={<HistoryOutlined />}>
              <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }} bordered>
                <Descriptions.Item label="Run ID">
                  <Text code copyable style={{ wordBreak: 'break-all' }}>{displayRunId(detailRun)}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="流水线">
                  <a className="ol-link" onClick={() => navigate(`/orchestration/pipelines/${detailRun.dagId}`)}>
                    {detailRun.dagName || detailRun.dagId}
                  </a>
                </Descriptions.Item>
                <Descriptions.Item label="Dagster Job">{detailRun.dagsterJob || '-'}</Descriptions.Item>
                <Descriptions.Item label="触发方式">{detailRun.triggerType}</Descriptions.Item>
                <Descriptions.Item label="状态"><StatusBadge status={detailRun.status} /></Descriptions.Item>
                {detailRun.backfillId && (
                  <Descriptions.Item label="回填批次">
                    <a
                      className="ol-link"
                      onClick={() => navigate(`/orchestration/pipelines/${detailRun.dagId}/backfills/${detailRun.backfillId}`)}
                    >
                      {detailRun.backfillId}
                    </a>
                  </Descriptions.Item>
                )}
                {detailRun.logicalDate && (
                  <Descriptions.Item label="业务日期">{formatBusinessDate(detailRun.logicalDate)}</Descriptions.Item>
                )}
                <Descriptions.Item label="触发人">
                  <span title={triggerActorTitle(detailRun)}>{triggerActorName(detailRun)}</span>
                </Descriptions.Item>
                <Descriptions.Item label="开始时间">{formatDate(detailRun.startedAt)}</Descriptions.Item>
                <Descriptions.Item label="结束时间">{formatDate(detailRun.finishedAt)}</Descriptions.Item>
                <Descriptions.Item label="耗时">{formatDuration(detailRun)}</Descriptions.Item>
              </Descriptions>
            </SectionCard>
            <SectionCard title="任务拓扑与节点状态" icon={<HistoryOutlined />} flatBody>
              <div style={{ padding: 12 }}>
                <TaskRunsPanel
                  runId={detailRun.id}
                  dagId={detailRun.dagId}
                  runStatus={detailRun.status}
                  refreshVersion={taskRefreshVersion}
                  onRunChanged={loadRunDetail}
                  allowPrivilegedActions={canManageRuns}
                />
              </div>
            </SectionCard>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="ol-page">
      <PageHeader
        icon={<HistoryOutlined />}
        title={backfillIdFilter ? '回填子 Run' : '运行实例'}
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description={backfillIdFilter
          ? `按回填批次 ${backfillIdFilter} 分组展示已派发的子 Run`
          : dagIdFilter
            ? '查看当前流水线运行历史，含触发方式、耗时、责任人'
            : '查看所有流水线运行历史，含触发方式、耗时、责任人'}
        meta={[
          { label: backfillIdFilter ? '已派发' : dagIdFilter ? '当前流水线' : '当前页', value: backfillIdFilter ? total : counts.total },
          { label: '成功', value: backfillGroup?.succeeded ?? counts.success },
          { label: '失败', value: backfillGroup?.failed ?? counts.failed },
          { label: backfillIdFilter ? '批次状态' : '回填批次', value: backfillGroup?.status ?? counts.backfills },
        ]}
        actions={(
          <Space size={8} wrap>
            {backfillIdFilter && backfillGroup && (
              <Button onClick={() => navigate(`/orchestration/pipelines/${backfillGroup.dag_id}/backfills/${backfillGroup.id}`)}>
                回填进度
              </Button>
            )}
            {(dagIdFilter || backfillIdFilter) && <Button onClick={() => navigate('/orchestration/runs')}>查看全部</Button>}
            <Button icon={<ReloadOutlined />} onClick={() => loadRuns()} loading={loading}>刷新</Button>
          </Space>
        )}
      />

      <SectionCard title={backfillIdFilter ? '批次子 Run' : '运行历史'} icon={<HistoryOutlined />} flatBody>
        {loading && visibleRuns.length === 0 ? (
          <StateView state="loading" rows={6} />
        ) : error ? (
          <StateView
            state={error.noPermission ? 'no-permission' : 'error'}
            title={error.noPermission ? '无权查看运行实例' : '运行实例加载失败'}
            description={error.message}
            onRetry={() => loadRuns()}
          />
        ) : (
        <Table
          rowKey="id"
          dataSource={visibleRuns}
          loading={loading}
          expandable={{
            expandedRowKeys: expandedRunIds,
            onExpand: (expanded, run) => setExpandedRunIds(expanded ? [run.id] : []),
            expandedRowRender: (run: JobRun) => (
              <ExpandedTaskRunsPanel
                run={run}
                onRunChanged={() => loadRuns()}
                onCollapse={() => setExpandedRunIds((keys) => keys.filter((key) => key !== run.id))}
                allowPrivilegedActions={canManageRuns}
              />
            ),
            rowExpandable: (record) => Boolean(record.dagId),
          }}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title={backfillIdFilter ? '尚未派发子 Run' : '暂无运行记录'}
                description={backfillIdFilter ? '回填窗口仍在队列中，派发后将在此出现' : '触发流水线后，运行历史将自动出现'}
              />
            ),
          }}
          size="middle"
          scroll={{ x: 1480 }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (value) => `共 ${value} 条`,
            onChange: (nextPage, nextPageSize) => loadRuns(nextPage - 1, nextPageSize),
          }}
          columns={[
            {
              title: 'Run ID',
              width: 220,
              render: (_: unknown, r: JobRun) => (
                <Text
                  code
                  ellipsis={{ tooltip: displayRunId(r) }}
                  style={{ display: 'block', maxWidth: 196, fontSize: 12 }}
                >
                  {displayRunId(r)}
                </Text>
              ),
            },
            { title: '流水线', render: (_: unknown, r: JobRun) => (
              <div>
                <a className="ol-link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>{r.dagName || r.dagId}</a>
                {r.dagsterJob && <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{r.dagsterJob}</div>}
              </div>
            ) },
            {
              title: 'Backfill',
              width: 150,
              onCell: (r: JobRun) => ({ rowSpan: backfillRowSpans.get(r.id) ?? 1 }),
              render: (_: unknown, r: JobRun) => r.backfillId ? (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => navigate(`/orchestration/runs?backfill_id=${encodeURIComponent(r.backfillId!)}`)}
                >
                  <Text code>{shortId(r.backfillId)}</Text>
                </Button>
              ) : <Text type="secondary">-</Text>,
            },
            {
              title: '业务日期',
              dataIndex: 'logicalDate',
              width: 170,
              render: (value?: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatBusinessDate(value)}</span>,
            },
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
            { title: '触发人', width: 120, render: (_: unknown, r: JobRun) => (
              <span title={triggerActorTitle(r)} style={{ fontSize: 12, color: 'var(--ol-ink)' }}>{triggerActorName(r)}</span>
            ) },
            { title: '操作', width: 190, render: (_: unknown, r: JobRun) => (
              <Space>
                <Button
                  size="small"
                  type="link"
                  onClick={() => navigate(`/orchestration/runs/${r.id}${r.backfillId ? `?backfill_id=${encodeURIComponent(r.backfillId)}` : ''}`)}
                >
                  查看详情
                </Button>
                <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>打开流水线</Button>
              </Space>
            ) },
          ]}
        />
        )}
      </SectionCard>
    </div>
  );
}

function ExpandedTaskRunsPanel({
  run,
  onCollapse,
  onRunChanged,
  allowPrivilegedActions,
}: {
  run: JobRun;
  onCollapse: () => void;
  onRunChanged?: () => void;
  allowPrivilegedActions: boolean;
}) {
  return (
    <div
      className="orchestration-run-expanded-panel"
      style={{
        border: '1px solid var(--ol-line-soft)',
        borderRadius: 8,
        background: 'var(--ol-card)',
        boxShadow: 'inset 0 1px 0 rgba(15, 23, 42, 0.03)',
        display: 'flex',
        flexDirection: 'column',
        maxHeight: 'min(72vh, 680px)',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      <div
        className="orchestration-run-expanded-panel__header"
        style={{
          alignItems: 'center',
          background: 'var(--ol-fill-soft)',
          borderBottom: '1px solid var(--ol-line-soft)',
          display: 'flex',
          gap: 12,
          justifyContent: 'space-between',
          padding: '10px 12px',
          flexShrink: 0,
        }}
      >
        <Space direction="vertical" size={0} style={{ minWidth: 0 }}>
          <Text strong style={{ fontSize: 13 }}>任务运行明细</Text>
          <Text type="secondary" ellipsis style={{ maxWidth: 720, fontSize: 12 }}>
            {run.dagName || run.dagId} · {displayRunId(run)} · {formatDate(run.startedAt)}
          </Text>
        </Space>
        <Button size="small" icon={<UpOutlined />} onClick={onCollapse}>
          收起
        </Button>
      </div>
      <div
        className="orchestration-run-expanded-panel__body"
        style={{
          minHeight: 0,
          overflow: 'auto',
          overscrollBehavior: 'contain',
          padding: 12,
        }}
      >
        <TaskRunsPanel
          runId={run.id}
          dagId={run.dagId}
          runStatus={run.status}
          onRunChanged={onRunChanged}
          allowPrivilegedActions={allowPrivilegedActions}
        />
      </div>
    </div>
  );
}

function TaskRunOverviewPanel({
  row,
  assetByFqn,
}: {
  row: TaskRunDisplayRow;
  assetByFqn: AssetByFqn;
}) {
  const task = row.task;
  const run = row.run;
  return (
    <Descriptions size="small" column={1} bordered>
      <Descriptions.Item label="节点名称">{task?.name || row.taskKey}</Descriptions.Item>
      <Descriptions.Item label="Task Key">
        <Text code copyable style={{ wordBreak: 'break-all' }}>{row.taskKey}</Text>
      </Descriptions.Item>
      <Descriptions.Item label="类型">{taskTypeLabel(task?.taskType)}</Descriptions.Item>
      <Descriptions.Item label="状态">
        <StatusBadge
          status={row.status}
          label={RUN_STATUS_LABEL[row.status]}
          tooltip={row.blockedBy.length > 0 ? `上游失败：${row.blockedBy.join('、')}` : undefined}
        />
      </Descriptions.Item>
      <Descriptions.Item label="Attempt">{run?.attempt ?? '-'}</Descriptions.Item>
      <Descriptions.Item label="开始时间">{formatDate(run?.startedAt)}</Descriptions.Item>
      <Descriptions.Item label="结束时间">{formatDate(run?.finishedAt)}</Descriptions.Item>
      <Descriptions.Item label="耗时">{formatTaskDuration(run)}</Descriptions.Item>
      <Descriptions.Item label="目标表">
        {task?.targetFqn ? <TableFqnLink fqn={task.targetFqn} assetByFqn={assetByFqn} /> : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="产物">
        <ArtifactPathValue path={run?.artifactPath} assetByFqn={assetByFqn} />
      </Descriptions.Item>
      <Descriptions.Item label="行数">{run?.rowsWritten == null ? '-' : run.rowsWritten.toLocaleString()}</Descriptions.Item>
      <Descriptions.Item label="扫描字节">{run?.scanBytes == null ? '-' : `${(run.scanBytes / 1024 / 1024).toFixed(1)} MB`}</Descriptions.Item>
      <Descriptions.Item label="Log Ref">
        {run?.logRef ? <Text code copyable style={{ wordBreak: 'break-all' }}>{run.logRef}</Text> : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="错误">
        {run?.errorMsg ? <Text type="danger">{run.errorMsg}</Text> : '-'}
      </Descriptions.Item>
    </Descriptions>
  );
}

function TaskRunLogPanel({
  dagId,
  runId,
  row,
  enabled,
}: {
  dagId: string;
  runId: string;
  row: TaskRunDisplayRow;
  enabled: boolean;
}) {
  const { message } = AntApp.useApp();
  const [tailLines, setTailLines] = useState(DEFAULT_LOG_TAIL_LINES);
  const [logText, setLogText] = useState('');
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<UiError | null>(null);
  const [downloading, setDownloading] = useState(false);
  const canReadLog = Boolean(row.run?.logRef);

  useEffect(() => {
    setTailLines(DEFAULT_LOG_TAIL_LINES);
    setLogText('');
    setLoaded(false);
    setError(null);
  }, [row.taskKey, row.run?.id, row.run?.logRef]);

  const loadLog = useCallback(() => {
    if (!canReadLog) return;
    setLoading(true);
    setError(null);
    PipelineAPI.getTaskLog(dagId, runId, row.taskKey, { tail: tailLines })
      .then((text) => {
        setLogText(text || '');
        setLoaded(true);
      })
      .catch((e) => {
        setError(toUiError(e, '节点日志加载失败'));
      })
      .finally(() => setLoading(false));
  }, [canReadLog, dagId, row.taskKey, runId, tailLines]);

  useEffect(() => {
    if (enabled && canReadLog) {
      loadLog();
    }
  }, [canReadLog, enabled, loadLog]);

  const downloadLog = () => {
    if (!canReadLog || downloading) return;
    setDownloading(true);
    PipelineAPI.downloadTaskRunLog(dagId, runId, row.taskKey)
      .then((response) => {
        const disposition = response.headers['content-disposition'] || response.headers['Content-Disposition'];
        const filename = filenameFromContentDisposition(disposition as string | undefined) || `${row.taskKey}.log`;
        downloadBlob(response.data, filename);
        message.success('日志下载完成');
      })
      .catch((e) => message.error(e.message || '日志下载失败'))
      .finally(() => setDownloading(false));
  };

  if (!row.run) {
    return (
      <StateView
        state="empty"
        title="暂无节点日志"
        description="该节点还没有对应 task_run 记录。"
      />
    );
  }

  if (!canReadLog) {
    return (
      <StateView
        state="empty"
        title="暂无节点日志"
        description="当前 task_run 尚未写入 log_ref。"
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <Space size={8} wrap>
          <Text type="secondary">Tail</Text>
          <Select
            size="small"
            value={tailLines}
            style={{ width: 112 }}
            onChange={setTailLines}
            options={LOG_TAIL_OPTIONS.map((value) => ({ value, label: `${value} 行` }))}
          />
          {row.run.attempt && <Tag style={{ margin: 0 }}>attempt {row.run.attempt}</Tag>}
        </Space>
        <Space size={8} wrap>
          <Button size="small" icon={<ReloadOutlined />} onClick={loadLog} loading={loading}>
            刷新
          </Button>
          <Button size="small" icon={<DownloadOutlined />} onClick={downloadLog} loading={downloading}>
            全量下载
          </Button>
        </Space>
      </div>

      {error && (
        <StateView
          state={error.noPermission ? 'no-permission' : 'error'}
          title={error.noPermission ? '无权查看节点日志' : '节点日志加载失败'}
          description={error.message}
          onRetry={loadLog}
        />
      )}

      {!error && loading && !loaded ? (
        <StateView state="loading" rows={5} />
      ) : !error ? (
        <pre
          style={{
            margin: 0,
            maxHeight: 'min(58vh, 560px)',
            overflow: 'auto',
            padding: 12,
            border: '1px solid var(--ol-line-soft)',
            borderRadius: 8,
            background: 'var(--ol-fill-soft)',
            color: 'var(--ol-ink)',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
            fontSize: 12,
            lineHeight: 1.55,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {logText || '暂无日志内容'}
        </pre>
      ) : null}
    </div>
  );
}

function TaskRunDrawer({
  row,
  dagId,
  runId,
  assetByFqn,
  activeTab,
  onTabChange,
  onClose,
  allowLogs,
}: {
  row: TaskRunDisplayRow | null;
  dagId: string;
  runId: string;
  assetByFqn: AssetByFqn;
  activeTab: string;
  onTabChange: (key: string) => void;
  onClose: () => void;
  allowLogs: boolean;
}) {
  return (
    <Drawer
      open={Boolean(row)}
      onClose={onClose}
      width={720}
      title={row ? (row.task?.name || row.taskKey) : '节点详情'}
      destroyOnClose
      extra={row?.run?.logRef ? <Tag style={{ margin: 0 }}>attempt {row.run.attempt ?? 1}</Tag> : undefined}
    >
      {row && (
        <Tabs
          activeKey={activeTab}
          onChange={onTabChange}
          items={[
            {
              key: 'overview',
              label: '概览',
              children: <TaskRunOverviewPanel row={row} assetByFqn={assetByFqn} />,
            },
            ...(allowLogs ? [{
              key: 'log',
              label: (
                <span>
                  <FileTextOutlined /> 日志
                </span>
              ),
              children: (
                <TaskRunLogPanel
                  dagId={dagId}
                  runId={runId}
                  row={row}
                  enabled={activeTab === 'log'}
                />
              ),
            }] : []),
          ]}
        />
      )}
    </Drawer>
  );
}

/**
 * Per-task status panel (P4). Loads via PipelineAPI.listTaskRuns for v2 pipelines.
 * Falls back to "no task_run data" when a run has no task-level rows.
 */
function TaskRunsPanel({
  runId,
  dagId,
  runStatus,
  refreshVersion,
  onRunChanged,
  allowPrivilegedActions,
}: {
  runId: string;
  dagId: string;
  runStatus?: string;
  refreshVersion?: number;
  onRunChanged?: () => void;
  allowPrivilegedActions: boolean;
}) {
  const { message } = AntApp.useApp();
  const [taskRuns, setTaskRuns] = useState<TaskRun[] | null>(null);
  const [tasks, setTasks] = useState<PipelineTask[]>([]);
  const [edges, setEdges] = useState<PipelineTaskEdge[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [error, setError] = useState<UiError | null>(null);
  const [selectedRow, setSelectedRow] = useState<TaskRunDisplayRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('overview');
  const [rerunningTask, setRerunningTask] = useState<string | null>(null);

  const hasLoadedTaskRuns = taskRuns !== null;

  const reloadTaskRuns = useCallback(async (options?: { silent?: boolean }) => {
    try {
      const runs = await PipelineAPI.listTaskRuns(dagId, runId);
      setTaskRuns(runs);
      setError(null);
    } catch (e) {
      const nextError = toUiError(e, 'task_run 加载失败');
      // Background node polling should not replace a usable task table with a
      // transient refresh error; explicit retries still surface the failure.
      if (!(options?.silent && hasLoadedTaskRuns)) {
        setError(nextError);
      }
      if (!options?.silent) {
        throw e;
      }
    }
  }, [dagId, hasLoadedTaskRuns, runId]);

  const handleRerunTask = async (row: TaskRunDisplayRow, mode: TaskRerunMode) => {
    if (!allowPrivilegedActions) return;
    if (!canRerunTaskForMode(row, mode)) {
      message.warning(mode === 'DOWNSTREAM' ? '仅失败节点或存在失败下游的已修复节点可从失败续跑' : '仅失败或上游失败节点可重跑');
      return;
    }
    const loadingKey = `${row.taskKey}:${mode}`;
    setRerunningTask(loadingKey);
    try {
      const result = await PipelineAPI.rerunTask(dagId, runId, row.taskKey, mode);
      const taskScope = result.rerunTasks?.length ? result.rerunTasks.join('、') : row.taskKey;
      message.success(`${mode === 'DOWNSTREAM' ? '已从失败续跑' : '已发起重跑'}：${taskScope}`);
      await reloadTaskRuns();
      onRunChanged?.();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '节点重跑失败');
    } finally {
      setRerunningTask(null);
    }
  };

  useEffect(() => {
    let cancelled = false;
    setError(null);
    Promise.all([
      PipelineAPI.listTaskRuns(dagId, runId),
      PipelineAPI.listTasks(dagId),
      PipelineAPI.listEdges(dagId),
      CatalogAPI.listAssets()
        .then(normalizeCatalogAssets)
        .catch(() => []),
    ])
      .then(([runs, taskDefs, edgeDefs, catalogAssets]) => {
        if (cancelled) return;
        setTaskRuns(runs);
        setTasks(taskDefs);
        setEdges(edgeDefs);
        setAssets(catalogAssets);
      })
      .catch((e) => !cancelled && setError(toUiError(e, 'task_run 加载失败')));
    return () => {
      cancelled = true;
    };
  }, [runId, dagId, refreshVersion]);

  const shouldPollTaskRuns = Boolean(runStatus && !isRunTerminal(runStatus));

  useEffect(() => {
    if (!shouldPollTaskRuns) return undefined;
    const timer = window.setInterval(() => {
      reloadTaskRuns({ silent: true }).catch(() => undefined);
    }, TASK_RUN_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [shouldPollTaskRuns, reloadTaskRuns]);

  const assetByFqn = useMemo(() => buildAssetByFqn(assets), [assets]);

  if (error) {
    return (
      <StateView
        state={error.noPermission ? 'no-permission' : 'error'}
        title={error.noPermission ? '无权查看节点运行' : '节点运行加载失败'}
        description={error.message}
        onRetry={() => reloadTaskRuns().catch(() => undefined)}
      />
    );
  }
  if (!taskRuns) {
    return <StateView state="loading" rows={5} />;
  }
  if (taskRuns.length === 0 && tasks.length === 0) {
    return (
      <StateView
        state="empty"
        title="无 task_run 记录"
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
  const openTaskDrawer = (row: TaskRunDisplayRow, tab = 'overview') => {
    setSelectedRow(row);
    setDrawerTab(tab);
  };

  function buildDownstreamSubgraph(rootKey: string) {
    const runByKey = new Map((taskRuns || []).map((run) => [run.taskKey, run]));
    const downstreamBySource = new Map<string, string[]>();
    edges
      .filter((edge) => edge.edgeLayer === 'PIPELINE')
      .forEach((edge) => {
        const children = downstreamBySource.get(edge.sourceKey) || [];
        children.push(edge.targetKey);
        downstreamBySource.set(edge.sourceKey, children);
      });

    const selected = new Set<string>([rootKey]);
    const queue = [rootKey];
    while (queue.length) {
      const current = queue.shift()!;
      (downstreamBySource.get(current) || []).forEach((childKey) => {
        const childRun = runByKey.get(childKey);
        if (!childRun || childRun.status === 'SUCCEEDED') return;
        if (!selected.has(childKey)) {
          selected.add(childKey);
          queue.push(childKey);
        }
      });
    }
    return selected;
  }

  function canResumeFromSucceededRoot(row: TaskRunDisplayRow) {
    if (!row.run || row.run.status !== 'SUCCEEDED') return false;
    const subgraph = buildDownstreamSubgraph(row.taskKey);
    if (subgraph.size <= 1) return false;

    if (findNonSucceededExternalUpstream(row.taskKey, subgraph)) {
      return false;
    }
    return hasRerunnableDescendant(row.taskKey, subgraph);
  }

  function hasRerunnableDescendant(rootKey: string, subgraph: Set<string>) {
    const runByKey = new Map((taskRuns || []).map((run) => [run.taskKey, run]));
    for (const taskKey of subgraph) {
      if (taskKey === rootKey) continue;
      const run = runByKey.get(taskKey);
      if (run && RERUNNABLE_TASK_STATUSES.has(run.status)) {
        return true;
      }
    }
    return false;
  }

  function findNonSucceededExternalUpstream(rootKey: string, subgraph: Set<string>) {
    if (subgraph.size <= 1) return null;
    const runByKey = new Map((taskRuns || []).map((run) => [run.taskKey, run]));
    const upstreamByTarget = new Map<string, string[]>();
    edges
      .filter((edge) => edge.edgeLayer === 'PIPELINE')
      .forEach((edge) => {
        const upstreams = upstreamByTarget.get(edge.targetKey) || [];
        upstreams.push(edge.sourceKey);
        upstreamByTarget.set(edge.targetKey, upstreams);
      });

    for (const taskKey of subgraph) {
      if (taskKey === rootKey) continue;
      for (const upstreamKey of upstreamByTarget.get(taskKey) || []) {
        if (subgraph.has(upstreamKey)) continue;
        if (runByKey.get(upstreamKey)?.status !== 'SUCCEEDED') {
          return `${upstreamKey} -> ${taskKey}`;
        }
      }
    }
    return null;
  }

  function canRerunTaskForMode(row: TaskRunDisplayRow, mode: TaskRerunMode) {
    if (!row.run) return false;
    if (mode === 'SINGLE') return RERUNNABLE_TASK_STATUSES.has(row.run.status);
    if (RERUNNABLE_TASK_STATUSES.has(row.run.status)) {
      const subgraph = buildDownstreamSubgraph(row.taskKey);
      return !findNonSucceededExternalUpstream(row.taskKey, subgraph);
    }
    return mode === 'DOWNSTREAM' && canResumeFromSucceededRoot(row);
  }

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
      <RunTaskGraph
        rows={rows}
        tasks={tasks}
        edges={edges}
        assetByFqn={assetByFqn}
        onOpenTask={(row) => openTaskDrawer(row)}
      />
      <Table<TaskRunDisplayRow>
        rowKey="id"
        dataSource={rows}
        pagination={false}
        scroll={{ x: 1180 }}
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
            return task?.targetFqn ? (
              <TableFqnLink fqn={task.targetFqn} assetByFqn={assetByFqn} />
            ) : (
              '-'
            );
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
          render: (_: unknown, row: TaskRunDisplayRow) => (
            <ArtifactPathValue path={row.run?.artifactPath} assetByFqn={assetByFqn} />
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
        {
          title: '操作',
          width: 320,
          fixed: 'right',
          render: (_: unknown, row: TaskRunDisplayRow) => {
            const canRerunSingle = canRerunTaskForMode(row, 'SINGLE');
            const canRerunDownstream = canRerunTaskForMode(row, 'DOWNSTREAM');
            const singleKey = `${row.taskKey}:SINGLE`;
            const downstreamKey = `${row.taskKey}:DOWNSTREAM`;
            const singleDisabledTitle = canRerunSingle ? undefined : '仅失败或上游失败节点可重跑';
            const downstreamDisabledTitle = canRerunDownstream ? undefined : '仅失败节点或存在失败下游的已修复节点可从失败续跑';
            const isRerunningThisRow = rerunningTask === singleKey || rerunningTask === downstreamKey;
            const rerunDisabledTitle = row.synthetic
              ? '该节点尚无 task_run，不能重跑'
              : canRerunSingle || canRerunDownstream
                ? undefined
                : '当前节点状态不能重跑';
            return (
              <Space size={4} wrap>
                <Button
                  size="small"
                  type="link"
                  icon={<EyeOutlined />}
                  onClick={() => openTaskDrawer(row)}
                >
                  详情
                </Button>
                {allowPrivilegedActions && <Button
                  size="small"
                  type="link"
                  icon={<FileTextOutlined />}
                  onClick={() => openTaskDrawer(row, 'log')}
                >
                  查看日志
                </Button>}
                {allowPrivilegedActions && <Dropdown
                  trigger={['click']}
                  disabled={row.synthetic || (!canRerunSingle && !canRerunDownstream) || Boolean(rerunningTask)}
                  menu={{
                    items: [
                      {
                        key: 'SINGLE',
                        label: <span title={singleDisabledTitle}>SINGLE · 仅当前节点</span>,
                        disabled: !canRerunSingle,
                      },
                      {
                        key: 'DOWNSTREAM',
                        label: <span title={downstreamDisabledTitle}>DOWNSTREAM · 节点及未成功下游</span>,
                        disabled: !canRerunDownstream,
                      },
                    ],
                    onClick: ({ key }) => handleRerunTask(row, key as TaskRerunMode),
                  }}
                >
                    <Button
                      size="small"
                      type="link"
                      title={rerunDisabledTitle}
                      icon={<RedoOutlined />}
                      loading={isRerunningThisRow}
                      disabled={row.synthetic || (!canRerunSingle && !canRerunDownstream) || (Boolean(rerunningTask) && !isRerunningThisRow)}
                    >
                      重跑
                      <DownOutlined />
                    </Button>
                </Dropdown>}
              </Space>
            );
          },
        },
        ]}
      />
      <TaskRunDrawer
        row={selectedRow}
        dagId={dagId}
        runId={runId}
        assetByFqn={assetByFqn}
        activeTab={drawerTab}
        onTabChange={setDrawerTab}
        onClose={() => setSelectedRow(null)}
        allowLogs={allowPrivilegedActions}
      />
    </div>
  );
}
