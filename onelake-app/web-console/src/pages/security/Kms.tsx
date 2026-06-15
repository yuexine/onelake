/**
 * 加密与密钥（对应原型 §8.7.4 升级版）。
 */
import { Table, Tag, Space, Button, message, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined, KeyOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { secrets } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Kms() {
  return (
    <div className="ol-page">
      <PageHeader
        icon={<KeyOutlined />}
        title="加密与密钥（KMS）"
        subtitle={<span className="ol-chip">安全 · L4-2</span>}
        description="密钥统一管理，支持热轮换、字段级加密、保格加密 FPE；密钥不可用时挂起不降级"
        actions={<Button type="primary" icon={<PlusOutlined />}>新建密钥</Button>}
      />

      <SectionCard title="密钥列表" icon={<KeyOutlined />} flatBody>
        <Table
          size="middle"
          rowKey="id"
          dataSource={secrets}
          pagination={false}
          columns={[
            { title: '引用 ref', dataIndex: 'refKey', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: 'KMS Key', dataIndex: 'kmsKeyId', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '上次轮换', dataIndex: 'rotatedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '状态', dataIndex: 'status', width: 110, render: () => (
              <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
                background: 'var(--ol-success-soft)', color: 'var(--ol-success)',
              }}>
                <span className="ol-status-dot is-success" />
                启用
              </span>
            ) },
            { title: '操作', width: 140, render: (_: unknown, r: any) => (
              <Button size="small" ghost icon={<ReloadOutlined />}
                onClick={() => message.success(`已触发轮换 ${r.kmsKeyId}（旧密钥保留期 30 天）`)}>
                立即轮换
              </Button>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="字段加密配置" icon={<LockOutlined />} flatBody
        extra={<Tag color="warning" style={{ margin: 0 }}>⚠ 密钥不可用时节点挂起，不降级输出明文</Tag>}
      >
        <Table
          size="middle"
          dataSource={[
            { key: '1', fqn: 'ods.users.id_card', strategy: '字段级加密', keyRef: 'cmk-user-v2' },
            { key: '2', fqn: 'ods.users.phone', strategy: '保格加密 FPE', keyRef: 'cmk-user-v2' },
          ]}
          pagination={false}
          columns={[
            { title: '资产 · 字段', dataIndex: 'fqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '策略', dataIndex: 'strategy', render: (s: string) => <Tag color="processing" style={{ margin: 0 }}>{s}</Tag> },
            { title: '密钥', dataIndex: 'keyRef', render: (k: string) => <Text code style={{ fontSize: 12 }}>{k}</Text> },
          ]}
        />
      </SectionCard>
    </div>
  );
}
