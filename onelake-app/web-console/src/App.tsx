/**
 * 全局布局（对应原型 §1.2 升级版）：
 *   顶栏 TopBar (Logo / 全局搜索 ⌘K / 租户切换 / 通知 / 帮助 / 头像)
 * + 左侧 SideNav (10 菜单可折叠)
 * + 主内容区 (面包屑 + 页面 + 右侧抽屉)
 * + 底部全局任务条
 */
import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Menu, Dropdown, Avatar, Badge, Space, Typography, Tooltip, Button, App as AntdApp } from 'antd';
import {
  ClusterOutlined, SearchOutlined,
  SafetyOutlined, LockOutlined, CloudOutlined, DashboardOutlined,
  BellOutlined, QuestionCircleOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, DownOutlined, UserOutlined,
  CloudSyncOutlined, NodeIndexOutlined, FileSearchOutlined, MonitorOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useAppStore } from './stores/app';
import { GlobalSearch } from './components/GlobalSearch';
import { NotificationCenter } from './components/NotificationCenter';
import { OneLakeLogo } from './components/OneLakeLogo';
import { TaskProgressBar, TaskProgressTrigger } from './components/TaskProgressBar';
import { getAuthUser, logout } from './auth/oidc';
import { TaskAPI } from './api';
import { useGlobalTasks } from './hooks/useGlobalTasks';
import { useNotifications } from './hooks/useNotifications';
import type { RunningTask } from './types';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

const NAV: import('antd').MenuProps['items'] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/integration', icon: <CloudSyncOutlined />, label: '数据集成',
    children: [
      { key: '/integration/datasources', label: '连接管理' },
      { key: '/integration/sync-tasks', label: '采集任务' },
      { key: '/integration/cdc', label: 'CDC 实时' },
      { key: '/integration/files', label: '文件采集' },
      { key: '/integration/templates', label: '任务模板' },
      { key: '/integration/monitor', label: '采集监控' },
    ],
  },
  { key: '/lakehouse', icon: <ClusterOutlined />, label: '湖仓与建模',
    children: [
      { key: '/lakehouse/tables', label: '分层表浏览' },
      { key: '/lakehouse/sql', label: 'SQL 工作台' },
      { key: '/lakehouse/optimize', label: '存储优化' },
    ],
  },
  { key: '/orchestration', icon: <NodeIndexOutlined />, label: '数据开发 · 编排',
    children: [
      { key: '/orchestration/pipelines', label: '流水线' },
      { key: '/orchestration/operators', label: '算子市场' },
      { key: '/orchestration/runs', label: '运行实例' },
    ],
  },
  { key: '/quality', icon: <SafetyOutlined />, label: '数据质量',
    children: [
      { key: '/quality/rules', label: '规则配置' },
      { key: '/quality/results', label: '稽核结果' },
      { key: '/quality/gate', label: '门禁失败' },
    ],
  },
  { key: '/catalog', icon: <FileSearchOutlined />, label: '数据目录与血缘',
    children: [
      { key: '/catalog/search', label: '搜索浏览' },
      { key: '/catalog/glossary', label: '业务术语表' },
      { key: '/catalog/lineage', label: '血缘图' },
    ],
  },
  { key: '/security', icon: <LockOutlined />, label: '资产与安全',
    children: [
      { key: '/security/map', label: '资产地图' },
      { key: '/security/pii', label: 'PII 识别' },
      { key: '/security/masking', label: '脱敏策略' },
      { key: '/security/kms', label: '加密与密钥' },
    ],
  },
  { key: '/dataservice', icon: <CloudOutlined />, label: '数据服务 DaaS',
    children: [
      { key: '/dataservice/apis', label: 'API 市场' },
      { key: '/dataservice/appkeys', label: '我的凭据' },
      { key: '/dataservice/gateway', label: '网关路由' },
      { key: '/dataservice/subscriptions', label: '订阅与计量' },
    ],
  },
  { key: '/monitor', icon: <MonitorOutlined />, label: '运营与监控',
    children: [
      { key: '/monitor/overview', label: '总览大盘' },
      { key: '/monitor/alerts', label: '告警中心' },
      { key: '/monitor/incidents', label: '故障复盘' },
      { key: '/monitor/sla', label: 'SLA 看板' },
    ],
  },
  { key: '/system', icon: <SettingOutlined />, label: '系统管理',
    children: [
      { key: '/system/tenants', label: '租户/项目' },
      { key: '/system/rbac', label: 'RBAC 权限' },
      { key: '/system/approvals', label: '审批中心' },
      { key: '/system/audit', label: '审计日志' },
      { key: '/system/channels', label: '通知渠道' },
    ],
  },
];

const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'ADMIN',
  DE: 'DE',
  OPS: 'OPS',
  SEC: 'SEC',
  CONSUMER: 'CONSUMER',
};

