/**
 * 数据源列表（对应原型 §4.2.2 / §8.2.1）。
 * FilterBar + Table + 行内测连 + 新建抽屉。
 */
import { Button, Table, Tag, Space, Input, Select, Drawer, Form, message, Card, Tooltip } from 'antd';
import { PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { dataSources } from '../../mock';
import type { DataSource } from '../../types';
import { StatusBadge } from '../../components';

const TYPE_ICON: Record<string, string> = {
  MYSQL: '🐬', POSTGRES: '🐘', HIVE: '🐝', KAFKA: '🚀', S3: '📁', FTP: '📁', SFTP: '📁',
};

export default function DatasourceList() {
  const navigate = useNavigate();
  const [rows] = useState<DataSource[]>(dataSources);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [filterType, setFilterType] = useState<string>();
  const [filterHealth, setFilterHealth] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [form] = Form.useForm();

  const filtered = rows.filter((r) =>
    (!filterType || r.type === filterType) &&
    (!filterHealth || r.health === filterHealth) &&
    (!keyword || r.name.toLowerCase().includes(keyword.toLowerCase()) || r.host.includes(keyword))
  );

  const columns = [
    { title: '类型', dataIndex: 'type', width: 100,
      render: (t: string) => <Space>{TYPE_ICON[t]}<Tag>{t}</Tag></Space> },
    { title: '名称', dataIndex: 'name', render: (n: string, r: DataSource) => <a onClick={() => navigate(`/integration/datasources/${r.id}`)}>{n}</a> },
    { title: 'Host', dataIndex: 'host' },
    { title: '环境', dataIndex: 'envLevel', width: 80,
      render: (e: string) => <Tag color={e === 'PROD' ? 'red' : e === 'TEST' ? 'orange' : 'default'}>{e}</Tag> },
    { title: '状态', dataIndex: 'health', width: 120,
      render: (h: string, r: DataSource) => <Space><StatusBadge status={h === 'OK' ? 'SUCCEEDED' : h === 'FAIL' ? 'FAILED' : 'PENDING'} label={h === 'OK' ? '连通' : h === 'FAIL' ? '异常' : '未知'} />{r.rttMs && <span style={{ color: '#8c8c8c' }}>{r.rttMs}ms</span>}</Space> },
    { title: '负责人', dataIndex: 'username', width: 100 },
    {
      title: '操作', width: 220,
      render: (_: unknown, r: DataSource) => (
        <Space>
          <Tooltip title="测连">
            <Button size="small" icon={<ThunderboltOutlined />} onClick={() => message.success(`已测连 ${r.name} (RTT ${r.rttMs}ms)`)}>测连</Button>
          </Tooltip>
          <Button size="small" type="link" onClick={() => navigate(`/integration/datasources/${r.id}`)}>详情</Button>
          <Button size="small" type="link" danger onClick={() => message.warning(`删除 ${r.name} 需先确认无活跃任务`)}>删除</Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Card
        title="数据集成 / 连接管理"
        extra={<Space><Button icon={<ReloadOutlined />} onClick={() => message.success('已刷新')}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => setDrawerOpen(true)}>新建连接</Button></Space>}
      >
        <Space style={{ marginBottom: 16 }}>
          <Input.Search placeholder="搜索名称/host" allowClear onSearch={setKeyword} style={{ width: 240 }} />
          <Select placeholder="类型" allowClear style={{ width: 140 }} onChange={setFilterType}
            options={['MYSQL', 'POSTGRES', 'HIVE', 'KAFKA', 'S3'].map((t) => ({ label: t, value: t }))} />
          <Select placeholder="状态" allowClear style={{ width: 140 }} onChange={setFilterHealth}
            options={[{ label: '连通', value: 'OK' }, { label: '异常', value: 'FAIL' }, { label: '未知', value: 'UNKNOWN' }].map((v: any) => ({ label: v, value: v }))} />
        </Space>

        <Table rowKey="id" columns={columns} dataSource={filtered} pagination={{ pageSize: 20 }} size="middle" />
      </Card>

      <Drawer title="新建数据源连接" width={520} open={drawerOpen} onClose={() => setDrawerOpen(false)}
        extra={<Space>
          <Button onClick={() => setDrawerOpen(false)}>取消</Button>
          <Button onClick={() => message.info('正在测连…')}>测连</Button>
          <Button type="primary" onClick={() => { form.validateFields().then(() => { setDrawerOpen(false); message.success('已创建（mock）'); }); }}>保存</Button>
        </Space>}>
        <Form form={form} layout="vertical">
          <Form.Item label="选择类型" required>
            <Select placeholder="选择数据源类型" options={['MYSQL', 'POSTGRES', 'HIVE', 'KAFKA', 'S3'].map((t) => ({ label: `${TYPE_ICON[t]} ${t}`, value: t }))} />
          </Form.Item>
          <Form.Item label="Host" name="host" rules={[{ required: true }]}><Input placeholder="10.0.0.1" /></Form.Item>
          <Form.Item label="Port" name="port" rules={[{ required: true }]}><Input placeholder="3306" /></Form.Item>
          <Form.Item label="库名" name="dbName"><Input /></Form.Item>
          <Form.Item label="账号" name="username" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true }]} extra={<Tag color="blue">🔒 加密存储，不回显</Tag>}><Input.Password /></Form.Item>
          <Form.Item label="绑定租户/项目" required>
            <Select mode="multiple" placeholder="选择租户和项目" options={[{ label: '交易事业部 / 订单域', value: 'tp-1' }, { label: '风控中心 / 风控域', value: 'tp-2' }].map((v: any) => ({ label: v, value: v }))} />
          </Form.Item>
          <Tag color="warning" style={{ marginTop: 8 }}>⚠ 检测到写权限账号，建议使用只读账号</Tag>
        </Form>
      </Drawer>
    </>
  );
}
