/**
 * Spark task inspector — focused node configuration for Spark-only pipelines.
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import { Button, Form, Input, InputNumber, Select, Space, Switch, Tabs, Tag, Typography } from 'antd';
import type { CSSProperties, ReactNode } from 'react';
import type { InspectorProps } from '../InspectorRouter';

const { Text } = Typography;

interface SparkConfig {
  script?: string;
  sql?: string;
  from_tables?: string[];
  dataflow?: {
    nodeKind?: string;
    joinType?: string;
    leftAlias?: string;
    rightAlias?: string;
    on?: string;
    select?: string;
    sourceAlias?: string;
    includeSourceColumns?: boolean;
    mode?: string;
    deriveColumns?: DerivedColumn[];
    derivedColumns?: DerivedColumn[];
    columns?: DerivedColumn[];
  };
  dataflow_inputs?: Array<{
    sourceTaskKey?: string;
    assetFqn?: string;
    targetInput?: string;
    alias?: string;
  }>;
  resource_profile?: {
    executor_memory?: string;
    executor_cores?: number;
    num_executors?: number;
    driver_memory?: string;
  };
}

interface DerivedColumn {
  name?: string;
  column?: string;
  target?: string;
  expression?: string;
  expr?: string;
}

function parseConfig(raw: Record<string, unknown> | undefined): SparkConfig {
  if (!raw) return {};
  return {
    script: typeof raw.script === 'string' ? raw.script : undefined,
    sql: typeof raw.sql === 'string' ? raw.sql : undefined,
    from_tables: Array.isArray(raw.from_tables) ? (raw.from_tables as string[]) : [],
    dataflow:
      raw.dataflow && typeof raw.dataflow === 'object'
        ? (raw.dataflow as SparkConfig['dataflow'])
        : undefined,
    dataflow_inputs: Array.isArray(raw.dataflow_inputs)
      ? (raw.dataflow_inputs as SparkConfig['dataflow_inputs'])
      : [],
    resource_profile:
      raw.resource_profile && typeof raw.resource_profile === 'object'
        ? (raw.resource_profile as SparkConfig['resource_profile'])
        : undefined,
  };
}

export function SparkInspector({ task, onChange }: InspectorProps) {
  const isPySpark = task.taskType === 'PYSPARK';
  const language = isPySpark ? 'python' : 'sql';
  const cfg = parseConfig(task.config);
  const script = isPySpark ? cfg.script : cfg.sql;
  const nodeKind = cfg.dataflow?.nodeKind;
  const isJoin = !isPySpark && nodeKind === 'JOIN';
  const isDerive = !isPySpark && nodeKind === 'DERIVE_COLUMN';
  const isSink = !isPySpark && nodeKind === 'SINK';
  const derivedColumns = cfg.dataflow?.deriveColumns ?? cfg.dataflow?.derivedColumns ?? cfg.dataflow?.columns ?? [];

  const patch = (next: Partial<SparkConfig>) => {
    onChange({
      taskType: task.taskType,
      config: { ...task.config, ...next } as Record<string, unknown>,
    });
  };

  const patchResource = (key: keyof NonNullable<SparkConfig['resource_profile']>, value: string | number) => {
    const rp = { ...(cfg.resource_profile ?? {}), [key]: value };
    patch({ resource_profile: rp });
  };

  const patchDataflow = (next: Partial<NonNullable<SparkConfig['dataflow']>>) => {
    patch({ dataflow: { ...(cfg.dataflow ?? { nodeKind: nodeKind ?? 'JOIN' }), ...next } });
  };

  const patchDerivedColumn = (index: number, key: 'name' | 'expression', value: string) => {
    const next = derivedColumns.map((item, i) => {
      if (i !== index) return item;
      return key === 'name'
        ? { ...item, name: value }
        : { ...item, expression: value };
    });
    patchDataflow({ deriveColumns: next });
  };

  const addDerivedColumn = () => {
    patchDataflow({ deriveColumns: [...derivedColumns, { name: '', expression: '' }] });
  };

  const removeDerivedColumn = (index: number) => {
    patchDataflow({ deriveColumns: derivedColumns.filter((_, i) => i !== index) });
  };

  const updateScript = (value: string | undefined) => {
    if (isPySpark) {
      patch({ script: value ?? '' });
    } else {
      patch({ sql: value ?? '' });
    }
  };

  return (
    <Tabs
      defaultActiveKey="basic"
      size="small"
      items={[
        {
          key: 'basic',
          label: '基础配置',
          children: (
            <div style={stackStyle}>
              <Section
                title="输出配置"
                hint="输入表由连线自动推导，通常只需确认产出表"
              >
                <Form layout="vertical" size="small">
                  <div style={{ ...formGridStyle, gridTemplateColumns: 'minmax(0, 1fr)' }}>
                    <Form.Item label="产出表 FQN" style={formItemStyle}>
                      <Input
                        value={task.targetFqn ?? ''}
                        placeholder="iceberg.dwd.order_wide"
                        onChange={(e) => onChange({ taskType: task.taskType, targetFqn: e.target.value })}
                      />
                    </Form.Item>
                  </div>
                </Form>
              </Section>

              {isJoin && renderJoinSection()}
              {isDerive && renderDeriveSection()}
              {isSink && renderSinkSection()}

              <Section
                title={isPySpark ? '脚本预览' : '生成 SQL 预览'}
                hint={isPySpark ? '可在“脚本”页签编辑完整内容' : '只读预览；需要手写 SQL 时进入“SQL 预览”页签'}
              >
                <Editor
                  height={132}
                  defaultLanguage={language}
                  value={script ?? ''}
                  theme="vs"
                  options={{ minimap: { enabled: false }, fontSize: 12, readOnly: true, scrollBeyondLastLine: false }}
                />
              </Section>
            </div>
          ),
        },
        {
          key: 'dataflow',
          label: '数据流',
          children: renderDataflowTab(),
        },
        {
          key: 'script',
          label: isPySpark ? '脚本' : 'SQL 预览',
          children: (
            <Section
              title={isPySpark ? 'PySpark 脚本' : 'Spark SQL'}
              hint="保存配置后，顶部“校验”会重新确认输入、输出和可执行状态"
            >
              <Editor
                height={390}
                defaultLanguage={language}
                value={script ?? ''}
                theme="vs"
                options={{ minimap: { enabled: false }, fontSize: 12, scrollBeyondLastLine: false }}
                onChange={updateScript}
              />
            </Section>
          ),
        },
        {
          key: 'advanced',
          label: '高级设置',
          children: renderAdvancedTab(),
        },
      ]}
    />
  );

  function renderJoinSection() {
    return (
      <Section
        title="关联配置"
        hint="保存后点击校验，系统会根据 left/right 入边重新生成 Spark SQL"
      >
        <Form layout="vertical" size="small">
          <div style={{ ...formGridStyle, gridTemplateColumns: '160px 1fr 1fr' }}>
            <Form.Item label="Join 类型" style={formItemStyle}>
              <Select
                value={cfg.dataflow?.joinType ?? 'LEFT'}
                options={[
                  { label: 'LEFT', value: 'LEFT' },
                  { label: 'INNER', value: 'INNER' },
                  { label: 'RIGHT', value: 'RIGHT' },
                  { label: 'FULL', value: 'FULL' },
                  { label: 'CROSS', value: 'CROSS' },
                ]}
                onChange={(value) => patchDataflow({ joinType: value })}
              />
            </Form.Item>
            <Form.Item label="左输入别名" style={formItemStyle}>
              <Input
                value={cfg.dataflow?.leftAlias ?? 'l'}
                onChange={(e) => patchDataflow({ leftAlias: e.target.value })}
              />
            </Form.Item>
            <Form.Item label="右输入别名" style={formItemStyle}>
              <Input
                value={cfg.dataflow?.rightAlias ?? 'r'}
                onChange={(e) => patchDataflow({ rightAlias: e.target.value })}
              />
            </Form.Item>
          </div>
          <Form.Item label="关联条件" style={formItemStyle}>
            <Input
              value={cfg.dataflow?.on ?? ''}
              placeholder="l.user_id = r.user_id"
              onChange={(e) => patchDataflow({ on: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="输出字段" style={formItemStyle}>
            <Input.TextArea
              rows={3}
              value={cfg.dataflow?.select ?? 'l.*, r.*'}
              placeholder="l.user_id, l.user_name, r.description"
              onChange={(e) => patchDataflow({ select: e.target.value })}
            />
          </Form.Item>
        </Form>
      </Section>
    );
  }

  function renderDeriveSection() {
    return (
      <Section
        title="派生字段"
        hint="从单个上游输入新增或重算字段"
      >
        <Form layout="vertical" size="small">
          <Space size={12} wrap>
            <Form.Item label="源表别名" style={formItemStyle}>
              <Input
                style={{ width: 120 }}
                value={cfg.dataflow?.sourceAlias ?? 'src'}
                onChange={(e) => patchDataflow({ sourceAlias: e.target.value })}
              />
            </Form.Item>
            <Form.Item label="保留源字段" style={formItemStyle}>
              <Switch
                checked={cfg.dataflow?.includeSourceColumns ?? true}
                onChange={(checked) => patchDataflow({ includeSourceColumns: checked })}
              />
            </Form.Item>
          </Space>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {derivedColumns.map((column, index) => (
              <Space key={index} align="start" style={{ width: '100%' }}>
                <Input
                  style={{ width: 180 }}
                  placeholder="字段名"
                  value={column.name ?? column.column ?? column.target ?? ''}
                  onChange={(e) => patchDerivedColumn(index, 'name', e.target.value)}
                />
                <Input
                  style={{ flex: 1, minWidth: 280 }}
                  placeholder="表达式，例如 uuid()"
                  value={column.expression ?? column.expr ?? ''}
                  onChange={(e) => patchDerivedColumn(index, 'expression', e.target.value)}
                />
                <Button
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => removeDerivedColumn(index)}
                  aria-label="删除派生字段"
                />
              </Space>
            ))}
            <Button size="small" icon={<PlusOutlined />} onClick={addDerivedColumn} style={{ width: 'fit-content' }}>
              新增派生字段
            </Button>
          </div>
        </Form>
      </Section>
    );
  }

  function renderSinkSection() {
    return (
      <Section
        title="DWD 输出"
        hint="将上游计算结果写入目标 DWD 表"
      >
        <Form layout="vertical" size="small">
          <Space size={12} wrap>
            <Form.Item label="源表别名" style={formItemStyle}>
              <Input
                style={{ width: 120 }}
                value={cfg.dataflow?.sourceAlias ?? 'src'}
                onChange={(e) => patchDataflow({ sourceAlias: e.target.value })}
              />
            </Form.Item>
            <Form.Item label="写入方式" style={formItemStyle}>
              <Select
                style={{ width: 140 }}
                value={cfg.dataflow?.mode ?? 'OVERWRITE'}
                options={[
                  { label: '覆盖写入', value: 'OVERWRITE' },
                  { label: '追加写入', value: 'APPEND' },
                ]}
                onChange={(value) => patchDataflow({ mode: value })}
              />
            </Form.Item>
          </Space>
          <Form.Item label="输出字段" style={formItemStyle}>
            <Input.TextArea
              rows={3}
              value={cfg.dataflow?.select ?? 'src.*'}
              placeholder="src.*"
              onChange={(e) => patchDataflow({ select: e.target.value })}
            />
          </Form.Item>
        </Form>
      </Section>
    );
  }

  function renderDataflowTab() {
    return (
      <div style={stackStyle}>
        <Section title="连线推导输入" hint="来自边契约，校验时会优先刷新到任务配置">
          {cfg.dataflow_inputs && cfg.dataflow_inputs.length > 0 ? (
            <div style={{ display: 'grid', gap: 10 }}>
              {cfg.dataflow_inputs.map((input, index) => (
                <div key={`${input.sourceTaskKey}-${index}`} style={summaryCardStyle}>
                  <Text strong style={{ display: 'block', fontSize: 13 }}>
                    {input.targetInput ?? 'in'} 输入
                  </Text>
                  <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
                    {input.assetFqn || '未声明资产 FQN'}
                  </Text>
                  {input.alias && <Tag style={{ marginTop: 8 }}>别名 {input.alias}</Tag>}
                </div>
              ))}
            </div>
          ) : (
            <Text type="secondary" style={{ fontSize: 13 }}>
              暂无推导输入。请先在画布上连接上游节点。
            </Text>
          )}
        </Section>
        <Section title="输入表清单" hint="只读展示自动推导结果；需要兼容旧配置时可在高级设置中覆盖">
          {(cfg.from_tables ?? []).length > 0 ? (
            <div style={{ display: 'grid', gap: 8 }}>
              {(cfg.from_tables ?? []).map((table) => (
                <div key={table} style={summaryCardStyle}>
                  <Text code>{table}</Text>
                </div>
              ))}
            </div>
          ) : (
            <Text type="secondary" style={{ fontSize: 13 }}>
              校验后会自动写入输入表清单。
            </Text>
          )}
        </Section>
      </div>
    );
  }

  function renderAdvancedTab() {
    return (
      <div style={stackStyle}>
        <Section title="兼容输入覆盖" hint="一般无需填写；仅用于历史配置或临时排障">
          <Form layout="vertical" size="small">
            <Form.Item label="手动输入表" style={formItemStyle}>
              <Input.TextArea
                rows={3}
                placeholder={'iceberg.dwd.orders\niceberg.dwd.order_items'}
                value={(cfg.from_tables ?? []).join('\n')}
                onChange={(e) =>
                  patch({
                    from_tables: e.target.value
                      .split('\n')
                      .map((s) => s.trim())
                      .filter(Boolean),
                  })
                }
              />
            </Form.Item>
          </Form>
        </Section>
        <Section title="资源档位" hint="默认资源适合调试运行；大表处理时再调整">
          <Form layout="vertical" size="small">
            <div style={{ ...formGridStyle, gridTemplateColumns: 'repeat(4, minmax(120px, 1fr))' }}>
              <Form.Item label="executor 内存" style={formItemStyle}>
                <Input
                  value={cfg.resource_profile?.executor_memory ?? '2g'}
                  onChange={(e) => patchResource('executor_memory', e.target.value)}
                />
              </Form.Item>
              <Form.Item label="executor 核数" style={formItemStyle}>
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={32}
                  value={cfg.resource_profile?.executor_cores ?? 2}
                  onChange={(v) => patchResource('executor_cores', Number(v) || 2)}
                />
              </Form.Item>
              <Form.Item label="executor 数量" style={formItemStyle}>
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={64}
                  value={cfg.resource_profile?.num_executors ?? 2}
                  onChange={(v) => patchResource('num_executors', Number(v) || 2)}
                />
              </Form.Item>
              <Form.Item label="driver 内存" style={formItemStyle}>
                <Input
                  value={cfg.resource_profile?.driver_memory ?? '1g'}
                  onChange={(e) => patchResource('driver_memory', e.target.value)}
                />
              </Form.Item>
            </div>
          </Form>
        </Section>
      </div>
    );
  }
}

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section style={sectionStyle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, marginBottom: 14 }}>
        <Text strong style={{ fontSize: 15 }}>
          {title}
        </Text>
        {hint && (
          <Text type="secondary" style={{ fontSize: 12, textAlign: 'right' }}>
            {hint}
          </Text>
        )}
      </div>
      {children}
    </section>
  );
}

const stackStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const sectionStyle: CSSProperties = {
  border: '1px solid var(--ol-border, #dfe6f0)',
  borderRadius: 10,
  padding: 16,
  background: 'var(--ol-bg, #fff)',
};

const formGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
  gap: 14,
};

const formItemStyle: CSSProperties = {
  marginBottom: 0,
};

const summaryCardStyle: CSSProperties = {
  border: '1px solid var(--ol-border, #dfe7f1)',
  borderRadius: 10,
  padding: 12,
  background: 'var(--ol-bg-elevated, #fbfdff)',
};
