/**
 * Mock 数据 - 公共、安全、运营、系统。
 */
import type { ApprovalRequest, AuditLog, Notification, Tenant, Role, RoleBinding, MaskingPolicy, Secret, AccessGrant, NotificationChannel } from '../types';

export const approvals: ApprovalRequest[] = [
  {
    id: 'ap-2087', requestType: 'ACCESS', applicantId: 'u-4', applicantName: '李四',
    targetRef: 'dwd.dwd_order_df', targetType: '资产访问',
    reason: '月度报表分析', riskLevel: 'LOW',
    impactSummary: { assets: 1 },
    payload: { columns: ['order_id', 'amount'], period: '90 天', permissions: { query: true, download: false, api: true } },
    status: 'PENDING',
    chain: [
      { role: '资产负责人', user: '张三', status: 'APPROVED', at: '2026-06-14T10:10:00Z', comment: '同意' },
      { role: '安全合规', status: 'PENDING' },
    ],
    createdAt: '2026-06-14T10:02:00Z',
  },
  {
    id: 'ap-2088', requestType: 'SUBSCRIPTION', applicantId: 'u-4', applicantName: '王五',
    targetRef: '/api/order/detail', targetType: 'API 订阅',
    reason: '临时查询订单', riskLevel: 'LOW',
    impactSummary: { apis: 1 },
    status: 'PENDING',
    chain: [{ role: 'API 负责人', status: 'PENDING' }],
    createdAt: '2026-06-14T10:02:00Z',
  },
  {
    id: 'ap-2089', requestType: 'SCHEMA_CHANGE', applicantId: 'sys', applicantName: '系统',
    targetRef: 'ods.users (DROP COLUMN age)', targetType: '破坏性 Schema 变更',
    reason: 'CDC 自动捕获', riskLevel: 'HIGH',
    impactSummary: { assets: 2, apis: 1, subscribers: 18 },
    payload: { change: 'DROP COLUMN age', table: 'users', source: 'mysql_orders_cdc' },
    status: 'PENDING',
    chain: [{ role: '资产负责人', user: '李四', status: 'PENDING' }, { role: '安全合规', status: 'PENDING' }],
    createdAt: '2026-06-14T09:50:00Z',
  },
  {
    id: 'ap-2090', requestType: 'MASK_EXEMPTION', applicantId: 'u-5', applicantName: '王五',
    targetRef: 'dwd.dwd_order_df.amount (范围规则)', targetType: '质量门禁豁免',
    reason: '历史脏数据，待数据修复后重跑', riskLevel: 'MEDIUM',
    impactSummary: { assets: 1, apis: 1 },
    status: 'PENDING',
    chain: [{ role: '资产负责人', user: '张三', status: 'APPROVED', at: '2026-06-14T09:35:00Z' }, { role: '安全合规', status: 'PENDING' }],
    createdAt: '2026-06-14T09:30:00Z',
  },
  {
    id: 'ap-2086', requestType: 'QUOTA_RAISE', applicantId: 'u-1', applicantName: '报表组',
    targetRef: '/api/order/detail (20→50 QPS)', targetType: '配额升额',
    reason: '大促期间流量上涨', riskLevel: 'LOW',
    impactSummary: { apis: 1 },
    payload: { period: '2026-06-18 ~ 2026-06-21' },
    status: 'PENDING',
    chain: [{ role: 'API 负责人', user: '张三', status: 'PENDING' }],
    createdAt: '2026-06-14T10:00:00Z',
  },
  {
    id: 'ap-2080', requestType: 'ACCESS', applicantId: 'u-6', applicantName: '赵六',
    targetRef: 'ads.ads_sales_df', targetType: '资产访问',
    reason: '日常运营监控', riskLevel: 'LOW',
    impactSummary: { assets: 1 },
    status: 'APPROVED', approverId: 'u-1', approverName: '张三',
    chain: [{ role: '资产负责人', user: '张三', status: 'APPROVED' }],
    createdAt: '2026-06-13T14:00:00Z', decidedAt: '2026-06-13T14:30:00Z',
  },
];

