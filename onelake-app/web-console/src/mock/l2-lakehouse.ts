/**
 * Mock 数据 - 湖仓与建模（L2）。
 */
import type { Asset, SubjectDomain, Metric } from '../types';

export const lakehouseAssets: Asset[] = [
  {
    id: 'a-001', fqn: 'ods.orders', name: 'orders', type: 'TABLE', layer: 'ODS', domain: '交易',
    ownerId: 'u-1', ownerName: '张三', tags: ['交易', '订单'], classification: 'L2', qualityScore: 92,
    popularity: 32, rows: 12345678, sizeBytes: 2_100_000_000, format: 'ICEBERG',
    lastSyncAt: '2026-06-14T02:00:49Z', syncedAt: '2026-06-14T02:01:00Z',
    partitions: ['dt'],
    columns: [
      { name: 'order_id', type: 'BIGINT', description: '订单ID' },
      { name: 'phone', type: 'STRING', description: '手机号', classification: 'L3', piiType: 'PHONE', upstreamFqn: 'mysql.orders.phone' },
      { name: 'amount', type: 'DECIMAL(18,2)', description: '订单金额' },
      { name: 'status', type: 'STRING', description: '订单状态' },
      { name: 'order_time', type: 'TIMESTAMP', description: '下单时间' },
    ],
  },
  {
    id: 'a-002', fqn: 'dwd.dwd_order_df', name: 'dwd_order_df', type: 'TABLE', layer: 'DWD', domain: '交易',
    ownerId: 'u-1', ownerName: '张三', tags: ['交易', '明细'], classification: 'L2', qualityScore: 91,
    popularity: 32, rows: 12000000, sizeBytes: 1_800_000_000, format: 'ICEBERG',
    partitions: ['stat_date'],
    columns: [
      { name: 'order_id', type: 'BIGINT', description: '订单ID', upstreamFqn: 'ods.orders.order_id' },
      { name: 'phone', type: 'STRING', description: '手机号', classification: 'L3', piiType: 'PHONE', upstreamFqn: 'ods.orders.phone' },
      { name: 'amount', type: 'DECIMAL(18,2)', description: '订单金额', upstreamFqn: 'ods.orders.amount' },
      { name: 'status', type: 'STRING', description: '订单状态', upstreamFqn: 'ods.orders.status' },
    ],
  },
  {
    id: 'a-003', fqn: 'dwd.dwd_user_df', name: 'dwd_user_df', type: 'TABLE', layer: 'DWD', domain: '用户',
    ownerId: 'u-2', ownerName: '李四', tags: ['用户'], classification: 'L3', qualityScore: 88,
    popularity: 18, rows: 80000, sizeBytes: 1_100_000_000, format: 'ICEBERG',
    columns: [
      { name: 'user_id', type: 'BIGINT', description: '用户ID' },
      { name: 'phone', type: 'STRING', description: '手机号', classification: 'L3', piiType: 'PHONE' },
      { name: 'id_card', type: 'STRING', description: '身份证号', classification: 'L4', piiType: 'ID_CARD' },
      { name: 'age', type: 'INT', description: '年龄' },
    ],
  },
  {
    id: 'a-004', fqn: 'dws.dws_user_order', name: 'dws_user_order', type: 'TABLE', layer: 'DWS', domain: '用户',
    ownerId: 'u-1', ownerName: '张三', tags: ['汇总'], classification: 'L3', qualityScore: 89,
    popularity: 12, rows: 80000, sizeBytes: 800_000_000, format: 'ICEBERG',
    columns: [
      { name: 'user_id', type: 'BIGINT', description: '用户ID' },
      { name: 'phone', type: 'STRING', description: '手机号', classification: 'L3' },
      { name: 'total_amount', type: 'DECIMAL(18,2)', description: '累计消费金额' },
    ],
  },
  {
    id: 'a-005', fqn: 'ads.ads_sales_df', name: 'ads_sales_df', type: 'TABLE', layer: 'ADS', domain: '交易',
    ownerId: 'u-3', ownerName: '王五', tags: ['GMV', '对外'], classification: 'L2', qualityScore: 95,
    popularity: 18, rows: 365, sizeBytes: 50_000_000, format: 'ICEBERG',
    columns: [
      { name: 'stat_date', type: 'DATE', description: '统计日期' },
      { name: 'gmv', type: 'DECIMAL(18,2)', description: 'GMV' },
      { name: 'order_cnt', type: 'INT', description: '订单数' },
      { name: 'uv', type: 'INT', description: 'UV' },
    ],
  },
];

