/**
 * 采集监控大盘（对应原型 §8.2.5）。
 */
import { Card, Row, Col, Statistic, Table, Tag, Space, Button, Typography, Select } from 'antd';
import { ArrowDownOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { syncTasks } from '../../mock';

const { Text } = Typography;

export default function CollectMonitor() {
  const navigate = useNavigate();

  const lineOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['吞吐', '失败率'] },
    xAxis: { type: 'category', data: Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`) },
    yAxis: [
      { type: 'value', name: 'rows/s' },
      { type: 'value', name: '失败率%', max: 100 },
    ],
    series: [
      { name: '吞吐', type: 'line', smooth: true, data: Array.from({ length: 24 }, () => Math.floor(1500 + Math.random() * 2000)) },
      { name: '失败率', type: 'line', yAxisIndex: 1, smooth: true, data: Array.from({ length: 24 }, () => Math.floor(Math.random() * 5)) },
    ],
  };

  const failTop = [
    { task: 'orders_sync', count: 3, lastAt: '02:10' },
    { task: 'user_cdc', count: 1, lastAt: '03:20' },
  ];

  return (
    <Card title="数据集成 / 采集监控大盘" extra={<Space><Select defaultValue="24h" options={[{ label: '近 24h', value: '24h' }, { label: '近 7d', value: '7d' }].map((v: any) => ({ label: v, value: v }))} /></Space>}>
      <Row gutter={16}>
        <Col span={6}><Card><Statistic title="成功率" value={98} suffix="%" valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="运行中" value={12} valueStyle={{ color: '#1677ff' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="失败" value={3} valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="平均时延" value={42} suffix="s" /></Card></Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={16}><Card title="吞吐 / 失败率曲线"><ReactECharts option={lineOption} style={{ height: 300 }} /></Card></Col>
        <Col span={8}>
          <Card title="失败 Top" extra={<Text type="secondary">点击下钻到 run 日志</Text>}>
            <Table size="small" rowKey="task" dataSource={failTop} pagination={false}
              columns={[
                { title: '任务', dataIndex: 'task' },
                { title: '次数', dataIndex: 'count' },
                { title: '最近', dataIndex: 'lastAt' },
                { title: '操作', render: () => <Button type="link" onClick={() => navigate('/integration/sync-tasks')}>下钻</Button> },
              ]} />
          </Card>
        </Col>
      </Row>
    </Card>
  );
}
