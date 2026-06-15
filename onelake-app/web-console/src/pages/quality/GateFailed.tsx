/**
 * 质量门禁失败处理（对应原型 §8.5.3 升级版）。
 */
import { Tag, Space, Button, Alert, Table, Typography, Radio, message } from 'antd';
import { SafetyOutlined, WarningOutlined, AuditOutlined } from '@ant-design/icons';
import { ImpactAnalysis, PageHeader, SectionCard } from '../../components';
import { gateExemptions, qualityResults } from '../../mock';
import { useState } from 'react';

const { Text } = Typography;

export default function GateFailed() {
  const [action, setAction] = useState('block');
  const result = qualityResults[0];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<WarningOutlined />}
        title={
          <Space size={8}>
            质量门禁失败
            <Text code style={{ fontSize: 13 }}>dwd_order_df @ 06-14</Text>
          </Space>
        }
        subtitle={<span className="ol-chip">质量 · L3-4</span>}
        description="阻断发布 / 豁免审批 / 降级告警 / 修复重跑，含下游影响分析"
      />

      <Alert
        type="error" showIcon
        icon={<WarningOutlined style={{ fontSize: 18 }} />}
        style={{ borderRadius: 10 }}
        message={
          <Space>
            <Text strong>失败规则：</Text>
            <Tag color="processing" style={{ margin: 0 }}>RANGE</Tag>
            <Text>范围 amount ∈ [0, 99999]，命中</Text>
            <Text strong style={{ color: 'var(--ol-error)' }}>{result.failedRows}</Text>
            <Text>行</Text>
          </Space>
        }
      />

      <SectionCard title="异常行样例" icon={<WarningOutlined />} flatBody>
        <Table size="middle" rowKey="order_id" dataSource={result.sample} pagination={false}
          columns={[
            { title: 'order_id', dataIndex: 'order_id', render: (v: number) => <Text code>{v}</Text> },
            { title: 'amount', dataIndex: 'amount', render: (v: number) => <Tag color="error" style={{ margin: 0 }}>{v}</Tag> },
            { title: 'status', dataIndex: 'status', render: (s: string) => <span className="ol-chip">{s}</span> },
            { title: 'phone', dataIndex: 'phone', render: (p: string) => <Text code style={{ fontSize: 12 }}>{p}</Text> },
          ]} />
      </SectionCard>

      <SectionCard title="下游影响分析" icon={<SafetyOutlined />}>
        <ImpactAnalysis impact={{
          assets: ['dws.dws_user_order'],
          apis: ['/api/order/detail'],
          blocking: true,
          suggestion: '修复数据后重跑 / 临时豁免（需审批） / 降级为告警 / 阻断发布',
        }} />
      </SectionCard>

      <SectionCard title="处理动作" icon={<AuditOutlined />}>
        <Radio.Group value={action} onChange={(e) => setAction(e.target.value)}>
          <Space direction="vertical" size={10}>
            <Radio value="fix">修复数据后重跑</Radio>
            <Radio value="exempt">临时豁免（需审批）</Radio>
            <Radio value="warn">降级为告警</Radio>
            <Radio value="block">阻断发布</Radio>
          </Space>
        </Radio.Group>
        <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px dashed var(--ol-line-soft)' }}>
          <Space>
            <Button type="primary" onClick={() => message.success(`已应用：${action}`)}>应用</Button>
            {action === 'exempt' && <Text type="secondary" style={{ fontSize: 12 }}>将提交审批，由安全合规确认</Text>}
          </Space>
        </div>
      </SectionCard>

      <SectionCard title="审批记录" icon={<AuditOutlined />} flatBody>
        <Table size="middle" rowKey="id" dataSource={gateExemptions} pagination={false}
          columns={[
            { title: '规则', dataIndex: 'rule', render: (r: string) => <Text code style={{ fontSize: 12 }}>{r}</Text> },
            { title: '申请人', dataIndex: 'applicant' },
            { title: '时间', dataIndex: 'at', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '原因', dataIndex: 'reason' },
            { title: '状态', dataIndex: 'status', render: () => <Tag color="processing" style={{ margin: 0 }}>待审批</Tag> },
          ]} />
      </SectionCard>
    </div>
  );
}
