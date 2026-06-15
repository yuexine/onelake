/**
 * CDC 实时监控（对应原型 §8.2.7）。
 * 位点/延迟 + Schema 演进待审批。
 */
import { Card, Tabs, Table, Tag, Space, Button, Progress, Typography, Descriptions, Timeline, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { PauseCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { StatusBadge } from '../../components';
import { schemaChangeRequests } from '../../mock';

const { Text } = Typography;

export default function CdcMonitor() {
  const navigate = useNavigate();

  const tabs = [
    { key: 'site', label: '位点与延迟', children: (
      <>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="当前位点"><Text code>binlog.000128 : 4456</Text></Descriptions.Item>
          <Descriptions.Item label="快照阶段"><Tag color="success">✓ 已完成</Tag></Descriptions.Item>
          <Descriptions.Item label="同步延迟"><Tag color="processing">1.2s</Tag></Descriptions.Item>
          <Descriptions.Item label="背压"><Tag color="success">正常</Tag></Descriptions.Item>
          <Descriptions.Item label="Exactly-Once"><Tag color="success">✓ 两阶段提交</Tag></Descriptions.Item>
          <Descriptions.Item label="状态"><StatusBadge status="RUNNING" /></Descriptions.Item>
        </Descriptions>
        <Card type="inner" title="延迟曲线（近 1h）" style={{ marginTop: 16 }}>
          <Progress percent={70} success={{ percent: 60 }} showInfo={false} />
          <Text type="secondary">平均 1.2s, P99 5.6s</Text>
        </Card>
      </>
    ) },
    { key: 'schema', label: 'Schema 演进待审批', children: (
      <Table size="small" rowKey="id" dataSource={schemaChangeRequests}
        columns={[
          { title: '时间', dataIndex: 'createdAt' },
          { title: 'CDC 任务', dataIndex: 'sourceName' },
          { title: '表', dataIndex: 'table' },
          { title: '变更', dataIndex: 'change' },
          { title: '类型', dataIndex: 'type', render: (t: string) => <Tag color={t === '破坏性' ? 'red' : 'success'}>{t}</Tag> },
          { title: '缓冲策略', dataIndex: 'bufferStrategy' },
          { title: '操作', render: (_: unknown, r: any) =>
            r.status === 'PENDING' ? <Button type="link" onClick={() => navigate(`/integration/schema-change/${r.id}`)}>审批</Button> :
            <Tag color={r.status === 'AUTO_APPLIED' ? 'success' : 'default'}>{r.status === 'AUTO_APPLIED' ? '已应用' : r.status}</Tag> },
        ]} />
    ) },
    { key: 'log', label: '运行日志', children: (
      <Card type="inner"><Timeline items={[
        { color: 'blue', children: '10:21:35 INFO BinlogReader connect to mysql-bin.000128:4456' },
        { color: 'blue', children: '10:21:36 INFO Snapshot phase DONE, switch to incremental' },
        { color: 'orange', children: '09:50:21 WARN DDL change detected: ALTER TABLE users DROP COLUMN age' },
        { color: 'red', children: '09:50:22 ERROR Pipeline paused for users (破坏性变更)，其他表继续' },
      ]} /></Card>
    ) },
  ];

  return (
    <Card title="CDC 实时采集 / mysql_orders_cdc" extra={
      <Space>
        <Button icon={<PauseCircleOutlined />} onClick={() => message.success('已暂停')}>暂停</Button>
        <Button icon={<ReloadOutlined />} onClick={() => message.success('已触发重建快照')}>重建快照</Button>
      </Space>
    }>
      <Tabs items={tabs} />
    </Card>
  );
}
