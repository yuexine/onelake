import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { StateView } from '../../../components';
import type { PipelineParam, PipelineParamScope, PipelineParamValueType } from '../../../types';

const { Text } = Typography;
const VALUE_TYPE_OPTIONS: Array<{ label: string; value: PipelineParamValueType }> = [
  { label: 'STRING', value: 'STRING' },
  { label: 'NUMBER', value: 'NUMBER' },
  { label: 'BOOL', value: 'BOOL' },
  { label: 'EXPR', value: 'EXPR' },
];

interface Props {
  value: PipelineParam[];
  scope: PipelineParamScope;
  taskKey?: string;
  onChange: (value: PipelineParam[]) => void;
  disabled?: boolean;
  compact?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
}

export function ParamTableEditor({
  value,
  scope,
  taskKey,
  onChange,
  disabled = false,
  compact = false,
  emptyTitle = '暂无参数',
  emptyDescription = '添加第一条参数后，可在 SQL 或节点配置中通过 ${key} 引用。',
}: Props) {
  const addParam = () => onChange([
    ...value,
    {
      scope,
      taskKey,
      paramKey: '',
      paramValue: '',
      valueType: 'STRING',
      description: '',
    },
  ]);
  const updateParam = (index: number, patch: Partial<PipelineParam>) => {
    onChange(value.map((param, current) => current === index ? { ...param, ...patch, scope, taskKey } : param));
  };
  const deleteParam = (index: number) => onChange(value.filter((_, current) => current !== index));

  if (compact) {
    return (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {value.length === 0 ? (
          <StateView
            state="empty"
            title={emptyTitle}
            description={emptyDescription}
            cta={<Button icon={<PlusOutlined />} onClick={addParam} disabled={disabled}>新增参数</Button>}
          />
        ) : value.map((param, index) => (
          <div
            key={param.id ?? `draft-${index}`}
            style={{ border: '1px solid var(--ol-border, #dfe7f1)', borderRadius: 10, padding: 12 }}
          >
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Space.Compact style={{ width: '100%' }}>
                <Input
                  aria-label="参数 Key"
                  value={param.paramKey}
                  placeholder="参数 Key"
                  disabled={disabled}
                  onChange={(event) => updateParam(index, { paramKey: event.target.value })}
                />
                <Select
                  aria-label="参数类型"
                  value={param.valueType}
                  options={VALUE_TYPE_OPTIONS}
                  style={{ width: 118 }}
                  disabled={disabled}
                  onChange={(valueType) => updateParam(index, { valueType })}
                />
              </Space.Compact>
              <ValueInput param={param} disabled={disabled} onChange={(paramValue) => updateParam(index, { paramValue })} />
              <Input
                aria-label="参数描述"
                value={param.description}
                placeholder="参数说明（可选）"
                maxLength={512}
                disabled={disabled}
                onChange={(event) => updateParam(index, { description: event.target.value })}
              />
              <Button type="text" danger icon={<DeleteOutlined />} disabled={disabled} onClick={() => deleteParam(index)}>
                删除
              </Button>
            </Space>
          </div>
        ))}
        {value.length > 0 && (
          <Button block type="dashed" icon={<PlusOutlined />} onClick={addParam} disabled={disabled}>新增参数</Button>
        )}
      </Space>
    );
  }

  return (
    <Table<PipelineParam>
      size="small"
      rowKey={(param, index) => param.id ?? `draft-${index}`}
      dataSource={value}
      pagination={false}
      tableLayout="fixed"
      columns={[
        {
          title: '参数 Key',
          width: '22%',
          render: (_, param, index) => (
            <Input
              value={param.paramKey}
              placeholder="例如 region"
              disabled={disabled}
              onChange={(event) => updateParam(index, { paramKey: event.target.value })}
            />
          ),
        },
        {
          title: 'value_type',
          width: 126,
          render: (_, param, index) => (
            <Select
              value={param.valueType}
              options={VALUE_TYPE_OPTIONS}
              style={{ width: '100%' }}
              disabled={disabled}
              onChange={(valueType) => updateParam(index, { valueType })}
            />
          ),
        },
        {
          title: '参数值',
          width: '27%',
          render: (_, param, index) => (
            <ValueInput param={param} disabled={disabled} onChange={(paramValue) => updateParam(index, { paramValue })} />
          ),
        },
        {
          title: 'description',
          render: (_, param, index) => (
            <Input
              value={param.description}
              placeholder="用途与覆盖说明"
              maxLength={512}
              disabled={disabled}
              onChange={(event) => updateParam(index, { description: event.target.value })}
            />
          ),
        },
        {
          title: '操作',
          width: 64,
          align: 'center',
          render: (_, __, index) => (
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              aria-label="删除参数"
              disabled={disabled}
              onClick={() => deleteParam(index)}
            />
          ),
        },
      ]}
      locale={{
        emptyText: (
          <StateView
            state="empty"
            title={emptyTitle}
            description={emptyDescription}
            cta={<Button icon={<PlusOutlined />} onClick={addParam} disabled={disabled}>新增参数</Button>}
          />
        ),
      }}
      title={() => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <Space size={8}>
            <Text type="secondary">共 {value.length} 项</Text>
            <Tag>{scope}</Tag>
          </Space>
          <Button size="small" icon={<PlusOutlined />} onClick={addParam} disabled={disabled}>新增参数</Button>
        </div>
      )}
    />
  );
}

function ValueInput({
  param,
  disabled,
  onChange,
}: {
  param: PipelineParam;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  if (param.valueType === 'BOOL') {
    return (
      <Select
        aria-label="参数值"
        value={param.paramValue || undefined}
        placeholder="选择布尔值"
        options={[{ label: 'true', value: 'true' }, { label: 'false', value: 'false' }]}
        style={{ width: '100%' }}
        disabled={disabled}
        onChange={onChange}
      />
    );
  }
  return (
    <Input
      aria-label="参数值"
      value={param.paramValue}
      placeholder={param.valueType === 'NUMBER' ? '例如 100' : param.valueType === 'EXPR' ? '例如 ${bizdate-1:yyyyMMdd}' : '参数值'}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}
