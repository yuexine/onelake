/**
 * SLA / SLO 看板（对应原型 §8.9.4）。
 */
import { Card, Table, Tag, Progress, Typography } from 'antd';
import { slaDashboard } from '../../mock';

const { Text } = Typography;

export default function Sla() {
  return (
    <Card title="运营 / SLA / SLO 看板（近 30 天）">
      <Table rowKey="metric" dataSource={slaDashboard} pagination={false}
        columns={[
          { title: '指标', dataIndex: 'metric' },
          { title: '当前值', dataIndex: 'value', render: (v: number, r: any) => `${v}${r.unit || (r.target > 10 ? '%' : '')}` },
          { title: '目标', dataIndex: 'target', render: (v: number, r: any) => `${v}${r.unit || (v > 10 ? '%' : '')}` },
          { title: '状态', render: (_: unknown, r: any) => <Tag color={r.status === 'OK' ? 'success' : 'error'}>{r.status === 'OK' ? '✓ 达标' : '✗ 违约'}</Tag> },
          { title: '进度', render: (_: unknown, r: any) => <Progress percent={Math.min(100, (r.value / r.target) * 100)} size="small" status={r.status === 'OK' ? 'success' : 'exception'} /> },
        ]} />

      <Card size="small" title="违约记录" style={{ marginTop: 16 }}>
        <Table size="small" dataSource={[
          { date: '2026-06-03', metric: '采集 SLA', issue: '采集任务延迟 2h', action: '已赔偿/告警' },
        ]} pagination={false}
          columns={[
            { title: '日期', dataIndex: 'date' },
            { title: '指标', dataIndex: 'metric' },
            { title: '问题', dataIndex: 'issue' },
            { title: '处理', dataIndex: 'action' },
          ]} />
      </Card>
    </Card>
  );
}
