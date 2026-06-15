/**
 * 连接详情（对应原型 §8.2.9 · 审查补全）。
 * 多 Tab：概览 / 连通历史 / 使用中任务 / 密钥 / 变更历史。
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tabs, Tag, Space, Button, Table, Timeline, Typography, Descriptions, message } from 'antd';
import { ThunderboltOutlined, EditOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { DetailPageLayout, DangerConfirm } from '../../components';
import { useState } from 'react';
import { dataSources, syncTasks } from '../../mock';

const { Text } = Typography;

export default function DatasourceDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const ds = dataSources.find((d) => d.id === id) || dataSources[0];
  const [confirmOpen, setConfirmOpen] = useState(false);
  const usingTasks = syncTasks.filter((t) => t.sourceId === ds.id);

  const tabs = [
    { key: 'overview', label: '概览', children: (
      <Descriptions column={2} bordered size="small">
        <Descriptions.Item label="类型">{ds.type}</Descriptions.Item>
        <Descriptions.Item label="Host">{ds.host}:{ds.port}</Descriptions.Item>
        <Descriptions.Item label="库名">{ds.dbName || '-'}</Descriptions.Item>
        <Descriptions.Item label="账号">{ds.username}</Descriptions.Item>
        <Descriptions.Item label="网络">{ds.networkMode}</Descriptions.Item>
        <Descriptions.Item label="环境">{ds.envLevel}</Descriptions.Item>
        <Descriptions.Item label="租户/项目">交易事业部 / 订单域</Descriptions.Item>
        <Descriptions.Item label="最近检查">{ds.lastCheckAt}</Descriptions.Item>
      </Descriptions>
    ) },
    { key: 'connectivity', label: '连通历史', children: (
      <Space direction="vertical">
        <Text>近 24h 探活结果：</Text>
        <Space size={4}>
          {Array.from({ length: 24 }).map((_, i) => {
            const ok = i !== 8;
            return <Tag key={i} color={ok ? 'success' : 'error'} style={{ width: 16, textAlign: 'center', padding: 0 }}>·</Tag>;
          })}
        </Space>
        <Text type="secondary">平均 RTT 23ms，1 次失败（鉴权）</Text>
      </Space>
    ) },
    { key: 'using', label: `使用中任务 (${usingTasks.length})`, children: (
      <Table size="small" rowKey="id" dataSource={usingTasks} pagination={false}
        columns={[
          { title: '任务名', dataIndex: 'name', render: (n: string, r: any) => <a onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>{n}</a> },
          { title: '模式', dataIndex: 'mode' },
          { title: '目标', dataIndex: 'targetTable' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ENABLED' ? 'processing' : 'default'}>{s}</Tag> },
        ]} />
    ) },
    { key: 'secret', label: '密钥', children: (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="引用 ref"><Text code>ds/order_db/pwd</Text></Descriptions.Item>
          <Descriptions.Item label="KMS Key">cmk-order-v3</Descriptions.Item>
          <Descriptions.Item label="上次轮换">2026-06-01</Descriptions.Item>
          <Descriptions.Item label="下次计划">2026-09-01</Descriptions.Item>
        </Descriptions>
        <Button icon={<ThunderboltOutlined />} onClick={() => message.success('已触发轮换（热更新，不中断任务）')}>立即轮换</Button>
      </Space>
    ) },
    { key: 'changes', label: '变更历史', children: (
      <Timeline items={[
        { children: '2026-06-01 12:00 张三 触发密钥轮换' },
        { children: '2026-05-15 18:00 张三 修改连接池 maxPoolSize 10 → 20' },
        { children: '2026-03-01 10:00 系统 创建连接' },
      ]} />
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        title={ds.name}
        subtitle={<Space><Tag>{ds.type}</Tag>{ds.host}:{ds.port} · 库 {ds.dbName}</Space>}
        status={<Tag color={ds.health === 'OK' ? 'success' : 'error'}>{ds.health === 'OK' ? '连通' : '异常'} (RTT {ds.rttMs}ms)</Tag>}
        breadcrumb={[{ path: '/integration/datasources', label: '连接管理' }, { label: ds.name }]}
        tabs={tabs}
        actions={[
          <Button key="test" icon={<ThunderboltOutlined />} onClick={() => message.success(`已测连 (RTT ${ds.rttMs}ms)`)}>测连</Button>,
          <Button key="edit" icon={<EditOutlined />}>编辑</Button>,
          <Button key="new-task" type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/integration/sync-tasks/new?sourceId=${ds.id}`)}>基于此连接建采集</Button>,
          <Button key="del" danger icon={<DeleteOutlined />} onClick={() => setConfirmOpen(true)}>删除</Button>,
        ]}
        meta={[
          { label: '负责人', value: ds.username },
          { label: '环境', value: ds.envLevel },
          { label: '网络模式', value: ds.networkMode },
          { label: '使用中任务', value: usingTasks.length },
          { label: '创建时间', value: ds.createdAt.slice(0, 10) },
        ]}
      />

      <DangerConfirm
        open={confirmOpen}
        title={`删除连接 ${ds.name}`}
        description="该操作不可恢复。请确认该连接没有正在运行的采集任务。"
        confirmName={ds.name}
        impacts={[
          { label: '关联任务', value: usingTasks.length },
          { label: '活跃运行', value: 0 },
        ]}
        impactLevel={usingTasks.length > 0 ? 'HIGH' : 'LOW'}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => { setConfirmOpen(false); message.success('已删除（mock）'); navigate('/integration/datasources'); }}
      />
    </>
  );
}
