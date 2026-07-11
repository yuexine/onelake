import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  DatePicker,
  Drawer,
  Form,
  InputNumber,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import timezonePlugin from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';
import { DependencyAPI, OrchestrationAPI, SchedulingAPI } from '../../../api';
import { BizError } from '../../../api/http';
import { getAuthUser } from '../../../auth/oidc';
import { StateView } from '../../../components';
import type {
  CreatePipelineDependencyRequest,
  Dag,
  DagScheduling,
  MisfirePolicy,
  PipelineDependency,
  PipelineDependencyOffsetGrain,
  PipelineDependencyType,
  ScheduleCalendar,
  ScheduleWait,
  ScheduleMode,
  UpdateDagSchedulingRequest,
} from '../../../types';

const { Text } = Typography;
const { RangePicker } = DatePicker;

dayjs.extend(utc);
dayjs.extend(timezonePlugin);

interface Props {
  dagId: string;
  open: boolean;
  onClose: () => void;
}

interface SchedulingFormValues {
  timezone: string;
  calendarId?: string;
  catchup: boolean;
  maxActiveRuns: number;
  priority: number;
  scheduleMode: ScheduleMode;
  misfirePolicy: MisfirePolicy;
  dependencyWaitTimeoutMinutes: number;
  slaMinutes?: number;
  timeoutMinutes?: number;
  runRetryCount: number;
  runRetryIntervalSeconds: number;
  scheduleWindow?: [Dayjs | null, Dayjs | null];
}

interface DependencyFormValues {
  upstreamDagId: string;
  dependencyType: PipelineDependencyType;
  offsetGrain?: PipelineDependencyOffsetGrain;
  offsetN?: number;
}

interface UiError {
  message: string;
  noPermission: boolean;
}

const TIMEZONE_OPTIONS = [
  'Asia/Shanghai',
  'Asia/Hong_Kong',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Europe/London',
  'Europe/Berlin',
  'America/New_York',
  'America/Los_Angeles',
  'UTC',
].map((value) => ({ label: value, value }));

const SCHEDULE_MODE_LABEL: Record<ScheduleMode, string> = {
  NORMAL: '正常',
  DRY_RUN: '空跑',
  FROZEN: '冻结',
};

const MISFIRE_POLICY_OPTIONS = [
  { value: 'FIRE_ONCE', label: '有槽位后补触发一次' },
  { value: 'SKIP', label: '直接跳过错过周期' },
];

const GRAIN_LABEL: Record<PipelineDependencyOffsetGrain, string> = {
  HOUR: '小时',
  DAY: '天',
  MONTH: '月',
};

const WALL_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss.SSS';

function toBusinessTime(value: string | undefined, timezone: string) {
  return value ? dayjs(value).tz(timezone) : null;
}

function toInstant(value: Dayjs | null | undefined, timezone: string) {
  return value
    ? dayjs.tz(value.format(WALL_TIME_FORMAT), timezone).toISOString()
    : null;
}

function toUiError(error: unknown): UiError {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const rawMessage = error instanceof Error ? error.message.trim() : '';
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  const technicalMessage = /^(request failed with status code \d+|network error)$/i.test(rawMessage)
    || /(?:timeout|econnrefused|failed to fetch)/i.test(rawMessage);

  let message = rawMessage || '请求失败，请稍后重试。';
  if (status === 401 || code === 40100) {
    message = '登录状态已失效，请重新登录。';
  } else if (status === 403 || code === 40300) {
    message = '当前账号没有管理调度与依赖的权限。';
  } else if (status === 404) {
    message = '未找到当前流水线的调度配置，请刷新页面后重试。';
  } else if ((status !== undefined && status >= 500) || (code !== undefined && code >= 50000)) {
    message = '调度服务暂时不可用，请稍后重试。';
  } else if (technicalMessage) {
    message = '无法连接调度服务，请检查网络后重试。';
  }

  return {
    message,
    noPermission,
  };
}

function createsCycle(
  downstreamDagId: string,
  upstreamDagId: string,
  graph: Map<string, PipelineDependency[]>,
) {
  const pending = [upstreamDagId];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const current = pending.shift()!;
    if (current === downstreamDagId) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    graph.get(current)?.filter((dependency) => dependency.enabled).forEach((dependency) => {
      pending.push(dependency.upstreamDagId);
    });
  }
  return false;
}

