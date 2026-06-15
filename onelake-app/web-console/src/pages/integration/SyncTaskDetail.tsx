/**
 * 任务详情 + 运行历史（对应原型 §8.2.4）。
 * Tab: 概览 / 配置 / 运行历史 / 血缘
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tabs, Table, Tag, Space, Button, Progress, Typography, message } from 'antd';
import { PauseCircleOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { DetailPageLayout, StatusBadge } from '../../components';
import { syncTasks, syncRuns } from '../../mock';

const { Text, Paragraph } = Typography;

export default function SyncTaskDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const task = syncTasks.find((t) => t.id === id) || syncTasks[0];
  const runs = syncRuns.filter((r) => r.taskId === task.id);

  const tabs = [
    { key: 'overview', label: '概览', children: (
      <Paragraph>
        <Space direction="vertical">
          <Text>源：{task.sourceName}</Text>
          <Text>模式：<Tag color="blue">{task.mode}</Tag></Text>
          <Text>目标表：<Text code>{task.targetTable}</Text></Text>
          <Text>调度：{task.scheduleCron || '-'}</Text>
          <Text>限流：{task.rateLimit || '-'} rows/s</Text>
        </Space>
      </Paragraph>
    ) },
    { key: 'config', label: '配置', children: <Card type="inner" title="字段映射"><Table size="small" pagination={false} rowKey="source"
      dataSource={task.fieldMapping || []}
      columns={[
        { title: '源', dataIndex: 'source' },
        { title: '源类型', dataIndex: 'sourceType' },
        { title: '目标', dataIndex: 'target' },
        { title: '目标类型', dataIndex: 'targetType' },
      ]} /></Card> },
    { key: 'history', label: '运行历史', children: (
      <Table size="small" rowKey="id" dataSource={runs} pagination={{ pageSize: 10 }}
        columns={[
          { title: 'Run ID', dataIndex: 'id', render: (v: string, r: any) => <a onClick={() => navigate(`/integration/sync-tasks/${task.id}/runs/${r.id}`)}>{v}</a> },
          { title: '开始时间', dataIndex: 'startedAt' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          { title: '读取行数', dataIndex: 'rowsRead', render: (v: number) => v.toLocaleString() },
          { title: '写入行数', dataIndex: 'rowsWritten', render: (v: number) => v.toLocaleString() },
          { title: '耗时', dataIndex: 'durationMs', render: (d?: number) => d ? `${(d/1000).toFixed(1)}s` : '-' },
          { title: '吞吐', dataIndex: 'throughputRows', render: (t?: number) => t ? `${t}/s` : '-' },
          { title: '操作', render: (_: unknown, r: any) => <Button size="small" type="link" onClick={() => navigate(`/integration/sync-tasks/${task.id}/runs/${r.id}`)}>日志</Button> },
        ]} />
    ) },
    { key: 'lineage', label: '血缘', children: (
      <Card type="inner"><Space><Tag>{task.sourceName}.{task.targetTable.split('.')[1]}</Tag><span>→</span><Tag color="blue">{task.targetTable}</Tag></Space></Card>
    ) },
  ];

  return (
    <DetailPageLayout
      title={task.name}
      subtitle={<Space><Tag>{task.mode}</Tag>目标 {task.targetTable}</Space>}
      status={<StatusBadge status={task.status} />}
      breadcrumb={[{ path: '/integration/sync-tasks', label: '采集任务' }, { label: task.name }]}
      tabs={tabs}
      actions={[
        <Button key="run" type="primary" icon={<ReloadOutlined />} onClick={() => message.success('已触发运行')}>触发运行</Button>,
        <Button key="pause" icon={<PauseCircleOutlined />}>暂停</Button>,
        <Button key="edit" icon={<EditOutlined />}>编辑</Button>,
      ]}
      meta={[
        { label: '源', value: task.sourceName },
        { label: '模式', value: task.mode },
        { label: '目标表', value: task.targetTable },
        { label: '调度', value: task.scheduleCron || '-' },
        { label: '限流', value: `${task.rateLimit || '-'} rows/s` },
        { label: '创建时间', value: task.createdAt.slice(0, 10) },
      ]}
    />
  );
}
