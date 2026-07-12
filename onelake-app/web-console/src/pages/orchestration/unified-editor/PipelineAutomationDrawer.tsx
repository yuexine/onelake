import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { OrchestrationAPI, SubscriptionAPI } from '../../../api';
import { BizError } from '../../../api/http';
import { getAuthUser } from '../../../auth/oidc';
import { StateView } from '../../../components';
import { color, space } from '../../../components/tokens';
import type {
  CreatePipelineSubscriptionRequest,
  Dag,
  PipelineSubscription,
  PipelineSubscriptionCondition,
  PipelineSubscriptionFreshnessPolicy,
  PipelineSubscriptionSourceType,
} from '../../../types';

const { Text } = Typography;

interface Props {
  dagId: string;
  open: boolean;
  onClose: () => void;
}

interface UiError {
  message: string;
  noPermission: boolean;
}

const CONDITION_LABEL: Record<PipelineSubscriptionCondition, string> = {
  ON_UPDATE: '仅更新',
  ON_UPDATE_AND_QUALITY_PASS: '更新且质量通过',
};

const FRESHNESS_LABEL: Record<PipelineSubscriptionFreshnessPolicy, string> = {
  LATEST: '使用最新可用',
  SAME_BATCH: '同一批次',
  SAME_FRESHNESS_WINDOW: '同一新鲜度窗口',
};

function toUiError(error: unknown): UiError {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const rawMessage = error instanceof Error ? error.message.trim() : '';
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  const technicalMessage = /^(request failed with status code \d+|network error)$/i.test(rawMessage)
    || /(?:timeout|econnrefused|failed to fetch|socket hang up)/i.test(rawMessage);

  if (status === 401 || code === 40100) {
    return { message: '登录状态已失效，请重新登录。', noPermission: true };
  }
  if (status === 403 || code === 40300) {
    return { message: '当前账号没有管理流水线自动化订阅的权限。', noPermission: true };
  }
  if (status === 404 || code === 40400) {
    return { message: '未找到当前流水线或自动化订阅接口，请刷新后重试。', noPermission: false };
  }
  if (status === 409 || code === 40900) {
    return { message: rawMessage || '相同来源的订阅已存在，请勿重复添加。', noPermission: false };
  }
  if ((status !== undefined && status >= 500) || (code !== undefined && code >= 50000)) {
    return { message: '自动化订阅服务暂时不可用，请稍后重试。', noPermission: false };
  }
  if (technicalMessage) {
    return { message: '无法连接自动化订阅服务，请检查网络后重试。', noPermission: false };
  }
  return { message: rawMessage || '自动化订阅请求失败，请稍后重试。', noPermission };
}

