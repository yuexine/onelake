/**
 * Mock 数据 - 数据集成（L1）。
 * 对应原型 §8.2 全部 11 个页面对应的数据。
 */
import type { DataSource, SyncTask, SyncRun, FieldMapping } from '../types';

export const dataSources: DataSource[] = [
  { id: 'ds-001', tenantId: 't-1', name: '订单库', type: 'MYSQL', host: '10.0.0.1', port: 3306, dbName: 'order_db', username: 'ro_user', networkMode: 'DIRECT', envLevel: 'PROD', health: 'OK', rttMs: 23, projectId: 'p-1', lastCheckAt: '2026-06-14T02:00:01Z', createdAt: '2026-03-01T10:00:00Z' },
  { id: 'ds-002', tenantId: 't-1', name: '用户库', type: 'POSTGRES', host: '10.0.0.2', port: 5432, dbName: 'user_db', username: 'ro_user', networkMode: 'DIRECT', envLevel: 'PROD', health: 'FAIL', rttMs: 5012, projectId: 'p-1', lastCheckAt: '2026-06-14T02:05:01Z', createdAt: '2026-03-01T10:30:00Z' },
  { id: 'ds-003', tenantId: 't-1', name: '日志-S3', type: 'S3', host: 's3.cn-north-1.amazonaws.com', port: 443, dbName: 'access-log', username: 'ak_xxx', networkMode: 'VPC', envLevel: 'PROD', health: 'OK', rttMs: 88, projectId: 'p-2', lastCheckAt: '2026-06-14T01:50:00Z', createdAt: '2026-04-01T08:00:00Z' },
  { id: 'ds-004', tenantId: 't-1', name: '风控-Hive', type: 'HIVE', host: 'hive.internal', port: 10000, dbName: 'risk', username: 'risk_ro', networkMode: 'DIRECT', envLevel: 'PROD', health: 'OK', rttMs: 152, projectId: 'p-3', lastCheckAt: '2026-06-14T02:00:00Z', createdAt: '2026-03-15T10:00:00Z' },
  { id: 'ds-005', tenantId: 't-1', name: '消息-Kafka', type: 'KAFKA', host: 'kafka-broker-1', port: 9092, dbName: 'events', username: 'consumer', networkMode: 'VPC', envLevel: 'PROD', health: 'OK', rttMs: 18, projectId: 'p-2', lastCheckAt: '2026-06-14T02:10:00Z', createdAt: '2026-02-20T10:00:00Z' },
];

const ordersMapping: FieldMapping[] = [
  { source: 'order_id', sourceType: 'BIGINT', target: 'order_id', targetType: 'BIGINT', compatible: true },
  { source: 'phone', sourceType: 'VARCHAR', target: 'phone', targetType: 'STRING', classification: 'L3', masked: true, compatible: true },
  { source: 'amount', sourceType: 'NUMBER(10,2)', target: 'amount', targetType: 'DECIMAL(18,2)', compatible: true },
  { source: 'status', sourceType: 'VARCHAR', target: 'status', targetType: 'STRING', compatible: true },
  { source: 'order_time', sourceType: 'DATETIME', target: 'order_time', targetType: 'TIMESTAMP', compatible: true },
  { source: 'memo', sourceType: 'TEXT', target: 'memo', targetType: 'STRING', compatible: false },
];

export const syncTasks: SyncTask[] = [
  { id: 'st-001', sourceId: 'ds-001', sourceName: '订单库', name: 'orders_sync', mode: 'INCREMENTAL', targetTable: 'ods.orders', fieldMapping: ordersMapping, scheduleCron: '0 2 * * *', rateLimit: 2500, dirtyThreshold: 0, status: 'ENABLED', airbyteConnectionId: 'abc-001', createdAt: '2026-03-01T11:00:00Z' },
  { id: 'st-002', sourceId: 'ds-001', sourceName: '订单库', name: 'order_items_sync', mode: 'INCREMENTAL', targetTable: 'ods.order_items', scheduleCron: '0 2 * * *', rateLimit: 2500, status: 'ENABLED', createdAt: '2026-03-01T11:00:00Z' },
  { id: 'st-003', sourceId: 'ds-002', sourceName: '用户库', name: 'user_cdc', mode: 'CDC', targetTable: 'ods.users', scheduleCron: '', status: 'ENABLED', createdAt: '2026-03-02T10:00:00Z' },
  { id: 'st-004', sourceId: 'ds-003', sourceName: '日志-S3', name: 'logs_file', mode: 'FILE', targetTable: 'ods.access_log', status: 'ENABLED', createdAt: '2026-04-01T08:30:00Z' },
  { id: 'st-005', sourceId: 'ds-004', sourceName: '风控-Hive', name: 'risk_events', mode: 'FULL', targetTable: 'ods.risk_events', scheduleCron: '0 4 * * 1', status: 'DRAFT', createdAt: '2026-03-15T11:00:00Z' },
];

