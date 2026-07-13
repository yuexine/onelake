import { Alert, Form, Input, Select } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorFormItemStyle, inspectorStackStyle } from './InspectorSection';

export function NotifyAssertionInspector({ task, onChange, validationErrors }: InspectorProps) {
  if (task.taskType === 'ASSERTION') {
    const expression = typeof task.config.expression === 'string' ? task.config.expression : '';
    return (
      <div style={inspectorStackStyle}>
        <Alert type="info" showIcon message="断言复用受控表达式求值；结果为假时节点失败并按现有短路语义阻断下游。" />
        <InspectorSection title="断言表达式" hint="支持参数与上游 outputs，不执行任意代码">
          <Form layout="vertical" size="small">
            <Form.Item
              label="表达式"
              required
              validateStatus={validationErrors.expression ? 'error' : undefined}
              help={validationErrors.expression}
              style={inspectorFormItemStyle}
            >
              <Input.TextArea rows={6} value={expression} placeholder="${upstream.load.rowsWritten} > 0"
                onChange={(event) => onChange({ taskType: 'ASSERTION', config: { ...task.config, expression: event.target.value } })} />
            </Form.Item>
          </Form>
        </InspectorSection>
      </div>
    );
  }

  const patch = (next: Record<string, unknown>) => onChange({
    taskType: 'NOTIFY',
    config: { ...task.config, ...next },
  });
  return (
    <div style={inspectorStackStyle}>
      <Alert type="info" showIcon message="标题、内容和链接支持 M2 参数与上游 outputs 渲染；重复回调不会重复创建通知。" />
      <InspectorSection title="通知内容">
        <Form layout="vertical" size="small">
          <Form.Item
            label="标题"
            required
            validateStatus={validationErrors.title ? 'error' : undefined}
            help={validationErrors.title}
          >
            <Input maxLength={256} showCount value={stringValue(task.config.title)}
              onChange={(event) => patch({ title: event.target.value })} />
          </Form.Item>
          <Form.Item
            label="内容"
            required
            validateStatus={validationErrors.message ? 'error' : undefined}
            help={validationErrors.message}
          >
            <Input.TextArea rows={6} value={stringValue(task.config.message)}
              placeholder="流水线 ${run_id} 已运行至当前节点"
              onChange={(event) => patch({ message: event.target.value })} />
          </Form.Item>
          <Form.Item label="级别">
            <Select value={stringValue(task.config.level) || 'INFO'} options={[
              { label: '信息', value: 'INFO' },
              { label: '警告', value: 'WARNING' },
              { label: '严重', value: 'CRITICAL' },
            ]} onChange={(level) => patch({ level })} />
          </Form.Item>
          <Form.Item label="跳转链接（可选）" style={inspectorFormItemStyle}>
            <Input value={stringValue(task.config.link)} placeholder="/orchestration/runs/${run_id}"
              onChange={(event) => patch({ link: event.target.value })} />
          </Form.Item>
        </Form>
      </InspectorSection>
    </div>
  );
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}
