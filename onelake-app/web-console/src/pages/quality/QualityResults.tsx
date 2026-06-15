/**
 * 稽核结果 + 评分看板（对应原型 §8.5.2）。
 */
import { Card, Row, Col, Progress, Table, Tag, Space, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import { qualityResults, qualityScoreTrend } from '../../mock';

const { Text } = Typography;

export default function QualityResults() {
  const dims = [
    { name: '完整', value: 90 },
    { name: '准确', value: 88 },
    { name: '一致', value: 95 },
    { name: '及时', value: 92 },
  ];

  const trendOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['完整', '准确', '一致', '及时'] },
    xAxis: { type: 'category', data: qualityScoreTrend.map((t) => t.date) },
    yAxis: { type: 'value', min: 80, max: 100 },
    series: [
      { name: '完整', type: 'line', data: qualityScoreTrend.map((t) => t.complete) },
      { name: '准确', type: 'line', data: qualityScoreTrend.map((t) => t.accurate) },
      { name: '一致', type: 'line', data: qualityScoreTrend.map((t) => t.consistent) },
      { name: '及时', type: 'line', data: qualityScoreTrend.map((t) => t.fresh) },
    ],
  };

  return (
    <Card title="数据质量 / 稽核结果 · dwd_order_df @06-14">
      <Row gutter={16}>
        <Col span={8}>
          <Card>
            <div style={{ textAlign: 'center' }}>
              <Progress type="dashboard" percent={96} />
              <Text>通过率</Text>
            </div>
          </Card>
        </Col>
        <Col span={16}>
          <Card title="质量分（多维度加权）">
            <Space direction="vertical" style={{ width: '100%' }}>
              {dims.map((d) => (
                <div key={d.name}>
                  <Text>{d.name}</Text>
                  <Progress percent={d.value} strokeColor={d.value > 90 ? '#52c41a' : '#faad14'} style={{ width: 300, marginLeft: 12, display: 'inline-block' }} />
                  <Text strong style={{ marginLeft: 8 }}>{d.value}</Text>
                </div>
              ))}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="趋势" style={{ marginTop: 16 }}>
        <ReactECharts option={trendOption} style={{ height: 260 }} />
      </Card>

      <Card title="异常行明细（抽样）" style={{ marginTop: 16 }}>
        <Table size="small" rowKey="order_id" dataSource={qualityResults[0]?.sample || []}
          pagination={false}
          columns={[
            { title: 'order_id', dataIndex: 'order_id' },
            { title: 'amount', dataIndex: 'amount', render: (v: number) => <Tag color="error">{v}</Tag> },
            { title: 'status', dataIndex: 'status' },
            { title: 'phone', dataIndex: 'phone' },
          ]} />
      </Card>
    </Card>
  );
}
