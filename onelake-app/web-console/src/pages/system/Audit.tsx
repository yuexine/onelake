/**
 * 审计日志（对应原型 §8.10.2）。
 */
import { Card, Table, Tag, Space, Button, Input, Select, DatePicker, Typography } from 'antd';
import { ExportOutlined } from '@ant-design/icons';
import { auditLogs } from '../../mock';

const { Text } = Typography;

export default function Audit() {
  return (
    <Card title="系统管理 / 审计日志" extra={<Button icon={<ExportOutlined />}>导出</Button>}>
      <Space style={{ marginBottom: 16 }}>
        <DatePicker.RangePicker />
        <Select placeholder="操作类型" allowClear style={{ width: 160 }} options={['CREATE', 'UPDATE', 'DELETE', '修改密级', '下载样例数据', '调用 API', '发布 API', '权限授予'].map((v: any) => ({ label: v, value: v }))} />
        <Input.Search placeholder="操作人/对象" allowClear style={{ width: 240 }} />
      </Space>

      <Table rowKey="id" dataSource={auditLogs} size="middle"
        columns={[
          { title: '时间', dataIndex: 'occurredAt' },
          { title: '操作人', dataIndex: 'actorName' },
          { title: '操作', dataIndex: 'action', render: (a: string, r: any) => <Space><Tag color={r.sensitive ? 'red' : 'default'}>{a}</Tag>{r.sensitive && <Tag color="warning">⚠ 敏感</Tag>}</Space> },
          { title: '对象', dataIndex: 'resourceId' },
          { title: '详情', dataIndex: 'detail', render: (v?: string) => v ? <Text type="secondary">{v}</Text> : '-' },
          { title: 'Trace ID', dataIndex: 'traceId', render: (v?: string) => <Text code>{v}</Text> },
        ]} />

      <Card size="small" style={{ marginTop: 16 }}>
        <Text type="secondary">不可篡改存储，支持全文检索与合规导出。敏感操作（密级变更/下载/密钥/越权尝试）高亮显示。</Text>
      </Card>
    </Card>
  );
}
