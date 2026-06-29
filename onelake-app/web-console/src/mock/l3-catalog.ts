/**
 * Mock 数据 - 数据目录与血缘（L3）。
 */
import type { LineageEdge } from '../types';

export const lineageEdges: LineageEdge[] = [
  { upstreamFqn: 'mysql.orders', downstreamFqn: 'ods.orders', jobRef: 'orders_sync' },
  { upstreamFqn: 'ods.orders', downstreamFqn: 'dwd.dwd_order_df', jobRef: 'dwd_order_df',
    columnMapping: [
      { from: 'order_id', to: 'order_id' },
      { from: 'phone', to: 'phone' },
      { from: 'amount', to: 'amount' },
      { from: 'status', to: 'status' },
      { from: 'order_time', to: 'order_time', transform: 'CAST(...) AS TIMESTAMP' },
    ] },
  { upstreamFqn: 'ods.order_items', downstreamFqn: 'dwd.dwd_order_df', jobRef: 'dwd_order_df' },
  { upstreamFqn: 'dwd.dwd_order_df', downstreamFqn: 'dws.dws_user_order', jobRef: 'dws_user_order' },
  { upstreamFqn: 'dwd.dwd_order_df', downstreamFqn: 'ads.ads_sales_df', jobRef: 'ads_sales_df' },
  { upstreamFqn: 'dws.dws_user_order', downstreamFqn: 'ads.ads_sales_df', jobRef: 'ads_sales_df' },
  { upstreamFqn: 'ads.ads_sales_df', downstreamFqn: 'API:/api/order/detail', jobRef: 'order_detail_api' },
];

export const searchHot = ['dwd_order_df', 'GMV', 'orders_sync', 'phone'];
export const searchRecent = ['dwd_order_df', 'orders_sync', '/api/order'];

// 元数据变更历史
export const metadataChanges = [
  { version: 5, time: '2026-06-14 10:21', author: '张三', source: 'CDC DDL 同步',
    diff: [
      { kind: 'add', field: 'memo', type: 'STRING' },
      { kind: 'change', field: 'amount', from: 'INT', to: 'DECIMAL(18,2)' },
    ] },
  { version: 4, time: '2026-06-10 18:00', author: '张三', source: '人工编辑',
    diff: [{ kind: 'remove', field: 'ext_col', type: 'STRING' }] },
  { version: 3, time: '2026-06-01 12:00', author: '李四', source: '建模发布', diff: [] },
];

// 资产价值评估
export const assetValue = [
  { asset: 'ads.ads_sales_df', score: 95, access: '高', apiCalls: 12000, downstream: 8, trend: 'up' },
  { asset: 'dws.dws_user_df', score: 88, access: '中', apiCalls: 3000, downstream: 5, trend: 'flat' },
  { asset: 'dwd.dwd_order_df', score: 92, access: '高', apiCalls: 8000, downstream: 6, trend: 'up' },
];

// 闲置资产
export const idleAssets = [
  { asset: 'ods.ods_tmp_2024', lastAccess: '180 天前', downstream: 0, suggestion: '建议下线' },
  { asset: 'dwd.dwd_unused_df', lastAccess: '120 天前', downstream: 0, suggestion: '建议下线' },
  { asset: 'dws.dws_old_report', lastAccess: '95 天前', downstream: 1, suggestion: '需确认下游' },
];

// PII 识别
export const piiScan = [
  { fqn: 'ods.users.phone', type: '手机号', confidence: 0.98, suggestLevel: 'L3', status: 'pending' },
  { fqn: 'ods.users.id_card', type: '身份证', confidence: 0.95, suggestLevel: 'L4', status: 'pending' },
  { fqn: 'ods.orders.email', type: '邮箱', confidence: 0.80, suggestLevel: 'L3', status: 'pending' },
  { fqn: 'dwd.dwd_user_df.phone', type: '手机号', confidence: 0.99, suggestLevel: 'L3', status: 'confirmed' },
];
