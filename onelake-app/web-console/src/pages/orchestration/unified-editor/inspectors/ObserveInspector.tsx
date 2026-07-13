import { Alert, Form, Input, InputNumber, Radio, Select } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorFormItemStyle, inspectorGridStyle, inspectorStackStyle } from './InspectorSection';

export function ObserveInspector({ task, onChange, validationErrors }: InspectorProps) {
  if (task.taskType === 'WAIT') {
    return <WaitFields task={task} onChange={onChange} validationErrors={validationErrors} />;
  }

  const patch = (next: Record<string, unknown>) => onChange({
    taskType: 'SENSOR',
    config: { ...task.config, ...next },
  });

  return (
    <div style={inspectorStackStyle}>
      <Alert type="info" showIcon message="Sensor 会轮询持久化资产就绪状态，节点保持运行中直到就绪或超时。" />
      <InspectorSection title="等待目标">
        <Form layout="vertical" size="small">
          <Form.Item
            label="资产 FQN"
            required
            validateStatus={validationErrors.assetFqn ? 'error' : undefined}
            help={validationErrors.assetFqn}
          >
            <Input
              value={stringValue(task.config.assetFqn)}
              placeholder="iceberg.ods.orders"
              onChange={(event) => patch({ assetFqn: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="分区 / 批次（可选）" style={inspectorFormItemStyle}>
            <Input
              value={stringValue(task.config.partition)}
              placeholder="例如 ${bizdate}"
              onChange={(event) => patch({ partition: event.target.value })}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
      <InspectorSection title="轮询与超时">
        <Form layout="vertical" size="small">
          <div style={{ ...inspectorGridStyle, gridTemplateColumns: 'repeat(3, minmax(120px, 1fr))' }}>
            <Form.Item
              label="超时（秒）"
              required
              validateStatus={validationErrors.timeoutSeconds ? 'error' : undefined}
              help={validationErrors.timeoutSeconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={1} max={86400} style={{ width: '100%' }} value={numberValue(task.config.timeoutSeconds, 300)}
                onChange={(value) => patch({ timeoutSeconds: value ?? 300 })} />
            </Form.Item>
            <Form.Item
              label="轮询间隔（秒）"
              required
              validateStatus={validationErrors.pollIntervalSeconds ? 'error' : undefined}
              help={validationErrors.pollIntervalSeconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber min={1} max={300} style={{ width: '100%' }} value={numberValue(task.config.pollIntervalSeconds, 5)}
                onChange={(value) => patch({ pollIntervalSeconds: value ?? 5 })} />
            </Form.Item>
            <Form.Item
              label="超时处置"
              required
              validateStatus={validationErrors.onTimeout ? 'error' : undefined}
              help={validationErrors.onTimeout}
              style={inspectorFormItemStyle}
            >
              <Select value={stringValue(task.config.onTimeout) || 'FAILED'} options={[
                { label: '节点失败', value: 'FAILED' },
                { label: '有意跳过', value: 'SKIPPED' },
              ]} onChange={(onTimeout) => patch({ onTimeout })} />
            </Form.Item>
          </div>
        </Form>
      </InspectorSection>
    </div>
  );
}

function WaitFields({ task, onChange, validationErrors }: Pick<InspectorProps, 'task' | 'onChange' | 'validationErrors'>) {
  const mode = task.config.offsetSeconds !== undefined ? 'OFFSET' : 'DURATION';
  const setMode = (nextMode: 'OFFSET' | 'DURATION') => {
    const { offsetSeconds: _offset, durationSeconds: _duration, ...rest } = task.config;
    onChange({
      taskType: 'WAIT',
      config: nextMode === 'OFFSET'
        ? { ...rest, offsetSeconds: 0 }
        : { ...rest, durationSeconds: 60 },
    });
  };
  const setSeconds = (value: number | null) => {
    const { offsetSeconds: _offset, durationSeconds: _duration, ...rest } = task.config;
    onChange({
      taskType: 'WAIT',
      config: mode === 'OFFSET'
        ? { ...rest, offsetSeconds: value ?? 0 }
        : { ...rest, durationSeconds: value ?? 60 },
    });
  };

  return (
    <div style={inspectorStackStyle}>
      <Alert type="info" showIcon message="等待节点最长 86400 秒；logical_date 偏移以本次运行冻结的业务时间为准。" />
      <InspectorSection title="等待方式">
        <Form layout="vertical" size="small">
          <Form.Item label="模式">
            <Radio.Group value={mode} onChange={(event) => setMode(event.target.value)}>
              <Radio.Button value="DURATION">固定时长</Radio.Button>
              <Radio.Button value="OFFSET">logical_date 偏移</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            label={mode === 'OFFSET' ? '偏移秒数' : '等待秒数'}
            required
            validateStatus={validationErrors.waitSeconds ? 'error' : undefined}
            help={validationErrors.waitSeconds}
            style={inspectorFormItemStyle}
          >
            <InputNumber
              min={mode === 'OFFSET' ? 0 : 1}
              max={86400}
              style={{ width: 240 }}
              value={numberValue(mode === 'OFFSET' ? task.config.offsetSeconds : task.config.durationSeconds, mode === 'OFFSET' ? 0 : 60)}
              onChange={setSeconds}
            />
          </Form.Item>
        </Form>
      </InspectorSection>
    </div>
  );
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' ? value : fallback;
}
