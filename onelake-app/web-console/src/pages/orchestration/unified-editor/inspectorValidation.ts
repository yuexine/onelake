import type { PipelineTask, PipelineTaskEdge } from '../../../types';

export type InspectorValidationErrors = Record<string, string>;

export function validateInspectorTask(
  task: PipelineTask,
  edges: PipelineTaskEdge[],
): InspectorValidationErrors {
  const errors: InspectorValidationErrors = {};
  const config = task.config ?? {};

  switch (task.taskType) {
  case 'TRINO_SQL': {
    const sql = text(config.sql);
    if (!sql.trim()) errors.sql = '请输入要执行的 SQL。';
    if (!text(config.catalog).trim()) errors.catalog = '请选择 Catalog。';
    if (!text(config.schema).trim()) errors.schema = '请选择 Schema。';
    if (isTrinoCtas(sql) && !task.targetFqn?.trim()) {
      errors.targetFqn = 'CTAS SQL 必须填写与建表目标一致的目标表 FQN。';
    }
    break;
  }
  case 'PYTHON':
  case 'SHELL': {
    if (!text(config.script).trim()) errors.script = '请输入要执行的脚本。';
    const timeout = config.timeout_seconds ?? 60;
    const cpuSeconds = config.cpu_seconds ?? 30;
    validateInteger(errors, 'timeout_seconds', timeout, 1, 900, '超时');
    validateInteger(errors, 'cpu_seconds', cpuSeconds, 1, 900, 'CPU 时间');
    validateInteger(errors, 'cpu_cores', config.cpu_cores ?? 1, 1, 4, 'CPU 核数');
    validateInteger(errors, 'memory_mb', config.memory_mb ?? 256, 32, 2048, '内存');
    if (isNumber(cpuSeconds) && isNumber(timeout) && cpuSeconds > timeout) {
      errors.cpu_seconds = 'CPU 时间不能超过任务超时。';
    }
    break;
  }
  case 'CONDITION':
    if (!text(config.expression).trim()) errors.expression = '请输入条件表达式。';
    break;
  case 'BRANCH':
    if (!text(config.expression).trim()) errors.expression = '请输入分支表达式。';
    validateBranchMappings(errors, task, edges);
    break;
  case 'SENSOR': {
    if (!text(config.assetFqn).trim()) errors.assetFqn = '请输入要等待的资产 FQN。';
    validateInteger(errors, 'timeoutSeconds', config.timeoutSeconds, 1, 86400, '超时');
    validateInteger(errors, 'pollIntervalSeconds', config.pollIntervalSeconds, 1, 300, '轮询间隔');
    if (isNumber(config.pollIntervalSeconds) && isNumber(config.timeoutSeconds)
      && config.pollIntervalSeconds > config.timeoutSeconds) {
      errors.pollIntervalSeconds = '轮询间隔不能超过超时。';
    }
    if (!['FAILED', 'SKIPPED'].includes(text(config.onTimeout))) {
      errors.onTimeout = '请选择超时后的节点状态。';
    }
    break;
  }
  case 'WAIT': {
    const hasOffset = config.offsetSeconds !== undefined;
    const hasDuration = config.durationSeconds !== undefined;
    if (hasOffset === hasDuration) {
      errors.waitSeconds = '固定时长和 logical_date 偏移必须且只能选择一种。';
    } else if (hasOffset) {
      validateInteger(errors, 'waitSeconds', config.offsetSeconds, 0, 86400, '偏移秒数');
    } else {
      validateInteger(errors, 'waitSeconds', config.durationSeconds, 1, 86400, '等待秒数');
    }
    break;
  }
  case 'SUB_PIPELINE':
    if (!UUID_PATTERN.test(text(config.subDagId))) errors.subDagId = '请选择一条已发布的目标流水线。';
    if (config.waitForCompletion !== false) {
      validateInteger(errors, 'timeoutSeconds', config.timeoutSeconds ?? 3600, 1, 86400, '超时');
      validateInteger(errors, 'pollIntervalSeconds', config.pollIntervalSeconds ?? 5, 1, 300, '轮询间隔');
    }
    break;
  case 'NOTIFY':
    if (!text(config.title).trim()) errors.title = '请输入通知标题。';
    if (!text(config.message).trim()) errors.message = '请输入通知内容。';
    break;
  case 'ASSERTION':
    if (!text(config.expression).trim()) errors.expression = '请输入断言表达式。';
    break;
  default:
    break;
  }

  return errors;
}

function validateBranchMappings(
  errors: InspectorValidationErrors,
  task: PipelineTask,
  edges: PipelineTaskEdge[],
) {
  const directDownstream = new Set(edges
    .filter((edge) => edge.edgeLayer === 'PIPELINE' && edge.sourceKey === task.taskKey)
    .map((edge) => edge.targetKey));
  if (directDownstream.size === 0) {
    errors.branches = '请先为分支节点添加至少一条直接下游连线。';
    return;
  }

  if (!isRecord(task.config.branches) || Object.keys(task.config.branches).length === 0) {
    errors.branches = '请至少添加一条分支结果映射。';
    return;
  }

  const mapped = new Set<string>();
  for (const [result, rawTargets] of Object.entries(task.config.branches)) {
    const targets = Array.isArray(rawTargets) ? rawTargets : [rawTargets];
    if (!result.trim() || targets.length === 0 || targets.some((target) => !text(target).trim())) {
      errors.branches = '每条分支都必须填写结果值并选择至少一个下游节点。';
      return;
    }
    for (const target of targets) {
      const taskKey = text(target);
      if (!directDownstream.has(taskKey)) {
        errors.branches = `分支目标 ${taskKey} 不是当前节点的直接下游。`;
        return;
      }
      mapped.add(taskKey);
    }
  }

  const missing = [...directDownstream].filter((taskKey) => !mapped.has(taskKey));
  if (missing.length > 0) errors.branches = `以下直接下游尚未映射：${missing.join('、')}。`;
}

function validateInteger(
  errors: InspectorValidationErrors,
  field: string,
  value: unknown,
  min: number,
  max: number,
  label: string,
) {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    errors[field] = `${label}必须是 ${min}–${max} 之间的整数。`;
  }
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LEADING_SQL_COMMENT = /^\s*(?:--[^\r\n]*(?:\r?\n|$)|\/\*[\s\S]*?\*\/)/;
const TRINO_CTAS_PATTERN = /^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*){0,2}\s+(?:WITH\s*\([\s\S]*?\)\s+)?AS\s+(?:SELECT|WITH)\b[\s\S]*$/i;

function isTrinoCtas(sql: string) {
  let statement = sql.trimStart();
  let leadingComment = statement.match(LEADING_SQL_COMMENT);
  while (leadingComment) {
    statement = statement.slice(leadingComment[0].length).trimStart();
    leadingComment = statement.match(LEADING_SQL_COMMENT);
  }
  return TRINO_CTAS_PATTERN.test(statement);
}
