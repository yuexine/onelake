/**
 * 规则配置（对应原型 §8.5.1 升级版）。
 */
import { Table, Tag, Space, Button, Modal, Form, Select, Input, Typography, message } from 'antd';
import { PlusOutlined, SafetyOutlined, CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { qualityRules } from '../../mock';
import { ClassificationBadge, StatusBadge, PageHeader, SectionCard, StateView } from '../../components';

const { Text } = Typography;

const RULE_LIBRARY = ['NOT_NULL', 'UNIQUE', 'RANGE', 'REGEX', 'ENUM', 'REFERENTIAL', 'DRIFT'];

export default function QualityRules() {
  const [open, setOpen] = useState(false);

  const counts = {
    total: qualityRules.length,
    enabled: qualityRules.filter((r) => r.enabled).length,
    block: qualityRules.filter((r) => r.severity === 'BLOCK').length,
    highPass: qualityRules.filter((r) => (r.lastPassRate ?? 0) > 95).length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyOutlined />}
        title="规则配置"
        subtitle={<span className="ol-chip">质量 · L3-4</span>}
        description="按资产/字段绑定规则，支持 BLOCK / WARN 严重度，随加工就绪或 CRON 触发"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建规则</Button>}
      />

      <SectionCard title="规则列表" icon={<SafetyOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={qualityRules}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无规则"
                description="新建质量规则，对资产和字段进行稽核"
                cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建规则</Button>}
              />
            ),
          }}
          size="middle"
          pagination={{ pageSize: 20 }}
          columns={[
            { title: '资产', dataIndex: 'targetFqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '字段', dataIndex: 'targetColumn', render: (c?: string) => c ? <Text code style={{ fontSize: 12 }}>{c}</Text> : <span className="ol-quiet">全表</span> },
            { title: '规则', dataIndex: 'ruleType', width: 110, render: (r: string) => <Tag color="blue" style={{ margin: 0 }}>{r}</Tag> },
            { title: '表达式', dataIndex: 'expression', ellipsis: true, render: (e: string) => <Text code style={{ fontSize: 11 }}>{e}</Text> },
            { title: '严重度', dataIndex: 'severity', width: 90, render: (s: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: s === 'BLOCK' ? 'var(--ol-error-soft)' : 'var(--ol-warning-soft)',
                color: s === 'BLOCK' ? 'var(--ol-error)' : '#B45309',
              }}>{s}</span>
            ) },
            { title: '最近通过率', dataIndex: 'lastPassRate', width: 110, render: (v?: number) => v ? (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: v > 95 ? 'var(--ol-success-soft)' : v > 90 ? 'var(--ol-warning-soft)' : 'var(--ol-error-soft)',
                color: v > 95 ? 'var(--ol-success)' : v > 90 ? '#B45309' : 'var(--ol-error)',
              }}>{v}%</span>
            ) : '-' },
            { title: '状态', dataIndex: 'enabled', width: 100, render: (e: boolean) => <StatusBadge status={e ? 'ACTIVE' : 'OFFLINE'} label={e ? '已启用' : '已停用'} /> },
            { title: '操作', width: 140, render: () => (
              <Space>
                <Button size="small" type="link">编辑</Button>
                <Button size="small" type="link">试跑</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <Modal
        open={open}
        title="新建规则"
        onCancel={() => setOpen(false)}
        width={680}
        onOk={() => { setOpen(false); message.success('规则已创建'); }}
      >
        <Form layout="vertical">
          <Form.Item label="绑定资产" required>
            <Select options={[{ label: 'dwd.dwd_order_df', value: 'a-1' }]} />
          </Form.Item>
          <Form.Item label="字段">
            <Select options={['order_id', 'amount', 'phone'].map((v) => ({ label: v, value: v }))} />
          </Form.Item>
          <Form.Item label="规则库（卡片选择）" required>
            <Space wrap>
              {RULE_LIBRARY.map((r) => <Tag key={r} color="blue" style={{ cursor: 'pointer' }}>{r}</Tag>)}
              <Tag color="default" style={{ cursor: 'pointer' }}>+ 自定义 SQL 规则</Tag>
            </Space>
          </Form.Item>
          <Form.Item label="阈值 / 表达式"><Input placeholder="0 ≤ amount ≤ 99999" /></Form.Item>
          <Form.Item label="严重度">
            <Select options={['BLOCK', 'WARN'].map((v) => ({ label: v, value: v }))} defaultValue="BLOCK" />
          </Form.Item>
          <Form.Item label="绑定调度">
            <Select options={[{ label: '随加工就绪触发', value: 'on' }, { label: 'CRON', value: 'cron' }]} />
          </Form.Item>
        </Form>
        <div className="ol-section" style={{ padding: 12, marginTop: 8, background: 'var(--ol-fill-soft)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 4 }}>试跑命中行数</div>
          <Text type="secondary" style={{ fontSize: 12 }}>点击「试跑」实时计算</Text>
        </div>
      </Modal>
    </div>
  );
}
