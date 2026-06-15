/**
 * Schema 变更审批详情（对应原型 §8.2.11 升级版）。
 *   - diff 视图（before / after）
 *   - 影响分析
 *   - 决策按钮（通过 / 驳回 / 要求人工映射）
 */
import { useParams, useNavigate } from 'react-router-dom';
import {
  Tag, Space, Button, Typography, message, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, CheckOutlined, CloseOutlined, WarningOutlined,
  ApartmentOutlined, ArrowRightOutlined, UserSwitchOutlined,
} from '@ant-design/icons';
import {
  PageHeader, ImpactAnalysis, SectionCard, StateView,
} from '../../components';
import { schemaChangeRequests } from '../../mock';

const { Text } = Typography;

export default function SchemaChangeApproval() {
  const { id } = useParams();
  const navigate = useNavigate();
  const req = schemaChangeRequests.find((r) => r.id === id);

  if (!req) {
    return (
      <div className="ol-page">
        <StateView
          state="empty"
          title="审批单不存在或已被处理"
          description={`未找到 ID 为 ${id} 的 Schema 变更审批单，可能已被其他审批人处理`}
          cta={<button className="ol-link" onClick={() => navigate('/integration/cdc')}>← 返回 CDC 监控</button>}
        />
      </div>
    );
  }

  const destructive = !req.compatible;

  return (
    <div className="ol-page">
      <PageHeader
        icon={<ApartmentOutlined />}
        title={
          <Space size={8}>
            Schema 变更审批
            <Text code style={{ fontSize: 13 }}>{req.table}</Text>
          </Space>
        }
        subtitle={<span style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>来源任务 <Text code>{req.sourceName}</Text> · {new Date(req.createdAt).toLocaleString('zh-CN')}</span>}
        breadcrumb={[
          { path: '/integration/cdc', label: 'CDC 监控' },
          { label: `审批 · ${req.table}` },
        ]}
        actions={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/integration/cdc')}>返回</Button>}
      />

      <Alert
        type={destructive ? 'error' : 'success'}
        showIcon
        icon={<WarningOutlined style={{ fontSize: 18 }} />}
        style={{ borderRadius: 10, padding: '14px 16px' }}
        message={
          <Space size={8}>
            <Tag color={destructive ? 'error' : 'success'} style={{ margin: 0 }}>{destructive ? '破坏性' : '兼容'}</Tag>
            <Text strong>{req.change}</Text>
          </Space>
        }
        description={
          <Text style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>
            {destructive
              ? '此变更将影响下游消费方，未审批期间按旧 schema 缓冲写入，仅暂停该表管道、不影响其他表'
              : '此变更为兼容性变更，已自动应用并通知下游'}
          </Text>
        }
      />

      <SectionCard title="Schema Diff" icon={<ApartmentOutlined />}>
        <div
          style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 0,
            border: '1px solid var(--ol-line-soft)', borderRadius: 8, overflow: 'hidden',
          }}
        >
          <div style={{ padding: 16, background: 'var(--ol-fill-soft)', borderRight: '1px solid var(--ol-line-soft)' }}>
            <div style={{ fontSize: 11, color: 'var(--ol-ink-3)', marginBottom: 8, fontWeight: 600 }}>变更前</div>
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              {['id', 'amount', 'phone', 'age'].map((col) => (
                <div key={col} style={{
                  display: 'flex', alignItems: 'center', gap: 6,
                  padding: '4px 8px', borderRadius: 4,
                  background: col === 'age' ? 'var(--ol-error-soft)' : 'transparent',
                  border: col === 'age' ? '1px solid #FCA5A5' : '1px solid transparent',
                }}>
                  <span className="ol-status-dot is-success" />
                  <Text code style={{ fontSize: 12 }}>{col}</Text>
                </div>
              ))}
            </Space>
          </div>
          <div style={{ padding: 16, background: 'var(--ol-card)' }}>
            <div style={{ fontSize: 11, color: 'var(--ol-ink-3)', marginBottom: 8, fontWeight: 600 }}>变更后</div>
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              {['id', 'amount', 'phone', 'age'].map((col) => {
                const removed = col === 'age';
                return (
                  <div key={col} style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    padding: '4px 8px', borderRadius: 4,
                    background: removed ? 'var(--ol-error-soft)' : 'transparent',
                    border: removed ? '1px solid #FCA5A5' : '1px solid transparent',
                    textDecoration: removed ? 'line-through' : 'none',
                    opacity: removed ? 0.6 : 1,
                  }}>
                    {removed ? <span className="ol-status-dot is-error" /> : <span className="ol-status-dot is-success" />}
                    <Text code style={{ fontSize: 12 }}>{col}</Text>
                    {removed && <Tag color="error" style={{ margin: 0, marginLeft: 'auto', fontSize: 10 }}>REMOVED</Tag>}
                  </div>
                );
              })}
            </Space>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="影响分析与处理建议" icon={<WarningOutlined />}>
        <ImpactAnalysis impact={{
          assets: req.impact.downstreamTables,
          tasks: req.impact.tasks,
          apis: req.impact.apis,
          blocking: !req.compatible,
          suggestion: req.compatible
            ? '兼容变更，可直接应用'
            : '通过 → 应用变更 → 通知下游 → 任务恢复；驳回 → 保持旧 schema',
        }} />
      </SectionCard>

      <div className="ol-section" style={{ padding: '12px 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {req.bufferStrategy}
        </Text>
        <Space size={8}>
          <Button icon={<UserSwitchOutlined />} onClick={() => message.warning({ content: '人工字段映射流程待接入：将通过审批中心创建字段映射工单', duration: 4 })}>要求人工字段映射</Button>
          <Button danger icon={<CloseOutlined />} onClick={() => { message.success('已驳回 → 保持旧 schema'); navigate('/integration/cdc'); }}>驳回</Button>
          <Button type="primary" icon={<CheckOutlined />} disabled={!destructive} onClick={() => {
            message.success('已通过 → 应用变更 → 通知下游 → 恢复任务');
            navigate('/integration/cdc');
          }}>通过并应用</Button>
        </Space>
      </div>
    </div>
  );
}
