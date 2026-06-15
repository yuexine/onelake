/**
 * RBAC 权限矩阵（对应原型 §8.10.1）。
 */
import { Card, Table, Tag, Space, Button, Checkbox, Row, Col, Typography, Alert } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { roles, roleBindings } from '../../mock';
import { ClassificationBadge } from '../../components';

const { Text } = Typography;

const MENUS = [
  { path: '/dashboard', name: '工作台' },
  { path: '/integration', name: '数据集成' },
  { path: '/lakehouse', name: '湖仓建模' },
  { path: '/orchestration', name: '数据开发' },
  { path: '/quality', name: '数据质量' },
  { path: '/catalog', name: '数据目录（只读）' },
  { path: '/security', name: '资产与安全' },
  { path: '/dataservice', name: '数据服务' },
  { path: '/monitor', name: '运营监控' },
  { path: '/system', name: '系统管理' },
];

export default function Rbac() {
  return (
    <Card title="系统管理 / RBAC 角色权限矩阵" extra={<Button type="primary" icon={<PlusOutlined />}>新建角色</Button>}>
      <Alert type="warning" message="⚠ 高危权限（删除/下线/密钥）需二次审批授予" style={{ marginBottom: 16 }} />

      <Row gutter={16}>
        <Col span={14}>
          <Card size="small" title="菜单权限矩阵">
            <Table size="small" rowKey="path" dataSource={MENUS.map((m) => ({ ...m, key: m.path }))} pagination={false}
              columns={[
                { title: '菜单', dataIndex: 'name' },
                ...roles.map((r) => ({
                  title: r.name, dataIndex: r.code,
                  render: () => <Checkbox defaultChecked={['DE'].includes(r.code) && !['/system'].includes('')} />,
                })),
              ]} />
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="数据权限">
            <Space direction="vertical">
              <div><Text>行级：</Text><Tag color="blue">仅本租户</Tag></div>
              <div><Text>列级：</Text><ClassificationBadge level="L3" showName={false} /> + 字段脱敏</div>
              <div><Text>库表范围：</Text><Tag>ads_* 只读</Tag></div>
            </Space>
          </Card>
          <Card size="small" title="操作权限" style={{ marginTop: 12 }}>
            <Space direction="vertical">
              {['read', 'write', 'publish', 'delete', 'grant'].map((a) => <div key={a}><Checkbox /> {a}</div>)}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="角色" style={{ marginTop: 16 }}>
        <Table size="small" rowKey="id" dataSource={roles}
          columns={[
            { title: '编码', dataIndex: 'code', render: (c: string) => <Tag color="blue">{c}</Tag> },
            { title: '名称', dataIndex: 'name' },
            { title: '描述', dataIndex: 'description' },
            { title: '成员', dataIndex: 'members' },
            { title: '操作', render: () => <Space><Button size="small" type="link">管理成员</Button><Button size="small" type="link">编辑权限</Button></Space> },
          ]} />
      </Card>
    </Card>
  );
}
