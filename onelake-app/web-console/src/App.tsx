/**
 * 全局布局（对应原型 §1.2）：
 *   顶栏 TopBar (Logo / 全局搜索 ⌘K / 租户切换 / 通知 / 帮助 / 头像)
 * + 左侧 SideNav (10 菜单可折叠)
 * + 主内容区 (面包屑 + 页面 + 右侧抽屉)
 * + 底部全局任务条
 */
import { useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Menu, Dropdown, Avatar, Badge, Space, Typography, Tooltip, Button, Tag } from 'antd';
import {
  AppstoreOutlined, DatabaseOutlined, ClusterOutlined, SearchOutlined,
  SafetyOutlined, LockOutlined, CloudOutlined, DashboardOutlined,
  ControlOutlined, BellOutlined, QuestionCircleOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, DownOutlined, UserOutlined,
} from '@ant-design/icons';
import { useAppStore } from './stores/app';
import { GlobalSearch } from './components/GlobalSearch';
import { NotificationCenter } from './components/NotificationCenter';
import { TaskProgressBar } from './components/TaskProgressBar';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

const NAV: import('antd').MenuProps['items'] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '① 工作台' },
  { key: '/integration', icon: <DatabaseOutlined />, label: '② 数据集成',
    children: [
      { key: '/integration/datasources', label: '连接管理' },
      { key: '/integration/sync-tasks', label: '采集任务' },
      { key: '/integration/cdc', label: 'CDC 实时' },
      { key: '/integration/files', label: '文件采集' },
      { key: '/integration/templates', label: '任务模板' },
      { key: '/integration/monitor', label: '采集监控' },
    ],
  },
  { key: '/lakehouse', icon: <ClusterOutlined />, label: '③ 湖仓与建模',
    children: [
      { key: '/lakehouse/tables', label: '分层表浏览' },
      { key: '/lakehouse/sql', label: 'SQL 工作台' },
      { key: '/lakehouse/optimize', label: '存储优化' },
    ],
  },
  { key: '/orchestration', icon: <AppstoreOutlined />, label: '④ 数据开发 · 编排',
    children: [
      { key: '/orchestration/pipelines', label: '流水线' },
      { key: '/orchestration/operators', label: '算子市场' },
      { key: '/orchestration/runs', label: '运行实例' },
    ],
  },
  { key: '/quality', icon: <SafetyOutlined />, label: '⑤ 数据质量',
    children: [
      { key: '/quality/rules', label: '规则配置' },
      { key: '/quality/results', label: '稽核结果' },
      { key: '/quality/gate', label: '门禁失败' },
    ],
  },
  { key: '/catalog', icon: <SearchOutlined />, label: '⑥ 数据目录与血缘',
    children: [
      { key: '/catalog/search', label: '搜索浏览' },
      { key: '/catalog/glossary', label: '业务术语表' },
      { key: '/catalog/lineage', label: '血缘图' },
    ],
  },
  { key: '/security', icon: <LockOutlined />, label: '⑦ 资产与安全',
    children: [
      { key: '/security/map', label: '资产地图' },
      { key: '/security/pii', label: 'PII 识别' },
      { key: '/security/masking', label: '脱敏策略' },
      { key: '/security/kms', label: '加密与密钥' },
    ],
  },
  { key: '/dataservice', icon: <CloudOutlined />, label: '⑧ 数据服务 DaaS',
    children: [
      { key: '/dataservice/apis', label: 'API 市场' },
      { key: '/dataservice/appkeys', label: '我的凭据' },
      { key: '/dataservice/gateway', label: '网关路由' },
      { key: '/dataservice/subscriptions', label: '订阅与计量' },
    ],
  },
  { key: '/monitor', icon: <ControlOutlined />, label: '⑨ 运营与监控',
    children: [
      { key: '/monitor/overview', label: '总览大盘' },
      { key: '/monitor/alerts', label: '告警中心' },
      { key: '/monitor/incidents', label: '故障复盘' },
      { key: '/monitor/sla', label: 'SLA 看板' },
    ],
  },
  { key: '/system', icon: <ControlOutlined />, label: '⑩ 系统管理',
    children: [
      { key: '/system/tenants', label: '租户/项目' },
      { key: '/system/rbac', label: 'RBAC 权限' },
      { key: '/system/approvals', label: '审批中心' },
      { key: '/system/audit', label: '审计日志' },
      { key: '/system/channels', label: '通知渠道' },
    ],
  },
];

