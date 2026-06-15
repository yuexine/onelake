/**
 * 租户/项目管理（对应原型 §8.10.3 升级版）。
 */
import { Table, Tag, Space, Button, Progress, Modal, Form, Input, InputNumber, message, Typography } from 'antd';
import { PlusOutlined, TeamOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { tenants } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Tenants() {
  const [open, setOpen] = useState(false);

  const counts = {
    total: tenants.length,
    active: tenants.filter((t) => t.status === 'ACTIVE').length,
    members: tenants.reduce((s, t) => s + t.memberCount, 0),
    cuUsed: tenants.reduce((s, t) => s + (t.quotaCuUsed ?? 0), 0),
    cuTotal: tenants.reduce((s, t) => s + (t.quotaCuTotal ?? 0), 0),
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<TeamOutlined />}
        title="租户与项目"
        subtitle={<span className="ol-chip">系统 · L10-1</span>}
        description="多租户隔离：tenant_id 强制下推、独立资源配额、独立审计"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建租户</Button>}
      />

      <SectionCard title="租户列表" icon={<TeamOutlined />} subtitle="点击行展开查看资源配额" flatBody>
        <Table
          rowKey="id"
          dataSource={tenants}
          size="middle"
          pagination={false}
          expandable={{
            expandedRowRender: (r: any) => (
              <div className="ol-section" style={{ padding: 16, background: 'var(--ol-fill-soft)' }}>
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>资源配额：CPU / 内存 / 存储 / 查询并发</Text>
                  <Progress
                    percent={Math.round(((r.quotaCuUsed || 0) / (r.quotaCuTotal || 1)) * 100)}
                    format={() => `${r.quotaCuUsed}/${r.quotaCuTotal} CU`}
                    style={{ maxWidth: 480 }}
                    strokeColor={r.quotaCuUsed >= r.quotaCuTotal ? 'var(--ol-error)' : 'var(--ol-brand)'}
                  />
                  <Space>
                    <Button size="small">调整配额</Button>
                    <Button size="small">查看审计</Button>
                  </Space>
                  <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>成员归属 + 数据隔离（tenant_id 强制下推）</Text>
                </Space>
              </div>
            ),
          }}
          columns={[
            { title: '租户', dataIndex: 'name', render: (v: string, r: any) => (
              <Space size={8}>
                <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>{r.code}</span>
                <Text strong>{v}</Text>
              </Space>
            ) },
            { title: '项目数', dataIndex: 'projectCount', align: 'right' as const },
            { title: '成员', dataIndex: 'memberCount', align: 'right' as const, render: (v: number) => <span className="mono tnum">{v}</span> },
            { title: '资源配额', dataIndex: 'quotaCuUsed', render: (v: number, r: any) => (
              <Tag color={v >= r.quotaCuTotal ? 'error' : 'success'} style={{ margin: 0 }}>
                {v} / {r.quotaCuTotal} CU
              </Tag>
            ) },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => (
              <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
                background: s === 'ACTIVE' ? 'var(--ol-success-soft)' : 'var(--ol-fill-soft)',
                color: s === 'ACTIVE' ? 'var(--ol-success)' : 'var(--ol-ink-3)',
              }}>
                <span className={`ol-status-dot ${s === 'ACTIVE' ? 'is-success' : ''}`} />
                {s === 'ACTIVE' ? '启用' : '停用'}
              </span>
            ) },
          ]}
        />
      </SectionCard>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        title="新建租户"
        onOk={() => { setOpen(false); message.success('已创建'); }}
      >
        <Form layout="vertical">
          <Form.Item label="编码"><Input placeholder="TRADE" /></Form.Item>
          <Form.Item label="名称"><Input placeholder="交易事业部" /></Form.Item>
          <Form.Item label="资源配额 CU"><InputNumber defaultValue={200} style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
