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
  Steps,
  Tag,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  CheckOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  LoadingOutlined,
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
import type { PipelineKind, PipelineTask, PipelineTaskEdgeRequest, PipelineTaskRequest, PipelineTaskType, PipelineValidationResult } from '../../../types';

const { Text } = Typography;

const defaultFreshnessPolicy = (targetInput?: string) =>
  targetInput === 'left' || targetInput === 'right' ? 'SAME_FRESHNESS_WINDOW' : 'LATEST';

const taskTypeCopy: Record<PipelineTaskType, { code: string; label: string; summary: string; color: string }> = {
  SYNC_REF: { code: 'IN', label: '同步引用', summary: '引用已采集的 ODS 表作为数据流起点', color: '#0ea5e9' },
  SPARK_SQL: { code: 'SQL', label: 'Spark SQL', summary: '使用 Spark SQL 处理上游数据并产出表', color: '#4f46e5' },
  PYSPARK: { code: 'PY', label: 'PySpark', summary: '使用 PySpark 脚本处理上游数据并产出表', color: '#7c3aed' },
  QUALITY_GATE: { code: 'QA', label: '质量门禁', summary: '校验上游产出表并阻断异常数据', color: '#16a34a' },
};

const validationSteps = [
  { title: '基础信息', description: '确认流水线状态、任务数量和基础配置' },
  { title: '拓扑关系', description: '检查环路、悬空节点和上下游依赖' },
  { title: '边与端口', description: '校验输入输出端口、基数和 fan-in/fan-out' },
  { title: '节点配置', description: '检查 Spark SQL、治理规则和质量门禁必填项' },
  { title: '编译准备', description: '推导输入资产、产出表和 Spark 执行配置' },
];

function statusTagColorOf(status: string | undefined) {
  if (status === 'VALIDATED') return 'green';
  if (status === 'FAILED') return 'red';
  return 'default';
}

function invalidTaskResults(result?: PipelineValidationResult) {
  return result?.taskResults.filter((item) => !item.valid) ?? [];
}

function taskTypeLabelOf(type: PipelineTaskType) {
  return taskTypeCopy[type]?.label ?? type;
}

function taskValidationCopy(task: PipelineValidationResult['taskResults'][number]) {
  const message = task.errorMessage ?? '';
  const normalized = message.toLowerCase();
  const taskTypeLabel = taskTypeLabelOf(task.taskType);

  if (normalized.includes('requires non-empty config.sql')) {
    return {
      label: '缺少 SQL 内容',
      description: `${taskTypeLabel} 节点未配置可执行的 SQL 语句。`,
      suggestion: '请打开节点详情，补充 SQL 处理逻辑后重新校验。',
    };
  }
  if (normalized.includes('requires non-empty config.script')) {
    return {
      label: '缺少脚本内容',
      description: `${taskTypeLabel} 节点未配置可执行的 PySpark 脚本。`,
      suggestion: '请打开节点详情，补充脚本内容后重新校验。',
    };
  }
  if (normalized.includes('requires targetfqn')) {
    return {
      label: '缺少产出表',
      description: `${taskTypeLabel} 节点未指定产出表，系统无法建立目录、血缘和质量检查关系。`,
      suggestion: '请填写产出表 FQN，例如 onelake.dwd.order_wide。',
    };
  }
  if (normalized.includes('quality_gate task requires non-empty config')) {
    return {
      label: '缺少质量规则',
      description: '质量门禁节点尚未配置检测规则。',
      suggestion: '请添加主键、非空、枚举、范围或自定义 SQL 等质量规则后重新校验。',
    };
  }
  if (normalized.includes('quality_gate task requires targetfqn') || normalized.includes('config.targetmodelfqn')) {
    return {
      label: '缺少检测目标',
      description: '质量门禁节点未指定需要检测的上游表或目标表。',
      suggestion: '请从上游连线继承输入表，或在节点详情中填写检测目标表。',
    };
  }
  if (normalized.includes('config.gates')) {
    return {
      label: '缺少质量规则',
      description: '质量门禁节点至少需要一条可执行的规则。',
      suggestion: '请新增质量规则后重新校验。',
    };
  }
  if (normalized.includes('sync_ref task requires targetfqn')) {
    return {
      label: '缺少上游表',
      description: '同步引用节点未选择已采集的 ODS 表。',
      suggestion: '请从资产库选择上游表，或填写已存在的 ODS 表 FQN。',
    };
  }
  if (task.errorCode === 'C1_VIOLATION') {
    return {
      label: '链路契约不满足',
      description: '当前节点的输入、输出或依赖关系不符合流水线契约。',
      suggestion: '请检查节点连线、输入端口和产出表配置。',
    };
  }
  if (task.errorCode === 'MODEL_REQUIRED') {
    return {
      label: '缺少关联模型',
      description: '当前节点需要关联模型后才能参与编译和运行。',
      suggestion: '请在节点详情中选择模型，或改用 Spark 数据流节点。',
    };
  }
  if (task.errorCode === 'MODEL_NOT_VALIDATED') {
    return {
      label: '模型未校验',
      description: '关联模型尚未通过校验，不能作为当前流水线的稳定输入。',
      suggestion: '请先完成模型校验，再重新校验流水线。',
    };
  }
  if (task.errorCode === 'SYNC_REF_INCOMPLETE') {
    return {
      label: '采集引用不完整',
      description: '同步引用节点缺少采集任务或上游表信息。',
      suggestion: '请绑定采集任务，或选择已加载完成的 ODS 表。',
    };
  }

  return {
    label: '配置不完整',
    description: `${taskTypeLabel} 节点存在未满足的必填配置。`,
    suggestion: '请检查节点详情中的必填项、输入表和产出表后重新校验。',
  };
}

