/**
 * 数据源列表（对应原型 §4.2.2 / §8.2.1 升级版）。
 *   - PageHeader + KPI 行 + FilterBar + SectionCard + Table
 *   - 测连：色点 + RTT，loading 时旋转图标
 *   - 行内 hover 高亮、批量选择
 */
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Table, Tag, Space, Input, Select, Drawer, Form, message,
  Tooltip, Button, Dropdown, Switch, InputNumber, Alert, Typography, AutoComplete,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, ThunderboltOutlined, DeleteOutlined,
  EditOutlined, EllipsisOutlined, KeyOutlined, CopyOutlined, LockOutlined,
  DatabaseOutlined, ApiOutlined,
} from '@ant-design/icons';
import { dataSources as mockDataSources } from '../../mock';
import type { DataSource } from '../../types';
import { IntegrationAPI, SystemAPI } from '../../api';
import {
  PageHeader, FilterBar, SectionCard, Toolbar, StatusBadge, StateView,
  EntityTypeIcon, IntentBadge, useAsyncAction, envColor,
} from '../../components';
import { useAppStore } from '../../stores/app';

const { Text } = Typography;

type DataSourceFormType = 'MYSQL' | 'POSTGRES' | 'HIVE' | 'KAFKA' | 'S3';
type FieldKind = 'input' | 'password' | 'number' | 'select' | 'switch';
type FieldDef = {
  name: string;
  label: string;
  kind: FieldKind;
  required?: boolean;
  placeholder?: string;
  defaultValue?: unknown;
  options?: { label: string; value: string }[];
  span?: 1 | 2;
};

const DATA_SOURCE_TYPES: DataSourceFormType[] = ['MYSQL', 'POSTGRES', 'HIVE', 'KAFKA', 'S3'];
const DATABASE_FIELD_TYPES: DataSourceFormType[] = ['MYSQL', 'POSTGRES', 'HIVE'];
const DATABASE_PROBE_TYPES: DataSourceFormType[] = ['MYSQL', 'POSTGRES'];

const NETWORK_MODE_OPTIONS = [
  { label: 'DIRECT', value: 'DIRECT' },
  { label: 'VPC', value: 'VPC', disabled: true },
  { label: 'SSH_TUNNEL', value: 'SSH_TUNNEL', disabled: true },
];

