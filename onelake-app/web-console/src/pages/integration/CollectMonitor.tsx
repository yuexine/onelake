/**
 * 采集监控大盘（对应原型 §8.2.5 升级版）。
 *   - KPI 行（4 指标）
 *   - 吞吐/失败率曲线 + 失败 Top
 */
import { Row, Col, Table, Space, Button, Select, Typography, message } from 'antd';
import {
  ArrowDownOutlined, ThunderboltOutlined, ReloadOutlined, CloudServerOutlined,
  FieldTimeOutlined, CheckCircleOutlined, WarningOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import {
  PageHeader, SectionCard, StatCard,
} from '../../components';
import { syncTasks } from '../../mock';

const { Text } = Typography;

export default function CollectMonitor() {
  const navigate = useNavigate();

  const lineOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['吞吐 (rows/s)', '失败率 (%)'], top: 0, textStyle: { color: '#64748B', fontSize: 12 } },
    grid: { left: 40, right: 50, top: 40, bottom: 30 },
    xAxis: {
      type: 'category' as const,
      data: Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`),
      axisLine: { lineStyle: { color: '#E2E8F0' } },
      axisLabel: { color: '#94A3B8', fontSize: 11 },
    },
    yAxis: [
      { type: 'value' as const, name: 'rows/s', splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
      { type: 'value' as const, name: '失败率%', max: 100, splitLine: { show: false }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    ],
    series: [
      {
        name: '吞吐 (rows/s)', type: 'line', smooth: true,
        data: Array.from({ length: 24 }, () => Math.floor(1500 + Math.random() * 2000)),
        itemStyle: { color: '#0F4FD8' }, symbol: 'none',
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [
          { offset: 0, color: 'rgba(15, 79, 216, 0.18)' },
          { offset: 1, color: 'rgba(15, 79, 216, 0.00)' },
        ]}},
      },
      {
        name: '失败率 (%)', type: 'line', yAxisIndex: 1, smooth: true,
        data: Array.from({ length: 24 }, () => Math.floor(Math.random() * 5)),
        itemStyle: { color: '#DC2626' }, symbol: 'none',
      },
    ],
  };

  const failTop = [
    { taskId: 'st-001', task: 'orders_sync', count: 3, lastAt: '02:10', cause: 'AUTH_401 账号密码过期' },
    { taskId: 'st-003', task: 'user_cdc', count: 1, lastAt: '03:20', cause: 'SCHEMA_DDL 破坏性变更' },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CloudServerOutlined />}
        title="采集监控大盘"
        subtitle={<span className="ol-chip">数据集成 · L1-4</span>}
        description="统一监控批 / 增 / CDC / 文件四类采集的成功率、吞吐与时延"
        actions={
          <Select
            defaultValue="24h"
            style={{ width: 110 }}
            options={[{ label: '近 24h', value: '24h' }, { label: '近 7d', value: '7d' }]}
          />
        }
      />

      <div className="ol-grid-stats">
        <StatCard icon={<CheckCircleOutlined />} intent="success" label="成功率" value={98} suffix="%" spark={[97, 98, 96, 98, 99, 98, 98, 98]} />
        <StatCard icon={<ReloadOutlined />} intent="info" label="运行中" value={12} suffix="个" spark={[8, 9, 10, 11, 12, 11, 12, 12]} />
        <StatCard icon={<WarningOutlined />} intent="error" label="失败" value={3} suffix="个" delta={{ value: '+2', direction: 'up', good: 'down' }} />
        <StatCard icon={<FieldTimeOutlined />} intent="brand" label="平均时延" value={42} suffix="s" delta={{ value: '-8s', direction: 'down', good: 'down' }} />
      </div>

      <Row gutter={16} align="stretch">
        <Col xs={24} lg={16}>
          <SectionCard
            title="吞吐 / 失败率曲线"
            icon={<ThunderboltOutlined />}
            subtitle="吞吐与失败率叠加 · 24 小时"
            extra={<Text type="secondary" style={{ fontSize: 11 }}>更新于 1 分钟前</Text>}
            style={{ height: '100%' }}
          >
            <ReactECharts option={lineOption} style={{ height: 320 }} />
          </SectionCard>
        </Col>
        <Col xs={24} lg={8}>
          <SectionCard
            title="失败 Top"
            icon={<ArrowDownOutlined />}
            subtitle="点击下钻到 run 日志"
            flatBody
            style={{ height: '100%' }}
          >
            <Table
              size="middle"
              rowKey="task"
              dataSource={failTop}
              pagination={false}
              columns={[
                { title: '任务', dataIndex: 'task', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
                { title: '次数', dataIndex: 'count', align: 'right' as const, render: (c: number) => <span className="mono tnum" style={{ color: 'var(--ol-error)', fontWeight: 600 }}>{c}</span> },
                { title: '原因', dataIndex: 'cause', render: (c: string) => <span style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{c}</span> },
                { title: '操作', render: (_: unknown, r: any) => (
                  <Button type="link" onClick={() => navigate(`/integration/sync-tasks/${r.taskId}?tab=history&from=monitor&window=${encodeURIComponent(r.lastAt)}`)}>下钻</Button>
                ) },
              ]}
            />
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="任务运行一览" icon={<ReloadOutlined />} flatBody>
        <Table
          size="middle"
          rowKey="id"
          dataSource={syncTasks}
          pagination={false}
          columns={[
            { title: '任务', dataIndex: 'name', render: (n: string) => <Text code style={{ fontSize: 12 }}>{n}</Text> },
            { title: '模式', dataIndex: 'mode', render: (m: string) => <span className="ol-chip">{m}</span> },
            { title: '目标', dataIndex: 'targetTable', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
            { title: '状态', dataIndex: 'status', render: () => (
              <Space size={6}>
                <span className="ol-status-dot is-success" />
                <span style={{ fontSize: 12 }}>正常</span>
              </Space>
            ) },
            { title: '最近 1h 吞吐', dataIndex: 'throughput', render: () => (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div style={{ flex: 1, height: 4, background: 'var(--ol-fill-soft)', borderRadius: 2, overflow: 'hidden' }}>
                  <div style={{ width: `${Math.floor(60 + Math.random() * 35)}%`, height: '100%', background: 'var(--ol-brand-gradient)' }} />
                </div>
                <span className="mono ol-quiet" style={{ fontSize: 11 }}>{Math.floor(1500 + Math.random() * 1500)}/s</span>
              </div>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