export default function App() {
  const [collapsed, setCollapsed] = useState(false);
  const { user, tenant, tenants, switchTenant, tasks, notifications, setSearchOpen, setNotifyOpen } = useAppStore();
  const navigate = useNavigate();
  const location = useLocation();
  const unread = notifications.filter((n) => !n.isRead).length;

  // 当前选中菜单（最长前缀匹配）
  const selectedKeys = (() => {
    const paths = location.pathname.split('/').filter(Boolean);
    const candidates: string[] = [];
    let cur = '';
    for (const p of paths) {
      cur += '/' + p;
      candidates.push(cur);
    }
    return candidates.length > 0 ? [candidates[candidates.length - 1]] : ['/dashboard'];
  })();

  return (
    <Layout hasSider style={{ minHeight: '100vh', flexDirection: 'row' }}>
      <Sider trigger={null} collapsible collapsed={collapsed} width={232} theme="light"
        style={{ boxShadow: '2px 0 8px rgba(0,0,0,0.04)', overflow: 'auto', height: '100vh', position: 'sticky', top: 0, left: 0, flexShrink: 0 }}>
        <div style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 28, height: 28, borderRadius: 6, background: 'linear-gradient(135deg, #1677ff 0%, #69b1ff 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', fontWeight: 700 }}>OL</div>
          {!collapsed && <Title level={5} style={{ margin: 0 }}>数据平台</Title>}
        </div>
        <Menu
          mode="inline"
          selectedKeys={selectedKeys}
          defaultOpenKeys={['/integration', '/lakehouse', '/orchestration', '/quality', '/catalog', '/security', '/dataservice', '/monitor', '/system']}
          style={{ borderRight: 'none' }}
          items={NAV}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout style={{ flex: 1, minWidth: 0, flexDirection: 'column' }}>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid #f0f0f0' }}>
          <Space>
            <Button type="text" onClick={() => setCollapsed(!collapsed)} icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} />
            <Tooltip title="全局搜索 (⌘K)">
              <Button type="default" icon={<SearchOutlined />} onClick={() => setSearchOpen(true)}>
                搜索资产 / 任务 / API…
              </Button>
            </Tooltip>
          </Space>
          <Space size={16}>
            <Dropdown menu={{
              items: tenants.map((t) => ({ key: t.id, label: <span><Tag color="blue">{t.code}</Tag>{t.name}</span> })),
              onClick: ({ key }) => switchTenant(key),
            }}>
              <Space style={{ cursor: 'pointer' }}>
                <Tag color="blue">{tenant.code}</Tag>
                <span>{tenant.name}</span>
                <DownOutlined />
              </Space>
            </Dropdown>
            <Tooltip title="帮助"><Button type="text" icon={<QuestionCircleOutlined />} /></Tooltip>
            <Tooltip title="通知中心">
              <Badge count={unread} size="small">
                <Button type="text" icon={<BellOutlined />} onClick={() => setNotifyOpen(true)} />
              </Badge>
            </Tooltip>
            <Dropdown menu={{
              items: [
                { key: 'profile', label: '个人资料' },
                { key: 'tokens', label: '访问令牌' },
                { type: 'divider' as const },
                { key: 'logout', label: '退出登录' },
              ],
            }}>
              <Space style={{ cursor: 'pointer' }}>
                <Avatar icon={<UserOutlined />} style={{ background: '#1677ff' }} />
                <div>
                  <div style={{ lineHeight: 1.2 }}>{user.name}</div>
                  <div style={{ lineHeight: 1.2, fontSize: 11, color: '#8c8c8c' }}>{user.roles.join('/')}</div>
                </div>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content style={{ padding: 20, paddingBottom: 60 }}>
          <Outlet />
        </Content>

        {/* 底部全局任务条（对应 §1.2 / §8.x） */}
        <div style={{ position: 'fixed', bottom: 16, left: collapsed ? 96 : 248, right: 24, zIndex: 100 }}>
          <TaskProgressBar tasks={tasks} />
        </div>
      </Layout>

      <GlobalSearch />
      <NotificationCenter />
    </Layout>
  );
}