const DATA_SOURCE_FORM_SCHEMAS: Record<DataSourceFormType, FieldDef[]> = {
  MYSQL: [
    { name: 'host', label: 'Host', kind: 'input', required: true, placeholder: '10.0.0.1' },
    { name: 'port', label: 'Port', kind: 'number', required: true, defaultValue: 3306, placeholder: '3306' },
    { name: 'username', label: '账号', kind: 'input', required: true },
    { name: 'password', label: '密码', kind: 'password', placeholder: '加密存储，不回显' },
  ],
  POSTGRES: [
    { name: 'host', label: 'Host', kind: 'input', required: true, placeholder: '10.0.0.1' },
    { name: 'port', label: 'Port', kind: 'number', required: true, defaultValue: 5432, placeholder: '5432' },
    { name: 'username', label: '账号', kind: 'input', required: true },
    { name: 'password', label: '密码', kind: 'password', placeholder: '加密存储，不回显' },
  ],
  HIVE: [
    { name: 'host', label: 'HiveServer2 Host', kind: 'input', required: true, placeholder: 'hive.internal' },
    { name: 'port', label: 'Port', kind: 'number', required: true, defaultValue: 10000, placeholder: '10000' },
    {
      name: 'authMode',
      label: '认证方式',
      kind: 'select',
      defaultValue: 'SIMPLE',
      options: [{ label: 'SIMPLE', value: 'SIMPLE' }, { label: 'KERBEROS', value: 'KERBEROS' }],
    },
    { name: 'username', label: '账号', kind: 'input' },
    { name: 'principal', label: 'Kerberos Principal', kind: 'input', placeholder: 'hive/_HOST@EXAMPLE.COM' },
  ],
  KAFKA: [
    { name: 'bootstrapServers', label: 'Bootstrap Servers', kind: 'input', required: true, placeholder: 'kafka-1:9092,kafka-2:9092', span: 2 },
    { name: 'topicPattern', label: 'Topic Pattern', kind: 'input', placeholder: 'orders.*' },
    {
      name: 'securityProtocol',
      label: '安全协议',
      kind: 'select',
      defaultValue: 'PLAINTEXT',
      options: [
        { label: 'PLAINTEXT', value: 'PLAINTEXT' },
        { label: 'SASL_PLAINTEXT', value: 'SASL_PLAINTEXT' },
        { label: 'SASL_SSL', value: 'SASL_SSL' },
      ],
    },
    {
      name: 'saslMechanism',
      label: 'SASL 机制',
      kind: 'select',
      options: [
        { label: 'PLAIN', value: 'PLAIN' },
        { label: 'SCRAM-SHA-256', value: 'SCRAM-SHA-256' },
        { label: 'SCRAM-SHA-512', value: 'SCRAM-SHA-512' },
      ],
    },
    { name: 'saslUsername', label: 'SASL 账号', kind: 'input' },
    { name: 'saslPassword', label: 'SASL 密码', kind: 'password', placeholder: '加密存储，不回显' },
  ],
  S3: [
    { name: 'endpoint', label: 'Endpoint', kind: 'input', placeholder: 'https://s3.example.com', span: 2 },
    { name: 'bucket', label: 'Bucket', kind: 'input', required: true, placeholder: 'raw-zone' },
    { name: 'region', label: 'Region', kind: 'input', placeholder: 'cn-north-1' },
    { name: 'prefix', label: 'Prefix', kind: 'input', placeholder: 'orders/', span: 2 },
    { name: 'accessKey', label: 'Access Key', kind: 'input' },
    { name: 'secretKey', label: 'Secret Key', kind: 'password', placeholder: '加密存储，不回显' },
    { name: 'pathStyleAccess', label: 'Path Style', kind: 'switch' },
  ],
};

const NETWORK_MODE_FORM_SCHEMAS: Record<string, FieldDef[]> = {
  DIRECT: [],
  VPC: [
    { name: 'networkAccessRef', label: '网络接入点', kind: 'input', required: true, placeholder: 'vpc-access-prod', span: 2 },
  ],
  SSH_TUNNEL: [
    { name: 'sshHost', label: 'SSH Host', kind: 'input', required: true, placeholder: 'bastion.internal' },
    { name: 'sshPort', label: 'SSH Port', kind: 'number', required: true, defaultValue: 22, placeholder: '22' },
    { name: 'sshUsername', label: 'SSH 账号', kind: 'input', required: true },
    {
      name: 'sshAuthType',
      label: 'SSH 认证方式',
      kind: 'select',
      required: true,
      defaultValue: 'PRIVATE_KEY',
      options: [
        { label: 'PRIVATE_KEY', value: 'PRIVATE_KEY' },
        { label: 'PASSWORD', value: 'PASSWORD' },
      ],
    },
    { name: 'sshPrivateKeyRef', label: '私钥密钥引用', kind: 'input', placeholder: 'vault://onelake/bastion/private-key', span: 2 },
    { name: 'sshPasswordRef', label: '密码密钥引用', kind: 'input', placeholder: 'vault://onelake/bastion/password', span: 2 },
  ],
};

const defaultValuesForType = (type: DataSourceFormType) => DATA_SOURCE_FORM_SCHEMAS[type].reduce<Record<string, unknown>>((acc, field) => {
  if (field.defaultValue !== undefined) {
    acc[field.name] = field.defaultValue;
  }
  return acc;
}, {});

