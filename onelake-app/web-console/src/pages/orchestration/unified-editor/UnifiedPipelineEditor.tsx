/**
 * Unified Pipeline Editor (P2 MVP).
 *
 * <p>Three-column layout:
 * <ul>
 *   <li>Left: TaskPalette — grouped task types</li>
 *   <li>Center: DagCanvasSimple — topological columns of task Cards</li>
 *   <li>Popup: InspectorRouter — dispatches by task_type for the selected node</li>
 * </ul>
 *
 * <p>Top toolbar: validate (L1+L2) · trigger · publish (status flow).
 *
 * <p>Component layering (§4.2):
 * <ul>
 *   <li>Shell (this file): ~250 lines</li>
 *   <li>TaskPalette: ~80 lines</li>
 *   <li>DagCanvasSimple: ~150 lines</li>
 *   <li>InspectorRouter + 5 inspectors: each < 200 lines</li>
 * </ul>
 */
import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  App as AntApp,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../../../components';
import { usePipelineEditor } from './usePipelineEditor';
import { TaskPalette } from './TaskPalette';
import { DagCanvasSimple } from './DagCanvasSimple';
import { InspectorRouter, type InspectorProps } from './InspectorRouter';
import type { TaskTypeMeta } from './taskTypes';
import { PipelineAPI } from '../../../api';
import type { PipelineKind, PipelineTaskEdgeRequest, PipelineTaskRequest, PipelineTaskType } from '../../../types';

const { Text } = Typography;

const defaultFreshnessPolicy = (targetInput?: string) =>
  targetInput === 'left' || targetInput === 'right' ? 'SAME_FRESHNESS_WINDOW' : 'LATEST';

const taskTypeCopy: Record<PipelineTaskType, { code: string; label: string; summary: string; color: string }> = {
  SYNC_REF: { code: 'IN', label: '同步引用', summary: '引用已采集的 ODS 表作为数据流起点', color: '#0ea5e9' },
  SPARK_SQL: { code: 'SQL', label: 'Spark SQL', summary: '使用 Spark SQL 处理上游数据并产出表', color: '#4f46e5' },
  PYSPARK: { code: 'PY', label: 'PySpark', summary: '使用 PySpark 脚本处理上游数据并产出表', color: '#7c3aed' },
  QUALITY_GATE: { code: 'QA', label: '质量门禁', summary: '校验上游产出表并阻断异常数据', color: '#16a34a' },
};

function statusTagColorOf(status: string | undefined) {
  if (status === 'VALIDATED') return 'green';
  if (status === 'FAILED') return 'red';
  return 'default';
}

