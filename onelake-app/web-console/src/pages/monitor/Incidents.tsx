/**
 * 故障复盘 / 事件时间线（对应原型 §8.9.3）。
 */
import { Card, Tag, Timeline, Typography, Space, Button, Descriptions } from 'antd';
import { incidents } from '../../mock';

const { Text, Title } = Typography;

export default function Incidents() {
  return (
    <Card title="运营 / 故障复盘">
      {incidents.map((inc) => (
        <Card key={inc.id} size="small" style={{ marginBottom: 12 }} title={<Space><Tag color="red">P0</Tag><Text strong>{inc.id}</Text><Text type="secondary">{inc.alert}</Text></Space>}>
          <Title level={5}>事件时间线</Title>
          <Timeline items={inc.timeline.map((t) => ({ children: <Space><Text strong>{t.at}</Text><Text>{t.event}</Text></Space> }))} />

          <Descriptions column={2} size="small" bordered style={{ marginTop: 12 }}>
            <Descriptions.Item label="影响时长">{inc.impactDuration}</Descriptions.Item>
            <Descriptions.Item label="下游影响">{inc.downstreamImpact}</Descriptions.Item>
            <Descriptions.Item label="RCA 根因" span={2}>{inc.rca}</Descriptions.Item>
          </Descriptions>

          <Card size="small" title="改进项" style={{ marginTop: 12 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              {inc.improvements.map((im, i) => (
                <div key={i}><Tag color="processing">待办</Tag><Text>{im.action}</Text><Tag style={{ marginLeft: 8 }}>{im.owner}</Tag><Tag>due {im.due}</Tag><Button size="small" type="link">建任务</Button></div>
              ))}
            </Space>
          </Card>
        </Card>
      ))}
    </Card>
  );
}
