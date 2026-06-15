/**
 * 告警中心（对应原型 §8.9.1 / §8.9.2）。
 */
import { Card, Table, Tag, Space, Button, Tabs, Drawer, Descriptions, Timeline, Typography, message } from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { opsAlerts } from '../../mock';

const { Text } = Typography;

export default function AlertCenter() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [drawerId, setDrawerId] = useState<string | undefined>(id);
  const [activeTab, setActiveTab] = useState('all');

  const alerts = opsAlerts;
  const current = alerts.find((a) => a.id === drawerId);

  return (
    <Card title="运营 / 告警中心" extra={<Space>
      <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
        { key: 'all', label: '全部' }, { key: 'P0', label: 'P0' }, { key: 'P1', label: 'P1' }, { key: 'P2', label: 'P2' },
      ]} />
      <Button>静默规则</Button>
    </Space>}>
      <Table rowKey="id" dataSource={activeTab === 'all' ? alerts : alerts.filter((a) => a.level === activeTab)} size="middle"
        columns={[
          { title: '级别', dataIndex: 'level', render: (l: string) => <Tag color={l === 'P0' ? 'red' : l === 'P1' ? 'orange' : 'default'}>{l}</Tag> },
          { title: '来源', dataIndex: 'source' },
          { title: '告警', dataIndex: 'title' },
          { title: '时间', dataIndex: 'createdAt' },
          { title: '认领', dataIndex: 'assignee', render: (a?: string) => a ? <Tag color="processing">{a}</Tag> : <Button size="small" type="link">认领</Button> },
          { title: '操作', render: (_: unknown, r: any) => <Button size="small" type="link" onClick={() => setDrawerId(r.id)}>处理</Button> },
        ]} />

      <Drawer open={!!current} onClose={() => setDrawerId(undefined)} title={current ? `告警详情 · ${current.title}` : ''} width={680}
        extra={<Space><Button onClick={() => message.success('已静默 2h')}>静默 2h</Button><Button>转工单</Button><Button type="primary" onClick={() => message.success('已关闭')}>关闭</Button></Space>}>
        {current && (
          <>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="级别"><Tag color={current.level === 'P0' ? 'red' : 'orange'}>{current.level}</Tag></Descriptions.Item>
              <Descriptions.Item label="来源">{current.source}</Descriptions.Item>
              <Descriptions.Item label="触发规则">{current.rule}</Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color="processing">● 处理中</Tag></Descriptions.Item>
              <Descriptions.Item label="处理人">{current.assignee || '-'}</Descriptions.Item>
              <Descriptions.Item label="关联"><Text code>{current.relatedRunId || current.relatedApi}</Text></Descriptions.Item>
            </Descriptions>
            <Card size="small" title="时间线" style={{ marginTop: 12 }}>
              <Timeline items={[
                { children: `${current.createdAt} 触发` },
                { children: `${current.createdAt} 通知值班（电话+钉钉）` },
                { children: '已认领（张三）' },
                { children: '重试中…' },
              ]} />
            </Card>
            <Space style={{ marginTop: 12 }}>
              <Button onClick={() => navigate('/integration/sync-tasks')}>直达 run</Button>
              <Button onClick={() => navigate('/catalog/lineage')}>查看血缘影响</Button>
            </Space>
          </>
        )}
      </Drawer>
    </Card>
  );
}
