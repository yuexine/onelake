/**
 * 规则配置（对应原型 §8.5.1）。
 */
import { Card, Table, Tag, Space, Button, Modal, Form, Select, Input, Typography, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { qualityRules } from '../../mock';
import { ClassificationBadge, StatusBadge } from '../../components';

const { Text } = Typography;

const RULE_LIBRARY = ['NOT_NULL', 'UNIQUE', 'RANGE', 'REGEX', 'ENUM', 'REFERENTIAL', 'DRIFT'];

export default function QualityRules() {
  const [open, setOpen] = useState(false);
  return (
    <Card title="数据质量 / 规则配置" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建规则</Button>}>
      <Table rowKey="id" dataSource={qualityRules} size="middle"
        columns={[
          { title: '资产', dataIndex: 'targetFqn' },
          { title: '字段', dataIndex: 'targetColumn' },
          { title: '规则', dataIndex: 'ruleType', render: (r: string) => <Tag color="blue">{r}</Tag> },
          { title: '表达式', dataIndex: 'expression', ellipsis: true },
          { title: '严重度', dataIndex: 'severity', render: (s: string) => <Tag color={s === 'BLOCK' ? 'red' : 'orange'}>{s}</Tag> },
          { title: '最近通过率', dataIndex: 'lastPassRate', render: (v?: number) => v ? <Tag color={v > 95 ? 'success' : v > 90 ? 'warning' : 'error'}>{v}%</Tag> : '-' },
          { title: '状态', dataIndex: 'enabled', render: (e: boolean) => <StatusBadge status={e ? 'ACTIVE' : 'OFFLINE'} label={e ? '已启用' : '已停用'} /> },
          { title: '操作', render: () => <Space><Button size="small" type="link">编辑</Button><Button size="small" type="link">试跑</Button></Space> },
        ]} />

      <Modal open={open} title="新建规则" onCancel={() => setOpen(false)} width={680}
        onOk={() => { setOpen(false); message.success('规则已创建'); }}>
        <Form layout="vertical">
          <Form.Item label="绑定资产" required><Select options={[{ label: 'dwd.dwd_order_df', value: 'a-1' }].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
          <Form.Item label="字段"><Select options={['order_id', 'amount', 'phone'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
          <Form.Item label="规则库（卡片选择）" required>
            <Space wrap>
              {RULE_LIBRARY.map((r) => <Tag key={r} color="blue" style={{ cursor: 'pointer' }}>{r}</Tag>)}
              <Tag color="default">+ 自定义 SQL 规则</Tag>
            </Space>
          </Form.Item>
          <Form.Item label="阈值/表达式"><Input placeholder="0 ≤ amount ≤ 99999" /></Form.Item>
          <Form.Item label="严重度"><Select options={['BLOCK', 'WARN'].map((v: any) => ({ label: v, value: v }))} defaultValue="BLOCK" /></Form.Item>
          <Form.Item label="绑定调度"><Select options={[{ label: '随加工就绪触发', value: 'on' }, { label: 'CRON', value: 'cron' }].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
        </Form>
        <Card size="small" title="试跑命中行数"><Text type="secondary">点击「试跑」实时计算</Text></Card>
      </Modal>
    </Card>
  );
}
