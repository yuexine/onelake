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
  sourceTable: string;
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
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
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

export interface RunningTask {
  id: UUID;
  sourceModule: 'INTEGRATION' | 'LAKEHOUSE' | 'ORCHESTRATION' | 'QUALITY' | 'DATASERVICE' | 'SECURITY' | 'SYSTEM' | string;
  taskType: 'COLLECT' | 'SQL' | 'DAG' | 'API' | 'QUALITY' | 'COMPACTION' | 'ALERT' | string;
  refType: string;
  refId: string;
  parentRefId?: string;
  title: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  progress?: number;
  phase?: string;
  detail?: string;
  errorCode?: string;
  errorMessage?: string;
  link?: string;
  cancellable?: boolean;
  cancelEndpoint?: string;
  startedAt: string;
  updatedAt: string;
  finishedAt?: string;
}

export interface Dag {
  id: UUID;
  name: string;
  dagsterJob: string;
  definition: { nodes?: DagNode[]; edges?: DagEdge[]; [key: string]: unknown } | DagNode[];
  edges?: DagEdge[];
  scheduleCron?: string;
  enabled: boolean;
  version: number;
  createdAt: string;
}

export interface DagNode {
  id: string;
  type: 'INPUT' | 'GOVERN' | 'MASK' | 'ENCRYPT' | 'OUTPUT' | 'QUALITY_GATE' | 'SQL' | string;
  name: string;
  sql?: string;
  engine?: string;
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
  piiType?: string;
  suggestLevel?: Classification;
  terms?: AssetColumnTerm[];
  upstreamFqn?: string;
  stats?: { nullRate?: number; cardinality?: number; min?: string; max?: string };
}

export interface AssetColumnTerm {
  id: UUID;
  code: string;
  name: string;
  status: string;
}

export interface AssetDetail {
  asset: Asset;
  lineage: AssetLineageSummary;
  quality: AssetQualitySummary;
  security: AssetSecuritySummary;
  subscription: AssetSubscriptionSummary;
}

export type AssetMaintenanceOperation = 'OPTIMIZE' | 'EXPIRE_SNAPSHOTS' | 'REMOVE_ORPHAN_FILES';

export interface AssetMaintenanceAssessment {
  assetId: UUID;
  fqn: string;
  layer: Asset['layer'];
  status: 'OK' | 'WARN' | 'CRITICAL';
  freshnessStatus: 'OK' | 'BREACHED' | 'UNKNOWN';
  freshnessLagMinutes?: number;
  freshnessSlaMinutes: number;
  fileCount?: number;
  smallFileCount?: number;
  totalBytes?: number;
  smallFileThresholdBytes: number;
  risks: string[];
  suggestedOperations: AssetMaintenanceOperation[];
  lastSyncAt?: string;
  assessedAt: string;
}

export interface AssetMaintenanceResult {
  assetId: UUID;
  fqn: string;
  operation: AssetMaintenanceOperation;
  status: 'SUCCEEDED' | 'FAILED';
  statement: string;
  message: string;
  submittedAt: string;
}

export interface AssetLineageSummary {
  upstream: AssetLineageEdge[];
  downstream: AssetLineageEdge[];
  downstreamFqns: string[];
}

export interface AssetLineageEdge {
  upstreamFqn: string;
  downstreamFqn: string;
  columns: { from: string; to: string; transform?: string }[];
  jobRef?: string;
  syncedAt?: string;
}

export interface AssetQualitySummary {
  score?: number;
  ruleCount: number;
  failedRuleCount: number;
  latestCheckedAt?: string;
  rules: AssetQualityRuleStatus[];
}

export interface AssetQualityRuleStatus {
  ruleId: UUID;
  ruleType: string;
  targetColumn?: string;
  severity: string;
  passed?: boolean;
  passRate?: number;
  failedRows?: number;
  checkedAt?: string;
}

export interface AssetSecuritySummary {
  classification?: Classification;
  sensitiveColumnCount: number;
  activeGrantCount: number;
  maskingPolicyCount: number;
  piiDetectionCount: number;
}

export interface AssetSubscriptionSummary {
  apiCount: number;
  publishedApiCount: number;
  approvedSubscriptionCount: number;
  callCount: number;
  popularity: number;
}

export interface TableCreateRequest {
  layer: Asset['layer'];
  domain: string;
  name: string;
  description?: string;
  columns: TableCreateColumn[];
  partitionStrategy?: string;
  format?: 'ICEBERG' | 'PARQUET' | 'ORC';
  compression?: 'ZSTD' | 'SNAPPY' | 'GZIP';
  ttlDays?: number;
  coldStorageAfterDays?: number;
}

export interface TableCreateColumn {
  name: string;
  type: string;
  primaryKey?: boolean;
  classification?: Classification;
  comment?: string;
}

export interface DwdModelDraftRequest {
  name: string;
  domain?: string;
  sourceFqn: string;
  targetFqn?: string;
  materialization?: 'VIEW' | 'TABLE' | 'INCREMENTAL';
  uniqueKey?: string;
  incrementalColumn?: string;
  partitionExpr?: string;
  columnMappings?: DwdModelColumnMappingRequest[];
}

export interface DwdModelColumnMappingRequest {
  source: string;
  target: string;
  sourceType?: string;
  targetType?: string;
  expression?: string;
  primaryKey?: boolean;
  classification?: Classification;
  piiType?: string;
  suggestLevel?: Classification;
}

