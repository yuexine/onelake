/**
 * 流水线列表（对应原型 §4.4.1 升级版）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, App as AntApp, Button, Form, Input, Modal, Radio, Select, Space, Switch, Table, Tag, Tooltip, Typography } from 'antd';
import {
  PlusOutlined, AppstoreOutlined, InfoCircleOutlined, PlayCircleOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard } from '../../components';
import { ModelingAPI, OrchestrationAPI, PipelineAPI } from '../../api';
import type { Dag, DataModel, JobRun } from '../../types';
import { dagContainsTargetFqn, dagNodes, pipelineTaskContainsTargetFqn } from '../../utils/pipelineTargetMatching';

const { Text } = Typography;

type CreateMode = 'BLANK' | 'ODS_DWD' | 'MULTI_LAYER';

function modelDisplayName(model: DataModel) {
  return `${model.name} / ${model.targetFqn}`;
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function runIdentity(run: JobRun) {
  return run.dagsterRunId || run.id;
}

function LastRunSummary({ run }: { run?: JobRun }) {
  if (!run) {
    return <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>暂无运行</span>;
  }
  return (
    <Space direction="vertical" size={4}>
      <Space size={4} wrap>
        <StatusBadge status={run.status} />
        {run.runMode === 'DEV' && <Tag color="purple" style={{ margin: 0 }}>试跑</Tag>}
      </Space>
      <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>
        {formatDate(run.startedAt)}
      </span>
    </Space>
  );
}

function LastRunIdCell({ run, onOpen }: { run?: JobRun; onOpen: (run: JobRun) => void }) {
  if (!run) {
    return <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>-</span>;
  }
  const identity = runIdentity(run);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
      <Text
        code
        copyable={{ text: identity }}
        ellipsis={{ tooltip: identity }}
        style={{ display: 'block', maxWidth: 176, fontSize: 11 }}
      >
        {identity}
      </Text>
      <Button size="small" type="link" style={{ paddingInline: 0, height: 20 }} onClick={() => onOpen(run)}>
        详情
      </Button>
    </div>
  );
}

function TriggerState({ dag }: { dag: Dag }) {
  const triggerable = Boolean(dag.triggerable);
  const label = triggerable ? '可触发' : dag.enabled ? '待绑定' : '草稿';
  return (
    <span
      title={triggerable ? undefined : dag.triggerBlockedReason}
      style={{
        padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
        background: triggerable ? 'var(--ol-success-soft)' : 'var(--ol-fill-soft)',
        color: triggerable ? 'var(--ol-success)' : 'var(--ol-ink-2)',
      }}
    >
      {label}
    </span>
  );
}

function isDwdGovernancePipeline(dag: Dag) {
  const definition = Array.isArray(dag.definition) ? {} : dag.definition || {};
  if (definition.pipelineMode === 'SPARK_GOVERNANCE') return true;
  return dagNodes(dag).some((node) => (
    node.operatorRef === 'standard.dwd_table_governance'
    || node.config?.governanceMode === 'SPARK_GOVERNANCE'
    || node.config?.modelKind === 'DWD_TABLE_GOVERNANCE'
  ));
}

function pipelineOpenPath(dag: Dag) {
  // All pipelines open in the Unified Pipeline Editor.
  return `/orchestration/pipelines/${dag.id}`;
}

export default function PipelineList() {
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [pipelines, setPipelines] = useState<Dag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);
  const [targetTaskLoading, setTargetTaskLoading] = useState(false);
  const [taskMatchedPipelineIds, setTaskMatchedPipelineIds] = useState<Set<string>>(() => new Set());
  const notifiedTargetFqnRef = useRef('');

  const loadPipelines = () => {
    setLoading(true);
    setError(null);
    OrchestrationAPI.listDags()
      .then(setPipelines)
      .catch((e) => setError(e.message || '流水线列表加载失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadPipelines();
  }, []);

  const targetFqnFilter = searchParams.get('targetFqn') || '';

  useEffect(() => {
    let cancelled = false;
    setTaskMatchedPipelineIds(new Set());
    if (!targetFqnFilter || pipelines.length === 0) {
      setTargetTaskLoading(false);
      return () => {
        cancelled = true;
      };
    }

    setTargetTaskLoading(true);
    Promise.all(pipelines.map(async (pipeline) => {
      try {
        const tasks = await PipelineAPI.listTasks(pipeline.id);
        return tasks.some((task) => pipelineTaskContainsTargetFqn(task, targetFqnFilter))
          ? pipeline.id
          : undefined;
      } catch {
        return undefined;
      }
    }))
      .then((matches) => {
        if (!cancelled) {
          setTaskMatchedPipelineIds(new Set(matches.filter((id): id is string => Boolean(id))));
        }
      })
      .finally(() => {
        if (!cancelled) setTargetTaskLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [pipelines, targetFqnFilter]);

  const visiblePipelines = useMemo(
    () => pipelines.filter((pipeline) => (
      dagContainsTargetFqn(pipeline, targetFqnFilter)
      || taskMatchedPipelineIds.has(pipeline.id)
    )),
    [pipelines, targetFqnFilter, taskMatchedPipelineIds],
  );

  const clearTargetFqnFilter = () => {
    const next = new URLSearchParams(searchParams);
    next.delete('targetFqn');
    setSearchParams(next, { replace: true });
  };

  const counts = useMemo(() => ({
    total: visiblePipelines.length,
    enabled: visiblePipelines.filter((p) => p.enabled).length,
    draft: visiblePipelines.filter((p) => !p.enabled).length,
  }), [visiblePipelines]);

  const targetFqnFilterNotice = useMemo(() => {
    if (!targetFqnFilter) return '';
    if (loading || targetTaskLoading) return '正在按目标表筛选流水线';
    if (visiblePipelines.length === 0) return '未找到目标表关联流水线';
    return `已按目标表筛选，显示 ${visiblePipelines.length} / ${pipelines.length} 条流水线`;
  }, [loading, pipelines.length, targetFqnFilter, targetTaskLoading, visiblePipelines.length]);

  useEffect(() => {
    if (!targetFqnFilter) {
      notifiedTargetFqnRef.current = '';
      return;
    }
    if (loading || targetTaskLoading || error || notifiedTargetFqnRef.current === targetFqnFilter) return;
    notifiedTargetFqnRef.current = targetFqnFilter;
    message.info({
      key: 'pipeline-target-fqn-filter',
      content: `${targetFqnFilterNotice}：${targetFqnFilter}`,
      duration: 3,
    });
  }, [error, loading, message, targetFqnFilter, targetFqnFilterNotice, targetTaskLoading]);

  const triggerPipeline = (dag: Dag) => {
    if (!dag.triggerable) {
      message.warning(dag.triggerBlockedReason || '当前流水线不可触发');
      return;
    }
    setTriggeringId(dag.id);
    const trigger = dag.dagsterJob === 'onelake_pipeline_run'
      ? PipelineAPI.trigger(dag.id)
      : OrchestrationAPI.triggerDag(dag.id);
    trigger
      .then(() => message.success(`流水线 ${dag.name} 已触发运行`))
      .catch((e) => message.error(e.message || '流水线触发失败'))
      .finally(() => {
        setTriggeringId(null);
        loadPipelines();
      });
  };

  // Unified create entry. ODS→DWD remains a template, not a separate top-level module.
  const [createOpen, setCreateOpen] = useState(false);
  const [createMode, setCreateMode] = useState<CreateMode>('ODS_DWD');
  const [createSubmitting, setCreateSubmitting] = useState(false);
  const [dwdModels, setDwdModels] = useState<DataModel[]>([]);
  const [dwdModelsLoading, setDwdModelsLoading] = useState(false);
  const [dwdModelsError, setDwdModelsError] = useState<string | null>(null);
  const [createForm] = Form.useForm<{
    pipelineName?: string;
    modelId?: string;
    includeQualityGate: boolean;
    includeFieldGovernance: boolean;
  }>();
  const selectedModelId = Form.useWatch('modelId', createForm);

  const selectedModel = useMemo(
    () => dwdModels.find((model) => model.id === selectedModelId),
    [dwdModels, selectedModelId],
  );

  const dwdModelOptions = useMemo(() => dwdModels.map((model) => ({
    label: modelDisplayName(model),
    value: model.id,
  })), [dwdModels]);

  const loadDwdModels = useCallback(() => {
    setDwdModelsLoading(true);
    setDwdModelsError(null);
    ModelingAPI.listModels()
      .then((items) => {
        const models = items
          .filter((model) => model.layer === 'DWD' && model.status === 'VALIDATED')
          .sort((a, b) => modelDisplayName(a).localeCompare(modelDisplayName(b), 'zh-CN'));
        setDwdModels(models);
        const currentModelId = createForm.getFieldValue('modelId');
        if (currentModelId && !models.some((model) => model.id === currentModelId)) {
          createForm.setFieldValue('modelId', undefined);
        }
      })
      .catch((e) => setDwdModelsError(e.message || 'DWD 模型加载失败'))
      .finally(() => setDwdModelsLoading(false));
  }, [createForm]);

  const openCreatePipeline = () => {
    createForm.resetFields();
    createForm.setFieldsValue({ includeQualityGate: true, includeFieldGovernance: true });
    setCreateMode('ODS_DWD');
    setCreateOpen(true);
  };

  useEffect(() => {
    if (!createOpen || createMode !== 'ODS_DWD') return;
    loadDwdModels();
  }, [createMode, createOpen, loadDwdModels]);

  const submitCreatePipeline = async () => {
    const values = await createForm.validateFields();
    setCreateSubmitting(true);
    try {
      if (createMode === 'BLANK') {
        const created = await PipelineAPI.create({
          name: values.pipelineName || `pipeline_${Date.now()}`,
          pipelineKind: 'BLANK',
        });
        message.success('已创建空白流水线');
        setCreateOpen(false);
        createForm.resetFields();
        navigate(`/orchestration/pipelines/${created.id}`);
        return;
      }
      if (createMode === 'ODS_DWD') {
        if (!selectedModel) {
          message.warning('请选择已通过校验的 DWD 模型');
          return;
        }
        const result = await PipelineAPI.applyOdsDwdTemplate({
          modelId: selectedModel.id,
          sourceFqn: selectedModel.sourceFqn,
          targetFqn: selectedModel.targetFqn,
          dbtModelName: selectedModel.dbtModelName || selectedModel.name,
          includeQualityGate: values.includeQualityGate ?? true,
          includeFieldGovernance: values.includeFieldGovernance ?? true,
        });
        message.success(`已创建 ODS→DWD 流水线（${result.taskIds.length} 任务）`);
        setCreateOpen(false);
        createForm.resetFields();
        navigate(`/orchestration/pipelines/${result.pipelineId}`);
      }
    } catch (err) {
      message.error((err as Error).message || '流水线创建失败');
    } finally {
      setCreateSubmitting(false);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title="流水线"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="DAG 画布编辑、版本管理、环境隔离、依赖触发"
        meta={[
          { label: '总流水线', value: counts.total },
          { label: '已启用', value: counts.enabled },
          { label: '草稿', value: counts.draft },
        ]}
        actions={(
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreatePipeline}>
            新建流水线
          </Button>
        )}
      />

      <SectionCard
        title="流水线列表"
        icon={<AppstoreOutlined />}
        flatBody
        extra={targetFqnFilter ? (
          <Space className="ol-filter-context" size={8}>
            <span className="ol-filter-context__label">
              <InfoCircleOutlined />
              <span>目标表</span>
              <Text code className="ol-filter-context__code" ellipsis={{ tooltip: targetFqnFilter }}>
                {targetFqnFilter}
              </Text>
            </span>
            <Button size="small" type="link" onClick={clearTargetFqnFilter}>
              查看全部
            </Button>
          </Space>
        ) : undefined}
      >
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            action={<Button size="small" onClick={loadPipelines}>重试</Button>}
            style={{ margin: 12 }}
          />
        )}
        <Table
          size="middle"
          rowKey="id"
          dataSource={visiblePipelines}
          loading={loading}
          pagination={false}
          locale={{
            emptyText: targetFqnFilter ? '未找到目标表关联流水线' : undefined,
          }}
          columns={[
            { title: '名称', dataIndex: 'name', render: (n: string, r: Dag) => (
              <Space size={10}>
                <div style={{ width: 28, height: 28, borderRadius: 6, background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
                  <AppstoreOutlined />
                </div>
                <div>
                  <Space size={6} wrap>
                    <a className="ol-link" style={{ fontSize: 13, fontWeight: 500 }} onClick={() => navigate(pipelineOpenPath(r))}>{n}</a>
                    {isDwdGovernancePipeline(r) && <Tag color="processing" style={{ margin: 0 }}>DWD 治理</Tag>}
                  </Space>
                  <div style={{ marginTop: 2, fontSize: 11, color: 'var(--ol-ink-3)' }}>{r.dagsterJob}</div>
                </div>
              </Space>
            ) },
            { title: '运行态', width: 90, render: (_: unknown, r: Dag) => <TriggerState dag={r} /> },
            { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag style={{ margin: 0 }}>v{v}</Tag> },
            { title: '状态', dataIndex: 'enabled', width: 110, render: (enabled: boolean) => <StatusBadge status={enabled ? 'ENABLED' : 'DRAFT'} /> },
            { title: '创建时间', dataIndex: 'createdAt', width: 180, render: (value?: string) => (
              <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatDate(value)}</span>
            ) },
            { title: '最近运行', width: 160, render: (_: unknown, r: Dag) => (
              <LastRunSummary run={r.lastRun} />
            ) },
            { title: '最近运行 ID', width: 250, render: (_: unknown, r: Dag) => (
              <LastRunIdCell run={r.lastRun} onOpen={(run) => navigate(`/orchestration/runs/${run.id}`)} />
            ) },
            { title: '操作', width: 180, render: (_: unknown, r: Dag) => (
              <Space>
                <Tooltip title={r.triggerable ? undefined : r.triggerBlockedReason}>
                  <span>
                    <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />}
                      disabled={!r.triggerable}
                      loading={triggeringId === r.id}
                      onClick={() => triggerPipeline(r)}
                    >
                      触发
                    </Button>
                  </span>
                </Tooltip>
                <Button size="small" type="link" onClick={() => navigate(pipelineOpenPath(r))}>
                  打开编辑器
                </Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      {/* Unified create modal. Template-specific endpoints are implementation details. */}
      <Modal
        title="新建流水线"
        open={createOpen}
        onOk={submitCreatePipeline}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={createSubmitting}
        okText="创建并打开"
        cancelText="取消"
        width={560}
      >
        <Form form={createForm} layout="vertical" size="small">
          <Form.Item label="创建方式" required>
            <Radio.Group
              optionType="button"
              buttonStyle="solid"
              value={createMode}
              onChange={(event) => setCreateMode(event.target.value)}
            >
              <Radio.Button value="ODS_DWD">ODS 到 DWD 标准模板</Radio.Button>
              <Radio.Button value="BLANK">空白流水线</Radio.Button>
              <Radio.Button value="MULTI_LAYER" disabled>多层建模</Radio.Button>
            </Radio.Group>
          </Form.Item>

          {createMode === 'ODS_DWD' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="系统将根据所选模型生成标准治理流程"
              description="将自动创建上游同步、DWD 模型、字段治理和质量校验任务。创建后可在流水线编辑器中调整任务配置。"
            />
          )}
          {createMode === 'BLANK' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="从空白编排开始"
              description="创建后进入流水线编辑器，按需添加任务并配置依赖关系。"
            />
          )}

          {createMode === 'BLANK' && (
            <Form.Item
              name="pipelineName"
              label="流水线名称"
              rules={[{ required: true, message: '请输入流水线名称' }]}
            >
              <Input placeholder="例如：dwd_order_wide_pipeline" />
            </Form.Item>
          )}

          {createMode === 'ODS_DWD' && (
            <>
              <Form.Item
                name="modelId"
                label="DWD 模型"
                rules={[{ required: true, message: '请选择 DWD 模型' }]}
              >
                <Select
                  showSearch
                  loading={dwdModelsLoading}
                  disabled={Boolean(dwdModelsError)}
                  placeholder="请选择已通过校验的 DWD 模型"
                  options={dwdModelOptions}
                  optionFilterProp="label"
                  notFoundContent={dwdModelsLoading ? '正在加载模型' : '暂无可用于创建流水线的 DWD 模型'}
                />
              </Form.Item>

              {dwdModelsError && (
                <Alert
                  type="error"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={dwdModelsError}
                  action={<Button size="small" onClick={loadDwdModels}>重试</Button>}
                />
              )}

              {!dwdModelsLoading && !dwdModelsError && dwdModels.length === 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message="暂无可用于创建流水线的 DWD 模型"
                  description="请先在湖仓建模中完成模型设计并通过校验。"
                />
              )}

              {selectedModel && (
                <div style={{
                  marginTop: -8,
                  marginBottom: 16,
                  padding: '10px 12px',
                  border: '1px solid var(--ol-border, #e4e7eb)',
                  borderRadius: 6,
                  background: 'var(--ol-fill-soft, #fafbfc)',
                }}>
                  <Space direction="vertical" size={6} style={{ width: '100%' }}>
                    <Space size={8} wrap>
                      <Text type="secondary">来源表</Text>
                      <Text code style={{ fontSize: 12 }}>{selectedModel.sourceFqn}</Text>
                    </Space>
                    <Space size={8} wrap>
                      <Text type="secondary">目标表</Text>
                      <Text code style={{ fontSize: 12 }}>{selectedModel.targetFqn}</Text>
                    </Space>
                    <Space size={8} wrap>
                      <Text type="secondary">模型标识</Text>
                      <Tag style={{ margin: 0 }}>{selectedModel.dbtModelName || selectedModel.name}</Tag>
                    </Space>
                  </Space>
                </div>
              )}

              <Form.Item label="流程选项" style={{ marginBottom: 0 }}>
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Space align="start" size={10}>
                    <Form.Item name="includeFieldGovernance" valuePropName="checked" noStyle>
                      <Switch />
                    </Form.Item>
                    <div>
                      <Text>生成字段治理任务</Text>
                      <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>基于模型字段映射生成治理检查任务。</div>
                    </div>
                  </Space>
                  <Space align="start" size={10}>
                    <Form.Item name="includeQualityGate" valuePropName="checked" noStyle>
                      <Switch />
                    </Form.Item>
                    <div>
                      <Text>生成质量校验任务</Text>
                      <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>为目标 DWD 表预置质量规则入口。</div>
                    </div>
                  </Space>
                </Space>
              </Form.Item>
            </>
          )}

          {createMode === 'MULTI_LAYER' && (
            <Form.Item>
              <Alert
                type="warning"
                showIcon
                message="多层建模模板暂未开放"
                description="当前版本可先通过空白流水线配置多层任务。"
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
