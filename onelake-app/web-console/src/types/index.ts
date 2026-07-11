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
  triggerable?: boolean;
  triggerBlockedReason?: string;
  version: number;
  createdAt: string;
  lastRun?: JobRun;
}

export type ScheduleMode = 'NORMAL' | 'DRY_RUN' | 'FROZEN';
export type MisfirePolicy = 'FIRE_ONCE' | 'SKIP';

export interface DagScheduling {
  dagId: UUID;
  timezone: string;
  catchup: boolean;
  maxActiveRuns: number;
  priority: number;
  scheduleMode: ScheduleMode;
  misfirePolicy: MisfirePolicy;
  dependencyWaitTimeoutMinutes: number;
  slaMinutes?: number;
  timeoutMinutes?: number;
  runRetryCount: number;
  runRetryIntervalSeconds: number;
  calendarId?: UUID;
  scheduleStart?: string;
  scheduleEnd?: string;
}

export interface UpdateDagSchedulingRequest {
  timezone: string;
  catchup: boolean;
  maxActiveRuns: number;
  priority: number;
  scheduleMode: ScheduleMode;
  misfirePolicy: MisfirePolicy;
  dependencyWaitTimeoutMinutes: number;
  slaMinutes?: number | null;
  timeoutMinutes?: number | null;
  runRetryCount: number;
  runRetryIntervalSeconds: number;
  calendarId?: UUID | null;
  scheduleStart?: string | null;
  scheduleEnd?: string | null;
}

export interface ScheduleCalendar {
  id: UUID;
  name: string;
  timezone: string;
  createdAt: string;
}

export type ScheduleWaitStatus = 'WAITING' | 'RESOLVED' | 'TIMED_OUT' | 'CANCELLED';