export const auditLogs: AuditLog[] = [
  { id: 1, actorId: 'u-1', actorName: '张三', action: '修改密级', resourceType: '资产', resourceId: 'dwd_order_df', detail: 'L2 → L3', sensitive: true, traceId: 'a1b2c3', occurredAt: '2026-06-14T10:21:03Z' },
  { id: 2, actorId: 'u-2', actorName: '李四', action: '下载样例数据', resourceType: '资产', resourceId: 'dim_user', detail: '100 行', sensitive: true, traceId: 'd4e5f6', occurredAt: '2026-06-14T10:05:11Z' },
  { id: 3, actorId: 'sys', actorName: '系统', action: 'DDL 演进审批拒绝', resourceType: 'Schema', resourceId: 'users.age', sensitive: false, traceId: 'g7h8i9', occurredAt: '2026-06-14T09:50:22Z' },
  { id: 4, actorId: 'u-4', actorName: '王五', action: '调用 API', resourceType: 'API', resourceId: '/v2/order', detail: '200', sensitive: false, traceId: 'j0k1l2', occurredAt: '2026-06-14T09:30:00Z' },
  { id: 5, actorId: 'u-1', actorName: '张三', action: '发布 API', resourceType: 'API', resourceId: '/api/order v3', sensitive: false, traceId: 'm3n4o5', occurredAt: '2026-06-14T09:00:00Z' },
  { id: 6, actorId: 'u-3', actorName: '王五', action: '权限授予', resourceType: '资产', resourceId: 'ads_sales', sensitive: true, traceId: 'p6q7r8', occurredAt: '2026-06-13T14:30:00Z' },
];

export const notifications: Notification[] = [
  { id: 'n-1', category: 'TASK', receiverId: 'u-1', title: '采集任务 orders_sync 连续失败 3 次', content: '错误码 AUTH_401，账号密码过期', level: 'CRITICAL', link: '/integration/sync-tasks/st-001', isRead: false, createdAt: '2026-06-14T10:21:00Z' },
  { id: 'n-2', category: 'APPROVAL', receiverId: 'u-1', title: '订阅申请待审批 - 王五', content: '/api/order/detail', level: 'INFO', link: '/system/approvals', isRead: false, createdAt: '2026-06-14T09:30:00Z' },
  { id: 'n-3', category: 'SECURITY', receiverId: 'u-1', title: 'dwd_order 密级变更 L2→L3', level: 'WARN', link: '/security/masking', isRead: false, createdAt: '2026-06-14T09:10:00Z' },
  { id: 'n-4', category: 'SYSTEM', receiverId: 'u-1', title: 'Compaction 完成 dws_user', level: 'INFO', link: '/lakehouse/optimize', isRead: false, createdAt: '2026-06-14T08:50:00Z' },
  { id: 'n-5', category: 'ALERT', receiverId: 'u-1', title: 'API 超时率升高', content: '/api/order/detail 99.2% → 95.1%', level: 'WARN', link: '/monitor/alerts', isRead: true, createdAt: '2026-06-14T08:00:00Z' },
];

export const tenants: Tenant[] = [
  { id: 't-1', code: 'TRADE', name: '交易事业部', status: 'ACTIVE', projectCount: 8, memberCount: 120, quotaCuUsed: 500, quotaCuTotal: 800, createdAt: '2026-01-01T00:00:00Z' },
  { id: 't-2', code: 'RISK', name: '风控中心', status: 'ACTIVE', projectCount: 3, memberCount: 45, quotaCuUsed: 200, quotaCuTotal: 200, createdAt: '2026-01-15T00:00:00Z' },
  { id: 't-3', code: 'MARKETING', name: '营销中心', status: 'ACTIVE', projectCount: 5, memberCount: 60, quotaCuUsed: 150, quotaCuTotal: 300, createdAt: '2026-02-01T00:00:00Z' },
];

export const roles: Role[] = [
  { id: 'rl-1', code: 'DE', name: '数据工程师', description: '接入、建模、开发、发布', members: 18 },
  { id: 'rl-2', code: 'ADMIN', name: '数据管理员', description: '资产治理、分级、审批', members: 5 },
  { id: 'rl-3', code: 'CONSUMER', name: '数据消费方', description: '找数、订阅、调 API', members: 28 },
  { id: 'rl-4', code: 'SEC', name: '安全合规', description: '密级、脱敏、审计', members: 3 },
  { id: 'rl-5', code: 'OPS', name: '运维', description: '监控、告警、排障', members: 6 },
];

export const roleBindings: RoleBinding[] = [
  { id: 'rb-1', roleId: 'rl-3', resourceType: 'MENU', resourceRef: '/catalog', actions: ['read'] },
  { id: 'rb-2', roleId: 'rl-3', resourceType: 'MENU', resourceRef: '/dataservice', actions: ['read', 'subscribe'] },
  { id: 'rb-3', roleId: 'rl-3', resourceType: 'ASSET', resourceRef: 'ads_*', actions: ['read'] },
  { id: 'rb-4', roleId: 'rl-3', resourceType: 'ASSET', resourceRef: '*.*', actions: ['read'], },
];

