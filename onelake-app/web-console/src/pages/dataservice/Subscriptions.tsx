/**
 * 订阅与计量（对应原型 §8.8.4 / §8.8.9）。
 */
import { Card, Table, Tag, Space, Button, Tabs, Form, Input, Select, InputNumber, Modal, message } from 'antd';
import { useState } from 'react';
import { subscriptions, quotaRaises } from '../../mock';
import { StatusBadge } from '../../components';

export default function Subscriptions() {
  const [applyOpen, setApplyOpen] = useState(false);
  return (
    <Card title="数据服务 / 订阅与计量">
      <Tabs items={[
        { key: 'mine', label: '我的订阅', children: (
          <Table rowKey="id" dataSource={subscriptions} size="middle"
            columns={[
              { title: 'API', dataIndex: 'apiPath' },
              { title: '订阅方', dataIndex: 'subscriberName' },
              { title: '原因', dataIndex: 'reason', ellipsis: true },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
              { title: '审批人', dataIndex: 'approvedBy' },
              { title: '时间', dataIndex: 'createdAt' },
              { title: '操作', render: () => <Button size="small" type="link">详情</Button> },
            ]} />
        ) },
        { key: 'usage', label: '计量看板', children: (
          <>
            <Space style={{ marginBottom: 16 }}>
              <Tag color="processing">调用量 12k/日</Tag>
              <Tag color="success">成功率 99.2%</Tag>
              <Tag>P99 时延 80ms</Tag>
              <Tag color="warning">超配额返 429 ×120 次</Tag>
            </Space>
            <Table size="small" dataSource={[
              { api: '/api/order/detail', todayCalls: 8200, todayQps: 32, quotaQps: 50, successRate: '99.5%' },
              { api: '/api/sales/daily', todayCalls: 1200, todayQps: 5, quotaQps: 100, successRate: '100%' },
            ]} pagination={false}
              columns={[
                { title: 'API', dataIndex: 'api' },
                { title: '今日调用', dataIndex: 'todayCalls' },
                { title: '实际 QPS', dataIndex: 'todayQps' },
                { title: 'QPS 上限', dataIndex: 'quotaQps' },
                { title: '成功率', dataIndex: 'successRate' },
              ]} />
            <Button type="primary" style={{ marginTop: 16 }} onClick={() => setApplyOpen(true)}>申请升额</Button>
          </>
        ) },
        { key: 'raise', label: `升额申请 (${quotaRaises.length})`, children: (
          <Table size="small" rowKey="id" dataSource={quotaRaises}
            columns={[
              { title: 'API', dataIndex: 'apiPath' },
              { title: '申请人', dataIndex: 'applicant' },
              { title: '当前→申请', render: (_: unknown, r: any) => `${r.current} → ${r.requested} QPS` },
              { title: '理由', dataIndex: 'reason' },
              { title: '有效期', dataIndex: 'period' },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
            ]} />
        ) },
      ]} />

      <Modal open={applyOpen} onCancel={() => setApplyOpen(false)} title="升额申请"
        onOk={() => { setApplyOpen(false); message.success('已提交升额申请，进入审批中心'); }}>
        <Form layout="vertical">
          <Form.Item label="API"><Select options={['/api/order/detail', '/api/sales/daily'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
          <Form.Item label="当前配额"><Input value="20 QPS" disabled /></Form.Item>
          <Form.Item label="申请配额"><InputNumber defaultValue={50} /> QPS</Form.Item>
          <Form.Item label="理由"><Input.TextArea placeholder="大促期间流量上涨" /></Form.Item>
          <Form.Item label="有效期"><Select options={[{ label: '永久', value: 'forever' }, { label: '临时（大促周期）', value: 'temp' }].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