export const subjectDomains: SubjectDomain[] = [
  { id: 'd-1', code: 'TRADE', name: '交易域' },
  { id: 'd-2', code: 'USER', name: '用户域' },
  { id: 'd-3', code: 'RISK', name: '风控域' },
  { id: 'd-4', code: 'MARKETING', name: '营销域' },
];

export const metrics: Metric[] = [
  { id: 'm-1', domainId: 'd-1', code: 'GMV', name: 'GMV', type: 'ATOMIC', caliberSql: "SUM(amount) WHERE status='PAID'", dbtModel: 'ads_sales_df', version: 3, owner: '张三' },
  { id: 'm-2', domainId: 'd-1', code: 'ORDER_CNT', name: '订单数', type: 'ATOMIC', caliberSql: 'COUNT(DISTINCT order_id)', dbtModel: 'ads_sales_df', version: 1, owner: '张三' },
  { id: 'm-3', domainId: 'd-1', code: 'UV', name: '下单UV', type: 'ATOMIC', caliberSql: 'COUNT(DISTINCT user_id)', version: 1, owner: '张三' },
  { id: 'm-4', domainId: 'd-1', code: 'ARPU', name: '客单价', type: 'DERIVED', caliberSql: 'GMV / UV', dbtModel: 'ads_sales_df', version: 2, owner: '张三' },
  { id: 'm-5', domainId: 'd-2', code: 'NEW_USER_CNT', name: '新增用户数', type: 'ATOMIC', version: 1, owner: '李四' },
];

// 表快照
export const tableSnapshots = [
  { id: 'snap-0614', snapshotId: 'snap-0614-1000', time: '2026-06-14 10:00', rows: 1230000, files: 12, current: true },
  { id: 'snap-0613', snapshotId: 'snap-0613-1000', time: '2026-06-13 10:00', rows: 1225000, files: 11 },
  { id: 'snap-0612', snapshotId: 'snap-0612-1000', time: '2026-06-12 10:00', rows: 1220000, files: 10 },
];

// 存储优化建议
export const optimizeSuggestions = [
  { table: 'dwd.dwd_order_df', smallFiles: 1200, status: '待 Compaction', suggestion: '合并 128MB', action: '优化' },
  { table: 'dws.dws_user_df', smallFiles: 320, status: '正常', suggestion: 'Z-Order', action: '优化' },
  { table: 'ods.ods_log_2025', smallFiles: 0, status: '冷分区 90 天+', suggestion: '下沉 Glacier', action: '下沉' },
];

// 业务术语表
export const glossaryTerms = [
  { term: 'GMV', domain: '交易域', definition: '一定周期内成交总额（含取消）', caliber: 'SUM(order.amount) WHERE paid', synonyms: '成交额, Gross Merchandise', owner: '张三', status: '已审定', related: ['ads.ads_sales_df.gmv', 'ads.ads_trade_df.total_amount'] },
  { term: '客单价', domain: '交易域', definition: 'GMV / 下单用户数', caliber: 'SUM(amount) / COUNT(DISTINCT user_id)', synonyms: 'ARPU', owner: '张三', status: '已审定', related: ['ads.ads_sales_df.arpu'] },
  { term: '留存率', domain: '用户域', definition: '次周/次月仍活跃用户占比', caliber: '留存用户 / 新增用户', synonyms: 'Retention Rate', owner: '李四', status: '草稿', related: ['ads.ads_retention_df'] },
];

// SQL 查询历史
export const sqlHistory = [
  { id: 'q-1', runner: '张三', at: '10:21', scanBytes: 12_000_000_000, durationMs: 48000, ok: true, sql: 'SELECT * FROM dwd_order_df WHERE dt = "2026-06-14"', saved: '日订单统计' },
  { id: 'q-2', runner: '张三', at: '09:50', scanBytes: 1_200_000_000_000, durationMs: 0, ok: false, sql: 'SELECT * FROM ods_log LIMIT 10', error: '扫描量超阈 (1.2TB)' },
  { id: 'q-3', runner: '李四', at: '昨天', scanBytes: 800_000_000, durationMs: 12000, ok: true, sql: 'SELECT user_id, COUNT(*) FROM dwd_order_df GROUP BY user_id', saved: '用户下单次数' },
];

// 保存的查询
export const savedQueries = [
  { id: 'sq-1', name: '日订单统计', owner: '张三', shared: false, sql: 'SELECT * FROM dwd_order_df WHERE dt = :dt' },
  { id: 'sq-2', name: '用户留存', owner: '李四', shared: true, sql: 'WITH ... SELECT retention_rate FROM ads_retention_df WHERE dt >= :start' },
];
