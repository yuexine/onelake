/**
 * 数据源列表（对应原型 §4.2.2 / §8.2.1 升级版）。
 *   - PageHeader + KPI 行 + FilterBar + SectionCard + Table
 *   - 测连：色点 + RTT，loading 时旋转图标
 *   - 行内 hover 高亮、批量选择
 */
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Table, Tag, Space, Input, Select, Drawer, Form, message,
  Tooltip, Button, Dropdown, Switch, InputNumber, Alert, Typography,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, ThunderboltOutlined, DeleteOutlined,
  EditOutlined, EllipsisOutlined, KeyOutlined, CopyOutlined, LockOutlined,
  DatabaseOutlined, ApiOutlined,
} from '@ant-design/icons';
import { dataSources } from '../../mock';
import type { DataSource } from '../../types';
import {
  PageHeader, FilterBar, SectionCard, Toolbar, StatusBadge,
  EntityTypeIcon,
} from '../../components';

const { Text } = Typography;

const ENV_COLOR: Record<string, { bg: string; fg: string }> = {
  PROD: { bg: 'var(--ol-error-soft)', fg: 'var(--ol-error)' },
  TEST: { bg: 'var(--ol-warning-soft)', fg: '#B45309' },
  DEV:  { bg: 'var(--ol-fill-soft)', fg: 'var(--ol-ink-3)' },
};

export default function DatasourceList() {
  const navigate = useNavigate();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [filterType, setFilterType] = useState<string>();
  const [filterHealth, setFilterHealth] = useState<string>();
  const [filterEnv, setFilterEnv] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [form] = Form.useForm();

  const filtered = useMemo(() => dataSources.filter((r) =>
    (!filterType || r.type === filterType) &&
    (!filterHealth || r.health === filterHealth) &&
    (!filterEnv || r.envLevel === filterEnv) &&
    (!keyword || r.name.toLowerCase().includes(keyword.toLowerCase()) || r.host.includes(keyword)),
  ), [filterType, filterHealth, filterEnv, keyword]);

  const counts = useMemo(() => ({
    total: dataSources.length,
    ok: dataSources.filter((d) => d.health === 'OK').length,
    fail: dataSources.filter((d) => d.health === 'FAIL').length,
    prod: dataSources.filter((d) => d.envLevel === 'PROD').length,
  }), []);

  const handleTest = (r: DataSource) => {
    setTestingId(r.id);
    setTimeout(() => {
      setTestingId(null);
      message.success({
        content: `${r.name} 连通成功 · RTT ${r.rttMs}ms`,
        icon: <ThunderboltOutlined style={{ color: '#16A34A' }} />,
      });
    }, 700);
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
      render: (e: string) => {
        const c = ENV_COLOR[e] || ENV_COLOR.DEV;
        return (
          <span
            style={{
              display: 'inline-flex', padding: '1px 8px', borderRadius: 4,
              fontSize: 11, fontWeight: 600, lineHeight: '18px',
              background: c.bg, color: c.fg,
            }}
          >
            {e}
          </span>
        );
      },
    },
    {
      title: '状态', dataIndex: 'health', width: 150,
      render: (h: string, r: DataSource) => (
        <Space size={8}>
          <StatusBadge
            status={h === 'OK' ? 'SUCCEEDED' : h === 'FAIL' ? 'FAILED' : 'PENDING'}
            label={h === 'OK' ? '连通' : h === 'FAIL' ? '异常' : '未知'}
          />
          {testingId === r.id ? (
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
              loading={testingId === r.id}
              onClick={() => handleTest(r)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              size="small" type="text" icon={<EditOutlined />}
              onClick={() => setDrawerOpen(true)}
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
              if (key === 'del') message.warning(`${r.name}：需先确认无活跃任务`);
              else message.success(`${key} · ${r.name}`);
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
            <Button icon={<ReloadOutlined />} onClick={() => message.success('已刷新')}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setDrawerOpen(true)}>新建连接</Button>
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
            <Button icon={<ThunderboltOutlined />} onClick={() => message.info('正在测连…')}>测连</Button>
            <Button type="primary" onClick={() => {
              form.validateFields().then(() => {
                setDrawerOpen(false);
                message.success('已创建（mock）');
              });
            }}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" requiredMark="optional">
          <Form.Item label="选择类型" required>
            <Select
              placeholder="选择数据源类型"
              options={['MYSQL', 'POSTGRES', 'HIVE', 'KAFKA', 'S3'].map((t) => ({
                label: <Space size={8}><EntityTypeIcon kind={t} size={20} /> {t}</Space>,
                value: t,
              }))}
            />
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
            <Form.Item label="Host" name="host" rules={[{ required: true }]}><Input placeholder="10.0.0.1" /></Form.Item>
            <Form.Item label="Port" name="port" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} placeholder="3306" /></Form.Item>
          </div>

          <Form.Item label="库名 / Topic" name="dbName"><Input placeholder="order_db" /></Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item label="账号" name="username" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true }]}
              extra={<Text type="secondary" style={{ fontSize: 12 }}><LockOutlined /> 加密存储，不回显</Text>}
            >
              <Input.Password />
            </Form.Item>
          </div>

          <Form.Item label="绑定租户 / 项目" required>
            <Select
              mode="multiple"
              placeholder="选择租户和项目"
              options={[
                { label: '交易事业部 / 订单域', value: 'tp-1' },
                { label: '风控中心 / 风控域', value: 'tp-2' },
              ]}
            />
          </Form.Item>

          <Alert
            type="warning" showIcon
            icon={<LockOutlined />}
            style={{ marginTop: 8, borderRadius: 6 }}
            message={<span style={{ fontSize: 12 }}>检测到写权限账号，建议使用只读账号以避免下游误操作</span>}
          />
        </Form>
      </Drawer>
    </div>
  );
}
