/**
 * 运行实例（对应原型 §4.4.1 列表 + 8.2.4 运行历史样式）。
 */
import { Card, Table, Tag, Space, Button, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { StatusBadge } from '../../components';

const runs = [
  { id: 'jr-1', dag: 'order_pipeline', runId: 'dag-9381', trigger: 'CRON', status: 'SUCCESS', startedAt: '2026-06-14 02:00:01', finishedAt: '2026-06-14 02:04:32', by: 'system' },
  { id: 'jr-2', dag: 'user_dws', runId: 'dag-9380', trigger: 'CRON', status: 'FAILED', startedAt: '2026-06-14 02:00:01', finishedAt: '2026-06-14 02:00:13', by: 'system' },
  { id: 'jr-3', dag: 'order_pipeline', runId: 'dag-9379', trigger: 'MANUAL', status: 'SUCCESS', startedAt: '2026-06-13 12:00:00', finishedAt: '2026-06-13 12:03:30', by: '张三' },
];

export default function RunInstances() {
  const navigate = useNavigate();
  return (
    <Card title="数据开发 / 运行实例">
      <Table rowKey="id" dataSource={runs} size="middle"
        columns={[
          { title: 'Run ID', dataIndex: 'runId', render: (v: string) => <a>{v}</a> },
          { title: '流水线', dataIndex: 'dag' },
          { title: '触发方式', dataIndex: 'trigger', render: (t: string) => <Tag color={t === 'CRON' ? 'blue' : 'default'}>{t}</Tag> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          { title: '开始', dataIndex: 'startedAt' },
          { title: '结束', dataIndex: 'finishedAt' },
          { title: '触发人', dataIndex: 'by' },
          { title: '操作', render: () => <Space><Button size="small" type="link" onClick={() => navigate('/integration/sync-tasks/st-001')}>日志</Button><Button size="small" type="link" onClick={() => message.success('已重试')}>重试</Button></Space> },
        ]} />
    </Card>
  );
}
