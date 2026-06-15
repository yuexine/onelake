/**
 * Mock 数据 - 数据服务 DaaS（L5）。
 */
import type { ApiDefinition, ApiVersion, AppKey, Subscription, ApiCallLog } from '../types';

export const apis: ApiDefinition[] = [
  { id: 'api-1', apiPath: '/order/detail', name: '订单明细查询', description: '按订单 ID 查询订单详情', viewName: 'v_order_detail', selectSql: 'SELECT order_id, phone, amount FROM ads.ads_sales_df WHERE order_id = :order_id', sourceFqn: 'ads.ads_sales_df', qpsLimit: 50, status: 'PUBLISHED', currentVersion: 2, classification: 'L2', subscriberCount: 18, successRate: 99.2, qps: 32, createdAt: '2026-04-01T10:00:00Z' },
  { id: 'api-2', apiPath: '/user/profile', name: '用户画像查询', description: '按用户 ID 查询画像', viewName: 'v_user_profile', selectSql: 'SELECT user_id, phone, age FROM dws.dws_user_order WHERE user_id = :user_id', sourceFqn: 'dws.dws_user_order', qpsLimit: 20, status: 'DEPRECATED', currentVersion: 1, classification: 'L3', subscriberCount: 5, successRate: 99.5, qps: 8, createdAt: '2026-04-15T10:00:00Z', deprecateAt: '2026-06-01T00:00:00Z', offlineAt: '2026-07-14T00:00:00Z' },
  { id: 'api-3', apiPath: '/sales/daily', name: '每日销售汇总', description: '查询每日 GMV / 订单数', viewName: 'v_sales_daily', selectSql: 'SELECT stat_date, gmv, order_cnt FROM ads.ads_sales_df WHERE stat_date = :dt', sourceFqn: 'ads.ads_sales_df', qpsLimit: 100, status: 'PUBLISHED', currentVersion: 1, classification: 'L2', subscriberCount: 12, successRate: 100, qps: 5, createdAt: '2026-03-20T10:00:00Z' },
  { id: 'api-4', apiPath: '/risk/score', name: '风控评分', description: '查询用户风控评分', viewName: 'v_risk_score', selectSql: 'SELECT user_id, score FROM ads.ads_risk_df', sourceFqn: 'ads.ads_risk_df', qpsLimit: 30, status: 'DRAFT', currentVersion: 1, classification: 'L4', createdAt: '2026-06-10T10:00:00Z' },
];

export const apiVersions: ApiVersion[] = [
  { id: 'v-2', apiId: 'api-1', version: 2, publishedAt: '2026-05-15T10:00:00Z', grayPercent: 100,
    spec: { params: [{ name: 'order_id', type: 'BIGINT', required: true }, { name: 'dt', type: 'DATE', required: false }],
            returns: [{ name: 'order_id', type: 'BIGINT' }, { name: 'phone', type: 'STRING', classification: 'L3', masked: true }, { name: 'amount', type: 'DECIMAL(18,2)' }] } },
  { id: 'v-1', apiId: 'api-1', version: 1, publishedAt: '2026-04-01T10:00:00Z', deprecatedAt: '2026-05-15T10:00:00Z',
    spec: { params: [{ name: 'order_id', type: 'BIGINT', required: true }],
            returns: [{ name: 'order_id', type: 'BIGINT' }, { name: 'phone', type: 'STRING', classification: 'L3' }] } },
];

export const appKeys: AppKey[] = [
  { id: 'ak-1', appKey: 'ak_8f3c45e2', secretHash: '******', ownerId: 'u-1', ownerName: '报表组', ipWhitelist: ['10.0.0.0/24'], quotaDaily: 100000, expiresAt: '2026-12-31T00:00:00Z', status: 'ACTIVE', createdAt: '2026-04-01T10:00:00Z', recentCalls: { status2xx: 11800, status429: 120, status401: 3 } },
  { id: 'ak-2', appKey: 'ak_a91f82d4', secretHash: '******', ownerId: 'u-2', ownerName: '风控', ipWhitelist: ['203.0.113.5'], quotaDaily: 50000, expiresAt: '2026-09-01T00:00:00Z', status: 'ACTIVE', createdAt: '2026-04-20T10:00:00Z', recentCalls: { status2xx: 8500, status429: 30, status401: 0 } },
];

export const subscriptions: Subscription[] = [
  { id: 'sub-1', apiId: 'api-1', apiPath: '/order/detail', subscriberId: 'u-1', subscriberName: '报表组', appKeyId: 'ak-1', status: 'APPROVED', approvedBy: '张三', reason: '月度报表分析', createdAt: '2026-04-05T10:00:00Z' },
  { id: 'sub-2', apiId: 'api-1', apiPath: '/order/detail', subscriberId: 'u-2', subscriberName: '风控', appKeyId: 'ak-2', status: 'APPROVED', approvedBy: '张三', reason: '风控规则计算', createdAt: '2026-04-22T10:00:00Z' },
  { id: 'sub-3', apiId: 'api-3', apiPath: '/sales/daily', subscriberId: 'u-3', subscriberName: 'BI 组', status: 'PENDING', reason: '需要做 GMV 看板', createdAt: '2026-06-14T09:30:00Z' },
  { id: 'sub-4', apiId: 'api-1', apiPath: '/order/detail', subscriberId: 'u-4', subscriberName: '王五', status: 'PENDING', reason: '临时查询', createdAt: '2026-06-14T10:02:00Z' },
];

export const callLogs: ApiCallLog[] = [
  { id: 'cl-1', apiId: 'api-1', appKeyId: 'ak-1', statusCode: 200, latencyMs: 80, requestIp: '10.0.0.5', calledAt: '2026-06-14T10:21:00Z' },
  { id: 'cl-2', apiId: 'api-1', appKeyId: 'ak-1', statusCode: 429, latencyMs: 5, requestIp: '10.0.0.6', calledAt: '2026-06-14T10:20:55Z' },
  { id: 'cl-3', apiId: 'api-1', appKeyId: 'ak-2', statusCode: 200, latencyMs: 95, requestIp: '203.0.113.5', calledAt: '2026-06-14T10:20:30Z' },
  { id: 'cl-4', apiId: 'api-1', appKeyId: undefined, statusCode: 401, latencyMs: 2, requestIp: '8.8.8.8', calledAt: '2026-06-14T10:20:00Z' },
];

export const apiCallTrend = Array.from({ length: 24 }, (_, i) => ({
  hour: `${String(i).padStart(2, '0')}:00`,
  calls: Math.floor(200 + Math.random() * 800 + (i >= 9 && i <= 21 ? 400 : 0)),
  latency: Math.floor(60 + Math.random() * 80),
}));

// 升额申请记录
export const quotaRaises = [
  { id: 'qr-1', apiPath: '/order/detail', applicant: '报表组', current: 20, requested: 50, reason: '大促期间流量上涨', period: '2026-06-18 ~ 2026-06-21', status: 'PENDING', createdAt: '2026-06-14T10:00:00Z' },
];
