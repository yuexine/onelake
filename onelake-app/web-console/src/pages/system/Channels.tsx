/**
 * 通知渠道（对应原型 §8.10.5）。
 */
import { Card, Table, Tag, Space, Button, Modal, Form, Input, Select, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { channels } from '../../mock';

export default function Channels() {
  const [open, setOpen] = useState(false);
  return (
    <Card title="系统管理 / 通知渠道" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建渠道</Button>}>
      <Table rowKey="id" dataSource={channels} size="middle"
        columns={[
          { title: '类型', dataIndex: 'type', render: (t: string) => <Tag color="blue">{t}</Tag> },
          { title: '配置', render: (_: unknown, r: any) => <code>{JSON.stringify(r.config).slice(0, 60)}</code> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'success' : 'default'}>{s === 'ACTIVE' ? '● 正常' : '○ 未配'}</Tag> },
          { title: '操作', render: (_: unknown, r: any) => <Space>
            <Button size="small" type="link" onClick={() => message.success(`已向 ${r.type} 发送测试消息`)}>测试发送</Button>
            <Button size="small" type="link">编辑</Button>
          </Space> },
        ]} />

      <Card size="small" title="路由规则" style={{ marginTop: 16 }}>
        <Space direction="vertical">
          <span>P0 → 电话 + 钉钉</span>
          <span>P1 → 钉钉</span>
          <span>P2 → 邮件</span>
        </Space>
      </Card>

      <Modal open={open} onCancel={() => setOpen(false)} title="新建通知渠道"
        onOk={() => { setOpen(false); message.success('已创建'); }}>
        <Form layout="vertical">
          <Form.Item label="类型"><Select options={['EMAIL', 'DINGTALK', 'WEBHOOK', 'PHONE'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
          <Form.Item label="配置 (JSON)"><Input.TextArea rows={4} placeholder='{"smtp":"smtp.corp.com","from":"onelake@corp.com"}' /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