export default function UnifiedPipelineEditor() {
  const params = useParams<{ id?: string; pipelineId?: string; dagId?: string }>();
  const dagId = params.id ?? params.pipelineId ?? params.dagId;
  const navigate = useNavigate();
  const { message, modal } = AntApp.useApp();
  const editor = usePipelineEditor(dagId);
  const [pipelineForm] = Form.useForm<{ name: string; pipelineKind: PipelineKind }>();
  const [creatingPipeline, setCreatingPipeline] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createMeta, setCreateMeta] = useState<TaskTypeMeta | undefined>(undefined);
  const [createForm] = Form.useForm<{ taskKey: string; name: string; modelId?: string; targetFqn?: string }>();
  const [edgeOpen, setEdgeOpen] = useState(false);
  const [edgeForm] = Form.useForm<PipelineTaskEdgeRequest>();
  const [draftPatch, setDraftPatch] = useState<Partial<PipelineTaskRequest> & { taskType: PipelineTaskType } | undefined>(undefined);
  const [inspectorHeaderEditing, setInspectorHeaderEditing] = useState(false);
  const [createPosition, setCreatePosition] = useState<{ x: number; y: number } | undefined>(undefined);

  const openCreate = useCallback((type: PipelineTaskType, meta: TaskTypeMeta, position?: { x: number; y: number }) => {
    setCreateMeta(meta);
    setCreatePosition(position);
    createForm.resetFields();
    createForm.setFieldsValue({
      taskKey: `${(meta.preset ?? type).toLowerCase()}_${Math.random().toString(36).slice(2, 6)}`,
      name: meta.name,
    });
    setCreateOpen(true);
  }, [createForm]);

  const defaultConfigFor = useCallback((meta: TaskTypeMeta): Record<string, unknown> => {
    if (meta.preset === 'SPARK_JOIN') {
      return {
        dataflow: {
          nodeKind: 'JOIN',
          joinType: 'LEFT',
          leftAlias: 'l',
          rightAlias: 'r',
          on: 'l.id = r.id',
          select: 'l.*, r.*',
        },
      };
    }
    if (meta.preset === 'SPARK_DERIVE_COLUMN') {
      return {
        dataflow: {
          nodeKind: 'DERIVE_COLUMN',
          sourceAlias: 'src',
          includeSourceColumns: true,
          deriveColumns: [
            { name: '用户 UUID', expression: 'uuid()' },
          ],
        },
      };
    }
    if (meta.preset === 'SPARK_SINK') {
      return {
        dataflow: {
          nodeKind: 'SINK',
          sourceAlias: 'src',
          mode: 'OVERWRITE',
          select: 'src.*',
        },
      };
    }
    return {};
  }, []);

  const submitCreate = useCallback(async () => {
    if (!createMeta || !dagId) return;
    const values = await createForm.validateFields();
    const payload: PipelineTaskRequest = {
      taskKey: values.taskKey,
      taskType: createMeta.type,
      name: values.name,
      engine: createMeta.engine,
      targetFqn: values.targetFqn,
      modelId: createMeta.requiresModel ? values.modelId : undefined,
      config: createMeta.requiresModel ? {} : defaultConfigFor(createMeta),
      positionX: createPosition?.x,
      positionY: createPosition?.y,
    };
    try {
      await editor.createTask(payload);
      setCreateOpen(false);
      setCreatePosition(undefined);
    } catch {
      // error already toasted
    }
  }, [createMeta, dagId, createForm, createPosition, defaultConfigFor, editor]);

  const openCreateEdge = useCallback(() => {
    edgeForm.resetFields();
    edgeForm.setFieldsValue({
      edgeLayer: 'PIPELINE',
      sourcePort: 'out',
      targetPort: 'in',
      sourceOutput: 'out',
      targetInput: 'in',
      triggerPolicy: 'ALL_SUCCEEDED',
      freshnessPolicy: 'LATEST',
    });
    setEdgeOpen(true);
  }, [edgeForm]);

  const submitCreateEdge = useCallback(async () => {
    const values = await edgeForm.validateFields();
    const payload: PipelineTaskEdgeRequest = {
      ...values,
      sourceOutput: values.sourceOutput || values.sourcePort || 'out',
      targetInput: values.targetInput || values.targetPort || 'in',
      joinRole: values.joinRole || values.targetInput || values.targetPort || 'in',
      triggerPolicy: values.triggerPolicy || 'ALL_SUCCEEDED',
      freshnessPolicy: values.freshnessPolicy || defaultFreshnessPolicy(values.targetInput || values.targetPort),
    };
    try {
      await editor.createEdge(payload);
      setEdgeOpen(false);
    } catch {
      // error already toasted
    }
  }, [edgeForm, editor]);

  const submitCreatePipeline = useCallback(async () => {
    const values = await pipelineForm.validateFields();
    setCreatingPipeline(true);
    try {
      const created = await PipelineAPI.create({
        name: values.name,
        pipelineKind: values.pipelineKind,
      });
      message.success('流水线已创建');
      navigate(`/orchestration/pipelines/${created.id}`, { replace: true });
    } catch (err) {
      message.error(`创建流水线失败: ${(err as Error).message}`);
    } finally {
      setCreatingPipeline(false);
    }
  }, [message, navigate, pipelineForm]);

  // Build inspector props from selected task + local draft patch
  const inspectorProps: InspectorProps | undefined = editor.selectedTask
    ? {
        task: { ...editor.selectedTask, ...(draftPatch as Partial<typeof editor.selectedTask> | undefined) },
        tasks: editor.tasks,
        edges: editor.edges,
        onChange: (patch) => setDraftPatch((prev) => ({ ...prev, ...patch } as typeof prev)),
        onSave: async () => {
          if (!editor.selectedTask || !draftPatch) return;
          try {
            await editor.updateTask(editor.selectedTask.taskKey, draftPatch);
            setDraftPatch(undefined);
            message.success('已保存');
          } catch {
            // toasted
          }
        },
        saving: editor.saving,
      }
    : undefined;

  // Reset draft when selection changes
  useEffect(() => {
    setDraftPatch(undefined);
    setInspectorHeaderEditing(false);
  }, [editor.selectedTaskKey]);

  if (editor.loading) {
    return (
      <div style={{ padding: 80, textAlign: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!dagId) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <PageHeader
          title="新建流水线"
          actions={
            <Button onClick={() => navigate('/orchestration/pipelines')}>
              返回列表
            </Button>
          }
        />
        <div style={{ maxWidth: 520, margin: 16 }}>
          <Form
            form={pipelineForm}
            layout="vertical"
            initialValues={{ pipelineKind: 'BLANK' as PipelineKind }}
          >
            <Form.Item name="name" label="流水线名称" rules={[{ required: true, message: '必填' }]}>
              <Input autoFocus placeholder="例如：dwd_order_wide_pipeline" />
            </Form.Item>
            <Form.Item name="pipelineKind" label="流水线类型" rules={[{ required: true }]}>
              <Select
                options={[
                  { label: '空白流水线', value: 'BLANK' },
                  { label: 'ODS -> DWD', value: 'ODS_DWD' },
                  { label: '多层建模', value: 'MULTI_LAYER' },
                ]}
              />
            </Form.Item>
            <Space>
              <Button type="primary" onClick={submitCreatePipeline} loading={creatingPipeline}>
                创建
              </Button>
              <Button onClick={() => navigate('/orchestration/pipelines')}>
                取消
              </Button>
            </Space>
          </Form>
        </div>
      </div>
    );
  }

  if (!editor.pipeline) {
    return (
      <Alert
        type="error"
        message="流水线不存在或无权访问"
        action={
          <Button size="small" onClick={() => navigate('/orchestration/pipelines')}>
            返回列表
          </Button>
        }
      />
    );
  }

  const statusTagColor =
    editor.pipeline.status === 'PUBLISHED' ? 'green'
    : editor.pipeline.status === 'VALIDATED' ? 'blue'
    : 'default';
  const closeInspector = () => {
    setDraftPatch(undefined);
    setInspectorHeaderEditing(false);
    editor.setSelectedTaskKey(undefined);
  };
  const deleteSelectedTask = () => {
    if (!editor.selectedTaskKey) return;
    const taskKey = editor.selectedTaskKey;
    modal.confirm({
      title: `删除节点 "${taskKey}"？`,
      content: '相关连线也会一并删除，此操作不可撤销。',
      okText: '删除节点',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: async () => {
        await editor.deleteTask(taskKey);
        closeInspector();
      },
    });
  };
  const inspectorTitle = inspectorProps ? (() => {
    const task = inspectorProps.task;
    const copy = taskTypeCopy[task.taskType];
    const inputCount = editor.edges.filter((edge) => edge.targetKey === task.taskKey).length;
    const outputCount = editor.edges.filter((edge) => edge.sourceKey === task.taskKey).length;
    const runtimeLabel = task.taskType === 'SYNC_REF' ? '数据入口' : task.engine || task.taskType;
    const customDescription = typeof task.config?.uiDescription === 'string' ? task.config.uiDescription : '';
    const displayDescription = customDescription.trim() || copy.summary;
    const runtimeTagColor =
      task.taskType === 'SYNC_REF'
        ? 'blue'
        : task.engine?.includes('SPARK') || task.taskType === 'PYSPARK'
          ? 'orange'
          : undefined;
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 16,
          width: '100%',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, minWidth: 0 }}>
          <div
            style={{
              width: 44,
              height: 44,
              flex: '0 0 44px',
              display: 'grid',
              placeItems: 'center',
              borderRadius: 10,
              border: `1px solid ${copy.color}33`,
              background: `${copy.color}14`,
              color: copy.color,
              fontWeight: 800,
              fontSize: 13,
            }}
          >
            {copy.code}
          </div>
          <div style={{ minWidth: 0, flex: 1 }}>
            {inspectorHeaderEditing ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 32px', gap: 8, maxWidth: 560 }}>
                <Input
                  size="small"
                  value={task.name}
                  placeholder="节点名称"
                  onChange={(e) => inspectorProps.onChange({ taskType: task.taskType, name: e.target.value })}
                />
                <Button
                  type="text"
                  size="small"
                  icon={<CheckOutlined />}
                  aria-label="完成标题编辑"
                  title="完成标题编辑"
                  onClick={() => setInspectorHeaderEditing(false)}
                />
                <Input
                  style={{ gridColumn: '1 / -1' }}
                  size="small"
                  value={customDescription}
                  placeholder={copy.summary}
                  onChange={(e) => inspectorProps.onChange({
                    taskType: task.taskType,
                    config: { ...(task.config ?? {}), uiDescription: e.target.value },
                  })}
                />
              </div>
            ) : (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                  <Text strong ellipsis style={{ fontSize: 18, lineHeight: '24px', maxWidth: 520 }}>
                    配置节点 · {task.name || task.taskKey}
                  </Text>
                  <Button
                    type="text"
                    size="small"
                    icon={<EditOutlined />}
                    aria-label="编辑节点标题和描述"
                    title="编辑节点标题和描述"
                    onClick={() => setInspectorHeaderEditing(true)}
                  />
                </div>
                <Text type="secondary" ellipsis style={{ display: 'block', marginTop: 6, fontSize: 13, maxWidth: 620 }}>
                  {copy.label} · {displayDescription}
                </Text>
              </>
            )}
          </div>
        </div>
        <Space size={8} wrap style={{ justifyContent: 'flex-end' }}>
          <Tag color={statusTagColorOf(task.compileStatus)}>{task.compileStatus ?? 'DRAFT'}</Tag>
          <Tag color={runtimeTagColor}>{runtimeLabel}</Tag>
          <Tag>{inputCount} 入 · {outputCount} 出</Tag>
          {task.targetFqn && <Tag>输出 {task.targetFqn}</Tag>}
        </Space>
      </div>
    );
  })() : '任务配置';

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: 'calc(100vh - 124px)',
        minHeight: 360,
        marginTop: -12,
        overflow: 'hidden',
      }}
    >
      <PageHeader
        style={{ flex: '0 0 auto' }}
        title={
          <Space>
            <Text strong>{editor.pipeline.name}</Text>
            <Tag color={statusTagColor}>{editor.pipeline.status ?? 'DRAFT'}</Tag>
            <Tag>{editor.pipeline.pipelineKind ?? 'BLANK'}</Tag>
            <Text type="secondary" style={{ fontSize: 11 }}>
              {editor.tasks.length} 任务
            </Text>
          </Space>
        }
        actions={
          <Space>
            <Button
              icon={<PlusOutlined />}
              onClick={openCreateEdge}
              disabled={editor.tasks.length < 2}
            >
              添加连线
            </Button>
            <Button
              icon={<CheckCircleOutlined />}
              onClick={editor.validate}
              loading={editor.saving}
            >
              校验
            </Button>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={editor.trigger}
              disabled={editor.tasks.length === 0}
            >
              运行
            </Button>
            <Button icon={<ReloadOutlined />} onClick={editor.reload}>
              刷新
            </Button>
          </Space>
        }
      />

      {editor.validation && !editor.validation.valid && (
        <Alert
          type="error"
          showIcon
          style={{ margin: '0 16px', flex: '0 0 auto' }}
          message="校验未通过"
          description={
            <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
              {editor.validation.taskResults
                .filter((t) => !t.valid)
                .map((t) => (
                  <li key={t.taskKey}>
                    <Tag color="red">{t.errorCode}</Tag>
                    <Text code>{t.taskKey}</Text>: {t.errorMessage}
                  </li>
                ))}
              {editor.validation.graphErrors.map((e, idx) => (
                <li key={`g-${idx}`}>
                  <Tag color="red">{e.code}</Tag>
                  {e.message}
                </li>
              ))}
            </ul>
          }
        />
      )}

      {/* P6-A: live run banner */}
      {editor.activeRunId && editor.latestRun && (
        <Alert
          type="info"
          showIcon
          style={{ margin: '8px 16px', flex: '0 0 auto' }}
          message={`运行中：${editor.latestRun.dagsterRunId?.slice(0, 12) ?? editor.activeRunId.slice(0, 8)}…`}
          description={
            <Space size={16}>
              <Text style={{ fontSize: 12 }}>
                状态: <Tag color="blue">{editor.latestRun.status || 'RUNNING'}</Tag>
              </Text>
              <Text style={{ fontSize: 12 }}>
                节点进度: {editor.taskRuns.filter((t) => t.status === 'SUCCEEDED').length}/
                {editor.taskRuns.length} 成功
              </Text>
              <Text type="secondary" style={{ fontSize: 11 }}>
                每 5s 自动刷新
              </Text>
            </Space>
          }
        />
      )}
      {editor.latestRun && !editor.activeRunId && (
        <Alert
          type={editor.latestRun.status === 'SUCCEEDED' ? 'success' : 'error'}
          showIcon
          style={{ margin: '8px 16px', flex: '0 0 auto' }}
          message={`最近运行: ${editor.latestRun.status}`}
          description={
            <Space size={16}>
              <Text style={{ fontSize: 12 }}>
                节点: {editor.taskRuns.filter((t) => t.status === 'SUCCEEDED').length}/
                {editor.taskRuns.length} 成功
              </Text>
              {editor.latestRun.finishedAt && (
                <Text type="secondary" style={{ fontSize: 11 }}>
                  完成于 {new Date(editor.latestRun.finishedAt).toLocaleString('zh-CN')}
                </Text>
              )}
            </Space>
          }
        />
      )}

      <div
        data-testid="pipeline-editor-workbench"
        style={{
          display: 'flex',
          flex: 1,
          minHeight: 0,
          marginTop: 8,
          overflow: 'hidden',
          borderTop: '1px solid var(--ol-border, #e4e7eb)',
        }}
      >
        {/* Left: Task palette */}
        <div
          style={{
            flex: '0 0 clamp(220px, 20vw, 260px)',
            width: 'clamp(220px, 20vw, 260px)',
            minWidth: 220,
            maxWidth: 260,
            minHeight: 0,
            display: 'flex',
            overflow: 'hidden',
            borderRight: '1px solid var(--ol-border, #e4e7eb)',
            background: 'var(--ol-bg, #fff)',
          }}
        >
          <TaskPalette onAdd={openCreate} disabled={editor.saving} />
        </div>

        {/* Center: DAG canvas */}
        <div
          data-testid="pipeline-canvas-region"
          style={{ flex: 1, minWidth: 0, minHeight: 0, overflow: 'hidden', background: 'var(--ol-fill-soft, #fafbfc)' }}
        >
          <DagCanvasSimple
            tasks={editor.tasks}
            edges={editor.edges}
            selectedKey={editor.selectedTaskKey}
            onSelect={editor.setSelectedTaskKey}
            taskRunByKey={editor.taskRunByKey}
            onDropTask={(meta, position) => openCreate(meta.type, meta, position)}
            onMoveTask={editor.moveTask}
          />
        </div>
      </div>

      <Modal
        title={inspectorTitle}
        open={Boolean(inspectorProps)}
        onCancel={closeInspector}
        width={1110}
        style={{ top: 24, maxWidth: 'calc(100vw - 32px)' }}
        styles={{
          header: {
            margin: 0,
            padding: '20px 24px 16px',
            borderBottom: '1px solid var(--ol-border, #e4e7eb)',
            background: 'linear-gradient(180deg, #fff 0%, #fbfdff 100%)',
          },
          body: {
            padding: 0,
            height: 'calc(100vh - 190px)',
            minHeight: 360,
            overflow: 'hidden',
          },
          footer: {
            margin: 0,
            padding: '12px 22px',
            borderTop: '1px solid var(--ol-border, #e4e7eb)',
          },
          content: {
            padding: 0,
            overflow: 'hidden',
            borderRadius: 14,
          },
        }}
        footer={
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!editor.selectedTaskKey}
              onClick={deleteSelectedTask}
            >
              删除节点
            </Button>
            <Space>
              <Button icon={<CloseOutlined />} onClick={closeInspector}>
                关闭
              </Button>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                onClick={inspectorProps?.onSave}
                loading={inspectorProps?.saving}
                disabled={!draftPatch}
              >
                保存配置
              </Button>
            </Space>
          </div>
        }
        destroyOnHidden
      >
        {inspectorProps && <InspectorRouter {...inspectorProps} />}
      </Modal>

      {/* Create-task modal */}
      <Modal
        title={createMeta ? `新建 ${createMeta.name} 任务` : '新建任务'}
        open={createOpen}
        onOk={submitCreate}
        onCancel={() => {
          setCreateOpen(false);
          setCreatePosition(undefined);
        }}
        okText="创建"
        cancelText="取消"
      >
        <Form form={createForm} layout="vertical" size="small">
          <Form.Item
            name="taskKey"
            label="任务 Key"
            rules={[{ required: true, message: '必填' }, {
              pattern: /^[a-z][a-z0-9_]*$/i,
              message: '字母开头，仅允许字母/数字/下划线',
            }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="name" label="任务名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          {createMeta?.requiresModel && (
            <Form.Item
              name="modelId"
              label="关联模型 ID"
              tooltip="仅用于历史模型任务迁移；新流水线请使用 Spark SQL/PySpark 节点。"
              rules={[{ required: true, message: '历史模型任务必须指定 modelId' }]}
            >
              <Input placeholder="modeling.data_model.id (UUID)" />
            </Form.Item>
          )}
          {!createMeta?.requiresModel && createMeta?.type !== 'QUALITY_GATE' && (
            <Form.Item name="targetFqn" label="目标表 FQN">
              <Input placeholder="iceberg.<schema>.<table>" />
            </Form.Item>
          )}
          <Alert
            type="info"
            showIcon
            message={createMeta?.description}
          />
        </Form>
      </Modal>

      {/* Create data-flow edge modal */}
      <Modal
        title="添加数据流连线"
        open={edgeOpen}
        onOk={submitCreateEdge}
        onCancel={() => setEdgeOpen(false)}
        okText="创建连线"
        cancelText="取消"
      >
        <Form form={edgeForm} layout="vertical" size="small">
          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item
              name="sourceKey"
              label="上游节点"
              rules={[{ required: true, message: '请选择上游节点' }]}
              style={{ flex: 1, minWidth: 180 }}
            >
              <Select
                options={editor.tasks.map((t) => ({
                  label: `${t.name} (${t.taskKey})`,
                  value: t.taskKey,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="targetKey"
              label="下游节点"
              rules={[{ required: true, message: '请选择下游节点' }]}
              style={{ flex: 1, minWidth: 180 }}
            >
              <Select
                options={editor.tasks.map((t) => ({
                  label: `${t.name} (${t.taskKey})`,
                  value: t.taskKey,
                }))}
              />
            </Form.Item>
          </Space>
          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item name="targetInput" label="输入端口" style={{ flex: 1, minWidth: 140 }}>
              <Select
                onChange={(value) => edgeForm.setFieldValue('freshnessPolicy', defaultFreshnessPolicy(value))}
                options={[
                  { label: '默认输入 in', value: 'in' },
                  { label: 'Join 左表 left', value: 'left' },
                  { label: 'Join 右表 right', value: 'right' },
                  { label: '多输入 inputs', value: 'inputs' },
                ]}
              />
            </Form.Item>
            <Form.Item name="inputAlias" label="输入别名" style={{ flex: 1, minWidth: 140 }}>
              <Input placeholder="例如：u / p" />
            </Form.Item>
          </Space>
          <Form.Item
            name="assetFqn"
            label="资产 FQN（可选）"
            tooltip="留空时使用上游节点的产出表 FQN；SYNC_REF 使用引用的 ODS 表。"
          >
            <Input placeholder="onelake.ods.user" />
          </Form.Item>
          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item name="triggerPolicy" label="触发策略" style={{ flex: 1, minWidth: 140 }}>
              <Select
                options={[
                  { label: '全部上游成功', value: 'ALL_SUCCEEDED' },
                  { label: '全部完成', value: 'ALL_DONE' },
                  { label: '任一成功', value: 'ANY_SUCCEEDED' },
                ]}
              />
            </Form.Item>
            <Form.Item name="freshnessPolicy" label="新鲜度" style={{ flex: 1, minWidth: 140 }}>
              <Select
                options={[
                  { label: '使用最新可用', value: 'LATEST' },
                  { label: '同一新鲜度窗口', value: 'SAME_FRESHNESS_WINDOW' },
                  { label: '同批次窗口', value: 'SAME_BATCH' },
                ]}
              />
            </Form.Item>
          </Space>
          <Alert
            type="info"
            showIcon
            message="连线会成为下游节点的输入"
            description="Spark Join 节点会在校验时根据 left/right 入边自动推导 from_tables 并生成 Spark SQL。"
          />
        </Form>
      </Modal>
    </div>
  );
}
