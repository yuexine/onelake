/**
 * Pipeline DAG canvas backed by @antv/x6.
 *
 * The persisted contract stays unchanged: pipeline_task renders as nodes and
 * pipeline_task_edge renders as data-flow edges. Start/end nodes are visual
 * helpers only, so the user can read the pipeline as a complete flow without
 * writing synthetic tasks back to the backend.
 */
import { useEffect, useMemo, useRef } from 'react';
import type { DragEvent as ReactDragEvent } from 'react';
import { Button, Space, Tag, Tooltip, Typography } from 'antd';
import {
  AimOutlined,
  CompressOutlined,
  MinusOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { Graph, Shape } from '@antv/x6';
import type { PipelineTask, PipelineTaskEdge, PipelineTaskType } from '../../../types';
import type { TaskTypeMeta } from './taskTypes';
import { StateView } from '../../../components';

const { Text } = Typography;

interface Props {
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  selectedKey?: string;
  onSelect: (key?: string) => void;
  /** Per-task run status (keyed by task_key) for live status badges. */
  taskRunByKey?: Map<string, { status?: string; rowsWritten?: number; errorMsg?: string }>;
  onDropTask?: (meta: TaskTypeMeta, position: { x: number; y: number }) => void;
  onMoveTask?: (taskKey: string, position: { x: number; y: number }) => void;
}

type RunState = { status?: string; rowsWritten?: number; errorMsg?: string };

interface CanvasNode {
  id: string;
  task?: PipelineTask;
  virtual?: 'START' | 'END';
  x: number;
  y: number;
  width: number;
  height: number;
  inputPorts: string[];
  outputPorts: string[];
  incoming: number;
  outgoing: number;
  runState?: RunState;
}

interface CanvasEdge {
  id: string;
  source: string;
  target: string;
  sourcePort?: string;
  targetPort?: string;
  label?: string;
  virtual?: boolean;
}

const NODE_WIDTH = 300;
const NODE_HEIGHT = 116;
const VIRTUAL_WIDTH = 162;
const VIRTUAL_HEIGHT = 72;
const COLUMN_GAP = 380;
const ROW_GAP = 72;
const ORIGIN_X = 72;
const ORIGIN_Y = 72;
const START_ID = '__pipeline_start__';
const END_ID = '__pipeline_end__';
const TASK_CARD_SHAPE = 'onelake-pipeline-task-card';

const TASK_KIND_TONE: Record<string, { fill: string; stroke: string; text: string }> = {
  SYNC_REF: { fill: '#E0F2FE', stroke: '#38BDF8', text: '#0369A1' },
  JOIN: { fill: '#EEF2FF', stroke: '#818CF8', text: '#3730A3' },
  DERIVE_COLUMN: { fill: '#ECFDF5', stroke: '#34D399', text: '#047857' },
  SINK: { fill: '#FFF7ED', stroke: '#FDBA74', text: '#C2410C' },
  QUALITY_GATE: { fill: '#F0FDF4', stroke: '#4ADE80', text: '#15803D' },
  SPARK_SQL: { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#334155' },
  PYSPARK: { fill: '#ECFEFF', stroke: '#22D3EE', text: '#0E7490' },
  TRINO_SQL: { fill: '#EFF6FF', stroke: '#60A5FA', text: '#1D4ED8' },
  PYTHON: { fill: '#FEFCE8', stroke: '#FACC15', text: '#A16207' },
  SHELL: { fill: '#F8FAFC', stroke: '#94A3B8', text: '#334155' },
  BRANCH: { fill: '#F5F3FF', stroke: '#A78BFA', text: '#6D28D9' },
  CONDITION: { fill: '#F5F3FF', stroke: '#C4B5FD', text: '#7C3AED' },
  SENSOR: { fill: '#ECFEFF', stroke: '#67E8F9', text: '#0E7490' },
  WAIT: { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#475569' },
  SUB_PIPELINE: { fill: '#FAF5FF', stroke: '#D8B4FE', text: '#7E22CE' },
  NOTIFY: { fill: '#FFF7ED', stroke: '#FDBA74', text: '#C2410C' },
  ASSERTION: { fill: '#ECFDF5', stroke: '#6EE7B7', text: '#047857' },
};

const RUN_TONE: Record<string, { fill: string; stroke: string; text: string }> = {
  SUCCEEDED: { fill: '#ECFDF3', stroke: '#86EFAC', text: '#166534' },
  FAILED: { fill: '#FEF2F2', stroke: '#FCA5A5', text: '#B91C1C' },
  RUNNING: { fill: '#EFF6FF', stroke: '#93C5FD', text: '#1D4ED8' },
  QUEUED: { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#475569' },
  CANCELLED: { fill: '#FFF7ED', stroke: '#FDBA74', text: '#C2410C' },
  UPSTREAM_FAILED: { fill: '#FFFBEB', stroke: '#FCD34D', text: '#B45309' },
  SKIPPED: { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#64748B' },
  DRAFT: { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#475569' },
};

function nodeTypeDisplay(type: PipelineTaskType, kind?: string): { code: string; label: string; role: string } {
  if (type === 'SYNC_REF') return { code: 'IN', label: '取数', role: '数据源入口' };
  if (type === 'QUALITY_GATE') return { code: 'QA', label: '质量', role: '规则检查' };
  if (kind === 'JOIN') return { code: 'JN', label: '关联', role: '多源合并' };
  if (kind === 'DERIVE_COLUMN') return { code: 'GV', label: '治理', role: '字段标准化' };
  if (kind === 'SINK') return { code: 'OUT', label: '落表', role: 'DWD 输出' };
  if (type === 'PYSPARK') return { code: 'PY', label: 'PySpark', role: '脚本计算' };
  const displays: Partial<Record<PipelineTaskType, { code: string; label: string; role: string }>> = {
    TRINO_SQL: { code: 'TR', label: 'Trino SQL', role: '轻量查询' },
    PYTHON: { code: 'PY', label: 'Python', role: '沙箱脚本' },
    SHELL: { code: 'SH', label: 'Shell', role: '沙箱脚本' },
    BRANCH: { code: 'BR', label: '多路分支', role: '流程控制' },
    CONDITION: { code: 'IF', label: '条件判断', role: '流程控制' },
    SENSOR: { code: 'SE', label: 'Sensor', role: '资产观测' },
    WAIT: { code: 'WT', label: '等待', role: '时间观测' },
    SUB_PIPELINE: { code: 'SUB', label: '子流水线', role: '流程控制' },
    NOTIFY: { code: 'NT', label: '通知', role: '运行观测' },
    ASSERTION: { code: 'AS', label: '断言', role: '运行观测' },
  };
  if (displays[type]) return displays[type]!;
  return { code: 'SQL', label: 'Spark SQL', role: 'Spark 计算' };
}

function nodeKind(task: PipelineTask): string {
  const dataflow = (task.config?.dataflow ?? {}) as Record<string, unknown>;
  if (typeof dataflow.nodeKind === 'string' && dataflow.nodeKind.trim()) {
    return dataflow.nodeKind;
  }
  return task.taskType;
}

function truncate(value: string | undefined, limit: number): string {
  if (!value) return '';
  if (value.length <= limit) return value;
  return `${value.slice(0, Math.max(0, limit - 1))}…`;
}

function compactFqn(value: string | undefined, limit = 34): string {
  const raw = value?.replace(/^table:/, '').trim();
  if (!raw) return '';
  const parts = raw.split('.').filter(Boolean);
  const display = parts.length >= 3
    ? `${parts[parts.length - 2]}.${parts[parts.length - 1]}`
    : raw;
  return truncate(display, limit);
}

function readablePort(value?: string): string {
  const raw = value?.trim();
  if (!raw) return '';
  const normalized = raw.toLowerCase();
  if (normalized === 'left' || normalized === 'l' || normalized.includes('left')) return '左输入';
  if (normalized === 'right' || normalized === 'r' || normalized.includes('right')) return '右输入';
  if (normalized === 'in' || normalized.includes('input')) return '输入';
  if (normalized === 'out' || normalized.includes('output')) return '输出';
  return truncate(raw, 10);
}

function portId(value?: string, fallback = 'in'): string {
  const raw = (value || fallback).trim() || fallback;
  return raw.replace(/[^a-zA-Z0-9_\-:.]/g, '_');
}

function edgePortLabel(edge: PipelineTaskEdge): string {
  return edge.targetInput
    || edge.targetPort
    || edge.joinRole
    || edge.inputAlias
    || edge.sourceOutput
    || edge.sourcePort
    || '';
}

function edgeLabel(edge: PipelineTaskEdge): string {
  const port = readablePort(edgePortLabel(edge));
  if (port) return port;
  const asset = compactFqn(edge.assetFqn || edge.inputAlias, 18);
  if (asset) return asset;
  return '';
}

function statusText(task: PipelineTask, runState?: RunState): string {
  if (runState?.status) return runState.status;
  if (task.compileStatus === 'VALIDATED') return '已校验';
  if (task.compileStatus === 'FAILED') return '校验失败';
  return '草稿';
}

function statusTone(status: string): { fill: string; stroke: string; text: string } {
  if (RUN_TONE[status]) return RUN_TONE[status];
  if (status === '已校验') return RUN_TONE.SUCCEEDED;
  if (status === '校验失败') return RUN_TONE.FAILED;
  return { fill: '#F8FAFC', stroke: '#CBD5E1', text: '#475569' };
}

function statusDisplay(status: string): { label: string; tone: { fill: string; stroke: string; text: string } } {
  const labelMap: Record<string, string> = {
    SUCCEEDED: '成功',
    FAILED: '失败',
    RUNNING: '运行中',
    QUEUED: '排队',
    CANCELLED: '取消',
    UPSTREAM_FAILED: '上游失败',
    SKIPPED: '跳过',
  };
  if (labelMap[status]) return { label: labelMap[status], tone: statusTone(status) };
  if (status === '已校验') return { label: '已校验', tone: statusTone(status) };
  if (status === '校验失败') return { label: '校验失败', tone: statusTone(status) };
  return { label: status || '草稿', tone: statusTone(status || 'DRAFT') };
}

function nodeTone(task: PipelineTask): { fill: string; stroke: string; text: string } {
  const kind = nodeKind(task);
  return TASK_KIND_TONE[kind] || TASK_KIND_TONE[task.taskType] || TASK_KIND_TONE.SPARK_SQL;
}

function engineLabel(task: PipelineTask): string {
  if (task.taskType === 'SYNC_REF') return 'SYNC';
  if (task.taskType === 'PYSPARK' || task.engine === 'PYSPARK') return 'PYSPARK';
  if (task.taskType === 'TRINO_SQL') return 'TRINO';
  if (task.taskType === 'PYTHON' || task.taskType === 'SHELL') return 'SANDBOX';
  if (task.category === 'CONTROL' || ['BRANCH', 'CONDITION', 'SUB_PIPELINE'].includes(task.taskType)) return 'CONTROL';
  if (task.category === 'OBSERVE' || ['SENSOR', 'WAIT', 'NOTIFY', 'ASSERTION'].includes(task.taskType)) return 'OBSERVE';
  return 'SPARK';
}

function tableRole(task: PipelineTask, kind: string): string {
  if (task.taskType === 'SYNC_REF') return '读取表';
  if (task.taskType === 'QUALITY_GATE') return '检查表';
  if (kind === 'SINK') return '输出表';
  if (task.taskType === 'BRANCH' || task.taskType === 'CONDITION' || task.taskType === 'ASSERTION') return '表达式';
  if (task.taskType === 'SENSOR') return '等待资产';
  if (task.taskType === 'WAIT') return '等待时长';
  if (task.taskType === 'SUB_PIPELINE') return '目标流水线';
  if (task.taskType === 'NOTIFY') return '通知标题';
  if (task.taskType === 'PYTHON' || task.taskType === 'SHELL') return '执行环境';
  return '产出表';
}

function taskDetail(task: PipelineTask): string {
  if (task.targetFqn) return compactFqn(task.targetFqn, 30);
  const config = task.config ?? {};
  if (task.taskType === 'BRANCH' || task.taskType === 'CONDITION' || task.taskType === 'ASSERTION') {
    return truncate(typeof config.expression === 'string' ? config.expression : '', 30) || '未配置表达式';
  }
  if (task.taskType === 'SENSOR') return truncate(typeof config.assetFqn === 'string' ? config.assetFqn : '', 30) || '未配置资产';
  if (task.taskType === 'WAIT') {
    if (typeof config.offsetSeconds === 'number') return `logical_date + ${config.offsetSeconds} 秒`;
    if (typeof config.durationSeconds === 'number') return `${config.durationSeconds} 秒`;
    return '未配置时长';
  }
  if (task.taskType === 'SUB_PIPELINE') return truncate(typeof config.subDagId === 'string' ? config.subDagId : '', 30) || '未选择流水线';
  if (task.taskType === 'NOTIFY') return truncate(typeof config.title === 'string' ? config.title : '', 30) || '未配置标题';
  if (task.taskType === 'PYTHON' || task.taskType === 'SHELL') return '默认禁网 · 受限沙箱';
  return '未配置表';
}

function flowMetric(node: CanvasNode): string {
  if (typeof node.runState?.rowsWritten === 'number') {
    return `输出 ${node.runState.rowsWritten.toLocaleString('zh-CN')} 行`;
  }
  return `${node.incoming} 入 · ${node.outgoing} 出`;
}

interface TaskCardData {
  typeCode: string;
  typeLabel: string;
  statusLabel: string;
  title: string;
  tableRole: string;
  table: string;
  flow: string;
  engine: string;
  selected: boolean;
  hasError: boolean;
  tone: { fill: string; stroke: string; text: string };
  runTone: { fill: string; stroke: string; text: string };
  engineColor: string;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function taskCardHtml(card: TaskCardData): string {
  const border = card.selected ? '#2563EB' : card.hasError ? '#EF4444' : '#D8E0EA';
  const ring = card.selected ? '0 0 0 3px rgba(37, 99, 235, 0.14), ' : '';
  const shadow = card.hasError
    ? '0 12px 22px rgba(239, 68, 68, 0.12)'
    : '0 12px 24px rgba(15, 23, 42, 0.09)';
  const accent = card.hasError ? '#EF4444' : card.tone.stroke;
  const badge = card.hasError ? '#B91C1C' : card.tone.text;

  return `
    <div style="
      width:100%;
      height:100%;
      box-sizing:border-box;
      border:1.2px solid ${border};
      border-left:6px solid ${accent};
      border-radius:10px;
      background:#FFFFFF;
      box-shadow:${ring}${shadow};
      padding:8px 12px 7px 14px;
      display:grid;
      grid-template-rows:20px 24px 24px 16px;
      row-gap:4px;
      overflow:hidden;
      contain:layout paint;
      color:#0F172A;
      font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
    ">
      <div style="display:flex;align-items:center;gap:8px;min-width:0;">
        <span style="
          width:34px;
          height:19px;
          border-radius:6px;
          background:${badge};
          color:#FFFFFF;
          display:inline-flex;
          align-items:center;
          justify-content:center;
          font-size:10px;
          font-weight:800;
          line-height:19px;
          flex:0 0 auto;
        ">${escapeHtml(card.typeCode)}</span>
        <span style="
          color:#1F2937;
          font-size:12px;
          font-weight:800;
          line-height:17px;
          white-space:nowrap;
          overflow:hidden;
          text-overflow:ellipsis;
          min-width:0;
        ">${escapeHtml(card.typeLabel)}</span>
        <span style="
          margin-left:auto;
          display:inline-flex;
          align-items:center;
          gap:5px;
          color:${card.runTone.text};
          font-size:11px;
          font-weight:750;
          line-height:17px;
          white-space:nowrap;
          flex:0 0 auto;
        ">
          <i style="display:block;width:7px;height:7px;border-radius:999px;background:${card.runTone.text};box-shadow:0 0 0 2px #FFFFFF;"></i>
          ${escapeHtml(card.statusLabel)}
        </span>
      </div>
      <div style="
        font-size:15px;
        font-weight:800;
        line-height:24px;
        white-space:nowrap;
        overflow:hidden;
        text-overflow:ellipsis;
      " title="${escapeHtml(card.title)}">${escapeHtml(card.title)}</div>
      <div style="
        display:grid;
        grid-template-columns:auto minmax(0,1fr);
        column-gap:8px;
        align-items:center;
        min-width:0;
        border:1px solid #E2E8F0;
        background:#F8FAFC;
        border-radius:7px;
        padding:0 8px;
        box-sizing:border-box;
        overflow:hidden;
      ">
        <span style="
          color:#64748B;
          font-size:10px;
          font-weight:800;
          line-height:18px;
          white-space:nowrap;
          flex:0 0 auto;
        ">${escapeHtml(card.tableRole)}</span>
        <span style="
          color:#334155;
          font-size:11px;
          font-weight:700;
          line-height:18px;
          white-space:nowrap;
          overflow:hidden;
          text-overflow:ellipsis;
          min-width:0;
        " title="${escapeHtml(card.table)}">${escapeHtml(card.table)}</span>
      </div>
      <div style="
        display:flex;
        align-items:center;
        justify-content:space-between;
        min-width:0;
        border-top:1px solid #E2E8F0;
        padding-top:3px;
        box-sizing:border-box;
        color:#64748B;
        font-size:10px;
        font-weight:750;
        line-height:13px;
      ">
        <span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0;">${escapeHtml(card.flow)}</span>
        <span style="color:${card.engineColor};font-weight:850;margin-left:10px;white-space:nowrap;flex:0 0 auto;">${escapeHtml(card.engine)}</span>
      </div>
    </div>
  `;
}

Shape.HTML.register({
  shape: TASK_CARD_SHAPE,
  html: (cell) => {
    const card = cell.getData()?.card as TaskCardData | undefined;
    return card ? taskCardHtml(card) : '';
  },
});

function topologicalLevels(
  tasks: PipelineTask[],
  pipelineEdges: PipelineTaskEdge[],
): Map<string, number> {
  const taskKeys = new Set(tasks.map((t) => t.taskKey));
  const indegree = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  const levels = new Map<string, number>();

  tasks.forEach((task) => {
    indegree.set(task.taskKey, 0);
    outgoing.set(task.taskKey, []);
    levels.set(task.taskKey, 0);
  });

  pipelineEdges.forEach((edge) => {
    if (!taskKeys.has(edge.sourceKey) || !taskKeys.has(edge.targetKey)) return;
    outgoing.get(edge.sourceKey)?.push(edge.targetKey);
    indegree.set(edge.targetKey, (indegree.get(edge.targetKey) ?? 0) + 1);
  });

  const queue = tasks
    .filter((task) => (indegree.get(task.taskKey) ?? 0) === 0)
    .map((task) => task.taskKey);
  const visited = new Set<string>();

  while (queue.length > 0) {
    const current = queue.shift()!;
    visited.add(current);
    const currentLevel = levels.get(current) ?? 0;
    (outgoing.get(current) ?? []).forEach((target) => {
      levels.set(target, Math.max(levels.get(target) ?? 0, currentLevel + 1));
      indegree.set(target, (indegree.get(target) ?? 0) - 1);
      if ((indegree.get(target) ?? 0) === 0) queue.push(target);
    });
  }

  tasks.forEach((task, index) => {
    if (!visited.has(task.taskKey)) {
      levels.set(task.taskKey, Math.max(levels.get(task.taskKey) ?? 0, index));
    }
  });

  return levels;
}

function buildCanvasData(
  tasks: PipelineTask[],
  edges: PipelineTaskEdge[],
  taskRunByKey?: Map<string, RunState>,
): { nodes: CanvasNode[]; edges: CanvasEdge[] } {
  const taskKeys = new Set(tasks.map((task) => task.taskKey));
  const pipelineEdges = edges.filter(
    (edge) => edge.edgeLayer === 'PIPELINE'
      && taskKeys.has(edge.sourceKey)
      && taskKeys.has(edge.targetKey),
  );
  const levels = topologicalLevels(tasks, pipelineEdges);

  const incomingEdges = new Map<string, PipelineTaskEdge[]>();
  const outgoingEdges = new Map<string, PipelineTaskEdge[]>();
  tasks.forEach((task) => {
    incomingEdges.set(task.taskKey, []);
    outgoingEdges.set(task.taskKey, []);
  });
  pipelineEdges.forEach((edge) => {
    incomingEdges.get(edge.targetKey)?.push(edge);
    outgoingEdges.get(edge.sourceKey)?.push(edge);
  });

  const byLevel = new Map<number, PipelineTask[]>();
  tasks.forEach((task) => {
    const level = levels.get(task.taskKey) ?? 0;
    if (!byLevel.has(level)) byLevel.set(level, []);
    byLevel.get(level)!.push(task);
  });

  const sortedLevels = [...byLevel.keys()].sort((a, b) => a - b);
  const nodes: CanvasNode[] = [];
  sortedLevels.forEach((level) => {
    const levelTasks = byLevel.get(level) ?? [];
    levelTasks.forEach((task, rowIndex) => {
      const inEdges = incomingEdges.get(task.taskKey) ?? [];
      const outEdges = outgoingEdges.get(task.taskKey) ?? [];
      const inputPorts = [...new Set(inEdges.map((edge) => portId(edge.targetPort || edge.targetInput || edge.joinRole, 'in')))];
      const outputPorts = [...new Set(outEdges.map((edge) => portId(edge.sourcePort || edge.sourceOutput, 'out')))];

      nodes.push({
        id: task.taskKey,
        task,
        x: typeof task.positionX === 'number' ? task.positionX : ORIGIN_X + (level + 1) * COLUMN_GAP,
        y: typeof task.positionY === 'number' ? task.positionY : ORIGIN_Y + rowIndex * (NODE_HEIGHT + ROW_GAP),
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
        inputPorts,
        outputPorts,
        incoming: inEdges.length,
        outgoing: outEdges.length,
        runState: taskRunByKey?.get(task.taskKey),
      });
    });
  });

  const sourceNodes = nodes.filter((node) => node.incoming === 0);
  const sinkNodes = nodes.filter((node) => node.outgoing === 0);
  const avgY = (list: CanvasNode[]) => {
    if (list.length === 0) return ORIGIN_Y + 24;
    return list.reduce((sum, node) => sum + node.y + node.height / 2, 0) / list.length;
  };
  const maxLevel = sortedLevels.length ? Math.max(...sortedLevels) : 0;
  const startY = avgY(sourceNodes) - VIRTUAL_HEIGHT / 2;
  const endY = avgY(sinkNodes) - VIRTUAL_HEIGHT / 2;
  const startX = sourceNodes.length > 0
    ? Math.max(24, Math.min(...sourceNodes.map((node) => node.x)) - VIRTUAL_WIDTH - 72)
    : ORIGIN_X;
  const endX = sinkNodes.length > 0
    ? Math.max(...sinkNodes.map((node) => node.x + node.width)) + 72
    : ORIGIN_X + (maxLevel + 2) * COLUMN_GAP;

  if (tasks.length > 0) {
    nodes.push({
      id: START_ID,
      virtual: 'START',
      x: startX,
      y: startY,
      width: VIRTUAL_WIDTH,
      height: VIRTUAL_HEIGHT,
      inputPorts: [],
      outputPorts: ['out'],
      incoming: 0,
      outgoing: sourceNodes.length,
    });
    nodes.push({
      id: END_ID,
      virtual: 'END',
      x: endX,
      y: endY,
      width: VIRTUAL_WIDTH,
      height: VIRTUAL_HEIGHT,
      inputPorts: ['in'],
      outputPorts: [],
      incoming: sinkNodes.length,
      outgoing: 0,
    });
  }

  const canvasEdges: CanvasEdge[] = pipelineEdges.map((edge) => ({
    id: edge.id,
    source: edge.sourceKey,
    target: edge.targetKey,
    sourcePort: portId(edge.sourcePort || edge.sourceOutput, 'out'),
    targetPort: portId(edge.targetPort || edge.targetInput || edge.joinRole, 'in'),
    label: edgeLabel(edge),
  }));

  sourceNodes.forEach((node) => {
    canvasEdges.push({
      id: `${START_ID}:${node.id}`,
      source: START_ID,
      target: node.id,
      sourcePort: 'out',
      virtual: true,
    });
  });
  sinkNodes.forEach((node) => {
    canvasEdges.push({
      id: `${node.id}:${END_ID}`,
      source: node.id,
      target: END_ID,
      targetPort: 'in',
      virtual: true,
    });
  });

  return { nodes, edges: canvasEdges };
}

function nodePorts(node: CanvasNode) {
  return {
    groups: {
      in: {
        position: { name: 'left' },
        attrs: {
          circle: {
            r: 4,
            magnet: false,
            stroke: '#94A3B8',
            strokeWidth: 2,
            fill: '#F8FAFC',
          },
        },
      },
      out: {
        position: { name: 'right' },
        attrs: {
          circle: {
            r: 4,
            magnet: false,
            stroke: '#2563EB',
            strokeWidth: 2,
            fill: '#DBEAFE',
          },
        },
      },
    },
    items: [
      ...node.inputPorts.map((id) => ({ id, group: 'in' })),
      ...node.outputPorts.map((id) => ({ id, group: 'out' })),
    ],
  };
}

function addVirtualNode(graph: Graph, node: CanvasNode) {
  const isStart = node.virtual === 'START';
  graph.addNode({
    id: node.id,
    shape: 'rect',
    x: node.x,
    y: node.y,
    width: node.width,
    height: node.height,
    zIndex: 2,
    data: { virtual: node.virtual },
    ports: nodePorts(node),
    attrs: {
      body: {
        rx: 8,
        ry: 8,
        fill: isStart ? '#F0F9FF' : '#F0FDF4',
        stroke: isStart ? '#7DD3FC' : '#86EFAC',
        strokeWidth: 1.5,
      },
      label: {
        text: isStart ? '起点\n数据源入口' : '终点\nDWD 输出',
        fontSize: 13,
        fontWeight: 700,
        fill: isStart ? '#0369A1' : '#166534',
        lineHeight: 20,
      },
    },
  });
}

function addTaskNode(graph: Graph, node: CanvasNode, selectedKey?: string) {
  if (!node.task) return;
  const task = node.task;
  const kind = nodeKind(task);
  const tone = nodeTone(task);
  const typeDisplay = nodeTypeDisplay(task.taskType, kind);
  const selected = task.taskKey === selectedKey;
  const status = statusText(task, node.runState);
  const runDisplay = statusDisplay(status);
  const runTone = runDisplay.tone;
  const hasError = Boolean(task.compileError || node.runState?.errorMsg || status === 'FAILED' || status === '校验失败');
  const title = truncate(task.name || task.taskKey, 22);
  const table = taskDetail(task);
  const engine = engineLabel(task);
  const engineColor = engine === 'PYSPARK' ? '#0E7490'
    : engine === 'SYNC' || engine === 'TRINO' ? '#0369A1'
    : engine === 'CONTROL' ? '#7C3AED'
    : engine === 'OBSERVE' ? '#0E7490'
    : engine === 'SANDBOX' ? '#A16207'
    : '#C2410C';
  const card: TaskCardData = {
    typeCode: typeDisplay.code,
    typeLabel: typeDisplay.label,
    statusLabel: runDisplay.label,
    title,
    tableRole: tableRole(task, kind),
    table,
    flow: flowMetric(node),
    engine,
    selected,
    hasError,
    tone,
    runTone,
    engineColor,
  };

  graph.addNode({
    id: task.taskKey,
    shape: TASK_CARD_SHAPE,
    x: node.x,
    y: node.y,
    width: node.width,
    height: node.height,
    zIndex: 2,
    data: {
      taskKey: task.taskKey,
      errorMsg: node.runState?.errorMsg || task.compileError,
      card,
    },
    ports: nodePorts(node),
  });
}

function addCanvasEdge(graph: Graph, edge: CanvasEdge) {
  const lineColor = edge.virtual ? '#CBD5E1' : '#64748B';
  const attrs: Record<string, unknown> = {
    line: {
      stroke: lineColor,
      strokeWidth: edge.virtual ? 1.4 : 2,
      strokeDasharray: edge.virtual ? '6 5' : undefined,
      targetMarker: {
        name: 'block',
        width: 9,
        height: 6,
      },
    },
  };

  graph.addEdge({
    id: edge.id,
    zIndex: edge.virtual ? 0 : 1,
    source: edge.sourcePort
      ? { cell: edge.source, port: edge.sourcePort }
      : { cell: edge.source },
    target: edge.targetPort
      ? { cell: edge.target, port: edge.targetPort }
      : { cell: edge.target },
    router: { name: 'manhattan', args: { padding: 18 } },
    connector: { name: 'rounded', args: { radius: 8 } },
    attrs,
    labels: edge.label
      ? [{
        position: { distance: 0.5, offset: -12 },
        attrs: {
          label: {
            text: edge.label,
            fontSize: 11,
            fill: '#475569',
            fontWeight: edge.virtual ? 500 : 600,
          },
          body: {
            ref: 'label',
            refX: -6,
            refY: -4,
            refWidth: '100%',
            refHeight: '100%',
            refWidth2: 12,
            refHeight2: 8,
            rx: 4,
            ry: 4,
            fill: '#FFFFFF',
            stroke: edge.virtual ? '#CBD5E1' : '#BFDBFE',
            strokeWidth: 1,
          },
        },
      }]
      : [],
  });
}

function fitGraphContent(graph: Graph, padding = 48) {
  graph.zoomToFit({ padding, maxScale: 1 });
  graph.centerContent();
}

export function DagCanvasSimple({
  tasks,
  edges,
  selectedKey,
  onSelect,
  taskRunByKey,
  onDropTask,
  onMoveTask,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const shellRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const onSelectRef = useRef(onSelect);
  const onMoveTaskRef = useRef(onMoveTask);
  const onDropTaskRef = useRef(onDropTask);
  const paletteDragRef = useRef<TaskTypeMeta | null>(null);
  const lastDropAtRef = useRef(0);
  const fitSignatureRef = useRef<string>('');

  useEffect(() => {
    onSelectRef.current = onSelect;
  }, [onSelect]);

  useEffect(() => {
    onMoveTaskRef.current = onMoveTask;
  }, [onMoveTask]);

  useEffect(() => {
    onDropTaskRef.current = onDropTask;
  }, [onDropTask]);

  const openCreateAtClientPoint = (meta: TaskTypeMeta, clientX: number, clientY: number) => {
    const graph = graphRef.current;
    const shell = shellRef.current;
    if (!shell || !onDropTaskRef.current) return;
    const rect = shell.getBoundingClientRect();
    if (
      clientX < rect.left
      || clientX > rect.right
      || clientY < rect.top
      || clientY > rect.bottom
    ) {
      return;
    }
    const point = graph
      ? graph.clientToLocal(clientX, clientY)
      : {
        x: clientX - rect.left,
        y: clientY - rect.top,
      };
    lastDropAtRef.current = Date.now();
    onDropTaskRef.current(meta, {
      x: Math.max(24, Math.round(point.x - NODE_WIDTH / 2)),
      y: Math.max(24, Math.round(point.y - NODE_HEIGHT / 2)),
    });
  };

  useEffect(() => {
    const handlePaletteDragStart = (event: Event) => {
      const detail = (event as CustomEvent<{ meta?: TaskTypeMeta }>).detail;
      paletteDragRef.current = detail?.meta ?? null;
    };
    const handlePointerEnd = (event: PointerEvent | globalThis.DragEvent) => {
      const meta = paletteDragRef.current;
      paletteDragRef.current = null;
      if (!meta || Date.now() - lastDropAtRef.current < 300) return;
      openCreateAtClientPoint(meta, event.clientX, event.clientY);
    };
    window.addEventListener('onelake:pipeline-palette-drag-start', handlePaletteDragStart);
    window.addEventListener('pointerup', handlePointerEnd);
    window.addEventListener('dragend', handlePointerEnd);
    return () => {
      window.removeEventListener('onelake:pipeline-palette-drag-start', handlePaletteDragStart);
      window.removeEventListener('pointerup', handlePointerEnd);
      window.removeEventListener('dragend', handlePointerEnd);
    };
  }, []);

  const canvasData = useMemo(
    () => buildCanvasData(tasks, edges, taskRunByKey),
    [tasks, edges, taskRunByKey],
  );

  useEffect(() => {
    if (!containerRef.current || graphRef.current) return;

    const graph = new Graph({
      container: containerRef.current,
      autoResize: true,
      background: { color: '#F8FAFC' },
      grid: {
        visible: true,
        type: 'dot',
        args: { color: '#D7DEE9', thickness: 1 },
      },
      panning: { enabled: true },
      mousewheel: {
        enabled: true,
        modifiers: ['ctrl', 'meta'],
        minScale: 0.35,
        maxScale: 1.8,
      },
      interacting: {
        nodeMovable: true,
        edgeMovable: false,
        edgeLabelMovable: false,
        arrowheadMovable: false,
      },
      connecting: { allowBlank: false },
    });
    graphRef.current = graph;

    graph.on('node:click', ({ node }) => {
      const taskKey = node.getData()?.taskKey as string | undefined;
      onSelectRef.current(taskKey);
    });
    graph.on('edge:click', ({ edge }) => {
      const target = edge.getTargetCellId();
      const targetData = target ? graph.getCellById(target)?.getData() : undefined;
      onSelectRef.current(targetData?.taskKey);
    });
    graph.on('blank:click', () => onSelectRef.current(undefined));
    graph.on('node:moved', ({ node }) => {
      const taskKey = node.getData()?.taskKey as string | undefined;
      if (!taskKey) return;
      const position = node.position();
      onMoveTaskRef.current?.(taskKey, {
        x: position.x,
        y: position.y,
      });
    });

    const resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(() => {
        if (graphRef.current && graphRef.current.getCells().length > 0) {
          fitGraphContent(graphRef.current, 36);
        }
      });
    });
    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      graph.dispose();
      graphRef.current = null;
    };
  }, []);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;

    graph.clearCells();
    canvasData.nodes.forEach((node) => {
      if (node.virtual) addVirtualNode(graph, node);
      else addTaskNode(graph, node, selectedKey);
    });
    canvasData.edges.forEach((edge) => addCanvasEdge(graph, edge));

    const fitSignature = [
      canvasData.nodes.map((node) => node.id).join('|'),
      canvasData.edges.map((edge) => edge.id).join('|'),
    ].join('::');
    requestAnimationFrame(() => {
      if (graphRef.current && fitSignatureRef.current !== fitSignature) {
        fitGraphContent(graph, 48);
        fitSignatureRef.current = fitSignature;
      }
    });
  }, [canvasData, selectedKey]);

  const handleDragOver = (event: ReactDragEvent<HTMLDivElement>) => {
    if (!Array.from(event.dataTransfer.types).includes('application/x-onelake-task')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  };

  const handleDrop = (event: ReactDragEvent<HTMLDivElement>) => {
    const raw = event.dataTransfer.getData('application/x-onelake-task');
    if (!raw || !onDropTask) return;
    event.preventDefault();
    try {
      const meta = JSON.parse(raw) as TaskTypeMeta;
      paletteDragRef.current = null;
      openCreateAtClientPoint(meta, event.clientX, event.clientY);
    } catch {
      // Ignore malformed drag payloads from outside the task palette.
    }
  };

  if (tasks.length === 0) {
    return (
      <div
        ref={shellRef}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        style={{
          height: '100%',
          width: '100%',
          minWidth: 0,
          overflow: 'hidden',
          contain: 'layout paint',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--ol-ink-3)',
        }}
      >
        <StateView state="empty" title="流水线还是空的" description="从左侧拖入任务，或点击任务面板添加。" />
      </div>
    );
  }

  return (
    <div
      ref={shellRef}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
      style={{
        height: '100%',
        width: '100%',
        minWidth: 0,
        overflow: 'hidden',
        contain: 'layout paint',
        position: 'relative',
        background: '#F8FAFC',
      }}
    >
      <div ref={containerRef} style={{ height: '100%', width: '100%', overflow: 'hidden' }} />
      <div
        style={{
          position: 'absolute',
          top: 12,
          left: 16,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 8px',
          border: '1px solid #E2E8F0',
          borderRadius: 8,
          background: 'rgba(255, 255, 255, 0.92)',
          boxShadow: '0 8px 20px rgba(15, 23, 42, 0.08)',
          pointerEvents: 'auto',
        }}
      >
        <Tag color="blue" style={{ margin: 0 }}>
          {tasks.length} 节点
        </Tag>
        <Tag color="geekblue" style={{ margin: 0 }}>
          {edges.filter((edge) => edge.edgeLayer === 'PIPELINE').length} 条边
        </Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Spark 数据流
        </Text>
      </div>
      <Space
        size={6}
        style={{
          position: 'absolute',
          top: 12,
          right: 16,
          padding: 6,
          border: '1px solid #E2E8F0',
          borderRadius: 8,
          background: 'rgba(255, 255, 255, 0.92)',
          boxShadow: '0 8px 20px rgba(15, 23, 42, 0.08)',
        }}
      >
        <Tooltip title="放大">
          <Button
            size="small"
            icon={<PlusOutlined />}
            onClick={() => graphRef.current?.zoom(0.12)}
          />
        </Tooltip>
        <Tooltip title="缩小">
          <Button
            size="small"
            icon={<MinusOutlined />}
            onClick={() => graphRef.current?.zoom(-0.12)}
          />
        </Tooltip>
        <Tooltip title="适配画布">
          <Button
            size="small"
            icon={<AimOutlined />}
            onClick={() => {
              graphRef.current?.zoomToFit({ padding: 48, maxScale: 1 });
              graphRef.current?.centerContent();
            }}
          />
        </Tooltip>
        <Tooltip title="实际大小">
          <Button
            size="small"
            icon={<CompressOutlined />}
            onClick={() => {
              graphRef.current?.zoomTo(1);
              graphRef.current?.centerContent();
            }}
          />
        </Tooltip>
      </Space>
    </div>
  );
}
