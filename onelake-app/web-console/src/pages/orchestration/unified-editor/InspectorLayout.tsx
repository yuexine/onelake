import { CheckCircleOutlined, CloseCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { Tag, Typography } from 'antd';
import type { CSSProperties, ReactNode } from 'react';
import type { PipelineTask, PipelineTaskEdge } from '../../../types';

const { Text } = Typography;

interface InspectorLayoutProps {
  task: PipelineTask;
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  children: ReactNode;
}

interface LineageItem {
  key: string;
  title: string;
  meta: string[];
}

export function InspectorLayout({ task, tasks, edges, children }: InspectorLayoutProps) {
  const taskNameByKey = new Map(tasks.map((item) => [item.taskKey, item.name || item.taskKey]));
  const inputs = edges
    .filter((edge) => edge.targetKey === task.taskKey)
    .map((edge) => ({
      key: edge.id || `${edge.sourceKey}-${edge.targetKey}-${edge.targetInput}`,
      title: `${portLabel(edge.targetInput ?? edge.targetPort, '输入')} · ${taskNameByKey.get(edge.sourceKey) ?? edge.sourceKey}`,
      meta: [
        edge.targetInput || edge.targetPort ? `端口 ${edge.targetInput ?? edge.targetPort}` : undefined,
        edge.inputAlias ? `别名 ${edge.inputAlias}` : undefined,
        edge.assetFqn ? edge.assetFqn : undefined,
        edge.freshnessPolicy ? `新鲜度 ${edge.freshnessPolicy}` : undefined,
      ].filter((item): item is string => Boolean(item)),
    }));
  const outputs = edges
    .filter((edge) => edge.sourceKey === task.taskKey)
    .map((edge) => ({
      key: edge.id || `${edge.sourceKey}-${edge.targetKey}-${edge.targetInput}`,
      title: `${portLabel(edge.sourceOutput ?? edge.sourcePort, '输出')} · ${taskNameByKey.get(edge.targetKey) ?? edge.targetKey}`,
      meta: [
        edge.targetInput || edge.targetPort ? `到 ${edge.targetInput ?? edge.targetPort}` : undefined,
        edge.assetFqn ? edge.assetFqn : task.targetFqn,
        edge.triggerPolicy ? `触发 ${edge.triggerPolicy}` : undefined,
      ].filter((item): item is string => Boolean(item)),
    }));

  return (
    <div
      data-testid="pipeline-inspector-layout"
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr) 318px',
        height: '100%',
        minHeight: 0,
        background: 'var(--ol-fill-soft, #f6f8fb)',
      }}
    >
      <div
        style={{
          minWidth: 0,
          padding: 20,
          height: '100%',
          overflowY: 'auto',
        }}
      >
        {children}
      </div>
      <aside
        style={{
          borderLeft: '1px solid var(--ol-border, #e4e7eb)',
          background: 'var(--ol-bg, #fff)',
          padding: 18,
          height: '100%',
          overflowY: 'auto',
        }}
      >
        <RailSection title="数据流关系">
          <LineageGroup title="输入来自" emptyText="暂无上游输入" items={inputs} />
          <LineageGroup title="输出给" emptyText="暂无下游节点" items={outputs} />
        </RailSection>
        <RailSection title="校验状态">
          <StatusCard task={task} inputCount={inputs.length} outputCount={outputs.length} />
        </RailSection>
        {task.taskType === 'SYNC_REF' ? (
          <RailSection title="触发规则">
            <div style={railCardStyle}>
              <Text strong style={{ fontSize: 13 }}>按表 FQN 匹配</Text>
              <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12, lineHeight: 1.6 }}>
                监听 integration.table.loaded 事件；事件中的目标表与本节点上游表 FQN 一致时，记录就绪并触发下游。
              </Text>
            </div>
          </RailSection>
        ) : task.engine && (
          <RailSection title="执行环境">
            <div style={railCardStyle}>
              <Text strong style={{ fontSize: 13 }}>{executionEnvironment(task).title}</Text>
              <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
                {executionEnvironment(task).description}
              </Text>
              {task.executable && (
                <Tag color="green" style={{ marginTop: 10 }}>
                  可执行
                </Tag>
              )}
            </div>
          </RailSection>
        )}
      </aside>
    </div>
  );
}

