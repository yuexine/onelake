import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Form, InputNumber, Select, Switch } from 'antd';
import { OrchestrationAPI } from '../../../../api';
import { BizError } from '../../../../api/http';
import { StateView } from '../../../../components';
import type { Dag } from '../../../../types';
import type { InspectorProps } from '../InspectorRouter';
import { InspectorSection, inspectorFormItemStyle, inspectorGridStyle, inspectorStackStyle } from './InspectorSection';

interface LoadError {
  message: string;
  noPermission: boolean;
}

export function SubPipelineInspector({
  dagId,
  task,
  onChange,
  validationErrors,
  onValidationChange,
}: InspectorProps) {
  const [pipelines, setPipelines] = useState<Dag[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<LoadError>();
  const waitForCompletion = task.config.waitForCompletion !== false;
  const patch = (next: Record<string, unknown>) => onChange({
    taskType: 'SUB_PIPELINE',
    config: { ...task.config, ...next },
  });

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(undefined);
    OrchestrationAPI.listDags()
      .then((items) => setPipelines(items.filter((item) => item.id !== dagId && Boolean(item.publishedVersionId))))
      .catch((error: unknown) => setLoadError(toLoadError(error)))
      .finally(() => setLoading(false));
  }, [dagId]);

  useEffect(() => load(), [load]);

  const options = useMemo(() => pipelines.map((pipeline) => ({
    value: pipeline.id,
    label: `${pipeline.name}${pipeline.enabled ? '' : ' · 未启用'}`,
    search: `${pipeline.name} ${pipeline.id}`,
  })), [pipelines]);
  const selectedSubDagId = typeof task.config.subDagId === 'string' ? task.config.subDagId : undefined;
  const unavailableSelection = Boolean(
    selectedSubDagId
    && !loading
    && !loadError
    && !pipelines.some((pipeline) => pipeline.id === selectedSubDagId),
  );
  const availabilityError = loading
    ? '正在验证目标流水线的发布状态。'
    : loadError
      ? '暂时无法验证目标流水线的发布状态，请重试列表加载。'
      : unavailableSelection
        ? '当前目标流水线未发布或已不可用，请重新选择。'
        : undefined;
  useEffect(() => {
    onValidationChange?.(task.taskKey, 'subDagId', availabilityError);
  }, [availabilityError, onValidationChange, task.taskKey]);
  const subDagError = unavailableSelection
    ? '当前目标流水线未发布或已不可用，请重新选择。'
    : validationErrors.subDagId;

  let selector;
  if (loading) {
    selector = <StateView state="loading" rows={3} />;
  } else if (loadError) {
    selector = (
      <StateView
        state={loadError.noPermission ? 'no-permission' : 'error'}
        title={loadError.noPermission ? '无权读取流水线' : '流水线加载失败'}
        description={loadError.message}
        onRetry={load}
      />
    );
  } else if (pipelines.length === 0) {
    selector = (
      <StateView
        state="empty"
        title="暂无可引用流水线"
        description="当前租户没有其他已发布流水线，或仅当前流水线已发布。"
      />
    );
  } else {
    selector = (
      <Form layout="vertical" size="small">
        <Form.Item
          label="目标流水线"
          required
          validateStatus={subDagError ? 'error' : undefined}
          help={subDagError}
          style={inspectorFormItemStyle}
        >
          <Select
            showSearch
            value={selectedSubDagId}
            options={options}
            placeholder="搜索流水线名称或 ID"
            filterOption={(input, option) => String((option as { search?: string })?.search ?? '').toLowerCase().includes(input.toLowerCase())}
            onChange={(subDagId) => patch({ subDagId })}
          />
        </Form.Item>
      </Form>
    );
  }

  return (
    <div style={inspectorStackStyle}>
      <Alert type="info" showIcon message="只能触发同租户内的已发布快照；后端会拒绝自引用、调用环和超过 32 层的递归。" />
      <InspectorSection title="选择子流水线">{selector}</InspectorSection>
      <InspectorSection title="等待策略">
        <Form layout="vertical" size="small">
          <Form.Item label="等待子流水线完成" style={{ marginBottom: 14 }}>
            <Switch checked={waitForCompletion} onChange={(checked) => patch({ waitForCompletion: checked })} />
          </Form.Item>
          <div style={inspectorGridStyle}>
            <Form.Item
              label="超时（秒）"
              validateStatus={validationErrors.timeoutSeconds ? 'error' : undefined}
              help={validationErrors.timeoutSeconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber disabled={!waitForCompletion} min={1} max={86400} style={{ width: '100%' }}
                value={typeof task.config.timeoutSeconds === 'number' ? task.config.timeoutSeconds : 3600}
                onChange={(value) => patch({ timeoutSeconds: value ?? 3600 })} />
            </Form.Item>
            <Form.Item
              label="轮询间隔（秒）"
              validateStatus={validationErrors.pollIntervalSeconds ? 'error' : undefined}
              help={validationErrors.pollIntervalSeconds}
              style={inspectorFormItemStyle}
            >
              <InputNumber disabled={!waitForCompletion} min={1} max={300} style={{ width: '100%' }}
                value={typeof task.config.pollIntervalSeconds === 'number' ? task.config.pollIntervalSeconds : 5}
                onChange={(value) => patch({ pollIntervalSeconds: value ?? 5 })} />
            </Form.Item>
          </div>
        </Form>
      </InspectorSection>
    </div>
  );
}

function toLoadError(error: unknown): LoadError {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  if (status === 401 || code === 40100) return { noPermission, message: '登录状态已失效，请重新登录。' };
  if (status === 403 || code === 40300) return { noPermission, message: '当前账号没有读取流水线列表的权限。' };
  return { noPermission, message: error instanceof Error && error.message ? error.message : '请稍后重试。' };
}