export default function App() {
  const [collapsed, setCollapsed] = useState(false);
  const [menuOpenKeys, setMenuOpenKeys] = useState<string[]>([]);
  const [taskBarCollapsed, setTaskBarCollapsed] = useState(true);
  const [taskActionId, setTaskActionId] = useState<string | null>(null);
  const { message } = AntdApp.useApp();
  const {
    user,
    tenant,
    tenants,
    tasks,
    taskLoadError,
    notifications,
    setSearchOpen,
    setNotifyOpen,
    setUser,
    removeTask,
  } = useAppStore();
  const navigate = useNavigate();
  const location = useLocation();
  const { refresh: refreshGlobalTasks } = useGlobalTasks({ expanded: !taskBarCollapsed });
  useNotifications();
  const unread = notifications.filter((n) => !n.isRead).length;
  const visibleRoles = user.roles.slice(0, 3);
  const hiddenRoleCount = Math.max(user.roles.length - visibleRoles.length, 0);

  useEffect(() => {
    const authUser = getAuthUser();
    if (authUser) {
      const current = useAppStore.getState().user;
      const roles = authUser.roles.length > 0 ? authUser.roles : current.roles;
      if (current.username === authUser.username && current.roles.join('/') === roles.join('/')) {
        return;
      }
      setUser({
        id: authUser.id,
        name: authUser.name,
        username: authUser.username,
        roles,
        tenant: current.tenant,
      });
    }
  }, [setUser]);

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

  // 顶层菜单（含子菜单时展开根 key）
  const root = '/' + (location.pathname.split('/')[1] || 'dashboard');
  const routeOpenKey = NAV?.find(
    (n) => n && typeof n === 'object' && 'key' in n && 'children' in n && (n.key as string) === root,
  );
  const routeOpenKeyValue = routeOpenKey && typeof routeOpenKey === 'object' && 'key' in routeOpenKey
    ? String(routeOpenKey.key)
    : null;

  useEffect(() => {
    if (collapsed || !routeOpenKeyValue) return;
    setMenuOpenKeys((keys) => {
      const nextKeys = Array.from(new Set([...keys, routeOpenKeyValue]));
      return nextKeys.length === keys.length ? keys : nextKeys;
    });
  }, [collapsed, routeOpenKeyValue]);

  const handleTaskOpen = (task: RunningTask) => {
    if (task.link) navigate(task.link);
  };

  const handleTaskCancel = async (task: RunningTask) => {
    setTaskActionId(task.id);
    try {
      await TaskAPI.cancel(task);
      message.success('已提交取消任务请求');
      await refreshGlobalTasks();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '取消任务失败');
    } finally {
      setTaskActionId(null);
    }
  };

  const handleTaskDismiss = async (task: RunningTask) => {
    setTaskActionId(task.id);
    try {
      await TaskAPI.dismiss(task.id);
      removeTask(task.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '关闭任务失败');
    } finally {
      setTaskActionId(null);
    }
  };

  return (
    <Layout hasSider style={{ minHeight: '100vh', flexDirection: 'row' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        width={232}
        collapsedWidth={64}
        theme="light"
        style={{
          boxShadow: 'var(--ol-shadow-e2)',
          borderRight: '1px solid var(--ol-line-soft)',
          overflow: 'auto',
          height: '100vh',
          position: 'sticky',
          top: 0, left: 0,
          flexShrink: 0,
          zIndex: 11,
        }}
      >
        <div
          style={{
            padding: '14px 16px',
            display: 'flex', alignItems: 'center', gap: 10,
            borderBottom: '1px solid var(--ol-line-soft)',
            height: 56,
          }}
        >
          <OneLakeLogo collapsed={collapsed} size={34} />
        </div>
        <Menu
          mode="inline"
          selectedKeys={selectedKeys}
          openKeys={collapsed ? [] : menuOpenKeys}
          style={{ borderRight: 'none', padding: '8px 6px' }}
          items={NAV}
          onOpenChange={(keys) => setMenuOpenKeys(keys as string[])}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout style={{ flex: 1, minWidth: 0, flexDirection: 'column' }}>
        <Header
          style={{
            background: '#fff',
            padding: '0 20px',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            borderBottom: '1px solid var(--ol-line-soft)',
            boxShadow: '0 1px 0 rgba(15,23,42,.02)',
            position: 'sticky', top: 0, zIndex: 10,
            height: 56, lineHeight: '56px',
          }}
        >
          <Space size={8}>
            <Button
              type="text"
              onClick={() => setCollapsed(!collapsed)}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              style={{ color: 'var(--ol-ink-2)' }}
            />
            <Tooltip title="全局搜索 (⌘K)">
              <Button
                onClick={() => setSearchOpen(true)}
                style={{
                  height: 34, padding: '0 12px',
                  background: 'var(--ol-fill)', border: '1px solid var(--ol-line-soft)',
                  color: 'var(--ol-ink-3)', fontSize: 13, borderRadius: 6,
                  display: 'flex', alignItems: 'center', gap: 8,
                }}
              >
                <SearchOutlined />
                <span>搜索资产 / 任务 / API…</span>
                <span className="ol-kbd" style={{ marginLeft: 16 }}>⌘K</span>
              </Button>
            </Tooltip>
          </Space>

          <Space size={14}>
            <Dropdown
              menu={{
                items: tenants.map((t) => ({
                  key: t.id,
                  label: (
                    <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                      <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>
                        {t.code}
                      </span>
                      <span style={{ color: 'var(--ol-ink)' }}>{t.name}</span>
                    </span>
                  ),
                })),
                onClick: ({ key }) => useAppStore.getState().switchTenant(key),
              }}
            >
              <Space size={6} style={{ cursor: 'pointer', padding: '4px 10px', borderRadius: 6, background: 'var(--ol-fill)' }}>
                <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>
                  {tenant.code}
                </span>
                <span style={{ fontSize: 13, color: 'var(--ol-ink)' }}>{tenant.name}</span>
                <DownOutlined style={{ fontSize: 10, color: 'var(--ol-ink-3)' }} />
              </Space>
            </Dropdown>

            {taskBarCollapsed && (
              <TaskProgressTrigger tasks={tasks} onClick={() => setTaskBarCollapsed(false)} />
            )}

            <Tooltip title="帮助">
              <Button type="text" icon={<QuestionCircleOutlined />} style={{ color: 'var(--ol-ink-3)' }} />
            </Tooltip>

            <Tooltip title="通知中心">
              <Badge count={unread} size="small" offset={[-2, 2]}>
                <Button
                  type="text"
                  aria-label="打开通知中心"
                  icon={<BellOutlined />}
                  onClick={() => setNotifyOpen(true)}
                  style={{ color: 'var(--ol-ink-2)' }}
                />
              </Badge>
            </Tooltip>

            <Dropdown
              menu={{
                items: [
                  { key: 'profile', label: '个人资料' },
                  { key: 'tokens', label: '访问令牌' },
                  { type: 'divider' as const },
                  { key: 'logout', label: '退出登录' },
                ],
                onClick: ({ key }) => {
                  if (key === 'logout') logout();
                },
              }}
            >
              <Space
                size={10}
                title={`${user.name} · ${user.roles.join('/')}`}
                style={{
                  cursor: 'pointer',
                  padding: '4px 10px 4px 4px',
                  borderRadius: 22,
                  transition: 'background var(--ol-dur-fast)',
                  maxWidth: 260,
                  minWidth: 0,
                  lineHeight: 1,
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--ol-fill)')}
                onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
              >
                <Avatar size={32} icon={<UserOutlined />} style={{ background: 'var(--ol-brand-gradient)', flexShrink: 0 }} />
                <div style={{ minWidth: 0, lineHeight: 1.1 }}>
                  <div
                    style={{
                      maxWidth: 190,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      fontSize: 13,
                      color: 'var(--ol-ink)',
                      fontWeight: 600,
                    }}
                  >
                    {user.name}
                  </div>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 4,
                      marginTop: 4,
                      maxWidth: 190,
                      overflow: 'hidden',
                    }}
                  >
                    {visibleRoles.map((role) => (
                      <span
                        key={role}
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          height: 18,
                          padding: '0 6px',
                          borderRadius: 5,
                          background: 'var(--ol-brand-soft)',
                          color: 'var(--ol-brand)',
                          fontSize: 10,
                          fontWeight: 600,
                          lineHeight: '18px',
                          flexShrink: 0,
                        }}
                      >
                        {ROLE_LABELS[role] || role}
                      </span>
                    ))}
                    {hiddenRoleCount > 0 && (
                      <span
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          height: 18,
                          padding: '0 6px',
                          borderRadius: 5,
                          background: 'var(--ol-fill)',
                          color: 'var(--ol-ink-3)',
                          border: '1px solid var(--ol-line-soft)',
                          fontSize: 10,
                          fontWeight: 600,
                          lineHeight: '16px',
                          flexShrink: 0,
                        }}
                      >
                        +{hiddenRoleCount}
                      </span>
                    )}
                </div>
                </div>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content style={{ padding: 20, paddingBottom: taskBarCollapsed ? 60 : 196, background: 'var(--ol-fill)' }}>
          <Outlet />
        </Content>

        {/* 底部全局任务条 */}
        {!taskBarCollapsed && (
          <div
            style={{
              position: 'fixed', bottom: 16,
              left: collapsed ? 80 : 248, right: 24,
              zIndex: 100,
              transition: 'left var(--ol-dur-base) var(--ol-ease)',
            }}
          >
            <TaskProgressBar
              tasks={tasks}
              taskError={taskLoadError}
              busyTaskId={taskActionId}
              onCollapse={() => setTaskBarCollapsed(true)}
              onTaskOpen={handleTaskOpen}
              onTaskCancel={handleTaskCancel}
              onTaskDismiss={handleTaskDismiss}
            />
          </div>
        )}
      </Layout>

      <GlobalSearch />
      <NotificationCenter />
    </Layout>
  );
}
