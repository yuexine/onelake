/**
 * 全局类型定义（对应数据库 schema + 设计文档中的业务实体）。
 */
export type UUID = string;

/** 密级 */
export type Classification = 'L1' | 'L2' | 'L3' | 'L4';

/** 数据源类型 */
export type DataSourceType = 'MYSQL' | 'POSTGRES' | 'ORACLE' | 'HIVE' | 'KAFKA' | 'S3' | 'FTP' | 'SFTP';

/** 同步模式 */
export type SyncMode = 'FULL' | 'INCREMENTAL' | 'CDC' | 'FILE';

export interface DataSource {
  id: UUID;
  tenantId: UUID;
  name: string;
  type: DataSourceType;
  host: string;
  port: number;
  dbName?: string;
  username: string;
  networkMode: 'DIRECT' | 'VPC' | 'SSH_TUNNEL';
  envLevel: 'PROD' | 'TEST' | 'DEV';
  health: 'OK' | 'FAIL' | 'UNKNOWN';
  rttMs?: number;
  projectId?: UUID;
  lastCheckAt?: string;
  createdAt: string;
}

export interface SyncTask {
  id: UUID;
  sourceId: UUID;
  sourceName: string;
  name: string;
  mode: SyncMode;
  targetTable: string;
  fieldMapping?: FieldMapping[];
  scheduleCron?: string;
  rateLimit?: number;
  dirtyThreshold?: number;
  status: 'DRAFT' | 'ENABLED' | 'PAUSED';
  airbyteConnectionId?: string;
  createdAt: string;
}

export interface FieldMapping {
  source: string;
  sourceType: string;
  target: string;
  targetType: string;
  classification?: Classification;
  masked?: boolean;
  compatible?: boolean;
}

export interface SyncRun {
  id: UUID;
  taskId: UUID;
  externalJobId?: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  rowsRead: number;
  rowsWritten: number;
  errorCode?: string;
  errorMsg?: string;
  checkpoint?: string;
  shardProgress?: number[];   // 分片进度
  startedAt: string;
  finishedAt?: string;
  durationMs?: number;
  throughputRows?: number;
}

export interface Dag {
  id: UUID;
  name: string;
  dagsterJob: string;
  definition: DagNode[];
  edges: DagEdge[];
  scheduleCron?: string;
  enabled: boolean;
  version: number;
  createdAt: string;
}

export interface DagNode {
  id: string;
  type: 'INPUT' | 'GOVERN' | 'MASK' | 'ENCRYPT' | 'OUTPUT' | 'QUALITY_GATE';
  name: string;
  schema?: string;
  status?: 'idle' | 'configured' | 'error' | 'running' | 'success' | 'failed';
  params?: Record<string, unknown>;
}

export interface DagEdge {
  source: string;
  target: string;
  valid?: boolean;
}

export interface Asset {
  id: UUID;
  fqn: string;            // 全限定名 ods.orders
  name: string;
  type: 'TABLE' | 'VIEW' | 'TOPIC' | 'API';
  layer: 'ODS' | 'DWD' | 'DWS' | 'ADS';
  domain: string;
  ownerId: UUID;
  ownerName: string;
  description?: string;
  tags: string[];
  classification?: Classification;
  qualityScore?: number;
  popularity?: number;       // 被订阅数
  accessCount?: number;
  rows?: number;
  sizeBytes?: number;
  columns: AssetColumn[];
  partitions?: string[];
  format?: 'ICEBERG' | 'PARQUET' | 'ORC';
  lastSyncAt?: string;
  syncedAt?: string;
}

export interface AssetColumn {
  name: string;
  type: string;
  description?: string;
  classification?: Classification;
  piiType?: 'PHONE' | 'ID_CARD' | 'BANK_CARD' | 'EMAIL' | 'NAME';
  upstreamFqn?: string;
  stats?: { nullRate?: number; cardinality?: number; min?: string; max?: string };
}

export interface LineageEdge {
  upstreamFqn: string;
  downstreamFqn: string;
  columnMapping?: { from: string; to: string; transform?: string }[];
  jobRef?: string;
}

export interface Metric {
  id: UUID;
  domainId?: UUID;
  code: string;
  name: string;
  type: 'ATOMIC' | 'DERIVED' | 'COMPOSITE';
  caliberSql?: string;
  dbtModel?: string;
  version: number;
  owner: string;
}

export interface SubjectDomain {
  id: UUID;
  code: string;
  name: string;
  parentId?: UUID;
  children?: SubjectDomain[];
}

export interface QualityRule {
  id: UUID;
  targetFqn: string;
  targetColumn?: string;
  ruleType: 'NOT_NULL' | 'UNIQUE' | 'RANGE' | 'REGEX' | 'ENUM' | 'REFERENTIAL' | 'DRIFT' | 'CUSTOM_SQL';
  expression: string;
  severity: 'BLOCK' | 'WARN';
  owner: string;
  enabled: boolean;
  version: number;
  schedule: 'ON_PARTITION' | 'CRON';
  lastPassRate?: number;
  trend?: number[];
  createdAt: string;
}

export interface QualityRunResult {
  id: UUID;
  ruleId: UUID;
  passed: boolean;
  passRate: number;
  failedRows: number;
  sample?: Record<string, unknown>[];
  jobRunId?: UUID;
  checkedAt: string;
}