export const syncRuns: SyncRun[] = [
  { id: 'sr-1042', taskId: 'st-001', externalJobId: 'ab-1042', status: 'SUCCEEDED', rowsRead: 123456, rowsWritten: 123456, shardProgress: [100, 100, 100, 100, 100], startedAt: '2026-06-14T02:00:01Z', finishedAt: '2026-06-14T02:00:49Z', durationMs: 48000, throughputRows: 2572 },
  { id: 'sr-1041', taskId: 'st-001', externalJobId: 'ab-1041', status: 'FAILED', rowsRead: 0, rowsWritten: 0, errorCode: 'AUTH_401', errorMsg: '账号密码过期', shardProgress: [60, 0, 0, 0, 0], startedAt: '2026-06-13T02:00:01Z', finishedAt: '2026-06-13T02:00:13Z', durationMs: 12000 },
  { id: 'sr-1040', taskId: 'st-001', externalJobId: 'ab-1040', status: 'SUCCEEDED', rowsRead: 122800, rowsWritten: 122800, shardProgress: [100, 100, 100, 100, 100], startedAt: '2026-06-12T02:00:01Z', finishedAt: '2026-06-12T02:00:45Z', durationMs: 44000, throughputRows: 2791 },
  { id: 'sr-1039', taskId: 'st-001', externalJobId: 'ab-1039', status: 'SUCCEEDED', rowsRead: 121900, rowsWritten: 121900, startedAt: '2026-06-11T02:00:01Z', finishedAt: '2026-06-11T02:00:42Z', durationMs: 41000 },
  { id: 'sr-1038', taskId: 'st-002', externalJobId: 'ab-1038', status: 'SUCCEEDED', rowsRead: 89500, rowsWritten: 89500, startedAt: '2026-06-14T02:00:01Z', finishedAt: '2026-06-14T02:00:30Z', durationMs: 29000 },
  { id: 'sr-1037', taskId: 'st-003', externalJobId: 'flink-9381', status: 'RUNNING', rowsRead: 1843201, rowsWritten: 1843201, checkpoint: 'binlog.000128:4456', shardProgress: [], startedAt: '2026-06-13T08:00:00Z' },
];

// Schema 变更审批
export const schemaChangeRequests = [
  {
    id: 'sc-001', sourceName: 'user_cdc', table: 'users',
    change: 'DROP COLUMN age', type: '破坏性', compatible: false,
    impact: { downstreamTables: ['dwd_user_df.age'], tasks: ['user_cdc'], apis: ['/api/user (返回字段含 age)'] },
    status: 'PENDING', bufferStrategy: '按旧 schema 缓冲写入', createdAt: '2026-06-14T09:50:00Z',
  },
  {
    id: 'sc-002', sourceName: 'mysql_orders_cdc', table: 'orders',
    change: 'ADD COLUMN memo VARCHAR(500)', type: '兼容性', compatible: true,
    impact: { downstreamTables: [], tasks: [], apis: [] },
    status: 'AUTO_APPLIED', bufferStrategy: '已应用', createdAt: '2026-06-14T10:21:00Z',
  },
];

// 采集任务模板
export const collectTemplates = [
  { id: 'tpl-1', name: '整库迁移', icon: '🗃', desc: '一键配库即可，全量入湖到 ODS', fields: ['源连接', '目标层', '表范围'] },
  { id: 'tpl-2', name: '单表增量', icon: '⏱', desc: '水位线增量抽取，自动回溯 5 分钟', fields: ['源连接', '增量列', '目标表'] },
  { id: 'tpl-3', name: 'CDC 实时', icon: '🔄', desc: 'Binlog 订阅 + 初始快照 + 位点续传', fields: ['源连接', '表名', '位点'] },
  { id: 'tpl-4', name: '文件批量', icon: '📁', desc: 'SFTP/对象桶监听，分片上传 + MD5 校验', fields: ['来源', '目录', '目标表'] },
];

// 文件采集监控
export const fileWatchList = [
  { id: 'fw-1', filename: 'orders_0614.csv', sizeMb: 1200, checksum: 'matched', progress: 100, status: '已入湖' },
  { id: 'fw-2', filename: 'orders_0613.csv', sizeMb: 900, checksum: 'mismatch', progress: 50, status: '重传中' },
  { id: 'fw-3', filename: 'orders_dup.csv', sizeMb: 1200, checksum: '-', progress: 0, status: '去重跳过' },
];
