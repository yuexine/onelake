/**
 * 网关路由（对应原型 §8.8.6）。
 */
import { Card, Table, Tag, Space, Button, Form, Select, Input, Modal, message } from 'antd';
import { useState } from 'react';

const routes = [
  { path: '/v2/order/detail', backend: 'daas-order', version: 'v2', gray: '100%', status: 'online' },
  { path: '/v1/order/detail', backend: 'daas-order', version: 'v1', gray: '-', status: 'deprecated' },
  { path: '/v1/user/profile', backend: 'daas-user', version: 'v1', gray: '-', status: 'deprecated' },
];

export default function Gateway() {
  const [open, setOpen] = useState(false);
  return (
    <Card title="数据服务 / API 网关 · 路由管理" extra={<>
      <Tag color="blue">统一域名：api.dataplat.io</Tag>
      <Button type="primary" onClick={() => setOpen(true)}>+ 新建路由</Button>
    </>}>
      <Table size="middle" rowKey="path" dataSource={routes}
        columns={[
          { title: '路径', dataIndex: 'path', render: (v: string) => <code>{v}</code> },
          { title: '后端服务', dataIndex: 'backend' },
          { title: '版本', dataIndex: 'version', render: (v: string) => <Tag color="blue">{v}</Tag> },
          { title: '灰度', dataIndex: 'gray' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'online' ? 'success' : s === 'deprecated' ? 'warning' : 'default'}>{s === 'online' ? '● 在线' : s === 'deprecated' ? '⚠ 410 倒计时' : s}</Tag> },
          { title: '操作', render: () => <Space><Button size="small" type="link">编辑</Button><Button size="small" type="link" danger onClick={() => message.success('已一键回退')}>一键回退</Button></Space> },
        ]} />

      <Card size="small" title="灰度发布" style={{ marginTop: 16 }}>
        <Space direction="vertical">
          <span>新版本 v3：按比例 <Select defaultValue="10" options={['10%', '30%', '50%', '100%'].map((v: any) => ({ label: v, value: v }))} style={{ width: 100 }} /></span>
          <span>或按消费方白名单：<Input placeholder="AppKey ak_xxx, ak_yyy" style={{ width: 300 }} /></span>
          <Button type="primary" onClick={() => message.success('已发布灰度，异常可一键回退 v2')}>发布灰度</Button>
        </Space>
      </Card>

      <Card size="small" title="协议转换" style={{ marginTop: 16 }}>
        对外 REST ⇄ 对内 gRPC <Tag color="success">已启用</Tag>
      </Card>

      <Modal open={open} onCancel={() => setOpen(false)} title="新建路由"
        onOk={() => { setOpen(false); message.success('路由已创建'); }}>
        <Form layout="vertical">
          <Form.Item label="路径"><Input placeholder="/v1/order/detail" /></Form.Item>
          <Form.Item label="后端服务"><Input placeholder="postgrest:3000" /></Form.Item>
          <Form.Item label="插件"><Select mode="multiple" options={['key-auth', 'limit-req', 'proxy-rewrite'].map((v: any) => ({ label: v, value: v }))} defaultValue={['key-auth', 'limit-req']} /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