function validationGraphIssues(result?: PipelineValidationResult) {
  return result?.graphErrors ?? [];
}

function edgeContractIssues(result?: PipelineValidationResult) {
  return validationGraphIssues(result).filter((issue) => /EDGE|PORT|INPUT|OUTPUT|FAN/i.test(`${issue.code} ${issue.message}`));
}

function topologyIssues(result?: PipelineValidationResult) {
  const edgeIssues = new Set(edgeContractIssues(result));
  return validationGraphIssues(result).filter((issue) => !edgeIssues.has(issue));
}

function validationStepStatus(
  index: number,
  activeStep: number,
  running: boolean,
  result?: PipelineValidationResult,
  requestError?: string,
): 'wait' | 'process' | 'finish' | 'error' {
  if (running) {
    return index <= activeStep ? 'process' : 'wait';
  }
  if (requestError) {
    const failedStep = Math.min(activeStep, validationSteps.length - 1);
    return index === failedStep ? 'error' : 'wait';
  }
  if (!result) return 'wait';
  const topologyProblems = topologyIssues(result);
  const edgeProblems = edgeContractIssues(result);
  const taskIssues = invalidTaskResults(result);
  const errorSteps = new Set<number>();
  if (topologyProblems.length > 0) errorSteps.add(1);
  if (edgeProblems.length > 0) errorSteps.add(2);
  if (taskIssues.length > 0) errorSteps.add(3);
  if (!result.valid && errorSteps.size === 0) errorSteps.add(4);
  if (errorSteps.has(index)) return 'error';
  if ([...errorSteps].some((step) => step < index)) return 'wait';
  return 'finish';
}