export interface QualityAlert {
  id: UUID;
  ruleId?: UUID;
  level: 'INFO' | 'WARN' | 'CRITICAL';
  source: string;
  message: string;
  status: 'OPEN' | 'ACK' | 'CLOSED';
  assignee?: string;
  relatedRunId?: UUID;
  createdAt: string;
}

export interface Secret {
  id: UUID;
  refKey: string;
  kmsKeyId: string;
  rotatedAt?: string;
  createdAt: string;
}

export interface MaskingPolicy {
  id: UUID;
  targetFqn: string;
  classification?: Classification;
  roleScope?: string;
  strategy: 'MASK' | 'HASH' | 'NULLIFY' | 'PARTIAL' | 'REDACT' | 'GENERALIZE' | 'RANDOMIZE';
  priority: number;
  algorithm?: string;
  preview?: { input: string; output: string };
}

export interface AccessGrant {
  id: UUID;
  subjectId: UUID;
  assetFqn: string;
  columns?: string[];
  permissions: { query?: boolean; download?: boolean; api?: boolean };
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  grantedAt: string;
  expiresAt?: string;
}

export interface Role {
  id: UUID;
  code: 'DE' | 'ADMIN' | 'CONSUMER' | 'SEC' | 'OPS';
  name: string;
  description?: string;
  members?: number;
}

export interface RoleBinding {
  id: UUID;
  roleId: UUID;
  resourceType: 'MENU' | 'ACTION' | 'ASSET';
  resourceRef: string;
  actions: string[];
}

export interface ApprovalRequest {
  id: UUID;
  requestType: 'ACCESS' | 'SUBSCRIPTION' | 'PUBLISH' | 'OFFLINE' | 'SCHEMA_CHANGE' | 'MASK_EXEMPTION' | 'QUOTA_RAISE';
  applicantId: UUID;
  applicantName: string;
  targetRef: string;
  targetType?: string;
  reason?: string;
  payload?: Record<string, unknown>;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
  approverId?: UUID;
  approverName?: string;
  chain?: { role: string; user?: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; at?: string; comment?: string }[];
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH';
  impactSummary?: { assets?: number; apis?: number; subscribers?: number };
  createdAt: string;
  decidedAt?: string;
}

export interface ApiDefinition {
  id: UUID;
  apiPath: string;
  name: string;
  description: string;
  viewName: string;
  selectSql: string;
  sourceFqn?: string;
  qpsLimit: number;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'OFFLINE';
  currentVersion: number;
  classification?: Classification;
  subscriberCount?: number;
  successRate?: number;
  qps?: number;
  createdAt: string;
  offlineAt?: string;
  deprecateAt?: string;
}

export interface ApiVersion {
  id: UUID;
  apiId: UUID;
  version: number;
  spec: { params?: ApiParam[]; returns?: ApiReturnField[] };
  publishedAt: string;
  deprecatedAt?: string;
  grayPercent?: number;
}

export interface ApiParam {
  name: string;
  type: 'STRING' | 'INT' | 'BIGINT' | 'DATE' | 'DATETIME';
  required: boolean;
  defaultValue?: string;
  validation?: string;
}

export interface ApiReturnField {
  name: string;
  type: string;
  classification?: Classification;
  masked?: boolean;
}

export interface AppKey {
  id: UUID;
  appKey: string;
  secretHash: string;
  ownerId: UUID;
  ownerName: string;
  ipWhitelist?: string[];
  quotaDaily?: number;
  expiresAt?: string;
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string;
  recentCalls?: { status2xx: number; status429: number; status401: number };
}

export interface Subscription {
  id: UUID;
  apiId: UUID;
  apiPath: string;
  subscriberId: UUID;
  subscriberName: string;
  appKeyId?: UUID;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  approvedBy?: string;
  reason?: string;
  createdAt: string;
}

export interface ApiCallLog {
  id: UUID;
  apiId: UUID;
  appKeyId?: UUID;
  statusCode: number;
  latencyMs: number;
  requestIp: string;
  calledAt: string;
}

export interface AuditLog {
  id: number;
  actorId: UUID;
  actorName: string;
  action: string;
  resourceType: string;
  resourceId: string;
  detail?: string;
  sensitive?: boolean;
  traceId?: string;
  occurredAt: string;
}

export interface Notification {
  id: UUID;
  category: 'TASK' | 'APPROVAL' | 'ALERT' | 'SECURITY' | 'SYSTEM';
  receiverId: UUID;
  title: string;
  content?: string;
  link?: string;
  level?: 'INFO' | 'WARN' | 'CRITICAL';
  isRead: boolean;
  createdAt: string;
}

export interface Tenant {
  id: UUID;
  code: string;
  name: string;
  status: 'ACTIVE' | 'DISABLED';
  projectCount: number;
  memberCount: number;
  quotaCuUsed?: number;
  quotaCuTotal?: number;
  createdAt: string;
}

export interface Project {
  id: UUID;
  tenantId: UUID;
  code: string;
  name: string;
  ownerId?: UUID;
}

export interface NotificationChannel {
  id: UUID;
  type: 'EMAIL' | 'DINGTALK' | 'WEBHOOK' | 'PHONE';
  config: Record<string, string>;
  status: 'ACTIVE' | 'INACTIVE';
}