export interface ScheduleWait {
  id: UUID;
  dagId: UUID;
  logicalDate: string;
  scheduledAt: string;
  waitReason: 'DEPENDENCY' | 'MISFIRE' | string;
  status: ScheduleWaitStatus | string;
  lastBlockers?: string;
  expiresAt: string;
  resolvedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export type PipelineDependencyType = 'SAME_CYCLE' | 'CROSS_CYCLE';
export type PipelineDependencyOffsetGrain = 'HOUR' | 'DAY' | 'MONTH';

export interface PipelineDependency {
  id: UUID;
  downstreamDagId: UUID;
  upstreamDagId: UUID;
  dependencyType: PipelineDependencyType;
  offsetGrain?: PipelineDependencyOffsetGrain;
  offsetN: number;
  enabled: boolean;
  createdAt: string;
}

export interface CreatePipelineDependencyRequest {
  upstreamDagId: UUID;
  dependencyType: PipelineDependencyType;
  offsetGrain?: PipelineDependencyOffsetGrain;
  offsetN?: number;
}

export type PipelineParamScope = 'GLOBAL' | 'PIPELINE' | 'TASK';
export type PipelineParamValueType = 'STRING' | 'NUMBER' | 'BOOL' | 'EXPR';

/** 租户全局、流水线或节点级运行参数。 */
export interface PipelineParam {
  id?: UUID;
  scope: PipelineParamScope;
  dagId?: UUID;
  taskKey?: string;
  paramKey: string;
  paramValue?: string;
  valueType: PipelineParamValueType;
  description?: string;
  updatedAt?: string;
}

/** 流水线参数 PUT 的目标作用域；每次只替换一个集合。 */
export interface PipelineParamReplaceRequest {
  scope: 'PIPELINE' | 'TASK';
  taskKey?: string;
  params: PipelineParam[];
}

export interface JobRun {
  id: UUID;
  dagId: UUID;
  dagName?: string;
  dagsterJob?: string;
  dagsterRunId?: string;
  triggerType: 'CRON' | 'MANUAL' | 'EVENT' | string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | string;
  runMode?: ScheduleMode | string;
  timezone?: string;
  logicalDate?: string;
  dataIntervalStart?: string;
  dataIntervalEnd?: string;
  backfillId?: UUID;
  startedAt?: string;
  finishedAt?: string;
  triggeredBy?: UUID;
  triggeredByName?: string;
  slaMissed?: boolean;
  retrySourceRunId?: UUID;
  runRetryAttempt?: number;
  pipelineVersionId?: UUID;
  pipelineVersion?: number;
}

export type BackfillGrain = 'DAY' | 'HOUR' | 'MONTH';
export type BackfillStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'PARTIAL';
export type BackfillRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface BackfillRun {
  id: UUID;
  job_run_id?: UUID;
  logical_date: string;
  data_interval_start: string;
  data_interval_end: string;
  status: BackfillRunStatus;
  error_msg?: string;
}

export interface Backfill {
  id: UUID;
  dag_id: UUID;
  status: BackfillStatus;
  total: number;
  succeeded: number;
  failed: number;
  max_parallel: number;
  range: {
    start: string;
    end: string;
  };
  grain: BackfillGrain;
  timezone: string;
  created_at: string;
  updated_at: string;
  runs: BackfillRun[];
}

export interface CreateBackfillRequest {
  rangeStart: string;
  rangeEnd: string;
  grain: BackfillGrain;
  maxParallel: number;
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
  operatorRef?: string;
  operatorVersion?: string;
  config?: Record<string, unknown>;
}

export interface DagEdge {
  source: string;
  target: string;
  valid?: boolean;
}

// ===== Pipeline v2 (P2 — Unified Pipeline Editor) =====
// See docs/流水线模块重设计方案.md §6.1

export type PipelineTaskType =
  | 'QUALITY_GATE'
  | 'SYNC_REF'
  | 'SPARK_SQL'
  | 'PYSPARK';

export type TaskCompileStatus = 'DRAFT' | 'VALIDATED' | 'FAILED';
export type EdgeLayer = 'PIPELINE' | 'CROSS_ENGINE';
export type PipelineKind = 'BLANK' | 'ODS_DWD' | 'MULTI_LAYER';
export type PipelineStatus = 'DRAFT' | 'VALIDATED' | 'PUBLISHED';
export type TaskRerunMode = 'SINGLE' | 'DOWNSTREAM';
export type TaskRunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UPSTREAM_FAILED'
  | 'SKIPPED';

export interface PipelineTask {
  id: UUID;
  dagId: UUID;
  taskKey: string;
  taskType: PipelineTaskType;
  name: string;
  engine: string;            // SPARK_SQL | PYSPARK
  targetFqn?: string;
  modelId?: UUID;            // deprecated; null for new Spark-only tasks
  syncTaskId?: UUID;         // SYNC_REF only
  config: Record<string, unknown>;
  compileStatus: TaskCompileStatus;
  compileError?: string;
  executable: boolean;
  positionX?: number;
  positionY?: number;
  createdAt: string;
  updatedAt: string;
}

export interface PipelineTaskEdge {
  id: UUID;
  dagId: UUID;
  sourceKey: string;
  targetKey: string;
  edgeLayer: EdgeLayer;
  sourcePort?: string;
  targetPort?: string;
  sourceOutput?: string;
  targetInput?: string;
  assetFqn?: string;
  inputAlias?: string;
  joinRole?: string;
  triggerPolicy?: 'ALL_SUCCEEDED' | 'ALL_DONE' | 'ANY_SUCCEEDED' | string;
  freshnessPolicy?: 'LATEST' | 'SAME_BATCH' | string;
  auto: boolean;
  createdAt: string;
}

export interface PipelineTaskRequest {
  taskKey: string;
  taskType: PipelineTaskType;
  name?: string;
  engine?: string;
  targetFqn?: string;
  modelId?: UUID;
  syncTaskId?: UUID;
  config?: Record<string, unknown>;
  positionX?: number;
  positionY?: number;
}

export interface PipelineTaskEdgeRequest {
  sourceKey: string;
  targetKey: string;
  edgeLayer?: EdgeLayer;
  sourcePort?: string;
  targetPort?: string;
  sourceOutput?: string;
  targetInput?: string;
  assetFqn?: string;
  inputAlias?: string;
  joinRole?: string;
  triggerPolicy?: string;
  freshnessPolicy?: string;
  auto?: boolean;
}

export interface PipelineValidationResult {
  pipelineId: UUID;
  valid: boolean;
  taskResults: Array<{
    taskKey: string;
    taskType: PipelineTaskType;
    valid: boolean;
    errorMessage?: string;
    errorCode?: string;
  }>;
  graphErrors: Array<{
    level: 'ERROR' | 'WARN';
    code: string;
    message: string;
    taskKeys: string[];
  }>;
}

export interface TaskRun {
  id: UUID;
  jobRunId: UUID;
  taskKey: string;
  status: TaskRunStatus;
  attempt?: number;
  logRef?: string;
  rowsWritten?: number;
  scanBytes?: number;
  errorMsg?: string;
  artifactPath?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface TaskRunLogOptions {
  tail?: number;
}

export interface TaskRerunResult {
  runId: UUID;
  rerunTasks: string[];
  dagsterRunId?: string;
}

export interface Pipeline {
  id: UUID;
  name: string;
  dagsterJob: string;
  definition?: Record<string, unknown>;
  scheduleCron?: string;
  enabled: boolean;
  version: number;
  createdAt: string;
  pipelineKind?: PipelineKind;
  status?: PipelineStatus;
  engine?: string;
  resourceGroup?: string;
  computeProfile?: string;
  timezone?: string;
  catchup?: boolean;
  maxActiveRuns?: number;
  priority?: number;
  scheduleMode?: ScheduleMode;
  slaMinutes?: number;
  timeoutMinutes?: number;
  runRetryCount?: number;
  runRetryIntervalSeconds?: number;
  calendarId?: UUID;
  scheduleStart?: string;
  scheduleEnd?: string;
  publishedVersionId?: UUID;
  hasUnpublishedChanges?: boolean;
  lastRun?: JobRun;
}

export interface PipelineVersionSummary {
  id: UUID;
  dagId: UUID;
  version: number;
  checksum: string;
  status: string;
  note?: string;
  publishedBy?: UUID;
  publishedByName?: string;
  createdAt: string;
}

export interface PipelineVersionSnapshot {
  dag: Record<string, unknown>;
  tasks: Array<Record<string, unknown>>;
  edges: Array<Record<string, unknown>>;
  pipeline_params: Array<Record<string, unknown>>;
  schedule: Record<string, unknown>;
}

export interface PipelineVersionDetail extends PipelineVersionSummary {
  snapshot: PipelineVersionSnapshot;
}

export interface PipelineVersionFieldChange {
  field: string;
  before?: unknown;
  after?: unknown;
}

export interface PipelineVersionItemDiff {
  key: string;
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
  fields: PipelineVersionFieldChange[];
}

export interface PipelineVersionCollectionDiff {
  added: PipelineVersionItemDiff[];
  removed: PipelineVersionItemDiff[];
  changed: PipelineVersionItemDiff[];
}

export interface PipelineVersionDiff {
  dagId: UUID;
  fromVersion: number;
  toVersion: number;
  tasks: PipelineVersionCollectionDiff;
  edges: PipelineVersionCollectionDiff;
  params: PipelineVersionCollectionDiff;
}

export interface OperatorPort {
  name: string;
  cardinality: 'ONE' | 'MANY' | string;
  accept: 'TABLE' | 'COLUMN' | string;
}

export interface OperatorManifest {
  operatorRef: string;
  version: string;
  category: string;
  scope: 'BUILTIN' | 'CUSTOM' | 'TENANT_PRIVATE' | string;
  displayName: string;
  description?: string;
  icon?: string;
  tags?: string[];
  inputPorts?: OperatorPort[];
  outputSchema?: { mode?: string; modifies?: string[]; [key: string]: unknown };
  paramsSchema?: Record<string, unknown>;
  compileTarget?: 'SPARK' | string;
  template?: { kind?: string; sql?: string; [key: string]: unknown };
  lineageRule?: Record<string, unknown>;
  securityRule?: Record<string, unknown>;
  qualityEmit?: boolean;
  policy?: Record<string, unknown>;
  resourceHint?: Record<string, unknown>;
  examples?: { title: string; params: Record<string, unknown> }[];
}

export interface OperatorVersion {
  id: UUID;
  version: string;
  manifest?: OperatorManifest;
  changelog?: string;
  createdBy?: UUID;
  createdAt?: string;
}

export interface Operator {
  id: UUID;
  operatorRef: string;
  category: string;
  scope: 'BUILTIN' | 'CUSTOM' | 'TENANT_PRIVATE' | string;
  displayName: string;
  description?: string;
  latestVersion: string;
  status: 'ACTIVE' | 'DEPRECATED' | string;
  installed: boolean;
  pinnedVersion?: string;
  manifest?: OperatorManifest;
  versions?: OperatorVersion[];
  createdAt?: string;
}

export interface OperatorValidationResult {
  ok: boolean;
  errors: string[];
  warnings: string[];
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
  primaryKey?: boolean;
  terms?: AssetColumnTerm[];
  upstreamFqn?: string;
  stats?: { nullRate?: number; cardinality?: number; min?: string; max?: string };
}

export interface AssetColumnMetadataUpdateRequest {
  name: string;
  description?: string;
  classification?: Classification;
  piiType?: string;
  suggestLevel?: Classification;
  primaryKey?: boolean;
}

export interface AssetMetadataUpdateRequest {
  description?: string;
  domain?: string;
  ownerName?: string;
  tags?: string[];
  columns?: AssetColumnMetadataUpdateRequest[];
}

export type SchemaChangeType = 'ADD_COLUMN' | 'DROP_COLUMN' | 'RENAME_COLUMN' | 'CHANGE_TYPE';

export interface SchemaChangeApprovalRequest {
  changeType: SchemaChangeType;
  columnName?: string;
  dataType?: string;
  afterName?: string;
  afterType?: string;
  nullable?: boolean;
  reason?: string;
  beforeColumns?: Array<Pick<AssetColumn, 'name' | 'type' | 'description' | 'classification'>>;
  impactSummary?: { assets?: number; apis?: number; subscribers?: number };
}

export interface SchemaChangeExecutionResult {
  approvalId: UUID;
  assetFqn: string;
  changeType: SchemaChangeType;
  status: 'SUCCEEDED' | 'FAILED' | string;
  statement?: string;
  message: string;
  executedAt?: string;
}

export interface AssetColumnTerm {
  id: UUID;
  code: string;
  name: string;
  status: BusinessTermStatus;
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
  smallFileRiskCount: number;
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

export type BusinessTermStatus = 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | 'DEPRECATED' | 'ARCHIVED' | string;

export interface CodebookEntry {
  from: string;
  to: string;
  label?: string;
}

export interface Codebook {
  id: UUID;
  code: string;
  name: string;
  domain?: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'ARCHIVED' | string;
  latestVersion?: string;
  noMatchPolicy: 'KEEP' | 'NULL' | 'FAIL' | string;
  entries: CodebookEntry[];
  tags: string[];
  createdAt?: string;
  updatedAt?: string;
  publishedAt?: string;
}

export interface BusinessTerm {
  id: UUID;
  code: string;
  name: string;
  domainId?: UUID;
  domainName?: string;
  definition?: string;
  caliberSql?: string;
  synonyms: string[];
  ownerId?: UUID;
  ownerName?: string;
  stewardId?: UUID;
  status: BusinessTermStatus;
  version: number;
  sensitivityLevel?: Classification | string;
  tags: string[];
  createdAt?: string;
  updatedAt?: string;
  approvedAt?: string;
  bindingCount?: number;
  bindings: BusinessTermBinding[];
}

export interface BusinessTermBinding {
  id: UUID;
  termId: UUID;
  termCode: string;
  termName: string;
  assetId?: UUID;
  assetFqn: string;
  columnName?: string;
  relationType: 'DEFINES' | 'USES' | 'DERIVES' | string;
  source: 'MANUAL' | 'CATALOG' | 'MODELING' | 'IMPORT' | 'SUGGESTED' | string;
  confidence?: number;
  status: 'ACTIVE' | 'PENDING' | 'REJECTED' | 'STALE' | string;
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessTermVersion {
  id: UUID;
  termId: UUID;
  version: number;
  snapshot: string;
  changeReason?: string;
  changedBy?: UUID;
  createdAt?: string;
}

export interface BusinessTermImpact {
  termId: UUID;
  termCode: string;
  termName: string;
  status: BusinessTermStatus;
  version: number;
  sensitivityLevel?: string;
  bindings: BusinessTermBinding[];
  downstreamAssets: BusinessTermImpactAsset[];
  qualityRules: BusinessTermImpactQualityRule[];
  apis: BusinessTermImpactApi[];
  dags: BusinessTermImpactDag[];
  securityNotices: BusinessTermImpactSecurityNotice[];
  approvals: BusinessTermImpactApproval[];
  warnings: string[];
  impactScore: number;
}

export interface BusinessTermImpactAsset {
  id?: UUID;
  fqn: string;
  displayName?: string;
  layer?: string;
  relation: 'BOUND' | 'DOWNSTREAM' | string;
}

export interface BusinessTermImpactQualityRule {
  id: UUID;
  targetFqn: string;
  targetColumn?: string;
  ruleType: string;
  severity?: string;
  enabled?: boolean;
}

export interface BusinessTermImpactApi {
  id: UUID;
  apiPath: string;
  sourceFqn?: string;
  status: string;
}

export interface BusinessTermImpactDag {
  id: UUID;
  name: string;
  dagsterJob?: string;
  enabled?: boolean;
}

export interface BusinessTermImpactSecurityNotice {
  type: string;
  fqn: string;
  level?: string;
  status?: string;
  message: string;
}

export interface BusinessTermImpactApproval {
  id: UUID;
  requestType: string;
  targetRef: string;
  status: string;
  createdAt?: string;
}

export interface BusinessTermVersionDiff {
  termId: UUID;
  fromVersion?: number;
  toVersion?: number;
  changes: { field: string; before?: unknown; after?: unknown }[];
}

export interface BusinessTermRequest {
  code?: string;
  name?: string;
  domainId?: UUID;
  definition?: string;
  caliberSql?: string;
  synonyms?: string[];
  ownerId?: UUID;
  ownerName?: string;
  stewardId?: UUID;
  sensitivityLevel?: string;
  tags?: string[];
}

export interface BusinessTermBindingRequest {
  assetId?: UUID;
  assetFqn: string;
  columnName?: string;
  relationType?: string;
  source?: string;
  confidence?: number;
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
  pipelineMode?: string;
  operatorGraphVersion?: number;
  operatorGraph?: string;
  resourceGroup?: string;
  computeProfile?: string;
  engine?: string;
  costPolicy?: string;
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
  termId?: UUID;
  termCode?: string;
  termName?: string;
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
  termId?: UUID;
  termCode?: string;
  termName?: string;
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

export interface ComputeProfile {
  id: UUID;
  code: string;
  displayName: string;
  engine: string;
  status: string;
  cpuCores?: number;
  memoryGb?: number;
  maxScanBytes?: number;
  timeoutSeconds?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ResourceGroup {
  id: UUID;
  code: string;
  displayName: string;
  engine: string;
  status: string;
  builtin: boolean;
  maxConcurrency?: number;
  quotaCpu?: number;
  quotaMemoryGb?: number;
  costPolicy?: Record<string, unknown>;
  computeProfiles: ComputeProfile[];
  createdAt?: string;
  updatedAt?: string;
}

export interface RuntimeContract {
  compileTarget: string;
  engine: string;
  dagsterJob: string;
  manifestSupported: boolean;
  graphExecutionSupported: boolean;
  dagsterJobAvailable: boolean;
  status: string;
  blockedReason?: string;
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

export interface LineageGraphData {
  rootFqn: string;
  nodes: LineageGraphNode[];
  edges: LineageGraphEdge[];
}

export interface LineageGraphNode {
  fqn: string;
  name: string;
  layer: string;            // SOURCE/ODS/DWD/DWS/ADS/API
  nodeType: string;         // TABLE / API / JOB
  classification?: string | null;
  qualityScore?: number | null;
  ownerName?: string | null;
  rowCount?: number | null;
  syncedAt?: string | null;
  columns?: { name: string; type: string; classification?: string | null }[];
}

export interface LineageGraphEdge {
  fromFqn: string;
  toFqn: string;
  jobRef?: string | null;
  columnEdges?: { fromColumn: string; toColumn: string; transform?: string | null }[];
}

export interface ImpactReport {
  rootFqn: string;
  directDownstream: string[];
  indirectDownstream: string[];
  affectedJobs: number;
  affectedApis: number;
  affectedSubscribers: number;
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  severityReasons: string[];
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
  suggestLevel?: Classification | string;
  masked?: boolean;
  termId?: UUID;
  termCode?: string;
  termName?: string;
  termDefinition?: string;
  caliberSql?: string;
  termStatus?: string;
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
