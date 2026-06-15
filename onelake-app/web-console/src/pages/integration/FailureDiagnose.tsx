/**
 * 采集失败诊断（对应原型 §8.2.10 升级版）。
 *   - 顶部错误横幅 + 错误分类卡
 *   - 影响分析（卡内）
 *   - 处置方案按钮组
 */
import { useParams, useNavigate } from 'react-router-dom';
import {
  Tag, Space, Button, Typography, message, Steps, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, BranchesOutlined, ReloadOutlined, LockOutlined,
  StepForwardOutlined, PauseCircleOutlined, WarningOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import {
  PageHeader, StatusBadge, ImpactAnalysis, SectionCard,
} from '../../components';
import { syncRuns, syncTasks } from '../../mock';

const { Text, Paragraph } = Typography;

export default function FailureDiagnose() {
  const { id, runId } = useParams();
  const navigate = useNavigate();
  const task = syncTasks.find((t) => t.id === id) || syncTasks[0];
  const run = syncRuns.find((r) => r.id === runId) || syncRuns[1];

  const failedShards = run.shardProgress?.filter((p: number) => p < 100).length ?? 0;
  const totalShards = run.shardProgress?.length ?? 0;

  return (
    <div className="ol-page">
      <PageHeader
        icon={<WarningOutlined />}
        title={
          <Space size={8}>
            失败诊断
            <Text code style={{ fontSize: 13 }}>{run.id}</Text>
          </Space>
        }
        subtitle={<span style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>{task.name} · <Text code style={{ fontSize: 12 }}>{task.targetTable}</Text></span>}
        breadcrumb={[
          { path: '/integration/sync-tasks', label: '采集任务' },
          { path: `/integration/sync-tasks/${task.id}`, label: task.name },
          { label: `诊断 · ${run.id}` },
        ]}
        actions={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/integration/sync-tasks/${task.id}`)}>返回任务</Button>}
      />

      <Alert
        type="error" showIcon
        icon={<ExclamationCircleOutlined style={{ fontSize: 18 }} />}
        style={{ borderRadius: 10, padding: '14px 16px', border: '1px solid var(--ol-error-soft)' }}
        message={
          <Space size={8}>
            <StatusBadge status="FAILED" label="失败" pulsing={false} />
            <Text strong>{run.errorMsg}</Text>
          </Space>
        }
        description={
          <Space split={<span className="ol-divider-v" />} wrap>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>错误码：<Text code>{run.errorCode}</Text></Text>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>开始：<Text code>{new Date(run.startedAt).toLocaleString('zh-CN')}</Text></Text>
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>耗时：{(run.durationMs ?? 0) / 1000}s</Text>
          </Space>
        }
      />

      <SectionCard title="处置建议（自动诊断）" icon={<StepForwardOutlined />}>
        <Steps
          size="small"
          direction="vertical"
          current={-1}
          items={[
            { title: '轮换源库密码', description: '通过 KMS 触发密钥轮换，热更新不中断其他任务', status: 'wait' },
            { title: '从 checkpoint 恢复', description: '基于最近位点 binlog.000128:4456 (02:00) 回放', status: 'wait' },
            { title: '重跑该任务', description: '从恢复点继续抽取，不重复处理已提交数据', status: 'wait' },
            { title: '验证下游', description: '检查 dwd.dwd_order_df 数据新鲜度与质量门禁', status: 'wait' },
          ]}
        />
      </SectionCard>

      <SectionCard title="下游影响分析" icon={<BranchesOutlined />}>
        <ImpactAnalysis impact={{
          assets: ['dwd.dwd_order_df'],
          tasks: [task.name],
          apis: ['/api/order/detail'],
          subscribers: 18,
          blocking: true,
          suggestion: '先轮换源库密码 → 从 checkpoint 恢复 → 重跑',
        }} />
      </SectionCard>

      <div className="ol-section" style={{ padding: '12px 16px' }}>
        <Space size={8} wrap>
          <Button type="primary" icon={<ReloadOutlined />} onClick={() => message.success('已重试')}>重试</Button>
          <Button icon={<StepForwardOutlined />} onClick={() => message.success('从 checkpoint 恢复中')}>从 checkpoint 恢复</Button>
          <Button onClick={() => message.info('已跳过 12 条坏记录')}>跳过坏记录</Button>
          <Button danger icon={<PauseCircleOutlined />} onClick={() => message.warning('已暂停管道')}>暂停管道</Button>
          <Button icon={<BranchesOutlined />} onClick={() => navigate('/catalog/lineage')}>查看血缘影响</Button>
        </Space>
      </div>
    </div>
  );
}
