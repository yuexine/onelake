/**
 * 租户/项目管理（对应原型 §8.10.3）。
 */
import { Card, Table, Tag, Space, Button, Progress, Modal, Form, Input, InputNumber, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { tenants } from '../../mock';

export default function Tenants() {
  const [open, setOpen] = useState(false);
  return (
    <Card title="系统管理 / 租户与项目" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建租户</Button>}>
      <Table rowKey="id" dataSource={tenants} size="middle"
        expandable={{ expandedRowRender: (r: any) => (
          <Space direction="vertical">
            <span>资源配额：CPU/内存/存储/查询并发</span>
            <Progress percent={Math.round(((r.quotaCuUsed || 0) / (r.quotaCuTotal || 1)) * 100)} format={() => `${r.quotaCuUsed}/${r.quotaCuTotal} CU`} style={{ width: 400 }} />
            <Button size="small">调整配额</Button>
            <span>成员归属 + 数据隔离（tenant_id 强制下推）</span>
          </Space>
        ) }}
        columns={[
          { title: '租户', dataIndex: 'name', render: (v: string, r: any) => <Space><Tag color="blue">{r.code}</Tag>{v}</Space> },
          { title: '项目数', dataIndex: 'projectCount' },
          { title: '成员', dataIndex: 'memberCount' },
          { title: '资源配额', dataIndex: 'quotaCuUsed', render: (v: number, r: any) => <Tag color={v >= r.quotaCuTotal ? 'error' : 'success'}>{v}/{r.quotaCuTotal} CU</Tag> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'success' : 'default'}>{s}</Tag> },
        ]} />

      <Modal open={open} onCancel={() => setOpen(false)} title="新建租户"
        onOk={() => { setOpen(false); message.success('已创建'); }}>
        <Form layout="vertical">
          <Form.Item label="编码"><Input placeholder="TRADE" /></Form.Item>
          <Form.Item label="名称"><Input placeholder="交易事业部" /></Form.Item>
          <Form.Item label="资源配额 CU"><InputNumber defaultValue={200} /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
