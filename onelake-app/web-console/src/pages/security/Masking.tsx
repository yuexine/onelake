/**
 * 脱敏策略（对应原型 §8.7.3 升级版）。
 */
import { Table, Tag, Space, Button, Modal, Form, Select, Input, Typography, message } from 'antd';
import { PlusOutlined, LockOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { maskingPolicies } from '../../mock';
import { ClassificationBadge, DangerConfirm, PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Masking() {
  const [createOpen, setCreateOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [conflictOpen, setConflictOpen] = useState(false);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<LockOutlined />}
        title="脱敏策略"
        subtitle={<span className="ol-chip">安全 · L4-3</span>}
        description="MASK / HASH / NULLIFY / PARTIAL / GENERALIZE / RANDOMIZE 六种算法，静态 + 动态双链路生效"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建策略</Button>}
      />

      <SectionCard title="策略列表" icon={<LockOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={maskingPolicies}
          size="middle"
          pagination={false}
          columns={[
            { title: '目标', dataIndex: 'targetFqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '密级', dataIndex: 'classification', width: 110, render: (c: string) => <ClassificationBadge level={c as any} /> },
            { title: '算法', dataIndex: 'strategy', width: 110, render: (s: string) => (
              <Tag color="orange" style={{ margin: 0 }}>{s}</Tag>
            ) },
            { title: '参数', dataIndex: 'algorithm', render: (a?: string) => a ? <span className="ol-quiet">{a}</span> : '-' },
            { title: '优先级', dataIndex: 'priority', width: 80, align: 'right' as const },
            { title: '预览', render: (_: unknown, r: any) => r.preview ? (
              <Space size={6}>
                <Text code style={{ fontSize: 11 }}>{r.preview.input}</Text>
                <span style={{ color: 'var(--ol-ink-4)' }}>→</span>
                <Text code style={{ fontSize: 11, color: 'var(--ol-brand)' }}>{r.preview.output}</Text>
              </Space>
            ) : '-' },
            { title: '操作', width: 160, render: () => (
              <Space>
                <Button size="small" type="link" onClick={() => setConflictOpen(true)}>查看冲突</Button>
                <Button size="small" type="link">编辑</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <Modal
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        title="新建脱敏策略"
        width={680}
        onOk={() => { setCreateOpen(false); setConfirmOpen(true); }}
      >
        <Form layout="vertical">
          <Form.Item label="适用范围（密级）">
            <Select options={['L1', 'L2', 'L3', 'L4'].map((v) => ({ label: v, value: v }))} defaultValue="L3" />
          </Form.Item>
          <Form.Item label="字段类型">
            <Select options={['手机号', '身份证', '银行卡', '邮箱', '姓名'].map((v) => ({ label: v, value: v }))} defaultValue="手机号" />
          </Form.Item>
          <Form.Item label="算法">
            <Select options={['MASK', 'HASH', 'NULLIFY', 'PARTIAL', 'GENERALIZE', 'RANDOMIZE'].map((v) => ({ label: v, value: v }))} defaultValue="MASK" />
          </Form.Item>
          <Form.Item label="算法参数"><Input placeholder="保留前 3 后 4" /></Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item label="静态（采集前 L1-1.2.6）"><Tag color="processing">启用</Tag></Form.Item>
            <Form.Item label="动态（API 返回 L5-3.1.4）"><Tag color="processing">启用</Tag></Form.Item>
          </div>
          <div className="ol-section" style={{ padding: 12, background: 'var(--ol-fill-soft)' }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 6 }}>预览（所见即所得）</div>
            <Space>
              原值：<Text code style={{ fontSize: 12 }}>13812348888</Text>
              <span style={{ color: 'var(--ol-ink-4)' }}>→</span>
              脱敏后：<Text code style={{ fontSize: 12, color: 'var(--ol-brand)' }}>138****8888</Text>
            </Space>
          </div>
        </Form>
      </Modal>

      <DangerConfirm
        open={confirmOpen}
        title="保存脱敏策略"
        impactLevel="HIGH"
        description="该策略变更将影响以下资产和 API"
        impacts={[{ label: '受影响资产', value: 12 }, { label: '受影响 API', value: 5 }]}
        okText="确认变更" okType="primary"
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => { setConfirmOpen(false); message.success('已保存，全站随动生效'); }}
      />

      <Modal open={conflictOpen} onCancel={() => setConflictOpen(false)} title="策略冲突 · 字段 phone" footer={null}>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <div className="ol-section" style={{ padding: 12 }}>
            <Text>命中策略 A：密级 L3 → <Text code style={{ fontSize: 12 }}>138****8888</Text></Text>
          </div>
          <div className="ol-section" style={{ padding: 12 }}>
            <Text>命中策略 B：Consumer 角色 → <Text code style={{ fontSize: 12 }}>***</Text></Text>
          </div>
          <div className="ol-section" style={{ padding: 12, background: 'var(--ol-brand-soft)', border: '1px solid var(--ol-brand-border)' }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-brand)', marginBottom: 4 }}>最终生效</div>
            <Text strong>取最高安全等级：<Text code>***</Text></Text>
          </div>
          <Text type="secondary" style={{ fontSize: 12 }}>冲突说明：角色策略优先级 &gt; 密级默认策略</Text>
          <Space>
            <Button>申请豁免（需审批）</Button>
            <Button>调整优先级</Button>
          </Space>
        </Space>
      </Modal>
    </div>
  );
}
