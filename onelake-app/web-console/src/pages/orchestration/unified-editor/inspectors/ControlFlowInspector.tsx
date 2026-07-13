import { useEffect, useState } from 'react';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Select, Space, Typography } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorStackStyle } from './InspectorSection';

const { Text } = Typography;

type BranchMap = Record<string, string | string[]>;

interface BranchRow {
  id: number;
  result: string;
  targets: string[];
}

let nextBranchRowId = 1;

export function ControlFlowInspector({
  task,
  tasks,
  edges,
  onChange,
  validationErrors,
  onValidationChange,
}: InspectorProps) {
  const isBranch = task.taskType === 'BRANCH';
  const expression = typeof task.config.expression === 'string' ? task.config.expression : '';
  const branches = isRecord(task.config.branches) ? task.config.branches as BranchMap : {};
  const [branchRows, setBranchRows] = useState<BranchRow[]>(() => Object.entries(branches)
    .map(([result, targets]) => createBranchRow(result, asTargets(targets))));
  useEffect(() => {
    onValidationChange?.(task.taskKey, 'branches');
  }, [onValidationChange, task.taskKey]);
  const patch = (next: Record<string, unknown>) => onChange({
    taskType: isBranch ? 'BRANCH' : 'CONDITION',
    config: { ...task.config, ...next },
  });
  const directDownstream = new Set(edges
    .filter((edge) => edge.edgeLayer === 'PIPELINE' && edge.sourceKey === task.taskKey)
    .map((edge) => edge.targetKey));
  const targetOptions = tasks
    .filter((item) => directDownstream.has(item.taskKey))
    .map((item) => ({ label: `${item.name} (${item.taskKey})`, value: item.taskKey }));

  const updateBranches = (nextRows: BranchRow[]) => {
    setBranchRows(nextRows);
    const results = nextRows.map((row) => row.result.trim());
    const duplicateResult = results.find((result, index) => result && results.indexOf(result) !== index);
    const draftError = results.some((result) => !result)
      ? '每条分支都必须填写结果值。'
      : duplicateResult
        ? `分支结果 ${duplicateResult} 重复，请使用唯一结果值。`
        : undefined;
    onValidationChange?.(task.taskKey, 'branches', draftError);
    if (draftError) return;
    patch({ branches: Object.fromEntries(nextRows.map((row) => [row.result.trim(), row.targets])) });
  };

  return (
    <div style={inspectorStackStyle}>
      <InspectorSection
        title={isBranch ? '分支表达式' : '条件表达式'}
        hint="仅支持参数、上游 outputs、比较与布尔运算，不执行任意代码"
      >
        <Form layout="vertical" size="small">
          <Form.Item
            label="表达式"
            required
            validateStatus={validationErrors.expression ? 'error' : undefined}
            help={validationErrors.expression}
            style={{ marginBottom: 0 }}
          >
            <Input.TextArea
              rows={4}
              value={expression}
              placeholder={isBranch ? '${route}' : '${upstream.load.rowsWritten} > 0'}
              onChange={(event) => patch({ expression: event.target.value })}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
      {isBranch && (
        <InspectorSection title="分支映射" hint="表达式结果 → 继续执行的下游 taskKey">
          {validationErrors.branches && (
            <Alert type="error" showIcon message={validationErrors.branches} style={{ marginBottom: 12 }} />
          )}
          {branchRows.length === 0 ? (
            <div style={{ padding: '18px 12px', textAlign: 'center', border: '1px dashed var(--ol-border)', borderRadius: 8 }}>
              <Text type="secondary">暂无分支映射；至少添加一个结果与下游节点。</Text>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {branchRows.map((row, index) => (
                <Space key={row.id} align="start" style={{ display: 'flex' }}>
                  <Input
                    aria-label={`分支 ${index + 1} 结果`}
                    value={row.result}
                    placeholder="例如 prod"
                    style={{ width: 160 }}
                    onChange={(event) => updateBranches(branchRows.map((current) => (
                      current.id === row.id ? { ...current, result: event.target.value } : current
                    )))}
                  />
                  <Select
                    aria-label={`分支 ${index + 1} 下游节点`}
                    mode="multiple"
                    value={row.targets}
                    options={targetOptions}
                    placeholder="选择直接下游节点"
                    style={{ flex: 1, minWidth: 330 }}
                    onChange={(targets) => updateBranches(branchRows.map((current) => (
                      current.id === row.id ? { ...current, targets } : current
                    )))}
                  />
                  <Button
                    danger
                    type="text"
                    aria-label={`删除分支 ${index + 1}`}
                    icon={<DeleteOutlined />}
                    onClick={() => updateBranches(branchRows.filter((current) => current.id !== row.id))}
                  />
                </Space>
              ))}
            </div>
          )}
          <Button
            icon={<PlusOutlined />}
            style={{ marginTop: 12 }}
            onClick={() => updateBranches([
              ...branchRows,
              createBranchRow(nextBranchResult(branchRows), []),
            ])}
          >
            添加分支
          </Button>
        </InspectorSection>
      )}
    </div>
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function asTargets(value: string | string[]): string[] {
  return Array.isArray(value) ? value : value ? [value] : [];
}

function createBranchRow(result: string, targets: string[]): BranchRow {
  return { id: nextBranchRowId++, result, targets };
}

function nextBranchResult(rows: BranchRow[]) {
  const used = new Set(rows.map((row) => row.result));
  let index = 1;
  while (used.has(`branch_${index}`)) index += 1;
  return `branch_${index}`;
}
