import Editor from '@monaco-editor/react';
import { Alert, Form, InputNumber } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorFormItemStyle, inspectorGridStyle, inspectorStackStyle } from './InspectorSection';

interface ScriptConfig {
  script: string;
  timeout_seconds: number;
  cpu_seconds: number;
  cpu_cores: number;
  memory_mb: number;
}

export function ScriptInspector({ task, onChange, validationErrors }: InspectorProps) {
  const config = task.config as Partial<ScriptConfig>;
  const type = task.taskType === 'SHELL' ? 'SHELL' : 'PYTHON';
  const patch = (next: Partial<ScriptConfig>) => onChange({
    taskType: type,
    config: { ...task.config, ...next },
  });

  return (
    <div style={inspectorStackStyle}>
      <Alert
        type="warning"
        showIcon
        message="脚本在默认禁网的受限沙箱中运行"
        description="不注入控制面或内部凭证；路径、进程、CPU、内存和输出大小均受后端策略限制。"
      />
      <InspectorSection title={type === 'PYTHON' ? 'Python 脚本' : 'Shell 脚本'}>
        <Form layout="vertical" size="small">
          <Form.Item
            required
            validateStatus={validationErrors.script ? 'error' : undefined}
            help={validationErrors.script}
            style={inspectorFormItemStyle}
          >
            <Editor
              height={330}
              language={type === 'PYTHON' ? 'python' : 'shell'}
              value={config.script ?? ''}
              theme="vs"
              options={{ minimap: { enabled: false }, fontSize: 12, scrollBeyondLastLine: false }}
              onChange={(script) => patch({ script: script ?? '' })}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
      <InspectorSection title="超时与资源" hint="超过租户沙箱上限时，节点校验会给出明确错误">
        <Form layout="vertical" size="small">
          <div style={{ ...inspectorGridStyle, gridTemplateColumns: 'repeat(4, minmax(100px, 1fr))' }}>
            <Form.Item
              label="超时（秒）"
              required
              validateStatus={validationErrors.timeout_seconds ? 'error' : undefined}
              help={validationErrors.timeout_seconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={1} max={900} style={{ width: '100%' }} value={config.timeout_seconds ?? 60}
                onChange={(value) => patch({ timeout_seconds: value ?? 60 })} />
            </Form.Item>
            <Form.Item
              label="CPU 时间（秒）"
              required
              validateStatus={validationErrors.cpu_seconds ? 'error' : undefined}
              help={validationErrors.cpu_seconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={1} max={config.timeout_seconds ?? 60} style={{ width: '100%' }} value={config.cpu_seconds ?? 30}
                onChange={(value) => patch({ cpu_seconds: value ?? 30 })} />
            </Form.Item>
            <Form.Item
              label="CPU 核"
              required
              validateStatus={validationErrors.cpu_cores ? 'error' : undefined}
              help={validationErrors.cpu_cores}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={1} max={4} style={{ width: '100%' }} value={config.cpu_cores ?? 1}
                onChange={(value) => patch({ cpu_cores: value ?? 1 })} />
            </Form.Item>
            <Form.Item
              label="内存（MB）"
              required
              validateStatus={validationErrors.memory_mb ? 'error' : undefined}
              help={validationErrors.memory_mb}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={32} max={2048} step={32} style={{ width: '100%' }} value={config.memory_mb ?? 256}
                onChange={(value) => patch({ memory_mb: value ?? 256 })} />
            </Form.Item>
          </div>
        </Form>
      </InspectorSection>
    </div>
  );
}