function executionEnvironment(task: PipelineTask) {
  if (task.taskType === 'TRINO_SQL') {
    return { title: 'Trino', description: '受限 Iceberg 会话；仅允许只读查询或匹配目标表的 CTAS' };
  }
  if (task.taskType === 'PYTHON' || task.taskType === 'SHELL') {
    return { title: '脚本沙箱', description: '默认禁网，不注入控制面或内部凭证，资源与输出有界' };
  }
  if (task.category === 'CONTROL' || ['BRANCH', 'CONDITION', 'SUB_PIPELINE'].includes(task.taskType)) {
    return { title: '控制节点', description: '受控求值与触发；不声明资产产出' };
  }
  if (task.category === 'OBSERVE' || ['SENSOR', 'WAIT', 'NOTIFY', 'ASSERTION'].includes(task.taskType)) {
    return { title: '观测节点', description: '等待、通知或断言；不声明资产产出' };
  }
  if (task.engine === 'PYSPARK') {
    return { title: 'PySpark', description: '共享 Iceberg / Hive Metastore / MinIO' };
  }
  return { title: 'Spark', description: '共享 Iceberg / Hive Metastore / MinIO' };
}

function RailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ marginBottom: 18 }}>
      <Text strong style={{ display: 'block', marginBottom: 12, fontSize: 14 }}>
        {title}
      </Text>
      {children}
    </section>
  );
}

function LineageGroup({
  title,
  emptyText,
  items,
}: {
  title: string;
  emptyText: string;
  items: LineageItem[];
}) {
  return (
    <div style={{ marginBottom: 10 }}>
      <Text type="secondary" style={{ display: 'block', marginBottom: 6, fontSize: 12 }}>
        {title}
      </Text>
      {items.length === 0 ? (
        <div style={railCardStyle}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {emptyText}
          </Text>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {items.map((item) => (
            <div key={item.key} style={railCardStyle}>
              <Text strong style={{ display: 'block', fontSize: 13 }}>
                {item.title}
              </Text>
              {item.meta.map((meta) => (
                <Text key={meta} type="secondary" style={{ display: 'block', marginTop: 5, fontSize: 12 }}>
                  {meta}
                </Text>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function StatusCard({
  task,
  inputCount,
  outputCount,
}: {
  task: PipelineTask;
  inputCount: number;
  outputCount: number;
}) {
  const isFailed = task.compileStatus === 'FAILED';
  const isValidated = task.compileStatus === 'VALIDATED';
  const Icon = isFailed ? CloseCircleOutlined : isValidated ? CheckCircleOutlined : WarningOutlined;
  const color = isFailed ? '#b42318' : isValidated ? '#166534' : '#92400e';
  const background = isFailed ? '#fff1f0' : isValidated ? '#f0fbf4' : '#fffbeb';
  const border = isFailed ? '#f5c2bd' : isValidated ? '#b8e7c9' : '#fde68a';
  const message = isFailed
    ? task.compileError || '当前配置未通过校验，请检查必填项和输入连线。'
    : isValidated
      ? `节点已校验，当前为 ${inputCount} 入 · ${outputCount} 出。`
      : '节点尚未校验，保存配置后点击顶部“校验”。';

  return (
    <div
      style={{
        ...railCardStyle,
        borderColor: border,
        background,
        color,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Icon />
        <Text strong style={{ color, fontSize: 13 }}>
          {isFailed ? '校验未通过' : isValidated ? '已校验' : '待校验'}
        </Text>
      </div>
      <Text style={{ display: 'block', marginTop: 8, color, fontSize: 12, lineHeight: 1.6 }}>
        {message}
      </Text>
    </div>
  );
}

function portLabel(port: string | undefined, fallback: string) {
  if (port === 'left') return '左输入';
  if (port === 'right') return '右输入';
  if (port === 'output' || port === 'out') return '输出';
  return fallback;
}

const railCardStyle: CSSProperties = {
  border: '1px solid var(--ol-border, #dfe7f1)',
  borderRadius: 10,
  padding: 12,
  background: 'var(--ol-bg-elevated, #fbfdff)',
};
