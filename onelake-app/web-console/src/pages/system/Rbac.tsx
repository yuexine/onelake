/**
 * RBAC 权限矩阵（对应原型 §8.10.1 升级版）。
 */
import { Table, Tag, Space, Button, Checkbox, Row, Col, Typography, Alert } from 'antd';
import { PlusOutlined, SafetyOutlined, KeyOutlined } from '@ant-design/icons';
import { roles, roleBindings } from '../../mock';
import { ClassificationBadge, PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

const MENUS = [
  { path: '/dashboard',     name: '工作台' },
  { path: '/integration',   name: '数据集成' },
  { path: '/lakehouse',     name: '湖仓建模' },
  { path: '/orchestration', name: '数据开发' },
  { path: '/quality',       name: '数据质量' },
  { path: '/catalog',       name: '数据目录（只读）' },
  { path: '/security',      name: '资产与安全' },
  { path: '/dataservice',   name: '数据服务' },
  { path: '/monitor',       name: '运营监控' },
  { path: '/system',        name: '系统管理' },
];

export default function Rbac() {
  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyOutlined />}
        title="RBAC 角色权限矩阵"
        subtitle={<span className="ol-chip">系统 · L10-2</span>}
        description="DE / ADMIN / CONSUMER / SEC / OPS 五大平台角色，菜单/数据/操作三层权限"
        actions={<Button type="primary" icon={<PlusOutlined />}>新建角色</Button>}
      />

      <Alert
        type="warning" showIcon
        style={{ borderRadius: 10 }}
        message={<span style={{ fontSize: 13 }}>⚠ 高危权限（删除 / 下线 / 密钥）需二次审批授予</span>}
      />

      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <SectionCard title="菜单权限矩阵" icon={<SafetyOutlined />} flatBody style={{ height: '100%' }}>
            <Table size="middle" rowKey="path" dataSource={MENUS.map((m) => ({ ...m, key: m.path }))} pagination={false}
              columns={[
                { title: '菜单', dataIndex: 'name', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
                ...roles.map((r) => ({
                  title: r.code,
                  dataIndex: r.code,
                  width: 90,
                  align: 'center' as const,
                  render: () => <Checkbox defaultChecked={['DE'].includes(r.code)} />,
                })),
              ]} />
          </SectionCard>
        </Col>
        <Col xs={24} lg={10}>
          <SectionCard title="数据权限" icon={<SafetyOutlined />} style={{ height: '100%' }}>
            <Space direction="vertical" size={12}>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>行级</Text>
                <div style={{ marginTop: 4 }}><Tag color="blue" style={{ margin: 0 }}>仅本租户</Tag></div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>列级</Text>
                <div style={{ marginTop: 4 }}>
                  <Space size={6}>
                    <ClassificationBadge level="L3" showName={false} />
                    <Text style={{ fontSize: 12 }}>+ 字段脱敏</Text>
                  </Space>
                </div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>库表范围</Text>
                <div style={{ marginTop: 4 }}>
                  <Space wrap>
                    <Tag style={{ margin: 0 }}>ads_* 只读</Tag>
                    <Tag style={{ margin: 0 }}>dwd_*.trade 可读</Tag>
                  </Space>
                </div>
              </div>
            </Space>
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="操作权限" icon={<KeyOutlined />}>
        <Space direction="vertical" size={8}>
          {[
            { action: 'read', label: '读取', desc: '查看资产/任务' },
            { action: 'write', label: '写入', desc: '创建/编辑' },
            { action: 'publish', label: '发布', desc: '发布 API / DAG' },
            { action: 'delete', label: '删除', desc: '高危 - 需二次审批' },
            { action: 'grant', label: '授权', desc: '高危 - 需二次审批' },
          ].map((a) => (
            <div key={a.action} style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '8px 12px', background: 'var(--ol-fill-soft)',
              borderRadius: 6, border: '1px solid var(--ol-line-soft)',
            }}>
              <Checkbox />
              <Text strong style={{ fontSize: 13, width: 80 }}>{a.label}</Text>
              <span className="ol-chip" style={{ fontSize: 11 }}>{a.action}</span>
              <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{a.desc}</Text>
            </div>
          ))}
        </Space>
      </SectionCard>

      <SectionCard title="平台角色" icon={<SafetyOutlined />} flatBody>
        <Table size="middle" rowKey="id" dataSource={roles} pagination={false}
          columns={[
            { title: '编码', dataIndex: 'code', render: (c: string) => (
              <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>{c}</span>
            ) },
            { title: '名称', dataIndex: 'name', render: (n: string) => <Text strong style={{ fontSize: 13 }}>{n}</Text> },
            { title: '描述', dataIndex: 'description' },
            { title: '成员', dataIndex: 'members', align: 'right' as const, render: (v?: number) => v ? <span className="mono tnum">{v}</span> : '-' },
            { title: '操作', width: 180, render: () => (
              <Space>
                <Button size="small" type="link">管理成员</Button>
                <Button size="small" type="link">编辑权限</Button>
              </Space>
            ) },
          ]} />
      </SectionCard>
    </div>
  );
}
