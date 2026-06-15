/**
 * 资产地图（对应原型 §8.7.1）。
 */
import { Card, Row, Col, Tag, Table, Typography, Button } from 'antd';
import { ExportOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';

const { Text } = Typography;

export default function AssetMap() {
  const navigate = useNavigate();
  const pieOption = {
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', left: 'left' },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      data: [
        { value: 40, name: 'L1 公开' },
        { value: 30, name: 'L2 内部' },
        { value: 20, name: 'L3 敏感' },
        { value: 10, name: 'L4 机密' },
      ],
    }],
  };
  const heatOption = {
    tooltip: {},
    xAxis: { type: 'category', data: ['ODS', 'DWD', 'DWS', 'ADS'] },
    yAxis: { type: 'category', data: ['交易域', '用户域', '风控域', '营销域'] },
    visualMap: { min: 0, max: 100, orient: 'horizontal', left: 'center', bottom: '0%' },
    series: [{ type: 'heatmap', data: [[80, 50, 20, 10], [50, 60, 30, 15], [30, 40, 50, 20], [10, 20, 30, 40]], label: { show: true } }],
  };
  return (
    <Card title="资产与安全 / 资产地图" extra={<Button icon={<ExportOutlined />}>导出报告</Button>}>
      <Row gutter={16}>
        <Col span={10}>
          <Card title="密级分布"><ReactECharts option={pieOption} style={{ height: 240 }} /></Card>
        </Col>
        <Col span={14}>
          <Card title="资产热力（域 × 层）"><ReactECharts option={heatOption} style={{ height: 240 }} /></Card>
        </Col>
      </Row>
      <Card title="价值评估 Top（近 90 天）" size="small" style={{ marginTop: 16 }}>
        <Table size="small" dataSource={[
          { asset: 'ads.ads_sales_df', value: 95, access: '高', api: '12k/日', downstream: 8, trend: '↗' },
          { asset: 'dws.dws_user_df', value: 88, access: '中', api: '3k/日', downstream: 5, trend: '→' },
          { asset: 'dwd.dwd_order_df', value: 92, access: '高', api: '8k/日', downstream: 6, trend: '↗' },
        ]} pagination={false}
          columns={[
            { title: '资产', dataIndex: 'asset', render: (v: string) => <a onClick={() => navigate('/catalog/search')}>{v}</a> },
            { title: '价值分', dataIndex: 'value', render: (v: number) => <Tag color="success">{v}</Tag> },
            { title: '访问', dataIndex: 'access' },
            { title: 'API 调用', dataIndex: 'api' },
            { title: '下游', dataIndex: 'downstream' },
            { title: '趋势', dataIndex: 'trend' },
          ]} />
      </Card>
      <Card title="闲置资产（建议下线）" size="small" style={{ marginTop: 16 }}>
        <Table size="small" dataSource={[
          { asset: 'ods.ods_tmp_2024', last: '180 天前', downstream: 0 },
          { asset: 'dwd.dwd_legacy_df', last: '120 天前', downstream: 0 },
        ]} pagination={false}
          columns={[
            { title: '资产', dataIndex: 'asset' },
            { title: '末次访问', dataIndex: 'last' },
            { title: '下游', dataIndex: 'downstream' },
            { title: '操作', render: () => <Button size="small" type="primary" danger>下线申请</Button> },
          ]} />
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>⚠ 下线前血缘校验：确认无下游引用方可下线</Text>
      </Card>
    </Card>
  );
}
