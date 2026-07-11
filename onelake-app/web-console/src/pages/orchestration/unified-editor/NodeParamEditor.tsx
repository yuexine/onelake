import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App as AntApp, Button, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ParamAPI } from '../../../api';
import { getAuthUser } from '../../../auth/oidc';
import { StateView } from '../../../components';
import type { PipelineParam } from '../../../types';
import { ParamTableEditor } from './ParamTableEditor';
import { type ParamUiError, toParamUiError, validateParamDrafts } from './paramSupport';

const { Text } = Typography;

export function NodeParamEditor({ dagId, taskKey }: { dagId: string; taskKey: string }) {
  const { message } = AntApp.useApp();
  const canManage = getAuthUser()?.roles.includes('DE') ?? false;
  const [allParams, setAllParams] = useState<PipelineParam[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ParamUiError>();
  const loadSequenceRef = useRef(0);

  const loadData = useCallback(async () => {
    if (!canManage) return;
    const sequence = ++loadSequenceRef.current;
    setLoading(true);
    setError(undefined);
    try {
      const nextParams = await ParamAPI.getPipeline(dagId);
      if (sequence !== loadSequenceRef.current) return;
      setAllParams(nextParams);
    } catch (loadError) {
      if (sequence === loadSequenceRef.current) setError(toParamUiError(loadError));
    } finally {
      if (sequence === loadSequenceRef.current) setLoading(false);
    }
  }, [canManage, dagId]);

  // 接口一次返回流水线全部 TASK 参数；节点切换复用同一快照，避免覆盖其他节点草稿。
  useEffect(() => {
    void loadData();
    return () => {
      loadSequenceRef.current += 1;
    };
  }, [loadData]);

  const taskParams = allParams.filter((param) => param.scope === 'TASK' && param.taskKey === taskKey);
  const updateTaskParams = (next: PipelineParam[]) => {
    setAllParams([
      ...allParams.filter((param) => !(param.scope === 'TASK' && param.taskKey === taskKey)),
      ...next.map((param) => ({ ...param, scope: 'TASK' as const, taskKey })),
    ]);
  };

  const save = async () => {
    try {
      validateParamDrafts(taskParams);
      setSaving(true);
      const saved = await ParamAPI.updatePipeline(dagId, {
        scope: 'TASK',
        taskKey,
        params: taskParams,
      });
      setAllParams((current) => [
        ...current.filter((param) => !(param.scope === 'TASK' && param.taskKey === taskKey)),
        ...saved,
      ]);
      message.success(`节点 ${taskKey} 的参数已保存`);
    } catch (saveError) {
      const uiError = toParamUiError(saveError);
      if (uiError.noPermission) setError(uiError);
      else message.error(uiError.message);
    } finally {
      setSaving(false);
    }
  };

  let content;
  if (!canManage) {
    content = (
      <StateView
        state="no-permission"
        title="无权管理节点参数"
        description="仅数据工程师（DE）可以查看和修改节点级运行参数。"
      />
    );
  } else if (loading) {
    content = <StateView state="loading" rows={4} />;
  } else if (error) {
    content = (
      <StateView
        state={error.noPermission ? 'no-permission' : 'error'}
        title={error.noPermission ? '无权管理节点参数' : '节点参数加载失败'}
        description={error.message}
        onRetry={() => void loadData()}
      />
    );
  } else {
    content = (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="节点作用域优先级最高"
          description={`仅节点 ${taskKey} 使用；同名值会覆盖流水线与全局参数。`}
        />
        <ParamTableEditor
          value={taskParams}
          scope="TASK"
          taskKey={taskKey}
          onChange={updateTaskParams}
          disabled={saving}
          emptyTitle="该节点暂无参数"
        />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          <Text type="secondary">支持 STRING、NUMBER、BOOL 与可解析的 EXPR。</Text>
          <Space>
            <Button icon={<ReloadOutlined />} disabled={saving} onClick={() => void loadData()}>刷新</Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={saving}
              onClick={() => void save()}
            >
              保存节点参数
            </Button>
          </Space>
        </div>
      </Space>
    );
  }

  return (
    <section style={{ marginTop: 24, paddingTop: 20, borderTop: '1px solid var(--ol-border, #e4e7eb)' }}>
      <Text strong style={{ display: 'block', marginBottom: 12, fontSize: 15 }}>节点参数</Text>
      {content}
    </section>
  );
}
