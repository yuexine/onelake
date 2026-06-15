/**
 * 工作台 / 首页（对应原型 §4.1 / §8.1 升级版）。
 *   - 4 KPI 卡（可下钻）
 *   - 我的待办（含详情抽屉）
 *   - 快捷入口
 *   - 近期动态时间线
 */
import { Col, Row, List, Tag, Typography, Space, Button, Drawer, Timeline } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import {
  DatabaseOutlined, CheckCircleOutlined, SyncOutlined, AlertOutlined,
  PlusOutlined, ArrowRightOutlined, DashboardOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import type { ApprovalRequest } from '../../types';
import { PageHeader, SectionCard, StatCard } from '../../components';

const { Text } = Typography;

const TODO_INTENT: Record<string, { bg: string; fg: string }> = {
  TASK:     { bg: 'var(--ol-error-soft)',   fg: 'var(--ol-error)' },
  APPROVAL: { bg: 'var(--ol-brand-soft)',   fg: 'var(--ol-brand)' },
  ALERT:    { bg: 'var(--ol-warning-soft)', fg: '#B45309' },
  SECURITY: { bg: 'var(--ol-warning-soft)', fg: '#B45309' },
  SYSTEM:   { bg: 'var(--ol-fill-soft)',    fg: 'var(--ol-ink-3)' },
};

export default function Dashboard() {
  const navigate = useNavigate();
  const [drawer, setDrawer] = useState<ApprovalRequest | null>(null);

  const todos: { type: string; title: string; meta: string; action: () => void; payload?: ApprovalRequest }[] = [
    { type: 'TASK', title: '采集任务 orders_sync 失败，请重跑', meta: 'AUTH_401', action: () => navigate('/integration/sync-tasks/st-001') },
    { type: 'APPROVAL', title: '订阅申请待审批 · 王五', meta: '/api/order/detail', action: () => navigate('/system/approvals/ap-2088'),
      payload: { id: 'ap-2088', requestType: 'SUBSCRIPTION', applicantId: 'u-4', applicantName: '王五', targetRef: '/api/order/detail', reason: '临时查询订单', status: 'PENDING', riskLevel: 'LOW', impactSummary: { apis: 1 }, createdAt: '', chain: [] } },
    { type: 'ALERT', title: '质量门禁拦截 dwd_order_df amount 越界 32 行', meta: 'CRITICAL', action: () => navigate('/quality/gate') },
    { type: 'APPROVAL', title: 'Schema 变更审批 users DROP age', meta: '破坏性', action: () => navigate('/integration/schema-change/sc-001'),
      payload: { id: 'ap-2089', requestType: 'SCHEMA_CHANGE', applicantId: 'sys', applicantName: '系统', targetRef: 'ods.users (DROP COLUMN age)', reason: 'CDC 自动捕获', status: 'PENDING', riskLevel: 'HIGH', impactSummary: { assets: 2, apis: 1, subscribers: 18 }, createdAt: '', chain: [] } },
    { type: 'SECURITY', title: '密级变更待确认 phone L2 → L3', meta: '影响 18 个订阅方', action: () => navigate('/security/masking') },
  ];

  const quickActions = [
    { name: '新建连接',     link: '/integration/datasources',     icon: <DatabaseOutlined /> },
    { name: '新建采集任务', link: '/integration/sync-tasks/new',  icon: <SyncOutlined /> },
    { name: '新建 dbt 模型', link: '/lakehouse/tables',           icon: <DatabaseOutlined /> },
    { name: '发布数据 API', link: '/dataservice/apis/new',        icon: <ThunderboltOutlined /> },
    { name: '配置质量规则', link: '/quality/rules',               icon: <CheckCircleOutlined /> },
    { name: '配置脱敏策略', link: '/security/masking',            icon: <AlertOutlined /> },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<DashboardOutlined />}
        title={`早上好，张三`}
        subtitle={<span className="ol-chip">工作台 · L0</span>}
        description="今日待办 5 条、运行中任务 12 个、待处理告警 5 条"
      />

      <div className="ol-grid-stats">
        <StatCard
          icon={<DatabaseOutlined />} intent="brand"
          label="资产总数"
          value={1280}
          suffix="个"
          spark={[1180, 1200, 1220, 1240, 1260, 1270, 1280]}
        />
        <StatCard
          icon={<CheckCircleOutlined />} intent="success"
          label="今日任务成功率"
          value={98.2} suffix="%"
          delta={{ value: '+0.4%', direction: 'up', good: 'up' }}
        />
        <StatCard
          icon={<SyncOutlined />} intent="info"
          label="运行中任务"
          value={12}
          suffix="个"
          hint="点击进入采集监控"
        />
        <StatCard
          icon={<AlertOutlined />} intent="error"
          label="待处理告警"
          value={5}
          suffix="条"
          delta={{ value: '+2', direction: 'up', good: 'down' }}
        />
      </div>

      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <SectionCard
            title="我的待办"
            icon={<AlertOutlined />}
            subtitle={`${todos.length} 条`}
            extra={<Button type="link" onClick={() => navigate('/system/approvals')}>全部审批 <ArrowRightOutlined /></Button>}
            style={{ height: '100%' }}
            padded="none"
            bodyStyle={{ padding: 0 }}
          >
            <List
              dataSource={todos}
              renderItem={(t) => {
                const intent = TODO_INTENT[t.type] || TODO_INTENT.SYSTEM;
                return (
                  <div
                    onClick={() => t.payload ? setDrawer(t.payload) : t.action()}
                    style={{
                      padding: '12px 16px',
                      borderBottom: '1px solid var(--ol-line-soft)',
                      cursor: 'pointer',
                      transition: 'background var(--ol-dur-fast) var(--ol-ease)',
                      display: 'flex', alignItems: 'center', gap: 12,
                    }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--ol-fill-soft)')}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    <span style={{
                      padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                      background: intent.bg, color: intent.fg, flexShrink: 0,
                    }}>{t.type}</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Text style={{ fontSize: 13, fontWeight: 500 }}>{t.title}</Text>
                      <div style={{ marginTop: 2 }}>
                        <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t.meta}</Text>
                      </div>
                    </div>
                    <ArrowRightOutlined style={{ color: 'var(--ol-ink-4)', fontSize: 12 }} />
                  </div>
                );
              }}
            />
          </SectionCard>
        </Col>
        <Col xs={24} lg={10}>
          <SectionCard title="快捷入口" icon={<ThunderboltOutlined />} style={{ height: '100%' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {quickActions.map((a) => (
                <button
                  key={a.name}
                  onClick={() => navigate(a.link)}
                  style={{
                    padding: '12px 14px', borderRadius: 8,
                    background: 'var(--ol-card)', border: '1px solid var(--ol-line-soft)',
                    cursor: 'pointer', textAlign: 'left',
                    display: 'flex', alignItems: 'center', gap: 10,
                    transition: 'all var(--ol-dur-fast) var(--ol-ease)',
                    color: 'var(--ol-ink-2)', fontSize: 13, fontWeight: 500,
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.borderColor = 'var(--ol-brand)';
                    e.currentTarget.style.background = 'var(--ol-brand-soft)';
                    e.currentTarget.style.color = 'var(--ol-brand)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.borderColor = 'var(--ol-line-soft)';
                    e.currentTarget.style.background = 'var(--ol-card)';
                    e.currentTarget.style.color = 'var(--ol-ink-2)';
                  }}
                >
                  <PlusOutlined style={{ fontSize: 12 }} />
                  {a.name}
                </button>
              ))}
            </div>
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="近期动态" icon={<DashboardOutlined />}>
        <Timeline
          items={[
            { color: 'blue',   children: <span style={{ fontSize: 13 }}><Text strong>10:21</Text> 资产 dwd_order_df Schema 变更（CDC 同步 ADD COLUMN memo）</span> },
            { color: 'orange', children: <span style={{ fontSize: 13 }}><Text strong>10:05</Text> 李四 下载样例数据 dim_user <Tag color="warning" style={{ margin: '0 0 0 6px' }}>敏感</Tag></span> },
            { color: 'red',    children: <span style={{ fontSize: 13 }}><Text strong>09:50</Text> 风控-Hive 系统密码即将过期（剩余 3 天）</span> },
            { color: 'green',  children: <span style={{ fontSize: 13 }}><Text strong>09:30</Text> Compaction 完成 dws_user_df（小文件 320 → 24）</span> },
            { color: 'blue',   children: <span style={{ fontSize: 13 }}><Text strong>昨天 02:00</Text> 采集任务 orders_sync 成功（12.3 万行，48s）</span> },
          ]}
        />
      </SectionCard>

      <Drawer
        open={!!drawer}
        onClose={() => setDrawer(null)}
        title="待办详情"
        width={520}
        extra={
          <Space>
            <Button>忽略</Button>
            <Button>转交</Button>
            <Button type="primary" onClick={() => { if (drawer) navigate(`/system/approvals/${drawer.id}`); }}>处理</Button>
          </Space>
        }
      >
        {drawer && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>类型</Text>
              <div style={{ marginTop: 4 }}>
                <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>
                  {drawer.requestType}
                </span>
              </div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>关联对象</Text>
              <div style={{ marginTop: 4 }}><Text code style={{ fontSize: 12 }}>{drawer.targetRef}</Text></div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>申请人</Text>
              <div style={{ marginTop: 4, fontSize: 13 }}>{drawer.applicantName}</div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>原因</Text>
              <div style={{ marginTop: 4, fontSize: 13 }}>{drawer.reason}</div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>风险等级</Text>
              <div style={{ marginTop: 4 }}>
                <Tag color={drawer.riskLevel === 'HIGH' ? 'error' : drawer.riskLevel === 'MEDIUM' ? 'warning' : 'success'} style={{ margin: 0 }}>
                  {drawer.riskLevel}
                </Tag>
              </div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>影响范围</Text>
              <div style={{ marginTop: 4, fontSize: 13 }}>
                {drawer.impactSummary?.assets ?? 0} 资产 / {drawer.impactSummary?.apis ?? 0} API / {drawer.impactSummary?.subscribers ?? 0} 订阅方
              </div>
            </div>
          </Space>
        )}
      </Drawer>
    </div>
  );
}
