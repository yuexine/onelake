/**
 * 质量门禁失败处理（对应原型 §8.5.3 升级版）。
 */
import { Tag, Space, Button, Alert, Table, Typography, Radio, App as AntdApp } from 'antd';
import { SafetyOutlined, WarningOutlined, AuditOutlined } from '@ant-design/icons';
import { ImpactAnalysis, PageHeader, SectionCard, StateView } from '../../components';
import { QualityAPI } from '../../api';
import type { QualityAlert } from '../../types';
import { useEffect, useMemo, useState } from 'react';

const { Text } = Typography;

export default function GateFailed() {
  const { message } = AntdApp.useApp();
  const [action, setAction] = useState('block');
  const [alerts, setAlerts] = useState<QualityAlert[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  const loadAlerts = () => {
    setLoading(true);
    QualityAPI.openAlerts()
      .then(setAlerts)
      .catch((error) => {
        message.error(error.message || '质量门禁告警加载失败');
        setAlerts([]);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAlerts();
  }, []);

  const alert = alerts[0];
  const sampleRows = alert?.sample || [];
  const impact = useMemo(() => ({
    assets: alert?.targetFqn ? [alert.targetFqn] : [],
    apis: [],
    blocking: alert?.level === 'CRITICAL',
    suggestion: '修复数据后重跑 / 临时豁免 / 降级为告警 / 阻断发布',
  }), [alert?.level, alert?.targetFqn]);

  const applyAction = async () => {
    if (!alert) return;
    if (action === 'block') {
      message.info('已保持阻断状态');
      return;
    }
    setSubmitting(true);
    try {
      await QualityAPI.closeAlert(alert.id);
      message.success('已处理当前质量告警');
      loadAlerts();
    } catch (error: any) {
      message.error(error.message || '处理质量告警失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<WarningOutlined />}
        title={
          <Space size={8}>
            质量门禁失败
            {alert?.targetFqn && <Text code style={{ fontSize: 13 }}>{alert.targetFqn}</Text>}
          </Space>
        }
        subtitle={<span className="ol-chip">质量 · {alert?.level || '待处理'}</span>}
        description="阻断发布 / 豁免审批 / 降级告警 / 修复重跑，含下游影响分析"
      />

      {loading && <SectionCard title="待处理质量告警" icon={<WarningOutlined />}><StateView state="loading" rows={4} /></SectionCard>}

      {!loading && !alert && (
        <SectionCard title="待处理质量告警" icon={<WarningOutlined />}>
          <StateView state="empty" title="暂无质量门禁失败" description="当前没有待处理的质量告警" />
        </SectionCard>
      )}

      {!loading && alert && (
        <>

      <Alert
        type="error" showIcon
        icon={<WarningOutlined style={{ fontSize: 18 }} />}
        style={{ borderRadius: 10 }}
        message={
          <Space>
            <Text strong>失败规则：</Text>
            <Tag color="processing" style={{ margin: 0 }}>{alert.ruleType || '-'}</Tag>
            <Text>{alert.expression || alert.message}</Text>
            <Text>，命中</Text>
            <Text strong style={{ color: 'var(--ol-error)' }}>{alert.failedRows ?? 0}</Text>
            <Text>行</Text>
          </Space>
        }
      />

      <SectionCard title="异常行样例" icon={<WarningOutlined />} flatBody>
        <Table size="middle" rowKey={(row) => `${row.row || 'row'}-${row.column || 'column'}-${row.value || 'value'}`} dataSource={sampleRows} pagination={false}
          columns={[
            { title: '行号', dataIndex: 'row', render: (v: unknown) => <Text code>{String(v || '-')}</Text> },
            { title: '资产', dataIndex: 'targetFqn', render: (v: unknown) => <Text code style={{ fontSize: 12 }}>{String(v || alert.targetFqn || '-')}</Text> },
            { title: '字段', dataIndex: 'column', render: (v: unknown) => <span className="ol-chip">{String(v || alert.targetColumn || '-')}</span> },
            { title: '异常值', dataIndex: 'value', render: (v: unknown) => <Tag color="error" style={{ margin: 0 }}>{String(v ?? '-')}</Tag> },
            { title: '规则', dataIndex: 'ruleType', render: (v: unknown) => <Tag color="processing" style={{ margin: 0 }}>{String(v || alert.ruleType || '-')}</Tag> },
          ]} />
      </SectionCard>

      <SectionCard title="下游影响分析" icon={<SafetyOutlined />}>
        <ImpactAnalysis impact={impact} />
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
            <Button type="primary" loading={submitting} onClick={applyAction}>应用</Button>
            {action === 'exempt' && <Text type="secondary" style={{ fontSize: 12 }}>需安全合规确认</Text>}
          </Space>
        </div>
      </SectionCard>

      <SectionCard title="审批记录" icon={<AuditOutlined />} flatBody>
        <Table size="middle" rowKey="id" dataSource={[]} pagination={false}
          columns={[
            { title: '规则', dataIndex: 'rule', render: (r: string) => <Text code style={{ fontSize: 12 }}>{r}</Text> },
            { title: '申请人', dataIndex: 'applicant' },
            { title: '时间', dataIndex: 'at', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '原因', dataIndex: 'reason' },
            { title: '状态', dataIndex: 'status', render: () => <Tag color="processing" style={{ margin: 0 }}>待审批</Tag> },
          ]} />
      </SectionCard>
        </>
      )}
    </div>
  );
}
