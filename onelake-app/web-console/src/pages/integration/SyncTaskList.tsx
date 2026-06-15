/**
 * 采集任务列表（对应原型 §4.2.1 / §8.2.4 列表部分）。
 */
import { Card, Table, Tag, Space, Button, Input, Select, Tooltip, message } from 'antd';
import { PlusOutlined, ReloadOutlined, PlayCircleOutlined, EditOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { syncTasks } from '../../mock';
import { StatusBadge } from '../../components';

export default function SyncTaskList() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [mode, setMode] = useState<string>();

  const rows = syncTasks.filter((t) =>
    (!mode || t.mode === mode) &&
    (!keyword || t.name.toLowerCase().includes(keyword.toLowerCase()) || t.targetTable.includes(keyword))
  );

  return (
    <Card title="数据集成 / 采集任务" extra={
      <Space>
        <Button icon={<ReloadOutlined />} onClick={() => message.success('已刷新')}>刷新</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/integration/sync-tasks/new')}>新建采集任务</Button>
      </Space>
    }>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search placeholder="搜索任务名/目标表" allowClear onSearch={setKeyword} style={{ width: 260 }} />
        <Select placeholder="采集模式" allowClear style={{ width: 140 }} onChange={setMode}
          options={['FULL', 'INCREMENTAL', 'CDC', 'FILE'].map((m) => ({ label: m, value: m }))} />
      </Space>

      <Table rowKey="id" dataSource={rows} size="middle" pagination={{ pageSize: 20 }}
        columns={[
          { title: '任务名', dataIndex: 'name', render: (n: string, r: any) => <a onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>{n}</a> },
          { title: '源', dataIndex: 'sourceName' },
          { title: '模式', dataIndex: 'mode', render: (m: string) => {
            const c = m === 'CDC' ? 'processing' : m === 'INCREMENTAL' ? 'blue' : m === 'FILE' ? 'gold' : 'default';
            return <Tag color={c}>{m}</Tag>;
          } },
          { title: '目标表', dataIndex: 'targetTable' },
          { title: '调度', dataIndex: 'scheduleCron', render: (c: string) => c || '-' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          {
            title: '操作', width: 200,
            render: (_: unknown, r: any) => (
              <Space>
                <Tooltip title="触发运行">
                  <Button size="small" icon={<PlayCircleOutlined />} onClick={() => { message.success(`已触发 ${r.name} (runId=mock)`); }} />
                </Tooltip>
                <Button size="small" type="link" onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>详情</Button>
                <Button size="small" type="link" icon={<EditOutlined />}>编辑</Button>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
