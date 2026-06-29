/**
 * 4 quality gate cards (PRIMARY / ACCEPTED_VALUES / RANGE / CUSTOM_SQL).
 *
 * <p>Schema matches DwdPipelineWorkbench so existing data_model.operator_graph
 * configs round-trip with the Spark pipeline quality-gate task.
 *
 * <p>Used by QualityGateInspector. Designed as a self-contained, reusable component.
 */
import { useMemo } from 'react';
import {
  Button,
  Card,
  Form,
  Input,
  Radio,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { PlusOutlined, MinusCircleOutlined } from '@ant-design/icons';

export type QualityGateKind = 'PRIMARY' | 'ACCEPTED_VALUES' | 'RANGE' | 'CUSTOM_SQL';
export type QualityGateAction = 'FAIL' | 'WARN';

export interface QualityGate {
  id: string;
  kind: QualityGateKind;
  title: string;
  enabled: boolean;
  columns: string[];
  actionOnViolation: QualityGateAction;
  valuesText?: string;      // ACCEPTED_VALUES: comma-separated
  minValue?: string;        // RANGE
  maxValue?: string;        // RANGE
  assertionSql?: string;    // CUSTOM_SQL
}

export interface QualityGateCardsProps {
  value: QualityGate[];
  onChange: (next: QualityGate[]) => void;
  /** Known target columns (from data_model.column_mappings). Optional. */
  targetColumns?: string[];
  disabled?: boolean;
}

const { Text } = Typography;

const KIND_META: Record<QualityGateKind, { label: string; color: string; hint: string }> = {
  PRIMARY: { label: '主键完整性', color: 'blue', hint: 'not null + unique' },
  ACCEPTED_VALUES: { label: '枚举值命中', color: 'cyan', hint: 'accepted values' },
  RANGE: { label: '数值范围', color: 'gold', hint: 'range check' },
  CUSTOM_SQL: { label: '自定义 SQL', color: 'purple', hint: 'SQL assertion' },
};

/** Default 4 gates seeded from a model — same shape as DwdPipelineWorkbench:187. */
export function defaultQualityGates(primaryColumns: string[] = [], firstColumn?: string): QualityGate[] {
  return [
    {
      id: 'primary',
      kind: 'PRIMARY',
      title: '主键完整性',
      enabled: primaryColumns.length > 0,
      columns: primaryColumns.length > 0 ? primaryColumns : firstColumn ? [firstColumn] : [],
      actionOnViolation: 'FAIL',
    },
    {
      id: 'accepted_values',
      kind: 'ACCEPTED_VALUES',
      title: '枚举值命中',
      enabled: false,
      columns: firstColumn ? [firstColumn] : [],
      actionOnViolation: 'WARN',
      valuesText: '',
    },
    {
      id: 'range',
      kind: 'RANGE',
      title: '数值范围',
      enabled: false,
      columns: firstColumn ? [firstColumn] : [],
      actionOnViolation: 'WARN',
      minValue: '',
      maxValue: '',
    },
    {
      id: 'custom_sql',
      kind: 'CUSTOM_SQL',
      title: '自定义 SQL',
      enabled: false,
      columns: [],
      actionOnViolation: 'FAIL',
      assertionSql: 'select * from {{ model }} where 1 = 0',
    },
  ];
}

export function QualityGateCards({ value, onChange, targetColumns, disabled }: QualityGateCardsProps) {
  const updateGate = (id: string, patch: Partial<QualityGate>) => {
    onChange(value.map((g) => (g.id === id ? { ...g, ...patch } : g)));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {value.map((gate) => {
        const meta = KIND_META[gate.kind];
        return (
          <Card
            key={gate.id}
            size="small"
            title={
              <Space>
                <Tag color={meta.color}>{gate.kind}</Tag>
                <Text strong>{gate.title}</Text>
              </Space>
            }
            extra={
              <Switch
                size="small"
                checked={gate.enabled}
                onChange={(checked) => updateGate(gate.id, { enabled: checked })}
                disabled={disabled}
              />
            }
          >
            <Form layout="vertical" size="small" disabled={disabled || !gate.enabled}>
              <Form.Item label="失败处理">
                <Radio.Group
                  value={gate.actionOnViolation}
                  onChange={(e) => updateGate(gate.id, { actionOnViolation: e.target.value })}
                >
                  <Radio value="FAIL">阻断流水线</Radio>
                  <Radio value="WARN">仅告警</Radio>
                </Radio.Group>
              </Form.Item>

              <ColumnsField
                gate={gate}
                targetColumns={targetColumns}
                onChange={(columns) => updateGate(gate.id, { columns })}
              />

              {gate.kind === 'ACCEPTED_VALUES' && (
                <Form.Item label="允许值（逗号分隔）" required>
                  <Input
                    value={gate.valuesText ?? ''}
                    placeholder="ACTIVE, INACTIVE, PENDING"
                    onChange={(e) => updateGate(gate.id, { valuesText: e.target.value })}
                  />
                </Form.Item>
              )}
              {gate.kind === 'RANGE' && (
                <Form.Item label="数值范围">
                  <Space>
                    <Input
                      style={{ width: 110 }}
                      placeholder="min"
                      value={gate.minValue ?? ''}
                      onChange={(e) => updateGate(gate.id, { minValue: e.target.value })}
                    />
                    <Text type="secondary">≤</Text>
                    <Input
                      style={{ width: 110 }}
                      placeholder="max"
                      value={gate.maxValue ?? ''}
                      onChange={(e) => updateGate(gate.id, { maxValue: e.target.value })}
                    />
                  </Space>
                </Form.Item>
              )}
              {gate.kind === 'CUSTOM_SQL' && (
                <Form.Item label="断言 SQL" help="返回任意行即视为失败">
                  <Input.TextArea
                    rows={3}
                    value={gate.assertionSql ?? ''}
                    onChange={(e) => updateGate(gate.id, { assertionSql: e.target.value })}
                  />
                </Form.Item>
              )}

              <Text type="secondary" style={{ fontSize: 11 }}>
                编译为 {meta.hint}
              </Text>
            </Form>
          </Card>
        );
      })}
    </div>
  );
}

function ColumnsField({
  gate,
  targetColumns,
  onChange,
}: {
  gate: QualityGate;
  targetColumns?: string[];
  onChange: (columns: string[]) => void;
}) {
  const showFreeInput = gate.kind === 'CUSTOM_SQL' || !targetColumns?.length;
  const knownCols = useMemo(() => targetColumns ?? [], [targetColumns]);

  if (!showFreeInput && knownCols.length > 0) {
    return (
      <Form.Item label="作用列">
        <Input
          value={gate.columns.join(', ')}
          placeholder="从目标模型同步"
          onChange={(e) => onChange(splitCsv(e.target.value))}
        />
      </Form.Item>
    );
  }
  return (
    <Form.Item label="作用列（每行一个）">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {gate.columns.map((col, idx) => (
          <Space key={idx} style={{ width: '100%' }}>
            <Input
              style={{ flex: 1 }}
              value={col}
              placeholder={`column_${idx + 1}`}
              onChange={(e) => {
                const next = [...gate.columns];
                next[idx] = e.target.value;
                onChange(next);
              }}
            />
            <Button
              size="small"
              danger
              icon={<MinusCircleOutlined />}
              onClick={() => onChange(gate.columns.filter((_, i) => i !== idx))}
            />
          </Space>
        ))}
        <Button
          size="small"
          type="dashed"
          icon={<PlusOutlined />}
          onClick={() => onChange([...gate.columns, ''])}
        >
          添加列
        </Button>
      </div>
    </Form.Item>
  );
}

function splitCsv(s: string): string[] {
  return s
    .split(/[,\s]+/)
    .map((t) => t.trim())
    .filter(Boolean);
}
