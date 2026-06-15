/**
 * 加密与密钥（对应原型 §8.7.4）。
 */
import { Card, Table, Tag, Space, Button, message } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { secrets } from '../../mock';

export default function Kms() {
  return (
    <Card title="资产与安全 / 加密与密钥（KMS）" extra={<Button type="primary" icon={<PlusOutlined />}>新建密钥</Button>}>
      <Card size="small" title="密钥列表" style={{ marginBottom: 16 }}>
        <Table size="small" rowKey="id" dataSource={secrets}
          columns={[
            { title: '引用 ref', dataIndex: 'refKey', render: (v: string) => <code>{v}</code> },
            { title: 'KMS Key', dataIndex: 'kmsKeyId' },
            { title: '上次轮换', dataIndex: 'rotatedAt' },
            { title: '状态', render: () => <Tag color="success">● 启用</Tag> },
            { title: '操作', render: (_: unknown, r: any) => <Button size="small" icon={<ReloadOutlined />} onClick={() => message.success(`已触发轮换 ${r.kmsKeyId}（旧密钥保留期 30 天）`)}>立即轮换</Button> },
          ]} />
      </Card>

      <Card size="small" title="字段加密配置">
        <Table size="small" dataSource={[
          { fqn: 'ods.users.id_card', strategy: '字段级加密', key: 'cmk-user-v2' },
          { fqn: 'ods.users.phone', strategy: '保格加密 FPE', key: 'cmk-user-v2' },
        ]} pagination={false}
          columns={[
            { title: '资产.字段', dataIndex: 'fqn' },
            { title: '策略', dataIndex: 'strategy' },
            { title: '密钥', dataIndex: 'key' },
          ]} />
        <Tag color="warning" style={{ marginTop: 12 }}>⚠ 密钥不可用时节点挂起，不降级输出明文</Tag>
      </Card>
    </Card>
  );
}
