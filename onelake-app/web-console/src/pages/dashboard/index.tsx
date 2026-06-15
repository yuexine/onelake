/**
 * 工作台 / 首页（对应原型 §4.1 / §8.1）。
 * - 4 指标卡（可下钻到对应列表）
 * - 我的待办（含详情抽屉）
 * - 快捷入口
 * - 近期动态
 */
import { Card, Col, Row, Statistic, List, Tag, Typography, Space, Button, Drawer, Descriptions, Timeline } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import {
  DatabaseOutlined, CheckCircleOutlined, SyncOutlined, AlertOutlined,
  PlusOutlined, ArrowRightOutlined,
} from '@ant-design/icons';
import type { ApprovalRequest } from '../../types';

const { Text } = Typography;

const TODO_COLORS: Record<string, string> = {
  TASK: 'red', APPROVAL: 'blue', ALERT: 'orange', SECURITY: 'orange', SYSTEM: 'default',
};

export default function Dashboard() {
  const navigate = useNavigate();
  const [drawer, setDrawer] = useState<ApprovalRequest | null>(null);

  const todos: { type: string; title: string; meta: string; action: () => void; payload?: ApprovalRequest }[] = [
    { type: 'TASK', title: '采集任务 orders_sync 失败，请重跑', meta: 'AUTH_401', action: () => navigate('/integration/sync-tasks/st-001') },
    { type: 'APPROVAL', title: '订阅申请待审批 - 王五', meta: '/api/order/detail', action: () => navigate('/system/approvals/ap-2088'),
      payload: { id: 'ap-2088', requestType: 'SUBSCRIPTION', applicantId: 'u-4', applicantName: '王五', targetRef: '/api/order/detail', reason: '临时查询订单', status: 'PENDING', riskLevel: 'LOW', impactSummary: { apis: 1 }, createdAt: '', chain: [] } },
    { type: 'ALERT', title: '质量门禁拦截 dwd_order_df amount 越界 32 行', meta: 'CRITICAL', action: () => navigate('/quality/gate') },
    { type: 'APPROVAL', title: 'Schema 变更审批 users DROP age', meta: '破坏性', action: () => navigate('/integration/schema-change/sc-001'),
      payload: { id: 'ap-2089', requestType: 'SCHEMA_CHANGE', applicantId: 'sys', applicantName: '系统', targetRef: 'ods.users (DROP COLUMN age)', reason: 'CDC 自动捕获', status: 'PENDING', riskLevel: 'HIGH', impactSummary: { assets: 2, apis: 1, subscribers: 18 }, createdAt: '', chain: [] } },
    { type: 'SECURITY', title: '密级变更待确认 phone L2 → L3', meta: '影响 18 个订阅方', action: () => navigate('/security/masking') },
  ];

  const quickActions = [
    { name: '+ 新建连接', link: '/integration/datasources' },
    { name: '+ 新建采集任务', link: '/integration/sync-tasks/new' },
    { name: '+ 新建 dbt 模型', link: '/lakehouse/tables' },
    { name: '+ 发布数据 API', link: '/dataservice/apis/new' },
    { name: '+ 配置质量规则', link: '/quality/rules' },
    { name: '+ 配置脱敏策略', link: '/security/masking' },
  ];

  return (
    <>
      <Row gutter={16}>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/catalog/search')}>
            <Statistic title="资产总数" value={1280} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/monitor/overview')}>
            <Statistic title="今日任务成功率" value={98.2} suffix="%" prefix={<CheckCircleOutlined />} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/integration/monitor')}>
            <Statistic title="运行中任务" value={12} prefix={<SyncOutlined spin />} valueStyle={{ color: '#1677ff' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/monitor/alerts')}>
            <Statistic title="待处理告警" value={5} prefix={<AlertOutlined />} valueStyle={{ color: '#fa541c' }} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={14}>
          <Card title="我的待办" extra={<a onClick={() => navigate('/system/approvals')}>全部审批 <ArrowRightOutlined /></a>}>
            <List
              dataSource={todos}
              renderItem={(t) => (
                <List.Item actions={[
                  <Button type="link" key="go" onClick={() => t.payload ? setDrawer(t.payload) : t.action()}>处理</Button>,
                  <Button type="link" key="ctx" onClick={t.action}>查看上下文</Button>,
                ]}>
                  <List.Item.Meta
                    avatar={<Tag color={TODO_COLORS[t.type]}>{t.type}</Tag>}
                    title={<Text>{t.title}</Text>}
                    description={<Text type="secondary">{t.meta}</Text>}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col span={10}>
          <Card title="快捷入口">
            <Space wrap>
              {quickActions.map((a) => (
                <Button key={a.name} type="default" icon={<PlusOutlined />} onClick={() => navigate(a.link)}>{a.name}</Button>
              ))}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="近期动态" style={{ marginTop: 16 }}>
        <Timeline
          items={[
            { color: 'blue', children: '10:21 资产 dwd_order_df Schema 变更（CDC 同步 ADD COLUMN memo）' },
            { color: 'orange', children: '10:05 李四 下载样例数据 dim_user (敏感)' },
            { color: 'red', children: '09:50 风控 - Hive 系统密码即将过期（剩余 3 天）' },
            { color: 'green', children: '09:30 Compaction 完成 dws_user_df（小文件 320 → 24）' },
            { color: 'blue', children: '昨天 02:00 采集任务 orders_sync 成功（12.3 万行，48s）' },
          ]}
        />
      </Card>

      {/* 待办详情抽屉（§8.1.3） */}
      <Drawer
        open={!!drawer}
        onClose={() => setDrawer(null)}
        title="待办详情"
        width={520}
        extra={<Space>
          <Button>忽略</Button>
          <Button>转交</Button>
          <Button type="primary" onClick={() => { if (drawer) navigate(`/system/approvals/${drawer.id}`); }}>处理</Button>
        </Space>}
      >
        {drawer && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="类型">{drawer.requestType}</Descriptions.Item>
            <Descriptions.Item label="关联">{drawer.targetRef}</Descriptions.Item>
            <Descriptions.Item label="申请人">{drawer.applicantName}</Descriptions.Item>
            <Descriptions.Item label="原因">{drawer.reason}</Descriptions.Item>
            <Descriptions.Item label="风险等级">
              <Tag color={drawer.riskLevel === 'HIGH' ? 'red' : drawer.riskLevel === 'MEDIUM' ? 'orange' : 'green'}>{drawer.riskLevel}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="影响">
              {drawer.impactSummary?.assets ?? 0} 资产 / {drawer.impactSummary?.apis ?? 0} API / {drawer.impactSummary?.subscribers ?? 0} 订阅方
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </>
  );
}
