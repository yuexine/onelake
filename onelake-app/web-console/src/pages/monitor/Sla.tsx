/**
 * SLA / SLO 看板（对应原型 §8.9.4 升级版）。
 */
import { Table, Tag, Progress, Typography } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { slaDashboard } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Sla() {
  const ok = slaDashboard.filter((s) => s.status === 'OK').length;
  const breach = slaDashboard.length - ok;
  const rate = ((ok / slaDashboard.length) * 100).toFixed(1);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyCertificateOutlined />}
        title="SLA / SLO 看板"
        subtitle={<span className="ol-chip">运营 · L9-3</span>}
        description="近 30 天 SLA / SLO 履约情况，含违约记录与改进"
      />

      <SectionCard title="SLA 履约明细" icon={<SafetyCertificateOutlined />} flatBody>
        <Table
          rowKey="metric"
          dataSource={slaDashboard}
          pagination={false}
          columns={[
            { title: '指标', dataIndex: 'metric', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
            { title: '当前值', dataIndex: 'value', align: 'right' as const, render: (v: number, r: any) => (
              <span className="mono tnum" style={{ fontSize: 13, fontWeight: 600 }}>
                {v}{r.unit || (r.target > 10 ? '%' : '')}
              </span>
            ) },
            { title: '目标', dataIndex: 'target', align: 'right' as const, render: (v: number, r: any) => (
              <span className="mono tnum" style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
                {v}{r.unit || (v > 10 ? '%' : '')}
              </span>
            ) },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: s === 'OK' ? 'var(--ol-success-soft)' : 'var(--ol-error-soft)',
                color: s === 'OK' ? 'var(--ol-success)' : 'var(--ol-error)',
              }}>{s === 'OK' ? '✓ 达标' : '✗ 违约'}</span>
            ) },
            { title: '进度', render: (_: unknown, r: any) => (
              <Progress
                percent={Math.min(100, (r.value / r.target) * 100)}
                size="small"
                strokeColor={r.status === 'OK' ? 'var(--ol-success)' : 'var(--ol-error)'}
                trailColor="var(--ol-fill-soft)"
                style={{ margin: 0, minWidth: 200 }}
              />
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="违约记录" icon={<SafetyCertificateOutlined />} flatBody>
        <Table
          size="middle"
          dataSource={[
            { key: 1, date: '2026-06-03', metric: '采集 SLA', issue: '采集任务延迟 2h', action: '已赔偿 / 告警' },
          ]}
          pagination={false}
          columns={[
            { title: '日期', dataIndex: 'date', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{t}</span> },
            { title: '指标', dataIndex: 'metric', render: (m: string) => <span className="ol-chip">{m}</span> },
            { title: '问题', dataIndex: 'issue' },
            { title: '处理', dataIndex: 'action', render: (a: string) => <Tag color="warning" style={{ margin: 0 }}>{a}</Tag> },
          ]}
        />
      </SectionCard>
    </div>
  );
}
