/**
 * AppKey / 凭据管理（对应原型 §8.8.7）。
 */
import { Card, Table, Tag, Space, Button, Modal, Form, Input, message, Tooltip, Typography } from 'antd';
import { PlusOutlined, CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { appKeys } from '../../mock';
import { StatusBadge } from '../../components';

const { Text } = Typography;

export default function AppKeys() {
  const [newOpen, setNewOpen] = useState(false);
  return (
    <Card title="数据服务 / 我的凭据" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setNewOpen(true)}>新建凭据</Button>}>
      <Table rowKey="id" dataSource={appKeys} size="middle"
        expandable={{
          expandedRowRender: (r: any) => (
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text>AppKey：<Text code>{r.appKey}</Text> <Tooltip title="复制"><Button type="text" size="small" icon={<CopyOutlined />} /></Tooltip></Text>
              <Text>Secret：****** <Button size="small" type="link" icon={<ReloadOutlined />} onClick={() => message.success('已重置 Secret')}>重置</Button></Text>
              <Text>IP 白名单：{(r.ipWhitelist || []).join(', ')}</Text>
              <Text>最近调用：200 × {r.recentCalls?.status2xx.toLocaleString()}，429 × {r.recentCalls?.status429}，401 × {r.recentCalls?.status401}</Text>
              <Space><Button type="link" onClick={() => message.info('查看调用统计')}>调用统计</Button><Button type="link" onClick={() => message.info('提交升额申请')}>申请升额</Button><Button type="link" danger>禁用</Button></Space>
            </Space>
          ),
        }}
        columns={[
          { title: 'AppKey', dataIndex: 'appKey', render: (v: string) => <Text code>{v}</Text> },
          { title: '所有者', dataIndex: 'ownerName' },
          { title: '配额/日', dataIndex: 'quotaDaily', render: (v?: number) => v?.toLocaleString() || '-' },
          { title: '过期', dataIndex: 'expiresAt', render: (v?: string) => v || '-' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
        ]} />

      <Modal open={newOpen} onCancel={() => setNewOpen(false)} title="新建 AppKey"
        onOk={() => { setNewOpen(false); message.success('AppKey 已创建（Secret 仅展示一次，请妥善保存）'); }}>
        <Form layout="vertical">
          <Form.Item label="所有者"><Input defaultValue="报表组" /></Form.Item>
          <Form.Item label="IP 白名单"><Input.TextArea placeholder="10.0.0.0/24" /></Form.Item>
          <Form.Item label="每日配额"><Input defaultValue={100000} /></Form.Item>
          <Form.Item label="过期时间"><Input placeholder="2026-12-31" /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
