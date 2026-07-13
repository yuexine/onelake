/**
 * Task type metadata — drives the Task Palette layout (§4.2).
 * Group order matches the design doc's ASCII mockup.
 */
import type { PipelineTaskType } from '../../../types';

export interface TaskTypeMeta {
  type: PipelineTaskType;
  category: 'EXEC' | 'CONTROL' | 'OBSERVE';
  name: string;
  icon: string;
  description: string;
  engine: 'SPARK_SQL' | 'PYSPARK' | 'TRINO' | 'SCRIPT' | 'CONTROL' | 'OBSERVE';
  defaultConfig: Record<string, unknown>;
  acceptsTargetFqn?: boolean;
  /** Model-backed task types are not exposed for new Spark pipelines. */
  requiresModel: boolean;
  /** True if this task is contract-only. */
  contractOnly?: boolean;
  /** Optional create preset that writes structured config for the same task type. */
  preset?: 'SPARK_JOIN' | 'SPARK_DERIVE_COLUMN' | 'SPARK_SINK';
}

export const TASK_TYPE_GROUPS: Array<{
  group: string;
  label: string;
  items: TaskTypeMeta[];
}> = [
  {
    group: 'exec',
    label: '执行',
    items: [
      {
        type: 'SPARK_SQL',
        category: 'EXEC',
        name: 'Spark SQL',
        icon: '⚡',
        description: 'Spark SQL 任务（共享 Iceberg/Hive Metastore/MinIO）',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
      },
      {
        type: 'PYSPARK',
        category: 'EXEC',
        name: 'PySpark',
        icon: '🐍',
        description: 'PySpark 脚本任务（共享 Iceberg/Hive Metastore/MinIO）',
        engine: 'PYSPARK',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
      },
      {
        type: 'TRINO_SQL',
        category: 'EXEC',
        name: 'Trino SQL',
        icon: '🔷',
        description: '通过受限 Trino 会话执行轻量查询或 Iceberg CTAS',
        engine: 'TRINO',
        defaultConfig: { sql: 'SELECT 1', catalog: 'iceberg', schema: 'default' },
        requiresModel: false,
        acceptsTargetFqn: true,
      },
      {
        type: 'PYTHON',
        category: 'EXEC',
        name: 'Python',
        icon: '🐍',
        description: '在默认禁网的受限沙箱中执行 Python 脚本',
        engine: 'SCRIPT',
        defaultConfig: {
          script: '# 在受限沙箱中执行\nprint("hello from OneLake")',
          timeout_seconds: 60,
          cpu_seconds: 30,
          cpu_cores: 1,
          memory_mb: 256,
        },
        requiresModel: false,
      },
      {
        type: 'SHELL',
        category: 'EXEC',
        name: 'Shell',
        icon: '⌨️',
        description: '在默认禁网的受限沙箱中执行 Shell 脚本',
        engine: 'SCRIPT',
        defaultConfig: {
          script: 'set -eu\necho "hello from OneLake"',
          timeout_seconds: 60,
          cpu_seconds: 30,
          cpu_cores: 1,
          memory_mb: 256,
        },
        requiresModel: false,
      },
      {
        type: 'SPARK_SQL',
        category: 'EXEC',
        name: '关联 Join',
        icon: '🔗',
        description: '两个上游输入按条件关联，编译为 Spark SQL 并产出新表',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
        preset: 'SPARK_JOIN',
      },
      {
        type: 'SPARK_SQL',
        category: 'EXEC',
        name: '派生字段',
        icon: '✚',
        description: '基于单个上游输入新增或重算字段，例如生成用户 UUID',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
        preset: 'SPARK_DERIVE_COLUMN',
      },
      {
        type: 'SPARK_SQL',
        category: 'EXEC',
        name: '落 DWD 表',
        icon: '📥',
        description: '将上游计算结果写入指定 DWD 表，作为流水线出口',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
        preset: 'SPARK_SINK',
      },
      {
        type: 'SPARK_SQL',
        category: 'EXEC',
        name: '字段治理',
        icon: '🛡️',
        description: '对上游字段执行脱敏、标准化、去空格和字段重算，编译为 Spark SQL',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
        acceptsTargetFqn: true,
        preset: 'SPARK_DERIVE_COLUMN',
      },
      {
        type: 'QUALITY_GATE',
        category: 'EXEC',
        name: '质量门禁',
        icon: '✅',
        description: '对 Spark 产出表执行主键、枚举、范围和自定义 SQL 校验',
        engine: 'SPARK_SQL',
        defaultConfig: {},
        requiresModel: false,
      },
    ],
  },
  {
    group: 'control',
    label: '控制',
    items: [
      {
        type: 'BRANCH', category: 'CONTROL', name: '多路分支', icon: '⑂',
        description: '按受控表达式的结果选择一组下游节点，其余分支置为已跳过',
        engine: 'CONTROL', defaultConfig: { expression: '"default"', branches: {} }, requiresModel: false,
      },
      {
        type: 'CONDITION', category: 'CONTROL', name: '条件判断', icon: '◇',
        description: '按受控布尔表达式决定下游是否继续执行',
        engine: 'CONTROL', defaultConfig: { expression: 'true' }, requiresModel: false,
      },
      {
        type: 'SUB_PIPELINE', category: 'CONTROL', name: '子流水线', icon: '↳',
        description: '触发同租户内另一条已发布流水线，可选择等待完成',
        engine: 'CONTROL', defaultConfig: { subDagId: '', waitForCompletion: true, timeoutSeconds: 3600, pollIntervalSeconds: 5 }, requiresModel: false,
      },
    ],
  },
  {
    group: 'observe',
    label: '观测',
    items: [
      {
        type: 'SYNC_REF', category: 'OBSERVE', name: '同步引用', icon: '🔄',
        description: '引用采集任务，建立运行先后依赖；ODS 表加载后自动触发下游',
        engine: 'OBSERVE', defaultConfig: {}, requiresModel: false, acceptsTargetFqn: true,
      },
      {
        type: 'SENSOR', category: 'OBSERVE', name: '资产 Sensor', icon: '📡',
        description: '轮询资产或分区就绪状态，超时后失败或有意跳过',
        engine: 'OBSERVE', defaultConfig: { assetFqn: '', partition: '', timeoutSeconds: 300, pollIntervalSeconds: 5, onTimeout: 'FAILED' }, requiresModel: false,
      },
      {
        type: 'WAIT', category: 'OBSERVE', name: '等待', icon: '⏱️',
        description: '等待固定时长或等待到 logical_date 偏移时刻',
        engine: 'OBSERVE', defaultConfig: { durationSeconds: 60 }, requiresModel: false,
      },
      {
        type: 'NOTIFY', category: 'OBSERVE', name: '运行通知', icon: '🔔',
        description: '向通知中心发送支持参数渲染的运行消息',
        engine: 'OBSERVE', defaultConfig: { title: '流水线运行通知', message: '流水线 ${run_id} 已执行到通知节点', level: 'INFO', link: '' }, requiresModel: false,
      },
      {
        type: 'ASSERTION', category: 'OBSERVE', name: '轻量断言', icon: '✓',
        description: '执行受控布尔表达式，断言为假时使节点失败',
        engine: 'OBSERVE', defaultConfig: { expression: '1 > 0' }, requiresModel: false,
      },
    ],
  },
];

export const ALL_TASK_TYPES = TASK_TYPE_GROUPS.flatMap((g) => g.items);

export function findTaskTypeMeta(type: PipelineTaskType): TaskTypeMeta | undefined {
  return ALL_TASK_TYPES.find((t) => t.type === type);
}