export function PipelineSchedulingDrawer({ dagId, open, onClose }: Props) {
  const { message } = AntApp.useApp();
  const [schedulingForm] = Form.useForm<SchedulingFormValues>();
  const [dependencyForm] = Form.useForm<DependencyFormValues>();
  const [scheduling, setScheduling] = useState<DagScheduling>();
  const [calendars, setCalendars] = useState<ScheduleCalendar[]>([]);
  const [dags, setDags] = useState<Dag[]>([]);
  const [dependencies, setDependencies] = useState<PipelineDependency[]>([]);
  const [scheduleWaits, setScheduleWaits] = useState<ScheduleWait[]>([]);
  const [dependencyGraph, setDependencyGraph] = useState<Map<string, PipelineDependency[]>>(new Map());
  const [graphComplete, setGraphComplete] = useState(true);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<UiError>();
  const [saving, setSaving] = useState(false);
  const [creatingDependency, setCreatingDependency] = useState(false);
  const [deletingDependencyId, setDeletingDependencyId] = useState<string>();
  const loadSequenceRef = useRef(0);
  const canManage = getAuthUser()?.roles.includes('DE') ?? false;
  const dependencyType = Form.useWatch('dependencyType', dependencyForm);
  const upstreamDagId = Form.useWatch('upstreamDagId', dependencyForm);
  const runRetryCount = Form.useWatch('runRetryCount', schedulingForm) ?? 0;

  const dagNameById = useMemo(
    () => new Map(dags.map((dag) => [dag.id, dag.name])),
    [dags],
  );

  const cycleWarning = useMemo(() => {
    if (!upstreamDagId) return undefined;
    if (upstreamDagId === dagId) return '流水线不能依赖自身，请选择其他上游。';
    if (createsCycle(dagId, upstreamDagId, dependencyGraph)) {
      return '该上游已通过依赖链指向当前流水线，新增后会形成环路。';
    }
    return undefined;
  }, [dagId, dependencyGraph, upstreamDagId]);

  const applySchedulingToForm = useCallback((value: DagScheduling) => {
    const businessTimezone = value.timezone || 'Asia/Shanghai';
    if (!value.scheduleStart && !value.scheduleEnd) {
      schedulingForm.resetFields(['scheduleWindow']);
    }
    schedulingForm.setFieldsValue({
      timezone: businessTimezone,
      calendarId: value.calendarId,
      catchup: value.catchup,
      maxActiveRuns: value.maxActiveRuns,
      priority: value.priority,
      scheduleMode: value.scheduleMode,
      misfirePolicy: value.misfirePolicy,
      dependencyWaitTimeoutMinutes: value.dependencyWaitTimeoutMinutes,
      slaMinutes: value.slaMinutes,
      timeoutMinutes: value.timeoutMinutes,
      runRetryCount: value.runRetryCount,
      runRetryIntervalSeconds: value.runRetryIntervalSeconds,
      scheduleWindow: value.scheduleStart || value.scheduleEnd
        ? [
          toBusinessTime(value.scheduleStart, businessTimezone),
          toBusinessTime(value.scheduleEnd, businessTimezone),
        ]
        : undefined,
    });
  }, [schedulingForm]);

  const loadData = useCallback(async () => {
    if (!open || !canManage) return;
    const loadSequence = ++loadSequenceRef.current;
    setLoading(true);
    setLoadError(undefined);
    setScheduling(undefined);
    setCalendars([]);
    setDags([]);
    setDependencies([]);
    setScheduleWaits([]);
    setDependencyGraph(new Map());
    setGraphComplete(true);
    schedulingForm.resetFields();
    dependencyForm.resetFields();
    try {
      const [
        [nextScheduling, nextCalendars, nextDags, nextDependencies, nextScheduleWaits],
        [graphResult],
      ] = await Promise.all([
        Promise.all([
          SchedulingAPI.get(dagId),
          SchedulingAPI.listCalendars(),
          OrchestrationAPI.listDags(),
          DependencyAPI.list(dagId),
          SchedulingAPI.listWaits(dagId),
        ]),
        Promise.allSettled([DependencyAPI.listEnabled()]),
      ]);
      if (loadSequence !== loadSequenceRef.current) return;

      setScheduling(nextScheduling);
      setCalendars(nextCalendars);
      setDags(nextDags);
      setDependencies(nextDependencies);
      setScheduleWaits(nextScheduleWaits);
      applySchedulingToForm(nextScheduling);
      dependencyForm.setFieldsValue({
        dependencyType: 'SAME_CYCLE',
        offsetN: 0,
      });

      const graphDependencies = graphResult.status === 'fulfilled'
        ? graphResult.value
        : nextDependencies.filter((dependency) => dependency.enabled);
      const nextGraph = new Map<string, PipelineDependency[]>();
      graphDependencies.filter((dependency) => dependency.enabled).forEach((dependency) => {
        const downstreamDependencies = nextGraph.get(dependency.downstreamDagId) ?? [];
        downstreamDependencies.push(dependency);
        nextGraph.set(dependency.downstreamDagId, downstreamDependencies);
      });
      setDependencyGraph(nextGraph);
      setGraphComplete(graphResult.status === 'fulfilled');
    } catch (error) {
      if (loadSequence === loadSequenceRef.current) {
        setLoadError(toUiError(error));
      }
    } finally {
      if (loadSequence === loadSequenceRef.current) {
        setLoading(false);
      }
    }
  }, [applySchedulingToForm, canManage, dagId, dependencyForm, open, schedulingForm]);

  useEffect(() => {
    if (open) void loadData();
    return () => {
      loadSequenceRef.current += 1;
    };
  }, [loadData, open]);

  const saveScheduling = useCallback(async () => {
    const values = await schedulingForm.validateFields();
    const [scheduleStart, scheduleEnd] = values.scheduleWindow ?? [];
    if (scheduleStart && scheduleEnd && scheduleEnd.isBefore(scheduleStart)) {
      message.error('调度窗口结束时间不能早于开始时间');
      return;
    }
    const payload: UpdateDagSchedulingRequest = {
      timezone: values.timezone,
      calendarId: values.calendarId ?? null,
      catchup: values.catchup,
      maxActiveRuns: values.maxActiveRuns,
      priority: values.priority,
      scheduleMode: values.scheduleMode,
      misfirePolicy: values.misfirePolicy,
      dependencyWaitTimeoutMinutes: values.dependencyWaitTimeoutMinutes,
      slaMinutes: values.slaMinutes ?? null,
      timeoutMinutes: values.timeoutMinutes ?? null,
      runRetryCount: values.runRetryCount,
      runRetryIntervalSeconds: values.runRetryCount > 0 ? values.runRetryIntervalSeconds : 0,
      scheduleStart: toInstant(scheduleStart, values.timezone),
      scheduleEnd: toInstant(scheduleEnd, values.timezone),
    };
    setSaving(true);
    try {
      const updated = await SchedulingAPI.update(dagId, payload);
      setScheduling(updated);
      applySchedulingToForm(updated);
      message.success('调度配置已保存');
    } catch (error) {
      const uiError = toUiError(error);
      if (uiError.noPermission) setLoadError(uiError);
      message.error(`调度配置保存失败：${uiError.message}`);
    } finally {
      setSaving(false);
    }
  }, [applySchedulingToForm, dagId, message, schedulingForm]);

  const createDependency = useCallback(async () => {
    const values = await dependencyForm.validateFields();
    if (values.upstreamDagId === dagId || createsCycle(dagId, values.upstreamDagId, dependencyGraph)) {
      return;
    }
    const payload: CreatePipelineDependencyRequest = {
      upstreamDagId: values.upstreamDagId,
      dependencyType: values.dependencyType,
      offsetGrain: values.dependencyType === 'CROSS_CYCLE' ? values.offsetGrain : undefined,
      offsetN: values.dependencyType === 'CROSS_CYCLE' ? values.offsetN : 0,
    };
    setCreatingDependency(true);
    try {
      const created = await DependencyAPI.create(dagId, payload);
      setDependencies((current) => [...current, created]);
      setDependencyGraph((current) => {
        const next = new Map(current);
        next.set(dagId, [...(next.get(dagId) ?? []), created]);
        return next;
      });
      dependencyForm.resetFields(['upstreamDagId', 'offsetGrain']);
      dependencyForm.setFieldsValue({ dependencyType: 'SAME_CYCLE', offsetN: 0 });
      message.success('上游依赖已添加');
    } catch (error) {
      const uiError = toUiError(error);
      message.error(
        uiError.message.includes('环路')
          ? '新增依赖会形成流水线环路，请调整上游选择'
          : `添加依赖失败：${uiError.message}`,
      );
    } finally {
      setCreatingDependency(false);
    }
  }, [dagId, dependencyForm, dependencyGraph, message]);

  const deleteDependency = useCallback(async (dependencyId: string) => {
    setDeletingDependencyId(dependencyId);
    try {
      await DependencyAPI.delete(dagId, dependencyId);
      setDependencies((current) => current.filter((item) => item.id !== dependencyId));
      setDependencyGraph((current) => {
        const next = new Map(current);
        next.set(dagId, (next.get(dagId) ?? []).filter((item) => item.id !== dependencyId));
        return next;
      });
      message.success('依赖已删除');
    } catch (error) {
      message.error(`删除依赖失败：${toUiError(error).message}`);
    } finally {
      setDeletingDependencyId(undefined);
    }
  }, [dagId, message]);

  const schedulePanel = (
    <Form
      form={schedulingForm}
      layout="vertical"
      requiredMark={false}
      initialValues={{
        timezone: 'Asia/Shanghai',
        catchup: false,
        maxActiveRuns: 1,
        priority: 5,
        scheduleMode: 'NORMAL',
        misfirePolicy: 'FIRE_ONCE',
        dependencyWaitTimeoutMinutes: 1440,
        runRetryCount: 0,
        runRetryIntervalSeconds: 0,
      }}
    >
      <Alert
        type={scheduling?.scheduleMode === 'FROZEN' ? 'warning' : 'info'}
        showIcon
        message={scheduling?.scheduleMode === 'FROZEN' ? '当前流水线已冻结' : '生产调度策略'}
        description={scheduling?.scheduleMode === 'FROZEN'
          ? '周期触发已暂停，且作为上游时会继续阻塞下游依赖。'
          : '时区同时用于 Cron 计划点、业务日期与调度窗口解释。'}
        style={{ marginBottom: 16 }}
      />
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: '0 16px' }}>
        <Form.Item name="timezone" label="业务时区" rules={[{ required: true, message: '请选择业务时区' }]}>
          <Select showSearch options={TIMEZONE_OPTIONS} optionFilterProp="label" />
        </Form.Item>
        <Form.Item name="calendarId" label="调度日历">
          <Select
            allowClear
            placeholder="不绑定日历"
            notFoundContent="暂无可用调度日历"
            options={calendars.map((calendar) => ({
              label: `${calendar.name} · ${calendar.timezone}`,
              value: calendar.id,
            }))}
          />
        </Form.Item>
        <Form.Item name="maxActiveRuns" label="最大并发 Run" rules={[{ required: true }]}>
          <InputNumber min={1} max={100} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="priority" label="调度优先级" rules={[{ required: true }]} tooltip="数值越大，同一调度周期内越先触发">
          <InputNumber min={0} max={100} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="misfirePolicy" label="错过周期策略" rules={[{ required: true }]}>
          <Select options={MISFIRE_POLICY_OPTIONS} />
        </Form.Item>
        <Form.Item
          name="dependencyWaitTimeoutMinutes"
          label="计划点最长等待（分钟）"
          tooltip="适用于上游未就绪和 FIRE_ONCE 等待；超时后保留审计记录但不再触发"
          rules={[{ required: true }]}
        >
          <InputNumber min={1} max={43200} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="slaMinutes" label="SLA（分钟）" tooltip="为空时不监控计划完成时限">
          <InputNumber min={1} max={525600} precision={0} placeholder="不设置" style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="timeoutMinutes" label="运行超时（分钟）" tooltip="为空时不启用超时监控">
          <InputNumber min={1} max={525600} precision={0} placeholder="不设置" style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="runRetryCount"
          label="失败自动重跑次数"
          tooltip="整条流水线失败后的 DAG 级重跑，不影响节点自身的重试策略"
          rules={[{ required: true }]}
        >
          <InputNumber min={0} max={100} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="runRetryIntervalSeconds"
          label="重跑间隔（秒）"
          tooltip="设为 0 表示失败后立即重跑"
          rules={[{ required: true }]}
        >
          <InputNumber min={0} max={604800} precision={0} disabled={runRetryCount === 0} style={{ width: '100%' }} />
        </Form.Item>
      </div>
      <Form.Item name="scheduleMode" label="运行模式" rules={[{ required: true }]}>
        <Segmented
          block
          options={(Object.keys(SCHEDULE_MODE_LABEL) as ScheduleMode[]).map((value) => ({
            value,
            label: SCHEDULE_MODE_LABEL[value],
          }))}
        />
      </Form.Item>
      <Form.Item name="scheduleWindow" label="调度窗口" tooltip="留空表示不限制有效期">
        <RangePicker showTime allowEmpty={[true, true]} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="catchup" label="历史周期补调" valuePropName="checked">
        <Switch checkedChildren="已开启" unCheckedChildren="已关闭" />
      </Form.Item>
      <div style={{ display: 'flex', justifyContent: 'flex-end', paddingTop: 8, borderTop: '1px solid var(--ol-line-soft)' }}>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void saveScheduling()}>
          保存调度配置
        </Button>
      </div>
    </Form>
  );

  const dependencyPanel = (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ border: '1px solid var(--ol-line-soft)', borderRadius: 8, padding: 16, background: 'var(--ol-fill-soft)' }}>
        <Text strong>新增上游依赖</Text>
        <Form
          form={dependencyForm}
          layout="vertical"
          requiredMark={false}
          initialValues={{ dependencyType: 'SAME_CYCLE', offsetN: 0 }}
          style={{ marginTop: 12 }}
        >
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 1fr)', gap: '0 12px' }}>
            <Form.Item
              name="upstreamDagId"
              label="上游流水线"
              rules={[
                { required: true, message: '请选择上游流水线' },
                {
                  validator: async (_, value?: string) => {
                    if (!value) return;
                    if (value === dagId) throw new Error('流水线不能依赖自身');
                    if (createsCycle(dagId, value, dependencyGraph)) throw new Error('新增后会形成流水线环路');
                  },
                },
              ]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择上游"
                options={dags.map((dag) => ({
                  label: dag.id === dagId ? `${dag.name}（当前流水线，不可选）` : dag.name,
                  value: dag.id,
                  disabled: dag.id === dagId,
                }))}
              />
            </Form.Item>
            <Form.Item name="dependencyType" label="周期关系" rules={[{ required: true }]}>
              <Segmented
                block
                options={[
                  { label: '同周期', value: 'SAME_CYCLE' },
                  { label: '跨周期', value: 'CROSS_CYCLE' },
                ]}
              />
            </Form.Item>
          </div>
          {dependencyType === 'CROSS_CYCLE' && (
            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: '0 12px' }}>
              <Form.Item name="offsetGrain" label="偏移粒度" rules={[{ required: true, message: '请选择偏移粒度' }]}>
                <Select options={(Object.keys(GRAIN_LABEL) as PipelineDependencyOffsetGrain[]).map((value) => ({ label: GRAIN_LABEL[value], value }))} />
              </Form.Item>
              <Form.Item name="offsetN" label="偏移量" rules={[{ required: true, message: '请输入偏移量' }]} tooltip="-1 表示依赖上游前一个周期">
                <InputNumber precision={0} min={-999} max={999} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          )}
          {cycleWarning && <Alert type="warning" showIcon message={cycleWarning} style={{ marginBottom: 12 }} />}
          {!graphComplete && (
            <Alert
              type="warning"
              showIcon
              message="部分依赖图加载失败，提交时仍会由服务端执行完整成环校验。"
              style={{ marginBottom: 12 }}
            />
          )}
          <Button
            type="primary"
            icon={<PlusOutlined />}
            loading={creatingDependency}
            disabled={Boolean(cycleWarning)}
            onClick={() => void createDependency()}
          >
            添加依赖
          </Button>
        </Form>
      </div>

      <Table<PipelineDependency>
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={dependencies}
        columns={[
          {
            title: '上游流水线',
            dataIndex: 'upstreamDagId',
            render: (value: string) => <Text strong>{dagNameById.get(value) ?? value}</Text>,
          },
          {
            title: '周期关系',
            width: 180,
            render: (_, dependency) => dependency.dependencyType === 'SAME_CYCLE'
              ? <Tag color="blue">同周期</Tag>
              : <Space size={4}><Tag color="purple">跨周期</Tag><Text type="secondary">{dependency.offsetN} {GRAIN_LABEL[dependency.offsetGrain!]}</Text></Space>,
          },
          {
            title: '操作',
            width: 72,
            align: 'right',
            render: (_, dependency) => (
              <Popconfirm
                title="删除此上游依赖？"
                description="后续计划点将不再等待该上游。"
                okText="删除"
                cancelText="保留"
                okButtonProps={{ danger: true, loading: deletingDependencyId === dependency.id }}
                onConfirm={() => deleteDependency(dependency.id)}
              >
                <Button type="text" danger size="small" icon={<DeleteOutlined />} aria-label="删除依赖" />
              </Popconfirm>
            ),
          },
        ]}
        locale={{
          emptyText: (
            <StateView
              state="empty"
              title="暂无上游依赖"
              description="当前流水线到点后无需等待其他流水线，可按需添加同周期或跨周期依赖。"
              cta={<Button icon={<PlusOutlined />} onClick={() => dependencyForm.focusField('upstreamDagId')}>添加第一条依赖</Button>}
            />
          ),
        }}
      />
    </Space>
  );

  const waitPanel = (
    <Table<ScheduleWait>
      rowKey="id"
      size="small"
      pagination={{ pageSize: 10, hideOnSinglePage: true }}
      dataSource={scheduleWaits}
      columns={[
        {
          title: '状态',
          dataIndex: 'status',
          width: 110,
          render: (status: string) => {
            const color = status === 'RESOLVED' ? 'green'
              : status === 'WAITING' ? 'blue'
                : status === 'TIMED_OUT' ? 'red' : 'default';
            return <Tag color={color}>{status}</Tag>;
          },
        },
        {
          title: '原因',
          dataIndex: 'waitReason',
          width: 120,
          render: (reason: string) => reason === 'MISFIRE' ? '并发限流' : '上游依赖',
        },
        {
          title: '业务日期',
          dataIndex: 'logicalDate',
          width: 180,
          render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
        },
        {
          title: '最近原因',
          dataIndex: 'lastBlockers',
          ellipsis: true,
          render: (value?: string) => value || '—',
        },
        {
          title: '失效时间',
          dataIndex: 'expiresAt',
          width: 180,
          render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
        },
      ]}
      locale={{
        emptyText: (
          <StateView
            state="empty"
            title="暂无等待计划点"
            description="当前没有因上游依赖或最大并发限制而等待的调度周期。"
          />
        ),
      }}
    />
  );

  let content;
  if (!canManage) {
    content = (
      <StateView
        state="no-permission"
        title="无权管理调度与依赖"
        description="仅数据工程师（DE）可以修改生产调度策略和流水线依赖。"
      />
    );
  } else if (loading && !scheduling) {
    content = <StateView state="loading" rows={8} />;
  } else if (loadError) {
    content = (
      <StateView
        state={loadError.noPermission ? 'no-permission' : 'error'}
        title={loadError.noPermission ? '无权管理调度与依赖' : '调度配置加载失败'}
        description={loadError.message}
        onRetry={() => void loadData()}
      />
    );
  } else {
    content = (
      <Tabs
        items={[
          { key: 'scheduling', label: '调度配置', children: schedulePanel },
          { key: 'dependencies', label: `依赖 (${dependencies.length})`, icon: <LinkOutlined />, children: dependencyPanel },
          { key: 'waits', label: `等待记录 (${scheduleWaits.length})`, children: waitPanel },
        ]}
      />
    );
  }

  return (
    <Drawer
      title="调度配置"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={canManage && !loading && !loadError ? (
        <Button icon={<ReloadOutlined />} onClick={() => void loadData()}>
          刷新
        </Button>
      ) : undefined}
    >
      {content}
    </Drawer>
  );
}
