/**
 * 审计日志（对应原型 §8.10.2 升级版）。
 */
import { Table, Tag, Space, Button, Input, Select, DatePicker, Typography, Alert } from 'antd';
import { ExportOutlined, FileSearchOutlined } from '@ant-design/icons';
import { auditLogs } from '../../mock';
import { PageHeader, SectionCard, FilterBar } from '../../components';

const { Text } = Typography;

export default function Audit() {

  return (
    <div className="ol-page">
      <PageHeader
        icon={<FileSearchOutlined />}
        title="审计日志"
        subtitle={<span className="ol-chip">系统 · L10-4</span>}
        description="不可篡改存储，支持全文检索与合规导出，敏感操作高亮"
        actions={<Button icon={<ExportOutlined />}>导出</Button>}
      />

      <FilterBar
        search={{ placeholder: '操作人 / 对象', width: 240 }}
        filters={
          <>
            <DatePicker.RangePicker />
            <Select placeholder="操作类型" allowClear style={{ width: 180 }}
              options={['CREATE', 'UPDATE', 'DELETE', '修改密级', '下载样例数据', '调用 API', '发布 API', '权限授予'].map((v) => ({ label: v, value: v }))} />
          </>
        }
        summary={<span className="ol-quiet" style={{ fontSize: 12 }}>共 {auditLogs.length} 条</span>}
      />

      <SectionCard title="日志列表" icon={<FileSearchOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={auditLogs}
          size="middle"
          pagination={{ pageSize: 20, showTotal: (t) => <span className="ol-quiet" style={{ fontSize: 12 }}>共 {t} 条</span> }}
          columns={[
            { title: '时间', dataIndex: 'occurredAt', width: 160, render: (t: string) => (
              <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{t}</span>
            ) },
            { title: '操作人', dataIndex: 'actorName', width: 100, render: (a: string) => (
              <Space size={6}>
                <div style={{ width: 22, height: 22, borderRadius: '50%', background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 600 }}>
                  {a.charAt(0)}
                </div>
                <Text style={{ fontSize: 12 }}>{a}</Text>
              </Space>
            ) },
            { title: '操作', dataIndex: 'action', width: 180, render: (a: string, r: any) => (
              <Space size={6}>
                <Tag color={r.sensitive ? 'error' : 'default'} style={{ margin: 0 }}>{a}</Tag>
                {r.sensitive && <Tag color="warning" style={{ margin: 0 }}>⚠ 敏感</Tag>}
              </Space>
            ) },
            { title: '对象', dataIndex: 'resourceId', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '详情', dataIndex: 'detail', ellipsis: true, render: (v?: string) => v ? (
              <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{v}</span>
            ) : '-' },
            { title: 'Trace ID', dataIndex: 'traceId', width: 160, render: (v?: string) => v ? <Text code style={{ fontSize: 11 }}>{v}</Text> : '-' },
          ]}
        />
      </SectionCard>

      <Alert
        type="info" showIcon
        style={{ borderRadius: 10 }}
        message={<span style={{ fontSize: 13 }}>不可篡改存储，支持全文检索与合规导出。敏感操作（密级变更 / 下载 / 密钥 / 越权尝试）高亮显示。</span>}
      />
    </div>
  );
}