const defaultValuesForNetworkMode = (networkMode: string) => (NETWORK_MODE_FORM_SCHEMAS[networkMode] || []).reduce<Record<string, unknown>>((acc, field) => {
  if (field.defaultValue !== undefined) {
    acc[field.name] = field.defaultValue;
  }
  return acc;
}, {});

const connectionFields = (type: DataSourceFormType, networkMode: string) => [
  ...DATA_SOURCE_FORM_SCHEMAS[type],
  ...(NETWORK_MODE_FORM_SCHEMAS[networkMode] || []),
];

const createPayload = (values: Record<string, unknown>) => {
  const type = (values.type || 'MYSQL') as DataSourceFormType;
  const networkMode = String(values.networkMode || 'DIRECT');
  const config = connectionFields(type, networkMode).reduce<Record<string, unknown>>((acc, field) => {
    const value = values[field.name];
    if (value !== undefined && value !== null && value !== '') {
      acc[field.name] = value;
    }
    return acc;
  }, {});
  if (DATABASE_FIELD_TYPES.includes(type) && values.dbName) {
    config.dbName = values.dbName;
  }
  return {
    name: values.name,
    type,
    config,
    projectId: values.projectId,
    networkMode,
    envLevel: values.envLevel || 'PROD',
  };
};

const probePayload = (values: Record<string, unknown>) => {
  const payload = createPayload(values);
  return {
    type: payload.type,
    networkMode: payload.networkMode,
    config: payload.config,
  };
};

const fieldRequired = (field: FieldDef, sshAuthType?: string) => {
  if (field.name === 'sshPrivateKeyRef') {
    return sshAuthType !== 'PASSWORD';
  }
  if (field.name === 'sshPasswordRef') {
    return sshAuthType === 'PASSWORD';
  }
  return Boolean(field.required);
};