export interface DataModel {
  id: UUID;
  name: string;
  layer: 'DWD' | string;
  domain?: string;
  sourceFqn: string;
  targetFqn: string;
  status: 'DRAFT' | 'VALIDATED' | 'PUBLISHED' | string;
  materialization: 'VIEW' | 'TABLE' | 'INCREMENTAL' | string;
  uniqueKey?: string;
  incrementalColumn?: string;
  partitionExpr?: string;
  sqlText?: string;
  compiledSql?: string;
  dbtModelName?: string;
  orchestrationDagId?: UUID;
  dagsterJob?: string;
  artifactPath?: string;
  lastRunId?: UUID;
  pipelineMode?: string;
  operatorGraphVersion?: number;
  operatorGraph?: string;
  resourceGroup?: string;
  computeProfile?: string;
  engine?: string;
  costPolicy?: string;
  ownerId?: UUID;
  ownerName?: string;
  createdAt?: string;
  updatedAt?: string;
  sources: DataModelSource[];
  columnMappings: DataModelColumnMapping[];
}

export interface DataModelSource {
  id: UUID;
  sourceFqn: string;
  sourceType: string;
  sortNo?: number;
}

export interface DataModelColumnMapping {
  id: UUID;
  source: string;
  target: string;
  sourceType?: string;
  targetType?: string;
  expression?: string;
  primaryKey?: boolean;
  classification?: Classification;
  piiType?: string;
  suggestLevel?: Classification;
  sortNo?: number;
}

export interface DwdModelValidation {
  ok: boolean;
  errors: string[];
  warnings: string[];
  compiledSql: string;
  dependencies: string[];
  outputColumns: string[];
}

export interface DwdModelCompileResult {
  modelId: UUID;
  dbtModelName: string;
  materialization: string;
  sqlPath: string;
  schemaPath: string;
  sourcePath: string;
  orchestrationDagId: UUID;
  dagsterJob: string;
  pipelineMode: string;
  operatorGraphVersion: number;
  operatorGraph: string;
  resourceGroup: string;
  computeProfile: string;
  engine: string;
  costPolicy: string;
  compiledSql: string;
  dependencies: string[];
  outputColumns: string[];
}

export interface DwdModelRun {
  id: UUID;
  modelId: UUID;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | string;
  triggerType: 'MANUAL' | 'ODS_EVENT' | 'BACKFILL' | string;
  sourceIntegrationRunId?: UUID;
  orchestrationDagId?: UUID;
  dagsterRunId?: string;
  engineRunId?: string;
  trinoQueryId?: string;
  resourceGroup?: string;
  computeProfile?: string;
  queuedAt?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMsg?: string;
  rowsRead?: number;
  rowsWritten?: number;
  artifactsPath?: string;
  estimatedScanBytes?: number;
  actualScanBytes?: number;
  costEstimate?: number;
  queueReason?: string;
  retryCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface SqlColumn {
  name: string;
  type: string;
}

export interface SqlExecuteResult {
  historyId: UUID;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  trinoQueryId?: string;
  columns: SqlColumn[];
  rows: Record<string, unknown>[];
  durationMs?: number;
  scanBytes?: number;
  rowCount?: number;
  truncated?: boolean;
  error?: string;
  errorCode?: string;
  maskedColumns?: string[];
  securityNotices?: string[];
}

export interface SqlQueryHistory {
  id: UUID;
  runner: string;
  at: string;
  trinoQueryId?: string;
  scanBytes?: number;
  durationMs?: number;
  ok: boolean;
  status: string;
  sql: string;
  error?: string;
}

export interface SavedQuery {
  id: UUID;
  name: string;
  ownerId?: UUID;
  owner: string;
  shared: boolean;
  sql: string;
  updatedAt?: string;
}

export interface QueryTemplatePlaceholder {
  name: string;
  type: 'string' | 'number' | 'date' | 'timestamp' | 'identifier' | 'boolean';
  required?: boolean;
  defaultValue?: string;
  description?: string;
}

export interface QueryTemplate {
  id: UUID;
  name: string;
  category?: string;
  description?: string;
  sqlTemplate: string;
  placeholders: QueryTemplatePlaceholder[];
  ownerId?: UUID;
  ownerName?: string;
  shared: boolean;
  updatedAt?: string;
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
  source?: string;
  message: string;
  status: 'OPEN' | 'ACK' | 'CLOSED';
  assignee?: string;
  relatedRunId?: UUID;
  createdAt: string;
  targetFqn?: string;
  targetColumn?: string;
  ruleType?: QualityRule['ruleType'];
  expression?: string;
  passRate?: number;
  failedRows?: number;
  sample?: Record<string, unknown>[];
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
  tenantId?: UUID;
  subjectId: UUID;
  assetFqn: string;
  columns?: string[] | string;
  permissions: { query?: boolean; download?: boolean; api?: boolean } | string;
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
  payload?: Record<string, unknown> | string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
  approverId?: UUID;
  approverName?: string;
  comment?: string;
  chain?: { role: string; user?: string; approverId?: UUID; status: 'PENDING' | 'APPROVED' | 'REJECTED'; at?: string; comment?: string }[];
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
  requestParams?: string;
  responseSchema?: string;
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
