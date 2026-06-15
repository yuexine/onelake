/**
 * 通知渠道（对应原型 §8.10.5 升级版）。
 */
import { Table, Tag, Space, Button, Modal, Form, Input, Select, message, Typography } from 'antd';
import { PlusOutlined, BellOutlined, SendOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { channels } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

const TYPE_COLOR: Record<string, { bg: string; fg: string }> = {
  EMAIL:    { bg: 'var(--ol-brand-soft)', fg: 'var(--ol-brand)' },
  DINGTALK: { bg: 'var(--ol-info-soft)',  fg: '#0369A1' },
  WEBHOOK:  { bg: 'var(--ol-success-soft)', fg: 'var(--ol-success)' },
  PHONE:    { bg: 'var(--ol-error-soft)', fg: 'var(--ol-error)' },
};

export default function Channels() {
  const [open, setOpen] = useState(false);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<BellOutlined />}
        title="通知渠道"
        subtitle={<span className="ol-chip">系统 · L10-5</span>}
        description="邮件 / 钉钉 / Webhook / 电话四类渠道，含 P0/P1/P2 路由规则"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建渠道</Button>}
      />

      <SectionCard title="渠道列表" icon={<BellOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={channels}
          size="middle"
          pagination={false}
          columns={[
            { title: '类型', dataIndex: 'type', width: 130, render: (t: string) => {
              const c = TYPE_COLOR[t] || TYPE_COLOR.EMAIL;
              return (
                <span style={{
                  padding: '2px 10px', borderRadius: 4, fontSize: 12, fontWeight: 600,
                  background: c.bg, color: c.fg,
                }}>{t}</span>
              );
            } },
            { title: '配置', render: (_: unknown, r: any) => (
              <Text code style={{ fontSize: 11 }}>{JSON.stringify(r.config).slice(0, 80)}…</Text>
            ) },
            { title: '状态', dataIndex: 'status', width: 120, render: (s: string) => (
              <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
                background: s === 'ACTIVE' ? 'var(--ol-success-soft)' : 'var(--ol-fill-soft)',
                color: s === 'ACTIVE' ? 'var(--ol-success)' : 'var(--ol-ink-3)',
              }}>
                <span className={`ol-status-dot ${s === 'ACTIVE' ? 'is-success' : ''}`} />
                {s === 'ACTIVE' ? '正常' : '未配'}
              </span>
            ) },
            { title: '操作', width: 180, render: (_: unknown, r: any) => (
              <Space>
                <Button size="small" type="link" icon={<SendOutlined />} onClick={() => message.success(`已向 ${r.type} 发送测试消息`)}>
                  测试发送
                </Button>
                <Button size="small" type="link">编辑</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="路由规则" icon={<BellOutlined />}>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {[
            { level: 'P0', target: '电话 + 钉钉', color: 'var(--ol-error)' },
            { level: 'P1', target: '钉钉', color: '#B45309' },
            { level: 'P2', target: '邮件', color: 'var(--ol-ink-3)' },
          ].map((r) => (
            <div key={r.level} style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '10px 14px', background: 'var(--ol-fill-soft)',
              borderRadius: 6, border: '1px solid var(--ol-line-soft)',
            }}>
              <span style={{
                padding: '2px 10px', borderRadius: 4, fontSize: 12, fontWeight: 600,
                background: `${r.color}15`, color: r.color,
              }}>{r.level}</span>
              <span style={{ color: 'var(--ol-ink-4)' }}>→</span>
              <Text style={{ fontSize: 13 }}>{r.target}</Text>
            </div>
          ))}
        </Space>
      </SectionCard>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        title="新建通知渠道"
        onOk={() => { setOpen(false); message.success('已创建'); }}
      >
        <Form layout="vertical">
          <Form.Item label="类型">
            <Select options={['EMAIL', 'DINGTALK', 'WEBHOOK', 'PHONE'].map((v) => ({ label: v, value: v }))} />
          </Form.Item>
          <Form.Item label="配置 (JSON)">
            <Input.TextArea rows={4} placeholder='{"smtp":"smtp.corp.com","from":"onelake@corp.com"}' style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
