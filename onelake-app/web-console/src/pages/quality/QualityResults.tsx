/**
 * 稽核结果 + 评分看板（对应原型 §8.5.2 升级版）。
 */
import { Row, Col, Progress, Table, Tag, Space, Typography } from 'antd';
import { SafetyOutlined, CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { qualityResults, qualityScoreTrend } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function QualityResults() {
  const dims = [
    { name: '完整性', value: 90, intent: 'var(--ol-warning)' },
    { name: '准确性', value: 88, intent: 'var(--ol-warning)' },
    { name: '一致性', value: 95, intent: 'var(--ol-success)' },
    { name: '及时性', value: 92, intent: 'var(--ol-success)' },
  ];

  const trendOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['完整', '准确', '一致', '及时'], top: 0, textStyle: { color: '#64748B', fontSize: 12 } },
    grid: { left: 40, right: 30, top: 40, bottom: 30 },
    xAxis: { type: 'category' as const, data: qualityScoreTrend.map((t) => t.date), axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    yAxis: { type: 'value' as const, min: 80, max: 100, splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    series: [
      { name: '完整', type: 'line', smooth: true, data: qualityScoreTrend.map((t) => t.complete), itemStyle: { color: '#0F4FD8' }, symbol: 'none' },
      { name: '准确', type: 'line', smooth: true, data: qualityScoreTrend.map((t) => t.accurate), itemStyle: { color: '#16A34A' }, symbol: 'none' },
      { name: '一致', type: 'line', smooth: true, data: qualityScoreTrend.map((t) => t.consistent), itemStyle: { color: '#F59E0B' }, symbol: 'none' },
      { name: '及时', type: 'line', smooth: true, data: qualityScoreTrend.map((t) => t.fresh), itemStyle: { color: '#7C3AED' }, symbol: 'none' },
    ],
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyOutlined />}
        title={
          <Space size={8}>
            稽核结果
            <Text code style={{ fontSize: 13 }}>dwd_order_df @ 06-14</Text>
          </Space>
        }
        subtitle={<span className="ol-chip">质量 · L3-4</span>}
        description="完整性 / 准确性 / 一致性 / 及时性 四维度评分"
      />

      <Row gutter={16}>
        <Col xs={24} lg={8}>
          <SectionCard title="整体通过率" icon={<SafetyOutlined />} style={{ height: '100%' }}>
            <div style={{ textAlign: 'center', padding: 12 }}>
              <Progress
                type="dashboard"
                percent={96}
                strokeColor={{ '0%': 'var(--ol-success)', '100%': 'var(--ol-brand)' }}
                trailColor="var(--ol-fill-soft)"
                format={(p) => <span className="tnum" style={{ fontSize: 18, fontWeight: 600, color: 'var(--ol-ink)' }}>{p}%</span>}
              />
              <div style={{ marginTop: 12, fontSize: 12, color: 'var(--ol-ink-3)' }}>
                通过 <Text strong style={{ color: 'var(--ol-success)' }}>11,518</Text> / 12,000 行
              </div>
            </div>
          </SectionCard>
        </Col>
        <Col xs={24} lg={16}>
          <SectionCard title="多维度评分" icon={<SafetyOutlined />} subtitle="完整 / 准确 / 一致 / 及时 四维加权" style={{ height: '100%' }}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              {dims.map((d) => (
                <div key={d.name}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <Text style={{ fontSize: 13, fontWeight: 500 }}>{d.name}</Text>
                    <span className="mono tnum" style={{ fontSize: 13, fontWeight: 600, color: d.intent }}>{d.value}</span>
                  </div>
                  <Progress
                    percent={d.value}
                    showInfo={false}
                    strokeColor={d.intent}
                    trailColor="var(--ol-fill-soft)"
                    size="small"
                  />
                </div>
              ))}
            </Space>
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="质量分趋势（近 7 天）" icon={<SafetyOutlined />}>
        <ReactECharts option={trendOption} style={{ height: 280 }} />
      </SectionCard>

      <SectionCard title="异常行明细（抽样）" icon={<WarningOutlined />} flatBody>
        <Table
          size="middle"
          rowKey="order_id"
          dataSource={qualityResults[0]?.sample || []}
          pagination={false}
          columns={[
            { title: 'order_id', dataIndex: 'order_id', render: (v: number) => <Text code>{v}</Text> },
            { title: 'amount', dataIndex: 'amount', render: (v: number) => <Tag color="error" style={{ margin: 0 }}>{v}</Tag> },
            { title: 'status', dataIndex: 'status', render: (s: string) => <span className="ol-chip">{s}</span> },
            { title: 'phone', dataIndex: 'phone', render: (p: string) => <Text code style={{ fontSize: 12 }}>{p}</Text> },
          ]}
        />
      </SectionCard>
    </div>
  );
}
