import Editor from '@monaco-editor/react';
import { Alert, Form, Input, Select } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorFormItemStyle, inspectorGridStyle, inspectorStackStyle } from './InspectorSection';

interface TrinoConfig {
  sql: string;
  catalog: string;
  schema: string;
}

export function TrinoSqlInspector({ task, onChange, validationErrors }: InspectorProps) {
  const config = task.config as Partial<TrinoConfig>;
  const patch = (next: Partial<TrinoConfig>) => onChange({
    taskType: 'TRINO_SQL',
    config: { ...task.config, ...next },
  });

  return (
    <div style={inspectorStackStyle}>
      <Alert
        type="info"
        showIcon
        message="Trino 仅允许只读查询或与目标表 FQN 一致的 CREATE TABLE AS SELECT。"
      />
      <InspectorSection title="Trino 会话" hint="当前环境允许 iceberg / default、ods、dwd">
        <Form layout="vertical" size="small">
          <div style={inspectorGridStyle}>
            <Form.Item
              label="Catalog"
              required
              validateStatus={validationErrors.catalog ? 'error' : undefined}
              help={validationErrors.catalog}
              style={inspectorFormItemStyle}
            >
              <Select
                value={config.catalog}
                placeholder="选择 Catalog"
                options={[{ label: 'iceberg', value: 'iceberg' }]}
                onChange={(catalog) => patch({ catalog })}
              />
            </Form.Item>
            <Form.Item
              label="Schema"
              required
              validateStatus={validationErrors.schema ? 'error' : undefined}
              help={validationErrors.schema}
              style={inspectorFormItemStyle}
            >
              <Select
                showSearch
                value={config.schema}
                placeholder="选择 Schema"
                options={['default', 'ods', 'dwd'].map((value) => ({ label: value, value }))}
                onChange={(schema) => patch({ schema })}
              />
            </Form.Item>
          </div>
          <Form.Item
            label="目标表 FQN（CTAS 时必填）"
            validateStatus={validationErrors.targetFqn ? 'error' : undefined}
            help={validationErrors.targetFqn}
            style={{ marginTop: 14, marginBottom: 0 }}
          >
            <Input
              value={task.targetFqn ?? ''}
              placeholder="iceberg.dwd.order_summary"
              onChange={(event) => onChange({ taskType: 'TRINO_SQL', targetFqn: event.target.value || undefined })}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
      <InspectorSection title="SQL" hint="支持 M2 参数表达式；保存后通过顶部校验确认安全边界">
        <Form layout="vertical" size="small">
          <Form.Item
            required
            validateStatus={validationErrors.sql ? 'error' : undefined}
            help={validationErrors.sql}
            style={inspectorFormItemStyle}
          >
            <Editor
              height={360}
              language="sql"
              value={config.sql ?? ''}
              theme="vs"
              options={{ minimap: { enabled: false }, fontSize: 12, scrollBeyondLastLine: false }}
              onChange={(sql) => patch({ sql: sql ?? '' })}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
    </div>
  );
}