export default function DatasourceList() {
  const navigate = useNavigate();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [filterType, setFilterType] = useState<string>();
  const [filterHealth, setFilterHealth] = useState<string>();
  const [filterEnv, setFilterEnv] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dataSources, setDataSources] = useState<DataSource[]>(mockDataSources);
  const [systemContext, setSystemContext] = useState<Awaited<ReturnType<typeof SystemAPI.context>>>();
  const [databaseOptions, setDatabaseOptions] = useState<{ label: string; value: string }[]>([]);
  const [databaseProbeLoading, setDatabaseProbeLoading] = useState(false);
  const [drawerTestLoading, setDrawerTestLoading] = useState(false);
  const [drawerWritePrivilegeDetected, setDrawerWritePrivilegeDetected] = useState(false);
  const [form] = Form.useForm();
  const selectedType = (Form.useWatch('type', form) || 'MYSQL') as DataSourceFormType;
  const selectedNetworkMode = String(Form.useWatch('networkMode', form) || 'DIRECT');
  const selectedSshAuthType = Form.useWatch('sshAuthType', form);
  const tenant = useAppStore((s) => s.tenant);
  const databaseFieldVisible = DATABASE_FIELD_TYPES.includes(selectedType);

  const openCreateDrawer = () => {
    form.resetFields();
    form.setFieldsValue({
      type: 'MYSQL',
      networkMode: 'DIRECT',
      envLevel: 'PROD',
      ...defaultValuesForType('MYSQL'),
    });
    setDrawerWritePrivilegeDetected(false);
    setDrawerOpen(true);
  };

  const loadDatasources = () => {
    IntegrationAPI.listDatasources()
      .then(setDataSources)
      .catch((e) => message.error(e.message || '连接列表加载失败'));
  };

  const handleProbeDatabases = () => {
    if (!DATABASE_PROBE_TYPES.includes(selectedType)) {
      message.info('当前类型暂不支持库列表探查，可手动输入');
      return;
    }
    const requiredConnectionFieldNames = connectionFields(selectedType, selectedNetworkMode)
      .filter((field) => fieldRequired(field, selectedSshAuthType))
      .map((field) => field.name);
    setDatabaseProbeLoading(true);
    form.validateFields(['type', 'networkMode', ...requiredConnectionFieldNames])
      .then((values) => IntegrationAPI.probeDatabases(probePayload({ ...form.getFieldsValue(), ...values })))
      .then((result) => {
        const options = (result.databases || []).map((name) => ({ label: name, value: name }));
        setDatabaseOptions(options);
        if (options.length) {
          message.success(`发现 ${options.length} 个库，可下拉选择`);
        } else {
          message.info(result.message || '未发现可选数据库，可手动输入');
        }
      })
      .catch((e) => {
        message.error(e.message || '库列表探查失败，可手动输入');
      })
      .finally(() => setDatabaseProbeLoading(false));
  };

  const handleDrawerTest = () => {
    const requiredConnectionFieldNames = connectionFields(selectedType, selectedNetworkMode)
      .filter((field) => fieldRequired(field, selectedSshAuthType))
      .map((field) => field.name);
    const requiredFieldNames = [
      'type',
      'networkMode',
      ...requiredConnectionFieldNames,
      ...(databaseFieldVisible ? ['dbName'] : []),
    ];
    setDrawerTestLoading(true);
    form.validateFields(requiredFieldNames)
      .then((values) => IntegrationAPI.testDatasourceConfig(probePayload({ ...form.getFieldsValue(), ...values })))
      .then((result) => {
        if (!result.ok) {
          throw new Error(result.message || '连接失败');
        }
        const writePrivilegeDetected = Boolean(
          result.writePrivilegeDetected || result.diagnostics?.writePrivilegeDetected,
        );
        setDrawerWritePrivilegeDetected(writePrivilegeDetected);
        message.success(`测连成功${result.rttMillis != null ? ` · ${result.rttMillis}ms` : ''}`);
      })
      .catch((e) => {
        setDrawerWritePrivilegeDetected(false);
        message.error(e.message || '测连失败，请检查连接信息');
      })
      .finally(() => setDrawerTestLoading(false));
  };

  useEffect(() => {
    loadDatasources();
    SystemAPI.context()
      .then(setSystemContext)
      .catch((e) => message.error(e.message || '租户项目上下文加载失败'));
  }, []);

  const filtered = useMemo(() => dataSources.filter((r) =>
    (!filterType || r.type === filterType) &&
    (!filterHealth || r.health === filterHealth) &&
    (!filterEnv || r.envLevel === filterEnv) &&
    (!keyword || r.name.toLowerCase().includes(keyword.toLowerCase()) || r.host.includes(keyword)),
  ), [dataSources, filterType, filterHealth, filterEnv, keyword]);

  const counts = useMemo(() => ({
    total: dataSources.length,
    ok: dataSources.filter((d) => d.health === 'OK').length,
    fail: dataSources.filter((d) => d.health === 'FAIL').length,
    prod: dataSources.filter((d) => d.envLevel === 'PROD').length,
  }), [dataSources]);

  const { run, isLoading } = useAsyncAction();

  const handleTest = (r: DataSource) => {
    run(`test-${r.id}`, async () => {
      const result = await IntegrationAPI.testDatasource(r.id);
      setDataSources((rows) => rows.map((item) => item.id === r.id
        ? { ...item, health: result.ok ? 'OK' : 'FAIL', rttMs: result.rttMillis, lastCheckAt: new Date().toISOString() }
        : item));
      if (!result.ok) throw new Error(result.message || '连接失败');
      return result;
    }, {
      successMsg: `${r.name} 连通成功`,
      errorMsg: `${r.name} 连接失败，请检查账号密码或网络`,
      duration: 2.5,
    });
  };

  const handleDelete = (r: DataSource) => {
    IntegrationAPI.deleteDatasource(r.id)
      .then(() => {
        setDataSources((rows) => rows.filter((item) => item.id !== r.id));
        message.success(`已删除 ${r.name}`);
      })
      .catch((e) => message.error(e.message || `${r.name} 删除失败`));
  };

  const columns = [
    {
      title: '名称', dataIndex: 'name',
      render: (n: string, r: DataSource) => (
        <Space size={10}>
          <EntityTypeIcon kind={r.type} size={32} />
          <div style={{ minWidth: 0 }}>
            <a
              className="ol-link ol-truncate"
              style={{ display: 'inline-block', maxWidth: 220, fontSize: 13, fontWeight: 500 }}
              onClick={() => navigate(`/integration/datasources/${r.id}`)}
            >
              {n}
            </a>
            <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }} className="ol-truncate">
              {r.host}:{r.port}{r.dbName ? ` · ${r.dbName}` : ''}
            </div>
          </div>
        </Space>
      ),
    },
    {
      title: '类型', dataIndex: 'type', width: 110,
      render: (t: string) => <span className="ol-chip">{t}</span>,
    },
    {
      title: '环境', dataIndex: 'envLevel', width: 84,
      render: (e: string) => <IntentBadge intent={envColor[e] || 'neutral'}>{e}</IntentBadge>,
    },
    {
      title: '状态', dataIndex: 'health', width: 150,
      render: (h: string, r: DataSource) => (
        <Space size={8}>
          <StatusBadge
            status={h === 'OK' ? 'SUCCEEDED' : h === 'FAIL' ? 'FAILED' : 'PENDING'}
            label={h === 'OK' ? '连通' : h === 'FAIL' ? '异常' : '未知'}
          />
          {isLoading(`test-${r.id}`) ? (
            <ReloadOutlined spin style={{ color: 'var(--ol-brand)', fontSize: 12 }} />
          ) : (
            r.rttMs != null && (
              <span className="mono ol-quiet" style={{ fontSize: 11 }}>
                {r.rttMs}ms
              </span>
            )
          )}
        </Space>
      ),
    },
    {
      title: '负责人', dataIndex: 'username', width: 100,
      render: (u: string) => <span style={{ color: 'var(--ol-ink-2)' }}>{u}</span>,
    },
    {
      title: '最近检查', dataIndex: 'lastCheckAt', width: 140,
      render: (t?: string) => (
        <span style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>
          {t ? new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}
        </span>
      ),
    },
    {
      title: '操作', key: 'actions', width: 140, fixed: 'right' as const,
      render: (_: unknown, r: DataSource) => (
        <Space size={4}>
          <Tooltip title="测连">
            <Button
              size="small" type="text"
              icon={<ThunderboltOutlined />}
              loading={isLoading(`test-${r.id}`)}
              onClick={() => handleTest(r)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              size="small" type="text" icon={<EditOutlined />}
              onClick={openCreateDrawer}
            />
          </Tooltip>
          <Button size="small" type="link" onClick={() => navigate(`/integration/datasources/${r.id}`)}>详情</Button>
          <Dropdown trigger={['click']} menu={{
            items: [
              { key: 'copy', icon: <CopyOutlined />, label: '复制配置' },
              { key: 'rotate', icon: <KeyOutlined />, label: '轮换密钥' },
              { type: 'divider' as const },
              { key: 'del', icon: <DeleteOutlined />, label: '删除', danger: true },
            ],
            onClick: ({ key }) => {
              if (key === 'del') handleDelete(r);
              else if (key === 'copy') message.warning({ content: '复制配置待接入：将预填新建抽屉', duration: 3 });
              else if (key === 'rotate') message.warning({ content: '密钥轮换待接入：需 KMS rotate API 联动', duration: 3 });
            },
          }}>
            <Button size="small" type="text" icon={<EllipsisOutlined />} />
          </Dropdown>
        </Space>
      ),
    },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<DatabaseOutlined />}
        title="连接管理"
        subtitle={<span className="ol-chip">数据集成 · L1</span>}
        description="统一管理业务库、消息队列、对象存储与文件源的连接，支持连通性探活、密钥轮换、写权限告警"
        meta={[
          { label: '总连接', value: counts.total },
          { label: '连通', value: counts.ok },
          { label: '异常', value: counts.fail },
          { label: '生产环境', value: counts.prod },
        ]}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={loadDatasources}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateDrawer}>新建连接</Button>
          </>
        }
      />

      <FilterBar
        search={{ placeholder: '搜索名称 / Host', value: keyword, onChange: setKeyword }}
        filters={
          <>
            <Select placeholder="类型" allowClear style={{ width: 140 }} value={filterType} onChange={setFilterType}
              options={['MYSQL', 'POSTGRES', 'HIVE', 'KAFKA', 'S3'].map((t) => ({ label: t, value: t }))} />
            <Select placeholder="健康状态" allowClear style={{ width: 140 }} value={filterHealth} onChange={setFilterHealth}
              options={[{ label: '连通', value: 'OK' }, { label: '异常', value: 'FAIL' }, { label: '未知', value: 'UNKNOWN' }]} />
            <Select placeholder="环境" allowClear style={{ width: 120 }} value={filterEnv} onChange={setFilterEnv}
              options={['PROD', 'TEST', 'DEV'].map((t) => ({ label: t, value: t }))} />
          </>
        }
        summary={<span className="ol-quiet" style={{ fontSize: 12 }}>共 {filtered.length} 条</span>}
        onReset={() => { setFilterType(undefined); setFilterHealth(undefined); setFilterEnv(undefined); setKeyword(''); }}
      />

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div style={{ padding: '8px 16px 0' }}>
          <Toolbar
            selectedCount={selectedKeys.length}
            bulkActions={[
              <Button size="small" icon={<ThunderboltOutlined />} onClick={() => message.success(`已批量测连 ${selectedKeys.length}`)}>批量测连</Button>,
              <Button size="small" icon={<DeleteOutlined />} danger onClick={() => message.warning('批量删除需确认')}>批量删除</Button>,
            ]}
            right={
              <>
                <span className="ol-quiet" style={{ fontSize: 12 }}>显示 {filtered.length} / {dataSources.length}</span>
              </>
            }
          />
        </div>

        <Table
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无连接"
                description="尝试调整筛选条件，或新建第一个数据源连接"
                cta={<Button type="primary" icon={<PlusOutlined />} onClick={openCreateDrawer}>新建连接</Button>}
              />
            ),
          }}
          pagination={{
            pageSize: 20, showSizeChanger: true,
            showTotal: (t) => <span className="ol-quiet" style={{ fontSize: 12 }}>共 {t} 条</span>,
          }}
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: setSelectedKeys,
          }}
          size="middle"
          scroll={{ x: 1100 }}
        />
      </SectionCard>

      <Drawer
        title={<Space><PlusOutlined /> 新建数据源连接</Space>}
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button icon={<ThunderboltOutlined />} loading={drawerTestLoading} onClick={handleDrawerTest}>测连</Button>
            <Button type="primary" onClick={() => {
              form.validateFields().then((values) => IntegrationAPI.createDatasource(createPayload(values))).then((created) => {
                setDataSources((rows) => [created, ...rows]);
                setDrawerOpen(false);
                form.resetFields();
                message.success('已创建');
              }).catch((e) => {
                message.error(e.message || '创建失败');
              });
            }}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" requiredMark="optional">
          <Form.Item label="选择类型" name="type" required rules={[{ required: true }]}>
            <Select
              placeholder="选择数据源类型"
              onChange={(type: DataSourceFormType) => {
                setDatabaseOptions([]);
                setDrawerWritePrivilegeDetected(false);
                form.setFieldsValue({ dbName: undefined, ...defaultValuesForType(type) });
              }}
              options={DATA_SOURCE_TYPES.map((t) => ({
                label: <Space size={8}><EntityTypeIcon kind={t} size={20} /> {t}</Space>,
                value: t,
              }))}
            />
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item label="连接名称" name="name" rules={[{ required: true, message: '请输入连接名称' }]}>
              <Input placeholder="orders-prod" />
            </Form.Item>
            <Form.Item label="环境" name="envLevel" rules={[{ required: true }]}>
              <Select options={['PROD', 'TEST', 'DEV'].map((t) => ({ label: t, value: t }))} />
            </Form.Item>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item label="当前租户">
              <Input value={systemContext?.tenant?.name || tenant.name} disabled />
            </Form.Item>
            <Form.Item label="绑定项目" name="projectId">
              <Select
                allowClear
                placeholder={(systemContext?.projects || []).length ? '选择项目（可选）' : '暂无项目，可留空'}
                options={(systemContext?.projects || []).map((p) => ({
                  label: `${p.name} (${p.code})`,
                  value: p.id,
                }))}
              />
            </Form.Item>
          </div>

          <Form.Item label="网络模式" name="networkMode" rules={[{ required: true }]}>
            <Select
              onChange={(networkMode) => {
                setDrawerWritePrivilegeDetected(false);
                form.setFieldsValue(defaultValuesForNetworkMode(networkMode));
              }}
              options={NETWORK_MODE_OPTIONS}
            />
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            {connectionFields(selectedType, selectedNetworkMode).map((field) => (
              <Form.Item
                key={field.name}
                label={field.label}
                name={field.name}
                valuePropName={field.kind === 'switch' ? 'checked' : undefined}
                rules={fieldRequired(field, selectedSshAuthType) ? [{ required: true, message: `请输入${field.label}` }] : undefined}
                extra={field.kind === 'password' ? (
                  <Text type="secondary" style={{ fontSize: 12 }}><LockOutlined /> 加密存储，不回显</Text>
                ) : undefined}
                style={field.span === 2 ? { gridColumn: '1 / span 2' } : undefined}
              >
                {field.kind === 'number' ? (
                  <InputNumber style={{ width: '100%' }} placeholder={field.placeholder} />
                ) : field.kind === 'select' ? (
                  <Select placeholder={field.placeholder || `选择${field.label}`} options={field.options || []} />
                ) : field.kind === 'password' ? (
                  <Input.Password placeholder={field.placeholder} />
                ) : field.kind === 'switch' ? (
                  <Switch />
                ) : (
                  <Input placeholder={field.placeholder} />
                )}
              </Form.Item>
            ))}
          </div>

          {databaseFieldVisible && (
            <Form.Item
              label={selectedType === 'HIVE' ? '数据库' : '库名'}
              name="dbName"
              rules={[{ required: true, message: `请输入或选择${selectedType === 'HIVE' ? '数据库' : '库名'}` }]}
              style={{ marginTop: 12 }}
            >
              <AutoComplete
                allowClear
                options={databaseOptions}
                placeholder={selectedType === 'HIVE' ? 'default' : '可探查后下拉选择，也可手动输入'}
              >
                <Input
                  suffix={
                    <Button
                      size="small"
                      type="link"
                      loading={databaseProbeLoading}
                      disabled={!DATABASE_PROBE_TYPES.includes(selectedType)}
                      onClick={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        handleProbeDatabases();
                      }}
                    >
                      探查库列表
                    </Button>
                  }
                />
              </AutoComplete>
            </Form.Item>
          )}

          <Alert
            type={drawerWritePrivilegeDetected ? 'warning' : 'info'} showIcon
            icon={<LockOutlined />}
            style={{ marginTop: 8, borderRadius: 6 }}
            message={(
              <span style={{ fontSize: 12 }}>
                {drawerWritePrivilegeDetected
                  ? '检测到写权限账号，建议使用只读账号以避免下游误操作'
                  : '建议使用只读账号接入，避免下游误操作'}
              </span>
            )}
          />
        </Form>
      </Drawer>
    </div>
  );
}
