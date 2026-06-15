/**
 * 采集失败诊断（对应原型 §8.2.10 · 审查补全）。
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tag, Descriptions, Space, Button, Typography, Alert, message } from 'antd';
import { ArrowLeftOutlined, BranchesOutlined } from '@ant-design/icons';
import { syncRuns, syncTasks } from '../../mock';
import { StatusBadge, ImpactAnalysis } from '../../components';

const { Text } = Typography;

export default function FailureDiagnose() {
  const { id, runId } = useParams();
  const navigate = useNavigate();
  const task = syncTasks.find((t) => t.id === id) || syncTasks[0];
  const run = syncRuns.find((r) => r.id === runId) || syncRuns[1];

  return (
    <Card title={<Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/integration/sync-tasks/${task.id}`)} />失败诊断 · {run.id}</Space>}>
      <Alert type="error" message={<Space><StatusBadge status="FAILED" />{run.errorMsg}</Space>} style={{ marginBottom: 16 }} />

      <Descriptions title="错误分类" bordered size="small" column={2}>
        <Descriptions.Item label="错误码">{run.errorCode}</Descriptions.Item>
        <Descriptions.Item label="类别"><Tag color="red">鉴权</Tag></Descriptions.Item>
        <Descriptions.Item label="失败分片">{run.shardProgress?.filter((p: number) => p < 100).length}/{run.shardProgress?.length || 0}</Descriptions.Item>
        <Descriptions.Item label="最近 checkpoint"><Text code>{run.checkpoint || 'binlog.000128:4456 (02:00)'}</Text></Descriptions.Item>
      </Descriptions>

      <div style={{ marginTop: 16 }}>
        <ImpactAnalysis impact={{
          assets: ['dwd.dwd_order_df'], tasks: [task.name], apis: ['/api/order/detail'],
          subscribers: 18, blocking: true, suggestion: '先轮换源库密码 → 从 checkpoint 恢复 → 重跑',
        }} />
      </div>

      <div style={{ marginTop: 16 }}>
        <Space wrap>
          <Button type="primary" onClick={() => message.success('已重试')}>重试</Button>
          <Button onClick={() => message.success('从 checkpoint 恢复中')}>从 checkpoint 恢复</Button>
          <Button>跳过坏记录</Button>
          <Button danger>暂停管道</Button>
          <Button icon={<BranchesOutlined />} onClick={() => navigate('/catalog/lineage')}>查看血缘影响</Button>
        </Space>
      </div>
    </Card>
  );
}
