/**
 * Mock 数据 - 数据质量（L3-4）。
 */
import type { QualityRule, QualityRunResult, QualityAlert } from '../types';

export const qualityRules: QualityRule[] = [
  { id: 'r-1', targetFqn: 'dwd.dwd_order_df', targetColumn: 'order_id', ruleType: 'NOT_NULL', expression: 'order_id IS NOT NULL', severity: 'BLOCK', owner: '张三', enabled: true, version: 1, schedule: 'ON_PARTITION', lastPassRate: 100, trend: [100, 100, 100, 100, 100], createdAt: '2026-03-01T10:00:00Z' },
  { id: 'r-2', targetFqn: 'dwd.dwd_order_df', targetColumn: 'amount', ruleType: 'RANGE', expression: '0 <= amount <= 99999', severity: 'BLOCK', owner: '张三', enabled: true, version: 3, schedule: 'ON_PARTITION', lastPassRate: 96, trend: [98, 97, 96, 97, 96], createdAt: '2026-03-01T10:00:00Z' },
  { id: 'r-3', targetFqn: 'dwd.dwd_order_df', targetColumn: 'phone', ruleType: 'REGEX', expression: '^1[3-9]\\d{9}$', severity: 'WARN', owner: '张三', enabled: true, version: 1, schedule: 'ON_PARTITION', lastPassRate: 99, trend: [99, 99, 98, 99, 99], createdAt: '2026-03-01T10:00:00Z' },
  { id: 'r-4', targetFqn: 'ods.orders', targetColumn: 'order_id', ruleType: 'UNIQUE', expression: 'COUNT(DISTINCT order_id) = COUNT(*)', severity: 'BLOCK', owner: '张三', enabled: true, version: 1, schedule: 'ON_PARTITION', lastPassRate: 100, trend: [100, 100, 100, 100, 100], createdAt: '2026-03-01T10:00:00Z' },
  { id: 'r-5', targetFqn: 'dwd.dwd_order_df', ruleType: 'DRIFT', expression: 'rows_drift < 10%', severity: 'WARN', owner: '张三', enabled: true, version: 1, schedule: 'CRON', lastPassRate: 92, trend: [95, 94, 93, 92, 91], createdAt: '2026-03-01T10:00:00Z' },
];

export const qualityResults: QualityRunResult[] = [
  { id: 'rr-1', ruleId: 'r-2', passed: false, passRate: 96, failedRows: 32, jobRunId: 'jr-1', checkedAt: '2026-06-14T02:00:50Z',
    sample: [
      { order_id: 2087, amount: -3, status: 'PAID', phone: '138****8888' },
      { order_id: 2088, amount: -1, status: 'PAID', phone: '139****1234' },
      { order_id: 2091, amount: 0, status: 'PAID', phone: '137****5678' },
    ] },
  { id: 'rr-2', ruleId: 'r-1', passed: true, passRate: 100, failedRows: 0, checkedAt: '2026-06-14T02:00:50Z' },
];

export const qualityAlerts: QualityAlert[] = [
  { id: 'qa-1', ruleId: 'r-2', level: 'CRITICAL', source: '质量门禁', message: 'dwd_order_df amount 范围失败 32 行', status: 'OPEN', createdAt: '2026-06-14T02:01:00Z' },
  { id: 'qa-2', ruleId: 'r-5', level: 'WARN', source: '质量稽核', message: 'dwd_order_df 行数漂移 12%', status: 'OPEN', createdAt: '2026-06-14T02:01:00Z' },
];

export const qualityScoreTrend = [
  { date: '06-08', complete: 90, accurate: 88, consistent: 95, fresh: 92 },
  { date: '06-09', complete: 91, accurate: 87, consistent: 94, fresh: 92 },
  { date: '06-10', complete: 90, accurate: 89, consistent: 95, fresh: 92 },
  { date: '06-11', complete: 92, accurate: 90, consistent: 96, fresh: 92 },
  { date: '06-12', complete: 91, accurate: 88, consistent: 95, fresh: 92 },
  { date: '06-13', complete: 93, accurate: 89, consistent: 95, fresh: 92 },
  { date: '06-14', complete: 90, accurate: 88, consistent: 95, fresh: 92 },
];

// 质量门禁失败处理记录
export const gateExemptions = [
  { id: 'ex-1', rule: 'r-2', applicant: '王五', at: '2026-06-14 09:30', reason: '历史脏数据，待数据修复后重跑', status: 'pending', approver: '张三' },
];
