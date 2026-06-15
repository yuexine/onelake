/**
 * 总览大盘（对应原型 §8.9）。
 */
import { Card, Row, Col, Statistic, Table, Tag, Space, Button, Progress } from 'antd';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { opsAlerts } from '../../mock';

export default function Overview() {
  const navigate = useNavigate();
  return (
    <Card title="运营与监控 / 总览大盘" extra={<Space><Tag>周期：近 7 天</Tag></Space>}>
      <Row gutter={16}>
        <Col span={6}><Card><Statistic title="采集健康" value={99} suffix="%" valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="加工健康" value={97} suffix="%" valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="查询成功率" value={99.7} suffix="%" /></Card></Col>
        <Col span={6}><Card><Statistic title="API 可用性" value={99.95} suffix="%" valueStyle={{ color: '#52c41a' }} /></Card></Col>
      </Row>

      <Card size="small" title="资源水位" style={{ marginTop: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div><span>CPU</span><Progress percent={60} style={{ width: 400, marginLeft: 12, display: 'inline-block' }} /></div>
          <div><span>内存</span><Progress percent={45} style={{ width: 400, marginLeft: 12, display: 'inline-block' }} /></div>
          <div><span>存储</span><Progress percent={72} style={{ width: 400, marginLeft: 12, display: 'inline-block' }} /></div>
        </Space>
      </Card>

      <Card size="small" title="告警中心" style={{ marginTop: 16 }} extra={<Button type="link" onClick={() => navigate('/monitor/alerts')}>全部告警</Button>}>
        <Table size="small" rowKey="id" dataSource={opsAlerts}
          columns={[
            { title: '级别', dataIndex: 'level', render: (l: string) => <Tag color={l === 'P0' ? 'red' : l === 'P1' ? 'orange' : 'default'}>{l}</Tag> },
            { title: '来源', dataIndex: 'source' },
            { title: '告警', dataIndex: 'title' },
            { title: '时间', dataIndex: 'createdAt' },
            { title: '认领', dataIndex: 'assignee', render: (a?: string) => a || '-' },
            { title: '操作', render: (_: unknown, r: any) => <Button type="link" onClick={() => navigate(`/monitor/alerts/${r.id}`)}>处理</Button> },
          ]} />
      </Card>
    </Card>
  );
}
