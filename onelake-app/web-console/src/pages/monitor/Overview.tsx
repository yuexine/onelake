/**
 * 总览大盘（对应原型 §8.9 升级版）。
 */
import { Row, Col, Table, Tag, Space, Button, Progress, Typography } from 'antd';
import {
  DashboardOutlined, CloudSyncOutlined, AppstoreOutlined,
  SearchOutlined, ApiOutlined, AlertOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { opsAlerts } from '../../mock';
import { PageHeader, SectionCard, StatCard } from '../../components';

const { Text } = Typography;

export default function Overview() {
  const navigate = useNavigate();

  const trendOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['采集', '加工', '查询', 'API'], top: 0, textStyle: { color: '#64748B', fontSize: 12 } },
    grid: { left: 40, right: 30, top: 40, bottom: 30 },
    xAxis: { type: 'category' as const, data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'], axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    yAxis: { type: 'value' as const, min: 95, max: 100, splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11, formatter: '{value}%' } },
    series: [
      { name: '采集', type: 'line', smooth: true, data: [99, 99, 98, 99, 99, 100, 99], itemStyle: { color: '#0F4FD8' }, symbol: 'none' },
      { name: '加工', type: 'line', smooth: true, data: [97, 98, 97, 98, 99, 98, 97], itemStyle: { color: '#16A34A' }, symbol: 'none' },
      { name: '查询', type: 'line', smooth: true, data: [99.7, 99.8, 99.5, 99.9, 99.8, 99.9, 99.7], itemStyle: { color: '#F59E0B' }, symbol: 'none' },
      { name: 'API', type: 'line', smooth: true, data: [99.95, 99.96, 99.94, 99.97, 99.95, 99.98, 99.95], itemStyle: { color: '#7C3AED' }, symbol: 'none' },
    ],
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<DashboardOutlined />}
        title="总览大盘"
        subtitle={<span className="ol-chip">运营 · L9</span>}
        description="采集 / 加工 / 查询 / API 四大链路健康度，资源水位与告警"
        actions={
          <Space size={8} style={{ padding: '4px 10px', background: 'var(--ol-fill)', borderRadius: 6 }}>
            <Tag color="processing" style={{ margin: 0 }}>周期：近 7 天</Tag>
          </Space>
        }
      />

      <div className="ol-grid-stats">
        <StatCard icon={<CloudSyncOutlined />} intent="success" label="采集健康" value={99} suffix="%" spark={[99, 99, 98, 99, 99, 100, 99]} />
        <StatCard icon={<AppstoreOutlined />}  intent="success" label="加工健康" value={97} suffix="%" spark={[97, 98, 97, 98, 99, 98, 97]} />
        <StatCard icon={<SearchOutlined />}    intent="info"    label="查询成功率" value={99.7} suffix="%" spark={[99.7, 99.8, 99.5, 99.9, 99.8, 99.9, 99.7]} />
        <StatCard icon={<ApiOutlined />}       intent="success" label="API 可用性" value={99.95} suffix="%" spark={[99.95, 99.96, 99.94, 99.97, 99.95, 99.98, 99.95]} />
      </div>

      <SectionCard title="健康度趋势（近 7 天）" icon={<ReloadOutlined />}>
        <ReactECharts option={trendOption} style={{ height: 260 }} />
      </SectionCard>

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <SectionCard title="资源水位" icon={<DashboardOutlined />} style={{ height: '100%' }}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              {[
                { name: 'CPU', percent: 60, intent: 'var(--ol-brand)' },
                { name: '内存', percent: 45, intent: 'var(--ol-success)' },
                { name: '存储', percent: 72, intent: 'var(--ol-warning)' },
              ].map((r) => (
                <div key={r.name}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <Text style={{ fontSize: 13, fontWeight: 500 }}>{r.name}</Text>
                    <span className="mono tnum" style={{ fontSize: 12, color: r.intent, fontWeight: 600 }}>{r.percent}%</span>
                  </div>
                  <Progress percent={r.percent} showInfo={false} strokeColor={r.intent} trailColor="var(--ol-fill-soft)" size="small" />
                </div>
              ))}
            </Space>
          </SectionCard>
        </Col>
        <Col xs={24} lg={12}>
          <SectionCard
            title="告警中心"
            icon={<AlertOutlined />}
            extra={<Button type="link" onClick={() => navigate('/monitor/alerts')}>全部告警 →</Button>}
            flatBody
            style={{ height: '100%' }}
          >
            <Table size="middle" rowKey="id" dataSource={opsAlerts.slice(0, 4)} pagination={false}
              columns={[
                { title: '级别', dataIndex: 'level', width: 70, render: (l: string) => (
                  <span style={{
                    padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                    background: l === 'P0' ? 'var(--ol-error-soft)' : l === 'P1' ? 'var(--ol-warning-soft)' : 'var(--ol-fill-soft)',
                    color: l === 'P0' ? 'var(--ol-error)' : l === 'P1' ? '#B45309' : 'var(--ol-ink-3)',
                  }}>{l}</span>
                ) },
                { title: '告警', dataIndex: 'title' },
                { title: '时间', dataIndex: 'createdAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
                { title: '操作', width: 80, render: (_: unknown, r: any) => (
                  <Button type="link" size="small" onClick={() => navigate(`/monitor/alerts/${r.id}`)}>处理</Button>
                ) },
              ]} />
          </SectionCard>
        </Col>
      </Row>
    </div>
  );
}
