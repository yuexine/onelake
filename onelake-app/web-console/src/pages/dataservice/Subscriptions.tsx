/**
 * 订阅与计量（对应原型 §8.8.4 / §8.8.9 升级版）。
 */
import { Table, Tag, Space, Button, Tabs, Form, Input, Select, InputNumber, Modal, message, Typography } from 'antd';
import { TeamOutlined, BarChartOutlined, ArrowUpOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { subscriptions, quotaRaises } from '../../mock';
import { StatusBadge, PageHeader, SectionCard, useAsyncAction } from '../../components';

const { Text } = Typography;

export default function Subscriptions() {
  const [applyOpen, setApplyOpen] = useState(false);
  const { run, isLoading } = useAsyncAction();

  return (
    <div className="ol-page">
      <PageHeader
        icon={<TeamOutlined />}
        title="订阅与计量"
        subtitle={<span className="ol-chip">数据服务 · L5-4</span>}
        description="订阅审批、计量看板、升额申请，全链路配额管理"
      />

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div style={{ padding: '0 16px' }}>
          <Tabs items={[
            { key: 'mine', label: '我的订阅', children: (
              <Table rowKey="id" dataSource={subscriptions} size="middle"
                columns={[
                  { title: 'API', dataIndex: 'apiPath', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
                  { title: '订阅方', dataIndex: 'subscriberName' },
                  { title: '原因', dataIndex: 'reason', ellipsis: true, render: (r: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{r}</span> },
                  { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
                  { title: '审批人', dataIndex: 'approvedBy', render: (a?: string) => a || '-' },
                  { title: '时间', dataIndex: 'createdAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
                  { title: '操作', width: 100, render: () => <Button size="small" type="link">详情</Button> },
                ]} />
            ) },
            { key: 'usage', label: '计量看板', children: (
              <>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Tag color="processing" style={{ padding: '2px 10px' }}>调用量 12k/日</Tag>
                  <Tag color="success" style={{ padding: '2px 10px' }}>成功率 99.2%</Tag>
                  <Tag style={{ padding: '2px 10px' }}>P99 时延 80ms</Tag>
                  <Tag color="warning" style={{ padding: '2px 10px' }}>超配额返 429 ×120 次</Tag>
                </Space>
                <Table size="middle" dataSource={[
                  { key: 1, api: '/api/order/detail', todayCalls: 8200, todayQps: 32, quotaQps: 50, successRate: '99.5%' },
                  { key: 2, api: '/api/sales/daily', todayCalls: 1200, todayQps: 5, quotaQps: 100, successRate: '100%' },
                ]} pagination={false}
                  columns={[
                    { title: 'API', dataIndex: 'api', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
                    { title: '今日调用', dataIndex: 'todayCalls', align: 'right' as const, render: (v: number) => <span className="mono tnum">{v.toLocaleString()}</span> },
                    { title: '实际 QPS', dataIndex: 'todayQps', align: 'right' as const, render: (v: number) => <span className="mono tnum">{v}</span> },
                    { title: 'QPS 上限', dataIndex: 'quotaQps', align: 'right' as const, render: (v: number) => <span className="mono tnum">{v}</span> },
                    { title: '成功率', dataIndex: 'successRate', render: (s: string) => (
                      <span style={{
                        padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                        background: 'var(--ol-success-soft)', color: 'var(--ol-success)',
                      }}>{s}</span>
                    ) },
                  ]} />
                <Button type="primary" style={{ marginTop: 16 }} onClick={() => setApplyOpen(true)}>申请升额</Button>
              </>
            ) },
            { key: 'raise', label: `升额申请 (${quotaRaises.length})`, children: (
              <Table size="middle" rowKey="id" dataSource={quotaRaises}
                columns={[
                  { title: 'API', dataIndex: 'apiPath', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
                  { title: '申请人', dataIndex: 'applicant' },
                  { title: '当前 → 申请', render: (_: unknown, r: any) => (
                    <Space>
                      <Text code style={{ fontSize: 11 }}>{r.current}</Text>
                      <span style={{ color: 'var(--ol-ink-4)' }}>→</span>
                      <Text code style={{ fontSize: 11, color: 'var(--ol-brand)' }}>{r.requested} QPS</Text>
                    </Space>
                  ) },
                  { title: '理由', dataIndex: 'reason', ellipsis: true },
                  { title: '有效期', dataIndex: 'period', render: (p: string) => <span className="ol-chip">{p}</span> },
                  { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
                ]} />
            ) },
          ]} />
        </div>
      </SectionCard>

      <Modal
        open={applyOpen}
        onCancel={() => setApplyOpen(false)}
        title="升额申请"
        onOk={() => run('quota-raise', async () => {
          await new Promise((r) => setTimeout(r, 600));
          setApplyOpen(false);
        }, {
          successMsg: '已提交升额申请，进入审批中心',
          errorMsg: '升额申请提交失败，请重试',
          duration: 2.5,
        })}
      >
        <Form layout="vertical">
          <Form.Item label="API">
            <Select options={['/api/order/detail', '/api/sales/daily'].map((v) => ({ label: v, value: v }))} />
          </Form.Item>
          <Form.Item label="当前配额"><Input value="20 QPS" disabled /></Form.Item>
          <Form.Item label="申请配额"><InputNumber defaultValue={50} /> QPS</Form.Item>
          <Form.Item label="理由"><Input.TextArea placeholder="大促期间流量上涨" /></Form.Item>
          <Form.Item label="有效期">
            <Select options={[{ label: '永久', value: 'forever' }, { label: '临时（大促周期）', value: 'temp' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
