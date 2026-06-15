/**
 * 质量门禁失败处理（对应原型 §8.5.3 · 审查补全）。
 */
import { Card, Tag, Space, Button, Descriptions, Alert, Table, Typography, Radio, message } from 'antd';
import { ImpactAnalysis } from '../../components';
import { gateExemptions, qualityResults } from '../../mock';
import { useState } from 'react';

const { Text } = Typography;

export default function GateFailed() {
  const [action, setAction] = useState('block');
  const result = qualityResults[0];

  return (
    <Card title="质量门禁失败 · dwd_order_df @06-14">
      <Alert type="error" message={<>失败规则：<Text strong>范围 (amount 0~99999)</Text>，命中 <Text strong>{result.failedRows}</Text> 行</>} style={{ marginBottom: 16 }} />

      <Card size="small" title="异常行样例" style={{ marginBottom: 16 }}>
        <Table size="small" rowKey="order_id" dataSource={result.sample} pagination={false}
          columns={[
            { title: 'order_id', dataIndex: 'order_id' },
            { title: 'amount', dataIndex: 'amount', render: (v: number) => <Tag color="error">{v}</Tag> },
            { title: 'status', dataIndex: 'status' },
            { title: 'phone', dataIndex: 'phone' },
          ]} />
      </Card>

      <ImpactAnalysis impact={{
        assets: ['dws.dws_user_order'], apis: ['/api/order/detail'],
        blocking: true, suggestion: '修复数据后重跑 / 临时豁免（需审批） / 降级为告警 / 阻断发布',
      }} />

      <Card size="small" title="处理动作" style={{ marginTop: 16 }}>
        <Radio.Group value={action} onChange={(e) => setAction(e.target.value)}>
          <Space direction="vertical">
            <Radio value="fix">修复数据后重跑</Radio>
            <Radio value="exempt">临时豁免（需审批）</Radio>
            <Radio value="warn">降级为告警</Radio>
            <Radio value="block">阻断发布</Radio>
          </Space>
        </Radio.Group>
        <div style={{ marginTop: 16 }}>
          <Space>
            <Button type="primary" onClick={() => message.success(`已应用：${action}`)}>应用</Button>
            {action === 'exempt' && <Text type="secondary">将提交审批，由安全合规确认</Text>}
          </Space>
        </div>
      </Card>

      <Card size="small" title="审批记录" style={{ marginTop: 16 }}>
        <Table size="small" rowKey="id" dataSource={gateExemptions} pagination={false}
          columns={[
            { title: '规则', dataIndex: 'rule' },
            { title: '申请人', dataIndex: 'applicant' },
            { title: '时间', dataIndex: 'at' },
            { title: '原因', dataIndex: 'reason' },
            { title: '状态', dataIndex: 'status', render: () => <Tag color="processing">待审批</Tag> },
          ]} />
      </Card>
    </Card>
  );
}