export const maskingPolicies: MaskingPolicy[] = [
  { id: 'mp-1', targetFqn: '*.phone', classification: 'L3', strategy: 'MASK', priority: 100, algorithm: '保留前 3 后 4', preview: { input: '13812348888', output: '138****8888' } },
  { id: 'mp-2', targetFqn: '*.id_card', classification: 'L4', strategy: 'MASK', priority: 100, algorithm: '保留前 6 后 4', preview: { input: '110101199001011234', output: '110101********1234' } },
  { id: 'mp-3', targetFqn: '*.email', classification: 'L3', strategy: 'PARTIAL', priority: 90, algorithm: '邮箱前缀*', preview: { input: 'zhang.san@onelake.io', output: 'zhang.s***@onelake.io' } },
];

export const secrets: Secret[] = [
  { id: 'sec-1', refKey: 'ds/order_db/pwd', kmsKeyId: 'cmk-order-v3', rotatedAt: '2026-06-01T00:00:00Z', createdAt: '2026-03-01T10:00:00Z' },
  { id: 'sec-2', refKey: 'ds/user_db/pwd', kmsKeyId: 'cmk-user-v2', rotatedAt: '2026-04-15T00:00:00Z', createdAt: '2026-03-01T10:30:00Z' },
];

export const accessGrants: AccessGrant[] = [
  { id: 'ag-1', subjectId: 'u-4', assetFqn: 'dwd.dwd_order_df', columns: ['order_id', 'amount'], permissions: { query: true, api: true }, status: 'ACTIVE', grantedAt: '2026-06-01T00:00:00Z', expiresAt: '2026-09-01T00:00:00Z' },
  { id: 'ag-2', subjectId: 'u-6', assetFqn: 'ads.ads_sales_df', permissions: { query: true }, status: 'ACTIVE', grantedAt: '2026-06-13T14:30:00Z' },
];

export const channels: NotificationChannel[] = [
  { id: 'ch-1', type: 'EMAIL', config: { smtp: 'smtp.corp.com', from: 'onelake@corp.com' }, status: 'ACTIVE' },
  { id: 'ch-2', type: 'DINGTALK', config: { webhook: 'https://oapi.dingtalk.com/robot/send?access_token=****' }, status: 'ACTIVE' },
  { id: 'ch-3', type: 'WEBHOOK', config: { url: 'https://hooks.corp.com/onelake' }, status: 'ACTIVE' },
  { id: 'ch-4', type: 'PHONE', config: {}, status: 'INACTIVE' },
];

// 告警
export const opsAlerts = [
  { id: 'oa-1', level: 'P0', source: '采集', title: 'orders_sync 连续失败 3 次', status: 'OPEN', assignee: '', createdAt: '2026-06-14T02:10:00Z', rule: '连续失败≥3 次', relatedRunId: 'sr-1041' },
  { id: 'oa-2', level: 'P0', source: 'DaaS', title: 'order_api 错误率>50% 熔断', status: 'PROCESSING', assignee: '张三', createdAt: '2026-06-14T02:30:00Z', rule: '错误率>50%', relatedApi: '/api/order/detail' },
  { id: 'oa-3', level: 'P1', source: '质量', title: 'dwd_order 门禁拦截 amount 范围失败', status: 'OPEN', assignee: '', createdAt: '2026-06-14T01:55:00Z', rule: '强规则失败' },
  { id: 'oa-4', level: 'P2', source: '湖仓', title: 'dwd_order_df 小文件超阈 1200 个', status: 'OPEN', assignee: '', createdAt: '2026-06-13T22:00:00Z', rule: '小文件>1000' },
];

// 故障复盘
export const incidents = [
  {
    id: 'INC-0614-001', alert: 'orders_sync 连续失败 3 次',
    timeline: [
      { at: '02:10', event: '告警触发（orders_sync 失败）' },
      { at: '02:12', event: '通知值班（电话+钉钉）' },
      { at: '02:15', event: '认领（张三）' },
      { at: '02:20', event: '重试失败' },
      { at: '02:35', event: '切换只读账号' },
      { at: '02:40', event: '恢复' },
    ],
    impactDuration: '30 min',
    downstreamImpact: '2 模型 / 1 API',
    rca: '源库账号密码过期未轮换',
    improvements: [{ action: '接入密钥到期预警', owner: '张三', due: '2026-06-21' }],
  },
];

// SLA/SLO 看板
export const slaDashboard = [
  { metric: '任务准点率', value: 99.2, target: 99, status: 'OK' },
  { metric: 'API 可用性', value: 99.95, target: 99.9, status: 'OK' },
  { metric: '查询成功率', value: 99.7, target: 99.5, status: 'OK' },
  { metric: 'ODS 数据新鲜度', value: 5, target: 5, status: 'OK', unit: 'min' },
  { metric: 'DWD 数据新鲜度', value: 1, target: 1, status: 'OK', unit: 'h' },
];
