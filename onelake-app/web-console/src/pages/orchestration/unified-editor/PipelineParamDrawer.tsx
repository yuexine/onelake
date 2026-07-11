import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App as AntApp, Button, Drawer, Space, Tabs, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ParamAPI } from '../../../api';
import { getAuthUser } from '../../../auth/oidc';
import { StateView } from '../../../components';
import type { PipelineParam } from '../../../types';
import { ParamTableEditor } from './ParamTableEditor';
import { type ParamUiError, toParamUiError, validateParamDrafts } from './paramSupport';

const { Text } = Typography;

interface Props {
  dagId: string;
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

export function PipelineParamDrawer({ dagId, open, onClose, onChanged }: Props) {
  const { message } = AntApp.useApp();
  const canManage = getAuthUser()?.roles.includes('DE') ?? false;
  const [globalParams, setGlobalParams] = useState<PipelineParam[]>([]);
  const [pipelineParams, setPipelineParams] = useState<PipelineParam[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<ParamUiError>();
  const [saving, setSaving] = useState<'GLOBAL' | 'PIPELINE'>();
  const loadSequenceRef = useRef(0);

  const loadData = useCallback(async () => {
    if (!open || !canManage) return;
    const sequence = ++loadSequenceRef.current;
    setLoading(true);
    setLoadError(undefined);
    try {
      const [nextGlobal, nextPipeline] = await Promise.all([
        ParamAPI.getGlobal(),
        ParamAPI.getPipeline(dagId),
      ]);
      if (sequence !== loadSequenceRef.current) return;
      setGlobalParams(nextGlobal.filter((param) => param.scope === 'GLOBAL'));
      setPipelineParams(nextPipeline.filter((param) => param.scope === 'PIPELINE' || param.scope === 'TASK'));
    } catch (error) {
      if (sequence === loadSequenceRef.current) setLoadError(toParamUiError(error));
    } finally {
      if (sequence === loadSequenceRef.current) setLoading(false);
    }
  }, [canManage, dagId, open]);

  useEffect(() => {
    void loadData();
    return () => {
      loadSequenceRef.current += 1;
    };
  }, [loadData]);

  const pipelineLevel = pipelineParams.filter((param) => param.scope === 'PIPELINE');
  const updatePipelineLevel = (next: PipelineParam[]) => {
    setPipelineParams([
      ...next.map((param) => ({ ...param, scope: 'PIPELINE' as const, taskKey: undefined })),
      ...pipelineParams.filter((param) => param.scope === 'TASK'),
    ]);
  };

  const saveGlobal = async () => {
    try {
      validateParamDrafts(globalParams);
      setSaving('GLOBAL');
      const saved = await ParamAPI.updateGlobal(globalParams.map((param) => ({
        ...param,
        scope: 'GLOBAL',
        dagId: undefined,
        taskKey: undefined,
      })));
      setGlobalParams(saved);
      message.success('全局参数已保存');
    } catch (error) {
      const uiError = toParamUiError(error);
      if (uiError.noPermission) setLoadError(uiError);
      else message.error(uiError.message);
    } finally {
      setSaving(undefined);
    }
  };

  const savePipeline = async () => {
    try {
      validateParamDrafts(pipelineLevel);
      setSaving('PIPELINE');
      const saved = await ParamAPI.updatePipeline(dagId, {
        scope: 'PIPELINE',
        params: pipelineLevel,
      });
      setPipelineParams((current) => [
        ...saved,
        ...current.filter((param) => param.scope === 'TASK'),
      ]);
      onChanged?.();
      message.success('流水线参数已保存');
    } catch (error) {
      const uiError = toParamUiError(error);
      if (uiError.noPermission) setLoadError(uiError);
      else message.error(uiError.message);
    } finally {
      setSaving(undefined);
    }
  };

  let content;
  if (!canManage) {
    content = (
      <StateView
        state="no-permission"
        title="无权管理参数"
        description="仅数据工程师（DE）可以查看和修改全局、流水线及节点参数。"
      />
    );
  } else if (loading) {
    content = <StateView state="loading" rows={8} />;
  } else if (loadError) {
    content = (
      <StateView
        state={loadError.noPermission ? 'no-permission' : 'error'}
        title={loadError.noPermission ? '无权管理参数' : '参数加载失败'}
        description={loadError.message}
        onRetry={() => void loadData()}
      />
    );
  } else {
    content = (
      <Tabs
        items={[
          {
            key: 'global',
            label: `全局参数 (${globalParams.length})`,
            children: (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Alert type="info" showIcon message="租户全局作用域" description="所有流水线默认可用；同名流水线参数或节点参数会覆盖全局值。" />
                <ParamTableEditor
                  value={globalParams}
                  scope="GLOBAL"
                  onChange={setGlobalParams}
                  disabled={saving !== undefined}
                />
                <div style={{ textAlign: 'right' }}>
                  <Button
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={saving === 'GLOBAL'}
                    disabled={saving !== undefined}
                    onClick={() => void saveGlobal()}
                  >
                    保存全局参数
                  </Button>
                </div>
              </Space>
            ),
          },
          {
            key: 'pipeline',
            label: `流水线参数 (${pipelineLevel.length})`,
            children: (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Alert
                  type="info"
                  showIcon
                  message="流水线作用域"
                  description="当前流水线全部节点默认可用；节点级参数请打开画布节点，在 Inspector 底部维护。"
                />
                <ParamTableEditor
                  value={pipelineLevel}
                  scope="PIPELINE"
                  onChange={updatePipelineLevel}
                  disabled={saving !== undefined}
                />
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                  <Text type="secondary">保存仅更新流水线作用域，不会覆盖节点参数。</Text>
                  <Button
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={saving === 'PIPELINE'}
                    disabled={saving !== undefined}
                    onClick={() => void savePipeline()}
                  >
                    保存流水线参数
                  </Button>
                </div>
              </Space>
            ),
          },
        ]}
      />
    );
  }

  return (
    <Drawer
      title="参数管理"
      width={880}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={canManage && !loading && !loadError ? (
        <Button
          icon={<ReloadOutlined />}
          disabled={saving !== undefined}
          onClick={() => void loadData()}
        >
          刷新
        </Button>
      ) : undefined}
    >
      {content}
    </Drawer>
  );
}