export function PipelineAutomationDrawer({ dagId, open, onClose }: Props) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<CreatePipelineSubscriptionRequest>();
  const canManage = getAuthUser()?.roles.includes('DE') ?? false;
  const [subscriptions, setSubscriptions] = useState<PipelineSubscription[]>([]);
  const [dags, setDags] = useState<Dag[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<UiError>();
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string>();
  const loadSequenceRef = useRef(0);
  const sourceType = Form.useWatch('sourceType', form);

  const dagNameById = useMemo(
    () => new Map(dags.map((dag) => [dag.id, dag.name])),
    [dags],
  );

  const loadData = useCallback(async () => {
    if (!open || !canManage) return;
    const sequence = ++loadSequenceRef.current;
    setLoading(true);
    setLoadError(undefined);
    try {
      const [nextSubscriptions, nextDags] = await Promise.all([
        SubscriptionAPI.list(dagId),
        OrchestrationAPI.listDags(),
      ]);
      if (sequence !== loadSequenceRef.current) return;
      setSubscriptions(nextSubscriptions);
      setDags(nextDags.filter((dag) => dag.id !== dagId));
    } catch (error) {
      if (sequence === loadSequenceRef.current) setLoadError(toUiError(error));
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

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({
      sourceType: 'ASSET',
      condition: 'ON_UPDATE',
      freshnessPolicy: 'LATEST',
    });
    setCreateOpen(true);
  };

  const createSubscription = async () => {
    let values: CreatePipelineSubscriptionRequest;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setCreating(true);
    try {
      const created = await SubscriptionAPI.create(dagId, {
        ...values,
        sourceRef: values.sourceRef.trim(),
      });
      setSubscriptions((current) => [created, ...current]);
      setCreateOpen(false);
      message.success('自动化订阅已添加');
    } catch (error) {
      const uiError = toUiError(error);
      if (uiError.noPermission) {
        setCreateOpen(false);
        setLoadError(uiError);
      } else {
        message.error(uiError.message);
      }
    } finally {
      setCreating(false);
    }
  };

  const deleteSubscription = async (subscription: PipelineSubscription) => {
    setDeletingId(subscription.id);
    try {
      await SubscriptionAPI.delete(dagId, subscription.id);
      setSubscriptions((current) => current.filter((item) => item.id !== subscription.id));
      message.success('自动化订阅已删除');
    } catch (error) {
      const uiError = toUiError(error);
      if (uiError.noPermission) setLoadError(uiError);
      else message.error(uiError.message);
    } finally {
      setDeletingId(undefined);
    }
  };

  let content;
  if (!canManage) {
    content = (
      <StateView
        state="no-permission"
        title="无权管理自动化"
        description="仅数据工程师（DE）可以查看、添加或删除流水线自动化订阅。"
      />
    );
  } else if (loading) {
    content = <StateView state="loading" rows={6} />;
  } else if (loadError) {
    content = (
      <StateView
        state={loadError.noPermission ? 'no-permission' : 'error'}
        title={loadError.noPermission ? '无权管理自动化' : '自动化订阅加载失败'}
        description={loadError.message}
        onRetry={() => void loadData()}
      />
    );
  } else if (subscriptions.length === 0) {
    content = (
      <StateView
        state="empty"
        title="暂无自动化规则"
        description="添加资产或上游流水线订阅后，当前流水线可按更新、质量和新鲜度条件自动运行。"
        cta={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加第一条订阅</Button>}
      />
    );
  } else {
    content = (
      <Table<PipelineSubscription>
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={subscriptions}
        scroll={{ x: 850 }}
        columns={[
          {
            title: '触发来源',
            key: 'source',
            width: 250,
            render: (_value, record) => (
              <Space direction="vertical" size={2} style={{ maxWidth: 230 }}>
                <Tag color={record.sourceType === 'ASSET' ? 'blue' : 'purple'}>
                  {record.sourceType === 'ASSET' ? '资产 FQN' : '上游流水线'}
                </Tag>
                <Text code copyable ellipsis={{ tooltip: record.sourceRef }}>
                  {record.sourceType === 'PIPELINE'
                    ? dagNameById.get(record.sourceRef) ?? record.sourceRef
                    : record.sourceRef}
                </Text>
              </Space>
            ),
          },
          {
            title: '触发条件',
            dataIndex: 'condition',
            width: 170,
            render: (value: PipelineSubscriptionCondition) => (
              <Tag color={value === 'ON_UPDATE_AND_QUALITY_PASS' ? 'green' : 'default'}>
                {CONDITION_LABEL[value] ?? value}
              </Tag>
            ),
          },
          {
            title: '新鲜度',
            dataIndex: 'freshnessPolicy',
            width: 190,
            render: (value: PipelineSubscriptionFreshnessPolicy) => (
              <Space direction="vertical" size={2}>
                <Text>{FRESHNESS_LABEL[value] ?? value}</Text>
                <Text type="secondary" style={{ fontSize: 11 }}>{value}</Text>
              </Space>
            ),
          },
          {
            title: '状态',
            dataIndex: 'enabled',
            width: 80,
            render: (enabled: boolean) => <Tag color={enabled ? 'success' : 'default'}>{enabled ? '启用' : '停用'}</Tag>,
          },
          {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 160,
            render: (value: string) => new Date(value).toLocaleString('zh-CN'),
          },
          {
            title: '操作',
            key: 'actions',
            width: 80,
            fixed: 'right',
            render: (_value, record) => (
              <Popconfirm
                title="删除这条自动化订阅？"
                description="删除后，该来源不再按此规则触发当前流水线。"
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => deleteSubscription(record)}
              >
                <Button
                  danger
                  type="text"
                  size="small"
                  icon={<DeleteOutlined />}
                  loading={deletingId === record.id}
                  aria-label={`删除订阅 ${record.sourceRef}`}
                />
              </Popconfirm>
            ),
          },
        ]}
      />
    );
  }

  return (
    <>
      <Drawer
        title={<Space><ThunderboltOutlined style={{ color: color.warning }} />自动化</Space>}
        width={920}
        open={open}
        onClose={() => {
          setCreateOpen(false);
          onClose();
        }}
        destroyOnHidden
        extra={canManage && !loading && !loadError ? (
          <Space>
            <Button icon={<ReloadOutlined />} disabled={Boolean(deletingId)} onClick={() => void loadData()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              添加订阅
            </Button>
          </Space>
        ) : undefined}
      >
        {canManage && !loading && !loadError && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: space.lg }}
            message="资产感知的声明式自动化"
            description="来源更新后，系统按质量门和新鲜度策略判断是否自动运行当前流水线。"
          />
        )}
        {content}
      </Drawer>

      <Modal
        title="添加自动化订阅"
        open={createOpen}
        okText="添加订阅"
        cancelText="取消"
        confirmLoading={creating}
        onOk={() => void createSubscription()}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <Form<CreatePipelineSubscriptionRequest>
          form={form}
          layout="vertical"
          requiredMark="optional"
          style={{ marginTop: space.lg }}
        >
          <Form.Item<PipelineSubscriptionSourceType>
            name="sourceType"
            label="来源类型"
            rules={[{ required: true, message: '请选择来源类型' }]}
          >
            <Select
              options={[
                { value: 'ASSET', label: '资产 FQN' },
                { value: 'PIPELINE', label: '上游流水线' },
              ]}
              onChange={() => form.setFieldValue('sourceRef', undefined)}
            />
          </Form.Item>

          <Form.Item
            name="sourceRef"
            label={sourceType === 'PIPELINE' ? '上游流水线' : '资产 FQN'}
            rules={[
              { required: true, whitespace: true, message: sourceType === 'PIPELINE' ? '请选择上游流水线' : '请输入资产 FQN' },
              { max: 512, message: '来源标识不能超过 512 个字符' },
            ]}
          >
            {sourceType === 'PIPELINE' ? (
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择上游流水线"
                options={dags.map((dag) => ({ value: dag.id, label: `${dag.name} · ${dag.id}` }))}
                notFoundContent="暂无其他可订阅流水线"
              />
            ) : (
              <Input placeholder="例如 onelake.ods.mall_orders" />
            )}
          </Form.Item>

          <Form.Item<PipelineSubscriptionCondition>
            name="condition"
            label="触发条件"
            rules={[{ required: true, message: '请选择触发条件' }]}
          >
            <Select
              options={[
                { value: 'ON_UPDATE', label: '仅更新' },
                { value: 'ON_UPDATE_AND_QUALITY_PASS', label: '更新且质量通过' },
              ]}
            />
          </Form.Item>

          <Form.Item<PipelineSubscriptionFreshnessPolicy>
            name="freshnessPolicy"
            label="新鲜度策略"
            rules={[{ required: true, message: '请选择新鲜度策略' }]}
          >
            <Select
              options={Object.entries(FRESHNESS_LABEL).map(([value, label]) => ({
                value,
                label: `${label} · ${value}`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