function ValidationModal({
  open,
  running,
  activeStep,
  result,
  tasks,
  requestError,
  onClose,
  onValidate,
}: {
  open: boolean;
  running: boolean;
  activeStep: number;
  result?: PipelineValidationResult;
  tasks: PipelineTask[];
  requestError?: string;
  onClose: () => void;
  onValidate: () => void;
}) {
  const taskIssues = invalidTaskResults(result);
  const graphIssues = validationGraphIssues(result);
  const taskByKey = new Map(tasks.map((task) => [task.taskKey, task]));
  const hasResult = Boolean(result || requestError);
  const summaryTone = requestError || (result && !result.valid) ? 'error' : result?.valid ? 'success' : 'running';
  const summaryTitle = requestError
    ? '校验请求失败'
    : result?.valid
      ? '校验通过'
      : result
        ? '校验未通过'
        : '正在校验流水线';
  const summaryDescription = requestError
    ? requestError
    : result
      ? `${result.taskResults.length} 个节点已检查，${taskIssues.length + graphIssues.length} 个问题需要处理。`
      : '系统正在检查拓扑、边契约、节点配置和 Spark 编译准备状态。';

  return (
    <Modal
      title="流水线校验"
      open={open}
      width={760}
      onCancel={running ? undefined : onClose}
      closable={!running}
      maskClosable={!running}
      footer={[
        <Button key="close" onClick={onClose} disabled={running}>
          关闭
        </Button>,
        <Button key="validate" type="primary" onClick={onValidate} loading={running}>
          {running ? '校验中' : hasResult ? '重新校验' : '开始校验'}
        </Button>,
      ]}
    >
      <div style={{ display: 'grid', gap: 18 }}>
        <div
          style={{
            alignItems: 'center',
            background: summaryTone === 'success' ? 'var(--ol-success-soft)' : summaryTone === 'error' ? '#fff7f7' : 'var(--ol-fill-soft)',
            border: `1px solid ${summaryTone === 'success' ? 'rgba(22, 163, 74, 0.22)' : summaryTone === 'error' ? 'rgba(239, 68, 68, 0.24)' : 'var(--ol-line-soft)'}`,
            borderRadius: 10,
            display: 'flex',
            gap: 12,
            padding: '14px 16px',
          }}
        >
          <span
            style={{
              alignItems: 'center',
              background: summaryTone === 'success' ? 'var(--ol-success)' : summaryTone === 'error' ? 'var(--ol-error)' : 'var(--ol-info)',
              borderRadius: '50%',
              color: '#fff',
              display: 'inline-flex',
              flexShrink: 0,
              height: 34,
              justifyContent: 'center',
              width: 34,
            }}
          >
            {running ? <LoadingOutlined spin /> : summaryTone === 'success' ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
          </span>
          <Space direction="vertical" size={2} style={{ minWidth: 0 }}>
            <Text strong style={{ fontSize: 15 }}>{summaryTitle}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>{summaryDescription}</Text>
          </Space>
        </div>

        <Steps
          direction="vertical"
          size="small"
          current={Math.min(activeStep, validationSteps.length - 1)}
          items={validationSteps.map((step, index) => {
            const status = validationStepStatus(index, activeStep, running, result, requestError);
            return {
              title: step.title,
              description: step.description,
              status,
              icon: running && index === activeStep ? <LoadingOutlined /> : undefined,
            };
          })}
        />

        {requestError && (
          <div style={{ border: '1px solid rgba(239, 68, 68, 0.22)', borderRadius: 8, padding: 12 }}>
            <Text type="danger" strong>校验服务返回异常</Text>
            <div style={{ marginTop: 6, fontSize: 12, color: 'var(--ol-ink-2)' }}>{requestError}</div>
          </div>
        )}

        {result?.valid && (
          <div style={{ border: '1px solid rgba(22, 163, 74, 0.22)', borderRadius: 8, padding: 12 }}>
            <Text strong style={{ color: 'var(--ol-success)' }}>全部校验项已通过</Text>
            <div style={{ marginTop: 6, fontSize: 12, color: 'var(--ol-ink-2)' }}>
              当前流水线拓扑、节点配置和 Spark 编译准备状态均满足运行要求。
            </div>
          </div>
        )}

        {result && !result.valid && (
          <div style={{ display: 'grid', gap: 12 }}>
            {graphIssues.length > 0 && (
              <div style={{ border: '1px solid var(--ol-line-soft)', borderRadius: 8, padding: 12 }}>
                <Text strong>拓扑与边契约问题</Text>
                <div style={{ display: 'grid', gap: 8, marginTop: 10 }}>
                  {graphIssues.map((issue, index) => (
                    <div key={`${issue.code}-${index}`} style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                      <Tag color={issue.level === 'WARN' ? 'warning' : 'red'} style={{ margin: 0 }}>{issue.code}</Tag>
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontSize: 12, color: 'var(--ol-ink)' }}>{issue.message}</div>
                        {issue.taskKeys.length > 0 && (
                          <Text type="secondary" style={{ fontSize: 11 }}>相关节点：{issue.taskKeys.join('、')}</Text>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {taskIssues.length > 0 && (
              <div style={{ border: '1px solid var(--ol-line-soft)', borderRadius: 8, padding: 12 }}>
                <Text strong>节点配置异常</Text>
                <div style={{ display: 'grid', gap: 8, marginTop: 10 }}>
                  {taskIssues.map((task) => {
                    const copy = taskValidationCopy(task);
                    const node = taskByKey.get(task.taskKey);
                    const nodeName = node?.name || task.taskKey;
                    return (
                      <div key={task.taskKey} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                        <Tag color="red" style={{ margin: 0, flexShrink: 0 }}>{copy.label}</Tag>
                        <div style={{ minWidth: 0 }}>
                          <Space size={6} wrap>
                            <Text strong style={{ fontSize: 13 }}>{nodeName}</Text>
                            <Tag style={{ margin: 0 }}>{taskTypeLabelOf(task.taskType)}</Tag>
                          </Space>
                          <div style={{ marginTop: 4, fontSize: 12, color: 'var(--ol-ink)' }}>
                            {copy.description}
                          </div>
                          <div style={{ marginTop: 2, fontSize: 12, color: 'var(--ol-ink-2)' }}>
                            处理建议：{copy.suggestion}
                          </div>
                          {nodeName !== task.taskKey && (
                            <Text type="secondary" style={{ display: 'block', marginTop: 2, fontSize: 11 }}>
                              节点 Key：{task.taskKey}
                            </Text>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
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
  const [validationOpen, setValidationOpen] = useState(false);
  const [validationRunning, setValidationRunning] = useState(false);
  const [validationActiveStep, setValidationActiveStep] = useState(0);
  const [validationResult, setValidationResult] = useState<PipelineValidationResult | undefined>(undefined);
  const [validationRequestError, setValidationRequestError] = useState<string | undefined>(undefined);

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

  const runValidation = useCallback(async () => {
    setValidationOpen(true);
    setValidationRunning(true);
    setValidationResult(undefined);
    setValidationRequestError(undefined);
    setValidationActiveStep(0);

    const timers = validationSteps.map((_, index) => (
      window.setTimeout(() => setValidationActiveStep(index), index * 360)
    ));
    const minimumDuration = new Promise((resolve) => {
      window.setTimeout(resolve, validationSteps.length * 360 + 240);
    });

    try {
      const [result] = await Promise.all([editor.validate(), minimumDuration]);
      if (result) {
        setValidationResult(result);
        setValidationActiveStep(validationSteps.length);
      } else {
        setValidationRequestError('未获取到校验结果，请确认流水线已加载完成后重试。');
        setValidationActiveStep(validationSteps.length - 1);
      }
    } catch (err) {
      setValidationRequestError((err as Error).message || '校验请求失败');
      setValidationActiveStep(validationSteps.length - 1);
    } finally {
      timers.forEach((timer) => window.clearTimeout(timer));
      setValidationRunning(false);
    }
  }, [editor]);

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
              onClick={runValidation}
              loading={validationRunning}
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

      <ValidationModal
        open={validationOpen}
        running={validationRunning}
        activeStep={validationActiveStep}
        result={validationResult}
        tasks={editor.tasks}
        requestError={validationRequestError}
        onClose={() => setValidationOpen(false)}
        onValidate={runValidation}
      />

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
