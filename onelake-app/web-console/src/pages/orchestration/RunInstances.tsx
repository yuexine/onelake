/**
 * 运行实例（对应原型 §4.4.1 升级版）。
 */
import { Table, Tag, Space, Button, message, Typography } from 'antd';
import { ReloadOutlined, FieldTimeOutlined, PlayCircleOutlined, HistoryOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

const runs = [
  { id: 'jr-1', dag: 'order_pipeline', runId: 'dag-9381', trigger: 'CRON', status: 'SUCCESS', startedAt: '2026-06-14 02:00:01', finishedAt: '2026-06-14 02:04:32', by: 'system' },
  { id: 'jr-2', dag: 'user_dws', runId: 'dag-9380', trigger: 'CRON', status: 'FAILED', startedAt: '2026-06-14 02:00:01', finishedAt: '2026-06-14 02:00:13', by: 'system' },
  { id: 'jr-3', dag: 'order_pipeline', runId: 'dag-9379', trigger: 'MANUAL', status: 'SUCCESS', startedAt: '2026-06-13 12:00:00', finishedAt: '2026-06-13 12:03:30', by: '张三' },
];

export default function RunInstances() {
  const navigate = useNavigate();

  const counts = {
    total: runs.length,
    success: runs.filter((r) => r.status === 'SUCCESS').length,
    failed: runs.filter((r) => r.status === 'FAILED').length,
    cron: runs.filter((r) => r.trigger === 'CRON').length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<HistoryOutlined />}
        title="运行实例"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="查看所有流水线运行历史，含触发方式、耗时、责任人"
      />

      <SectionCard title="运行历史" icon={<HistoryOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={runs}
          size="middle"
          pagination={false}
          columns={[
            { title: 'Run ID', dataIndex: 'runId', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '流水线', dataIndex: 'dag', render: (d: string) => <Text code style={{ fontSize: 12 }}>{d}</Text> },
            { title: '触发方式', dataIndex: 'trigger', width: 110, render: (t: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: t === 'CRON' ? 'var(--ol-brand-soft)' : 'var(--ol-fill-soft)',
                color: t === 'CRON' ? 'var(--ol-brand)' : 'var(--ol-ink-2)',
              }}>{t}</span>
            ) },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: '开始', dataIndex: 'startedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{t}</span> },
            { title: '结束', dataIndex: 'finishedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{t}</span> },
            { title: '触发人', dataIndex: 'by', width: 100, render: (b: string) => (
              <span style={{ fontSize: 12, color: b === 'system' ? 'var(--ol-ink-3)' : 'var(--ol-ink)' }}>{b}</span>
            ) },
            { title: '操作', width: 140, render: () => (
              <Space>
                <Button size="small" type="link" onClick={() => navigate('/integration/sync-tasks/st-001')}>日志</Button>
                <Button size="small" type="link" onClick={() => message.success('已重试')}>重试</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
