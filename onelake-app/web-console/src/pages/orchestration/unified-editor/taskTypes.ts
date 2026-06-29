/**
 * Task type metadata — drives the Task Palette layout (§4.2).
 * Group order matches the design doc's ASCII mockup.
 */
import type { PipelineTaskType } from '../../../types';

export interface TaskTypeMeta {
  type: PipelineTaskType;
  name: string;
  icon: string;
  description: string;
  engine: 'SPARK_SQL' | 'PYSPARK';
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
    group: 'sync',
    label: '取数',
    items: [
      {
        type: 'SYNC_REF',
        name: '同步引用',
        icon: '🔄',
        description: '引用采集任务，建立运行先后依赖；ODS 表加载后自动触发下游',
        engine: 'SPARK_SQL',
        requiresModel: false,
      },
    ],
  },
  {
    group: 'develop',
    label: '开发',
    items: [
      {
        type: 'SPARK_SQL',
        name: 'Spark SQL',
        icon: '⚡',
        description: 'Spark SQL 任务（共享 Iceberg/Hive Metastore/MinIO）',
        engine: 'SPARK_SQL',
        requiresModel: false,
      },
      {
        type: 'PYSPARK',
        name: 'PySpark',
        icon: '🐍',
        description: 'PySpark 脚本任务（共享 Iceberg/Hive Metastore/MinIO）',
        engine: 'PYSPARK',
        requiresModel: false,
      },
    ],
  },
  {
    group: 'compute',
    label: '关联计算',
    items: [
      {
        type: 'SPARK_SQL',
        name: '关联 Join',
        icon: '🔗',
        description: '两个上游输入按条件关联，编译为 Spark SQL 并产出新表',
        engine: 'SPARK_SQL',
        requiresModel: false,
        preset: 'SPARK_JOIN',
      },
      {
        type: 'SPARK_SQL',
        name: '派生字段',
        icon: '✚',
        description: '基于单个上游输入新增或重算字段，例如生成用户 UUID',
        engine: 'SPARK_SQL',
        requiresModel: false,
        preset: 'SPARK_DERIVE_COLUMN',
      },
      {
        type: 'SPARK_SQL',
        name: '落 DWD 表',
        icon: '📥',
        description: '将上游计算结果写入指定 DWD 表，作为流水线出口',
        engine: 'SPARK_SQL',
        requiresModel: false,
        preset: 'SPARK_SINK',
      },
    ],
  },
  {
    group: 'governance',
    label: '治理',
    items: [
      {
        type: 'SPARK_SQL',
        name: '字段治理',
        icon: '🛡️',
        description: '对上游字段执行脱敏、标准化、去空格和字段重算，编译为 Spark SQL',
        engine: 'SPARK_SQL',
        requiresModel: false,
        preset: 'SPARK_DERIVE_COLUMN',
      },
    ],
  },
  {
    group: 'quality',
    label: '质量门禁',
    items: [
      {
        type: 'QUALITY_GATE',
        name: '质量门禁',
        icon: '✅',
        description: '对 Spark 产出表执行主键、枚举、范围和自定义 SQL 校验',
        engine: 'SPARK_SQL',
        requiresModel: false,
      },
    ],
  },
];

export const ALL_TASK_TYPES = TASK_TYPE_GROUPS.flatMap((g) => g.items);

export function findTaskTypeMeta(type: PipelineTaskType): TaskTypeMeta | undefined {
  return ALL_TASK_TYPES.find((t) => t.type === type);
}
