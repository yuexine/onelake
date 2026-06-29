/**
 * 资产地图（对应原型 §8.7.1 升级版）。
 */
import { Row, Col, Tag, Table, Typography, Button, Alert } from 'antd';
import { ExportOutlined, AppstoreOutlined, FireOutlined, PoweroffOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function AssetMap() {
  const navigate = useNavigate();
  const pieOption = {
    tooltip: { trigger: 'item' as const },
    legend: { orient: 'vertical', left: 'left', textStyle: { color: '#64748B', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['45%', '70%'],
      data: [
        { value: 40, name: 'L1 公开', itemStyle: { color: '#64748B' } },
        { value: 30, name: 'L2 内部', itemStyle: { color: '#0F4FD8' } },
        { value: 20, name: 'L3 敏感', itemStyle: { color: '#F97316' } },
        { value: 10, name: 'L4 机密', itemStyle: { color: '#DC2626' } },
      ],
      label: { color: '#0F172A', fontSize: 11 },
      itemGap: 4,
    }],
  };
  const heatOption = {
    tooltip: {},
    xAxis: { type: 'category' as const, data: ['ODS', 'DWD', 'DWS', 'ADS'], axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    yAxis: { type: 'category' as const, data: ['交易域', '用户域', '风控域', '营销域'], axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    visualMap: { min: 0, max: 100, orient: 'horizontal', left: 'center', bottom: '0%', textStyle: { color: '#64748B' } },
    series: [{ type: 'heatmap', data: [[80, 50, 20, 10], [50, 60, 30, 15], [30, 40, 50, 20], [10, 20, 30, 40]], label: { show: true, color: '#0F172A' } }],
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title="资产地图"
        subtitle={<span className="ol-chip">安全 · L3-5</span>}
        description="资产密级分布、热力图、价值评估与闲置资产盘点"
        actions={<Button icon={<ExportOutlined />}>导出报告</Button>}
      />

      <Row gutter={16}>
        <Col xs={24} lg={10}>
          <SectionCard title="密级分布" icon={<AppstoreOutlined />} style={{ height: '100%' }}>
            <ReactECharts option={pieOption} style={{ height: 260 }} />
          </SectionCard>
        </Col>
        <Col xs={24} lg={14}>
          <SectionCard title="资产热力（域 × 层）" icon={<AppstoreOutlined />} style={{ height: '100%' }}>
            <ReactECharts option={heatOption} style={{ height: 260 }} />
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="价值评估 Top（近 90 天）" icon={<FireOutlined />} flatBody>
        <Table
          size="middle"
          dataSource={[
            { key: 1, asset: 'ads.ads_sales_df', value: 95, access: '高', api: '12k/日', downstream: 8, trend: '↗' },
            { key: 2, asset: 'dws.dws_user_df', value: 88, access: '中', api: '3k/日', downstream: 5, trend: '→' },
            { key: 3, asset: 'dwd.dwd_order_df', value: 92, access: '高', api: '8k/日', downstream: 6, trend: '↗' },
          ]}
          pagination={false}
          columns={[
            { title: '资产', dataIndex: 'asset', render: (v: string) => (
              <a className="ol-link" onClick={() => navigate('/catalog/search')}><Text code style={{ fontSize: 12 }}>{v}</Text></a>
            ) },
            { title: '价值分', dataIndex: 'value', align: 'right' as const, render: (v: number) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: 'var(--ol-success-soft)', color: 'var(--ol-success)',
              }}>{v}</span>
            ) },
            { title: '访问热度', dataIndex: 'access', render: (a: string) => <span className="ol-chip">{a}</span> },
            { title: 'API 调用', dataIndex: 'api', render: (v: string) => <span className="mono ol-quiet">{v}</span> },
            { title: '下游', dataIndex: 'downstream', align: 'right' as const },
            { title: '趋势', dataIndex: 'trend', render: (t: string) => (
              <span style={{ fontSize: 14, color: t === '↗' ? 'var(--ol-success)' : 'var(--ol-ink-3)' }}>{t}</span>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="闲置资产（建议下线）" icon={<PoweroffOutlined />} flatBody>
        <Table
          size="middle"
          dataSource={[
            { key: 1, asset: 'ods.ods_tmp_2024', last: '180 天前', downstream: 0 },
            { key: 2, asset: 'dwd.dwd_unused_df', last: '120 天前', downstream: 0 },
          ]}
          pagination={false}
          columns={[
            { title: '资产', dataIndex: 'asset', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '末次访问', dataIndex: 'last', render: (l: string) => <span style={{ fontSize: 12, color: 'var(--ol-warning)' }}>{l}</span> },
            { title: '下游', dataIndex: 'downstream', align: 'right' as const },
            { title: '操作', render: () => <Button size="small" type="primary" danger>下线申请</Button> },
          ]}
        />
        <Alert
          type="warning" showIcon
          style={{ marginTop: 12, borderRadius: 6 }}
          message={<span style={{ fontSize: 12 }}>⚠ 下线前血缘校验：确认无下游引用方可下线</span>}
        />
      </SectionCard>
    </div>
  );
}
