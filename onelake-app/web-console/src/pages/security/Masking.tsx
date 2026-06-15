/**
 * 脱敏策略（对应原型 §8.7.3 / §8.7.6 / §8.7.7）。
 */
import { Card, Table, Tag, Space, Button, Modal, Form, Select, Input, Typography, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { maskingPolicies } from '../../mock';
import { ClassificationBadge, DangerConfirm } from '../../components';

const { Text } = Typography;

export default function Masking() {
  const [createOpen, setCreateOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [conflictOpen, setConflictOpen] = useState(false);

  return (
    <Card title="资产与安全 / 脱敏策略" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建策略</Button>}>
      <Table rowKey="id" dataSource={maskingPolicies} size="middle"
        columns={[
          { title: '目标', dataIndex: 'targetFqn', render: (v: string) => <Text code>{v}</Text> },
          { title: '密级', dataIndex: 'classification', render: (c: string) => <ClassificationBadge level={c as any} /> },
          { title: '算法', dataIndex: 'strategy', render: (s: string) => <Tag color="orange">{s}</Tag> },
          { title: '参数', dataIndex: 'algorithm' },
          { title: '优先级', dataIndex: 'priority' },
          { title: '预览', render: (_: unknown, r: any) => r.preview ? <Space><Text code>{r.preview.input}</Text>→<Text code>{r.preview.output}</Text></Space> : '-' },
          { title: '操作', render: () => <Space><Button size="small" type="link" onClick={() => setConflictOpen(true)}>查看冲突</Button><Button size="small" type="link">编辑</Button></Space> },
        ]} />

      <Modal open={createOpen} onCancel={() => setCreateOpen(false)} title="新建脱敏策略" width={680}
        onOk={() => { setCreateOpen(false); setConfirmOpen(true); }}>
        <Form layout="vertical">
          <Form.Item label="适用范围（密级）"><Select options={['L1', 'L2', 'L3', 'L4'].map((v: any) => ({ label: v, value: v }))} defaultValue="L3" /></Form.Item>
          <Form.Item label="字段类型"><Select options={['手机号', '身份证', '银行卡', '邮箱', '姓名'].map((v: any) => ({ label: v, value: v }))} defaultValue="手机号" /></Form.Item>
          <Form.Item label="算法"><Select options={['MASK', 'HASH', 'NULLIFY', 'PARTIAL', 'GENERALIZE', 'RANDOMIZE'].map((v: any) => ({ label: v, value: v }))} defaultValue="MASK" /></Form.Item>
          <Form.Item label="算法参数"><Input placeholder="保留前 3 后 4" /></Form.Item>
          <Form.Item label="静态（采集前 L1-1.2.6）"><Tag color="processing">启用</Tag></Form.Item>
          <Form.Item label="动态（API 返回 L5-3.1.4）"><Tag color="processing">启用</Tag></Form.Item>
          <Card size="small" title="预览（所见即所得）">
            <Space>原值：13812348888 → 脱敏后：138****8888</Space>
          </Card>
        </Form>
      </Modal>

      <DangerConfirm open={confirmOpen} title="保存脱敏策略" impactLevel="HIGH"
        description="该策略变更将影响以下资产和 API"
        impacts={[{ label: '受影响资产', value: 12 }, { label: '受影响 API', value: 5 }]}
        okText="确认变更" okType="primary"
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => { setConfirmOpen(false); message.success('已保存，全站随动生效'); }}
      />

      <Modal open={conflictOpen} onCancel={() => setConflictOpen(false)} title="策略冲突 - 字段 phone" footer={null}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Card size="small"><Text>命中策略 A：密级 L3 → 掩码 138****8888</Text></Card>
          <Card size="small"><Text>命中策略 B：Consumer 角色 → 全隐藏 ***</Text></Card>
          <Card size="small" type="inner" title="最终生效"><Text strong>取最高安全等级：全隐藏 ***</Text></Card>
          <Text type="secondary">冲突说明：角色策略优先级 &gt; 密级默认策略</Text>
          <Space><Button>申请豁免（需审批）</Button><Button>调整优先级</Button></Space>
        </Space>
      </Modal>
    </Card>
  );
}
