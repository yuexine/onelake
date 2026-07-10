import { http } from './http';
import type {
  ApiDefinition,
  AccessGrant,
  Asset,
  AssetDetail,
  AssetMaintenanceAssessment,
  AssetMaintenanceOperation,
  AssetMaintenanceResult,
  AssetMetadataUpdateRequest,
  SchemaChangeApprovalRequest,
  SchemaChangeExecutionResult,
  ApprovalRequest,
  BusinessTerm,
  BusinessTermBinding,
  BusinessTermBindingRequest,
  BusinessTermImpact,
  BusinessTermRequest,
  BusinessTermVersion,
  BusinessTermVersionDiff,
  Backfill,
  CreateBackfillRequest,
  DataSource,
  ImpactReport,
  LineageGraphData,
  Notification,
  ComputeProfile,
  Codebook,
  Operator,
  OperatorManifest,
  OperatorValidationResult,
  ResourceGroup,
  RuntimeContract,
  QualityAlert,
  QualityRule,
  QualityRunResult,
  SavedQuery,
  QueryTemplate,
  QueryTemplatePlaceholder,
  Dag,
  JobRun,
  DataModel,
  DwdModelCompileResult,
  DwdModelDraftRequest,
  DwdModelValidation,
  RunningTask,
  SubjectDomain,
  SqlExecuteResult,
  SqlQueryHistory,
  SyncRun,
  SyncTask,
  TableCreateRequest,
  Pipeline,
  PipelineTask,
  PipelineTaskEdge,
  PipelineTaskRequest,
  PipelineTaskEdgeRequest,
  PipelineValidationResult,
  PipelineKind,
  PipelineStatus,
  PipelineTaskType,
  TaskRun,
  TaskRunLogOptions,
  TaskRerunMode,
  TaskRerunResult,
} from '../types';

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const unwrap = <T>(request: Promise<unknown>) => request as Promise<T>;

type ModelMigrationResult = {
  dryRun: boolean;
  totalCandidates: number;
  plannedItems?: unknown[];
  createdPipelineIds?: string[];
  skippedModelIds?: string[];
  errors: string[];
};

const modelMigrationDryRun = () =>
  unwrap<ModelMigrationResult>(http.get('/orchestration/pipelines/model-migration'));

const modelMigrationExecute = (dryRun = false) =>
  unwrap<ModelMigrationResult>(
    http.post('/orchestration/pipelines/model-migration', null, { params: { dryRun } }),
  );

export interface TenantOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

export interface ProjectOption {
  id: string;
  tenantId: string;
  code: string;
  name: string;
}

export interface SystemContext {
  tenant: TenantOption;
  projects: ProjectOption[];
  userId?: string;
  username?: string;
  roles: string[];
}

export interface ConnectivityTestResult {
  ok: boolean;
  rttMillis?: number;
  errorCode?: string;
  message?: string;
  writePrivilegeDetected?: boolean;
  diagnostics?: Record<string, unknown>;
}

export interface DiscoveredColumn {
  name: string;
  type: string;
  nullable: boolean;
  primaryKey: boolean;
  ordinalPosition: number;
}

export interface SyncTaskDryRunCheck {
  code: string;
  label: string;
  passed: boolean;
  message: string;
}

export interface SyncTaskDryRunResult {
  ready: boolean;
  checks: SyncTaskDryRunCheck[];
}

export interface SyncRunLogLine {
  timestamp: string;
  level: string;
  message: string;
}

export interface AirbyteConnectorDefinition {
  id: string;
  name: string;
  dockerRepository?: string;
  dockerImageTag?: string;
  type: 'SOURCE' | 'DESTINATION';
}

export interface AirbyteConnectorSpec {
  definitionId: string;
  type: 'SOURCE' | 'DESTINATION';
  documentationUrl?: string;
  connectionSpecification?: Record<string, unknown>;
}

export const SystemAPI = {
  context: () => unwrap<SystemContext>(http.get('/system/context')),

  projects: () => unwrap<ProjectOption[]>(http.get('/system/projects')),
};

export const TaskAPI = {
  listRunning: (params?: { includeRecent?: boolean; limit?: number }) =>
    unwrap<RunningTask[]>(http.get('/tasks/running', { params })),

  dismiss: (id: string) =>
    unwrap<RunningTask>(http.post(`/tasks/${id}/dismiss`)),

  cancel: (task: RunningTask) => {
    if (!task.cancelEndpoint) {
      return Promise.reject(new Error('该任务不支持全局取消'));
    }
    return unwrap<unknown>(http.post(task.cancelEndpoint));
  },
};

export const NotificationAPI = {
  list: (params?: { limit?: number }) =>
    unwrap<Notification[]>(http.get('/notifications', { params })),

  markRead: (id: string) =>
    unwrap<Notification>(http.post(`/notifications/${id}/read`)),

  markAllRead: () =>
    unwrap<void>(http.post('/notifications/read-all')),
};

export const IntegrationAPI = {
  listDatasources: (params?: { type?: string; health?: string; envLevel?: string; keyword?: string }) =>
    unwrap<DataSource[]>(http.get('/integration/datasources', { params })),

  getDatasource: (id: string) =>
    unwrap<DataSource>(http.get(`/integration/datasources/${id}`)),

  createDatasource: (body: unknown) =>
    unwrap<DataSource>(http.post('/integration/datasources', body)),

  probeDatabases: (body: unknown) =>
    unwrap<{ databases: string[]; manualAllowed: boolean; message?: string }>(
      http.post('/integration/datasources/probe-databases', body),
    ),

  listDatasourceSchemas: (id: string) =>
    unwrap<string[]>(http.get(`/integration/datasources/${id}/schemas`)),

  listDatasourceTables: (id: string, schema?: string) =>
    unwrap<string[]>(http.get(`/integration/datasources/${id}/tables`, { params: { schema } })),

  describeDatasourceTable: (id: string, objectName: string) =>
    unwrap<DiscoveredColumn[]>(
      http.get(`/integration/datasources/${id}/tables/${encodeURIComponent(objectName)}/columns`),
    ),

  testDatasource: (id: string) =>
    unwrap<ConnectivityTestResult>(
      http.post(`/integration/datasources/${id}/test`),
    ),

  testDatasourceConfig: (body: unknown) =>
    unwrap<ConnectivityTestResult>(
      http.post('/integration/datasources/test-config', body),
    ),

  listAirbyteSourceDefinitions: () =>
    unwrap<AirbyteConnectorDefinition[]>(http.get('/integration/datasources/airbyte/source-definitions')),

  listAirbyteDestinationDefinitions: () =>
    unwrap<AirbyteConnectorDefinition[]>(http.get('/integration/datasources/airbyte/destination-definitions')),

  getAirbyteSourceDefinitionSpec: (definitionId: string) =>
    unwrap<AirbyteConnectorSpec>(
      http.get(`/integration/datasources/airbyte/source-definitions/${definitionId}/spec`),
    ),

  getAirbyteDestinationDefinitionSpec: (definitionId: string) =>
    unwrap<AirbyteConnectorSpec>(
      http.get(`/integration/datasources/airbyte/destination-definitions/${definitionId}/spec`),
    ),

  deleteDatasource: (id: string) =>
    unwrap<void>(http.delete(`/integration/datasources/${id}`)),

  listSyncTasks: (params?: { sourceId?: string; mode?: string; status?: string; keyword?: string }) =>
    unwrap<SyncTask[]>(http.get('/integration/sync-tasks', { params })),

  getSyncTask: (id: string) =>
    unwrap<SyncTask>(http.get(`/integration/sync-tasks/${id}`)),

  createSyncTask: (body: unknown) =>
    unwrap<SyncTask>(http.post('/integration/sync-tasks', body)),

  updateSyncTask: (id: string, body: unknown) =>
    unwrap<SyncTask>(http.put(`/integration/sync-tasks/${id}`, body)),

  deleteSyncTask: (id: string) =>
    unwrap<void>(http.delete(`/integration/sync-tasks/${id}`)),

  enableSyncTask: (id: string) =>
    unwrap<SyncTask>(http.post(`/integration/sync-tasks/${id}/enable`)),

  disableSyncTask: (id: string) =>
    unwrap<SyncTask>(http.post(`/integration/sync-tasks/${id}/disable`)),

  dryRunSyncTaskDraft: (body: unknown) =>
    unwrap<SyncTaskDryRunResult>(http.post('/integration/sync-tasks/dry-run', body)),

  dryRunSyncTask: (id: string) =>
    unwrap<SyncTaskDryRunResult>(http.post(`/integration/sync-tasks/${id}/dry-run`)),

  listSyncTasksBySource: (sourceId: string) =>
    unwrap<SyncTask[]>(http.get(`/integration/sync-tasks/by-source/${sourceId}`)),

  triggerSyncTask: (id: string) =>
    unwrap<{ runId: string }>(http.post(`/integration/sync-tasks/${id}/trigger`)),

  getSyncRun: (runId: string) =>
    unwrap<SyncRun>(http.get(`/integration/sync-tasks/runs/${runId}`)),

  cancelSyncRun: (runId: string) =>
    unwrap<SyncRun>(http.post(`/integration/sync-tasks/runs/${runId}/cancel`)),

  getSyncRunLogs: (runId: string) =>
    unwrap<SyncRunLogLine[]>(http.get(`/integration/sync-tasks/runs/${runId}/logs`)),

  listRuns: (taskId: string, page = 0, size = 20) =>
    unwrap<PageResult<SyncRun>>(http.get(`/integration/sync-tasks/${taskId}/runs`, { params: { page, size } })),

  // CDC
  listCdcTasks: () =>
    unwrap<unknown[]>(http.get('/integration/cdc-tasks')),
  getCdcStatus: (id: string) =>
    unwrap<Record<string, unknown>>(http.get(`/integration/cdc-tasks/${id}/status`)),
  startCdcTask: (id: string) =>
    unwrap<unknown>(http.post(`/integration/cdc-tasks/${id}/start`)),
  stopCdcTask: (id: string) =>
    unwrap<unknown>(http.post(`/integration/cdc-tasks/${id}/stop`)),

  // 文件采集
  listFileSources: () =>
    unwrap<unknown[]>(http.get('/integration/file-sources')),
  listFileSourceFiles: (id: string) =>
    unwrap<unknown[]>(http.get(`/integration/file-sources/${id}/files`)),

  // 监控
  healthSummary: (hours = 24) =>
    unwrap<Record<string, unknown>>(http.get('/integration/monitor/health-summary', { params: { hours } })),
  failTop: (hours = 24, limit = 10) =>
    unwrap<Record<string, unknown>[]>(http.get('/integration/monitor/fail-top', { params: { hours, limit } })),

  // Schema 快照
  captureSnapshot: (sourceId: string, objectName: string) =>
    unwrap<unknown>(http.post(`/integration/datasources/${sourceId}/schema-snapshots`, null, { params: { objectName } })),
  listSnapshots: (sourceId: string) =>
    unwrap<unknown[]>(http.get(`/integration/datasources/${sourceId}/schema-snapshots`)),
};

export const OrchestrationAPI = {
  listDags: () => unwrap<Dag[]>(http.get('/orchestration/dags')),
  getDag: (id: string) => unwrap<Dag>(http.get(`/orchestration/dags/${id}`)),
  createDag: (payload: Partial<Dag> & { dagsterJob?: string; scheduleCron?: string; enabled?: boolean }) =>
    unwrap<Dag>(http.post('/orchestration/dags', payload)),
  updateDag: (id: string, payload: Partial<Dag> & { dagsterJob?: string; scheduleCron?: string; enabled?: boolean }) =>
    unwrap<Dag>(http.put(`/orchestration/dags/${id}`, payload)),
  triggerDag: (id: string, trigger = 'MANUAL') =>
    unwrap<{ runId: string }>(http.post(`/orchestration/dags/${id}/run`, null, { params: { trigger } })),
  listRuns: (page = 0, size = 20) =>
    unwrap<PageResult<JobRun>>(http.get('/orchestration/runs', { params: { page, size } })),
  getRun: (id: string) =>
    unwrap<JobRun>(http.get(`/orchestration/runs/${id}`)),
  cancelRun: (id: string) =>
    unwrap<JobRun>(http.post(`/orchestration/runs/${id}/cancel`)),
  listDagRuns: (id: string, page = 0, size = 20) =>
    unwrap<PageResult<JobRun>>(http.get(`/orchestration/dags/${id}/runs`, { params: { page, size } })),
};

export const BackfillAPI = {
  create: (dagId: string, payload: CreateBackfillRequest) =>
    unwrap<Backfill>(http.post(`/orchestration/pipelines/${dagId}/backfill`, payload)),
  get: (id: string) =>
    unwrap<Backfill>(http.get(`/orchestration/backfills/${id}`)),
  list: (dagId: string) =>
    unwrap<Backfill[]>(http.get(`/orchestration/pipelines/${dagId}/backfills`)),
  listRuns: (id: string, page = 0, size = 20) =>
    unwrap<PageResult<JobRun>>(http.get(`/orchestration/backfills/${id}/runs`, { params: { page, size } })),
  getRun: (id: string, runId: string) =>
    unwrap<JobRun>(http.get(`/orchestration/backfills/${id}/runs/${runId}`)),
  cancel: (id: string) =>
    unwrap<Backfill>(http.post(`/orchestration/backfills/${id}/cancel`)),
};

/**
 * 流水线 V2 API：统一流水线编辑器使用的前端接口门面。
 *
 * 约定：后端统一挂载在 /api/v1/orchestration/pipelines/*。
 */
export const PipelineAPI = {
  // 流水线（Dag）生命周期。
  create: (payload: { name: string; pipelineKind?: PipelineKind }) =>
    unwrap<Pipeline>(http.post('/orchestration/pipelines', payload)),
  get: (id: string) =>
    unwrap<Pipeline>(http.get(`/orchestration/pipelines/${id}`)),
  updateStatus: (id: string, status: PipelineStatus) =>
    unwrap<Pipeline>(http.put(`/orchestration/pipelines/${id}/status`, { status })),

  // 流水线节点。
  listTasks: (id: string) =>
    unwrap<PipelineTask[]>(http.get(`/orchestration/pipelines/${id}/tasks`)),
  createTask: (id: string, payload: PipelineTaskRequest) =>
    unwrap<PipelineTask>(http.post(`/orchestration/pipelines/${id}/tasks`, payload)),
  updateTask: (id: string, taskKey: string, payload: Partial<PipelineTaskRequest> & { taskType: PipelineTaskType }) =>
    unwrap<PipelineTask>(http.put(`/orchestration/pipelines/${id}/tasks/${encodeURIComponent(taskKey)}`, payload)),
  deleteTask: (id: string, taskKey: string) =>
    unwrap<void>(http.delete(`/orchestration/pipelines/${id}/tasks/${encodeURIComponent(taskKey)}`)),

  // 流水线边。
  listEdges: (id: string) =>
    unwrap<PipelineTaskEdge[]>(http.get(`/orchestration/pipelines/${id}/edges`)),
  createEdge: (id: string, payload: PipelineTaskEdgeRequest) =>
    unwrap<PipelineTaskEdge>(http.post(`/orchestration/pipelines/${id}/edges`, payload)),
  deleteEdge: (id: string, sourceKey: string, targetKey: string) =>
    unwrap<void>(http.delete(`/orchestration/pipelines/${id}/edges`, { params: { sourceKey, targetKey } })),

  // L1 + L2 校验。
  validate: (id: string) =>
    unwrap<PipelineValidationResult>(http.post(`/orchestration/pipelines/${id}/validate`, null)),

  // 触发运行，进入 triggerPipelineRun 链路。
  trigger: (id: string, trigger = 'MANUAL') =>
    unwrap<{ runId: string }>(http.post(`/orchestration/pipelines/${id}/trigger`, null, { params: { trigger } })),

  // 节点运行记录与节点级操作。
  listTaskRuns: (id: string, runId: string) =>
    unwrap<TaskRun[]>(http.get(`/orchestration/pipelines/${id}/runs/${runId}/tasks`)),
  rerunTask: (id: string, runId: string, taskKey: string, mode: TaskRerunMode = 'SINGLE') =>
    unwrap<TaskRerunResult>(http.post(
      `/orchestration/pipelines/${id}/runs/${runId}/tasks/${encodeURIComponent(taskKey)}/rerun`,
      { mode },
    )),
  // 新调用方显式传入日志选项；下面保留数字 tail 包装方法，兼容尚未切到 options 对象的旧调用方。
  getTaskLog: (id: string, runId: string, taskKey: string, options: TaskRunLogOptions = {}) =>
    unwrap<string>(http.get(`/orchestration/pipelines/${id}/runs/${runId}/tasks/${encodeURIComponent(taskKey)}/log`, {
      params: { tail: options.tail },
      responseType: 'text',
      headers: { Accept: 'text/plain' },
    })),
  readTaskRunLog: (id: string, runId: string, taskKey: string, tail = 300) =>
    unwrap<string>(http.get(`/orchestration/pipelines/${id}/runs/${runId}/tasks/${encodeURIComponent(taskKey)}/log`, {
      params: { tail },
      responseType: 'text',
      headers: { Accept: 'text/plain' },
    })),
  downloadTaskRunLog: (id: string, runId: string, taskKey: string) =>
    http.get(`/orchestration/pipelines/${id}/runs/${runId}/tasks/${encodeURIComponent(taskKey)}/log`, {
      params: { download: true },
      responseType: 'blob',
      headers: { Accept: 'text/plain' },
    }) as unknown as Promise<import('./http').AxiosResponse<Blob>>,

  // 历史模型迁移到流水线实体（管理员能力）。
  modelMigrationDryRun,
  modelMigrationExecute,
  // @deprecated 请使用 modelMigrationDryRun。保留一版，等待调用方迁移。
  backfillDryRun: modelMigrationDryRun,
  // @deprecated 请使用 modelMigrationExecute。保留一版，等待调用方迁移。
  backfillExecute: modelMigrationExecute,

  // P3：ODS→DWD 模板。
  applyOdsDwdTemplate: (payload: {
    pipelineName?: string;
    modelId: string;
    sourceFqn: string;
    targetFqn: string;
    dbtModelName?: string;
    includeQualityGate?: boolean;
    includeFieldGovernance?: boolean;
  }) =>
    unwrap<{ pipelineId: string; taskIds: string[]; edgeIds: string[]; warnings?: string }>(
      http.post('/orchestration/pipelines/templates/ods-dwd', payload)
    ),
};

export const OperatorAPI = {
  listOperators: (params?: { category?: string; scope?: string; keyword?: string }) =>
    unwrap<Operator[]>(http.get('/orchestration/operators', { params })),

  getOperator: (ref: string) =>
    unwrap<Operator>(http.get(`/orchestration/operators/${encodeURIComponent(ref)}`)),

  validateOperator: (manifest: OperatorManifest) =>
    unwrap<OperatorValidationResult>(http.post('/orchestration/operators/validate', manifest)),

  validateGraph: (graph: unknown) =>
    unwrap<OperatorValidationResult>(http.post('/orchestration/operators/graph/validate', graph)),

  registerOperator: (manifest: OperatorManifest) =>
    unwrap<Operator>(http.post('/orchestration/operators', manifest)),

  publishVersion: (ref: string, payload: { manifest: OperatorManifest; changelog?: string }) =>
    unwrap<Operator>(http.post(`/orchestration/operators/${encodeURIComponent(ref)}/versions`, payload)),

  updateOperator: (ref: string, payload: { displayName?: string; description?: string; status?: string }) =>
    unwrap<Operator>(http.put(`/orchestration/operators/${encodeURIComponent(ref)}`, payload)),

  installOperator: (ref: string, payload?: { pinnedVersion?: string }) =>
    unwrap<Operator>(http.post(`/orchestration/operators/${encodeURIComponent(ref)}/install`, payload ?? {})),

  listResourceGroups: () =>
    unwrap<ResourceGroup[]>(http.get('/orchestration/resource-groups')),

  upsertResourceGroup: (payload: Partial<ResourceGroup> & { code: string; engine: string }) =>
    unwrap<ResourceGroup>(http.post('/orchestration/resource-groups', payload)),

  upsertComputeProfile: (groupCode: string, payload: { code: string; displayName?: string; engine?: string; status?: string; cpuCores?: number; memoryGb?: number; maxScanBytes?: number; timeoutSeconds?: number }) =>
    unwrap<ComputeProfile>(http.post(`/orchestration/resource-groups/${encodeURIComponent(groupCode)}/profiles`, payload)),

  listRuntimeContracts: () =>
    unwrap<RuntimeContract[]>(http.get('/orchestration/runtime-contracts')),
};

export const CatalogAPI = {
  listAssets: (layerOrParams?: string | { layer?: string; keyword?: string; term?: string }) =>
    unwrap<Asset[]>(http.get('/catalog/assets', {
      params: typeof layerOrParams === 'string' ? { layer: layerOrParams } : layerOrParams,
    })),
  getAsset: (id: string) => unwrap<Asset>(http.get(`/catalog/assets/${id}`)),
  getAssetDetail: (id: string) => unwrap<AssetDetail>(http.get(`/catalog/assets/${id}/detail`)),
  updateAssetMetadata: (id: string, payload: AssetMetadataUpdateRequest) =>
    unwrap<Asset>(http.patch(`/catalog/assets/${id}/metadata`, payload)),
  executeSchemaChange: (approvalId: string) =>
    unwrap<SchemaChangeExecutionResult>(http.post(`/catalog/schema-changes/${approvalId}/execute`)),
  createTable: (payload: TableCreateRequest) => unwrap<Asset>(http.post('/catalog/tables', payload)),
  downstream: (fqn: string) => unwrap<string[]>(http.get('/catalog/lineage/downstream', { params: { fqn } })),
  lineageGraph: (fqn: string, direction: 'UP' | 'DOWN' | 'BOTH' = 'BOTH', depth = 3) =>
    unwrap<LineageGraphData>(http.get('/catalog/lineage/graph', { params: { fqn, direction, depth } })),
  lineageImpact: (fqn: string) =>
    unwrap<ImpactReport>(http.get('/catalog/lineage/impact', { params: { fqn } })),
  exportImpactUrl: (fqn: string) =>
    `/api/v1/catalog/lineage/impact/export?fqn=${encodeURIComponent(fqn)}`,
  notifyImpact: (fqn: string) =>
    unwrap<{ notified: boolean; severity: string; rootFqn: string; message: string }>(
      http.post('/catalog/lineage/impact/notify', null, { params: { fqn } }),
    ),
  sync: () => unwrap<{ synced: number }>(http.post('/catalog/sync')),
  refreshColumns: () => unwrap<{ refreshed: number }>(http.post('/catalog/assets/refresh-columns')),
  listMaintenance: () => unwrap<AssetMaintenanceAssessment[]>(http.get('/catalog/assets/maintenance')),
  getMaintenance: (assetId: string) =>
    unwrap<AssetMaintenanceAssessment>(http.get(`/catalog/assets/${assetId}/maintenance`)),
  runMaintenance: (assetId: string, operation: AssetMaintenanceOperation) =>
    unwrap<AssetMaintenanceResult>(http.post(`/catalog/assets/${assetId}/maintenance`, { operation })),
};

export const SqlWorkbenchAPI = {
  estimate: (payload: { sql: string; engine?: string; resourceGroup?: string }) =>
    unwrap<{ engine: string; estimatedScanBytes?: number; thresholdExceeded: boolean; message: string; routeReason?: string }>(
      http.post('/lakehouse/sql/estimate', payload),
    ),

  execute: (payload: { sql: string; engine?: string; resourceGroup?: string; confirmLargeQuery?: boolean; maxRows?: number }) =>
    unwrap<SqlExecuteResult>(http.post('/lakehouse/sql/execute', payload)),

  submit: (payload: { sql: string; engine?: string; resourceGroup?: string; confirmLargeQuery?: boolean; maxRows?: number }) =>
    unwrap<SqlExecuteResult>(http.post('/lakehouse/sql/queries', payload)),

  query: (id: string) =>
    unwrap<SqlExecuteResult>(http.get(`/lakehouse/sql/queries/${id}`)),

  cancel: (id: string) =>
    unwrap<SqlExecuteResult>(http.post(`/lakehouse/sql/queries/${id}/cancel`)),

  history: () =>
    unwrap<SqlQueryHistory[]>(http.get('/lakehouse/sql/history')),

  savedQueries: () =>
    unwrap<SavedQuery[]>(http.get('/lakehouse/sql/saved-queries')),

  saveQuery: (payload: { name: string; sql: string; shared: boolean }) =>
    unwrap<SavedQuery>(http.post('/lakehouse/sql/saved-queries', payload)),

  updateSavedQuery: (id: string, payload: { name: string; sql: string; shared: boolean }) =>
    unwrap<SavedQuery>(http.put(`/lakehouse/sql/saved-queries/${id}`, payload)),

  deleteSavedQuery: (id: string) =>
    unwrap<void>(http.delete(`/lakehouse/sql/saved-queries/${id}`)),

  export: (payload: { sql: string; engine?: string; resourceGroup?: string; confirmLargeQuery?: boolean; format: 'csv' | 'tsv'; maxRows?: number }) =>
    http.post('/lakehouse/sql/export', payload, {
      responseType: 'blob',
      headers: { Accept: 'text/csv, text/tab-separated-values' },
    }) as unknown as Promise<import('./http').AxiosResponse<Blob>>,

  templates: () =>
    unwrap<QueryTemplate[]>(http.get('/lakehouse/sql/templates')),

  createTemplate: (payload: {
    name: string;
    category?: string;
    description?: string;
    sqlTemplate: string;
    placeholders?: QueryTemplatePlaceholder[];
    shared?: boolean;
  }) =>
    unwrap<QueryTemplate>(http.post('/lakehouse/sql/templates', payload)),

  updateTemplate: (id: string, payload: {
    name: string;
    category?: string;
    description?: string;
    sqlTemplate: string;
    placeholders?: QueryTemplatePlaceholder[];
    shared?: boolean;
  }) =>
    unwrap<QueryTemplate>(http.put(`/lakehouse/sql/templates/${id}`, payload)),

  deleteTemplate: (id: string) =>
    unwrap<void>(http.delete(`/lakehouse/sql/templates/${id}`)),

  renderTemplate: (id: string, values: Record<string, string>) =>
    unwrap<{ sql: string; replacedCount: number; submittedDirectly: boolean }>(
      http.post(`/lakehouse/sql/templates/${id}/render`, { values }),
    ),
};

export const ModelingAPI = {
  listDomains: () => unwrap<SubjectDomain[]>(http.get('/modeling/domains')),
  listMetricsByDomain: (domainId: string) =>
    http.get(`/modeling/metrics/by-domain/${domainId}`),
  createDwdDraft: (payload: DwdModelDraftRequest) =>
    unwrap<DataModel>(http.post('/modeling/models/dwd/draft', payload)),
  listModels: (params?: { sourceFqn?: string; targetFqn?: string }) =>
    unwrap<DataModel[]>(http.get('/modeling/models', { params })),
  getModel: (id: string) =>
    unwrap<DataModel>(http.get(`/modeling/models/${id}`)),
  updateModel: (id: string, payload: DwdModelDraftRequest) =>
    unwrap<DataModel>(http.put(`/modeling/models/${id}`, payload)),
  validateModel: (id: string) =>
    unwrap<DwdModelValidation>(http.post(`/modeling/models/${id}/validate`)),
  compileModel: (id: string) =>
    unwrap<DwdModelCompileResult>(http.post(`/modeling/models/${id}/compile`)),
  publishModel: (id: string, payload?: { comment?: string }) =>
    unwrap<DataModel>(http.post(`/modeling/models/${id}/publish`, payload || {})),
  listCodebooks: (params?: { keyword?: string; status?: string; domain?: string }) =>
    unwrap<Codebook[]>(http.get('/modeling/codebooks', { params })),
};

export const GlossaryAPI = {
  listTerms: (params?: { keyword?: string; domainId?: string; status?: string }) =>
    unwrap<BusinessTerm[]>(http.get('/modeling/glossary/terms', { params })),
  createTerm: (payload: BusinessTermRequest) =>
    unwrap<BusinessTerm>(http.post('/modeling/glossary/terms', payload)),
  getTerm: (id: string) =>
    unwrap<BusinessTerm>(http.get(`/modeling/glossary/terms/${id}`)),
  updateTerm: (id: string, payload: BusinessTermRequest) =>
    unwrap<BusinessTerm>(http.put(`/modeling/glossary/terms/${id}`, payload)),
  submitTerm: (id: string) =>
    unwrap<BusinessTerm>(http.post(`/modeling/glossary/terms/${id}/submit`)),
  approveTerm: (id: string, comment?: string) =>
    unwrap<BusinessTerm>(http.post(`/modeling/glossary/terms/${id}/approve`, { comment })),
  rejectTerm: (id: string, comment?: string) =>
    unwrap<BusinessTerm>(http.post(`/modeling/glossary/terms/${id}/reject`, { comment })),
  deprecateTerm: (id: string, comment?: string) =>
    unwrap<BusinessTerm>(http.post(`/modeling/glossary/terms/${id}/deprecate`, { comment })),
  listBindings: (termId: string) =>
    unwrap<BusinessTermBinding[]>(http.get(`/modeling/glossary/terms/${termId}/bindings`)),
  bindTerm: (termId: string, payload: BusinessTermBindingRequest) =>
    unwrap<BusinessTermBinding>(http.post(`/modeling/glossary/terms/${termId}/bindings`, payload)),
  removeBinding: (bindingId: string) =>
    unwrap<BusinessTermBinding>(http.delete(`/modeling/glossary/bindings/${bindingId}`)),
  bindingsByAsset: (assetFqn: string) =>
    unwrap<BusinessTermBinding[]>(http.get('/modeling/glossary/bindings/by-asset', { params: { assetFqn } })),
  termVersions: (termId: string) =>
    unwrap<BusinessTermVersion[]>(http.get(`/modeling/glossary/terms/${termId}/versions`)),
  termImpact: (termId: string) =>
    unwrap<BusinessTermImpact>(http.get(`/modeling/glossary/terms/${termId}/impact`)),
  termVersionDiff: (termId: string) =>
    unwrap<BusinessTermVersionDiff>(http.get(`/modeling/glossary/terms/${termId}/version-diff`)),
};

export const QualityAPI = {
  listRules: () => unwrap<QualityRule[]>(http.get('/quality/rules')),
  getRule: (ruleId: string) => unwrap<QualityRule>(http.get(`/quality/rules/${ruleId}`)),
  createRule: (payload: Partial<QualityRule>) => unwrap<QualityRule>(http.post('/quality/rules', payload)),
  runRule: (ruleId: string) => unwrap<QualityRunResult>(http.post(`/quality/rules/${ruleId}/run`)),
  openAlerts: () => unwrap<QualityAlert[]>(http.get('/quality/alerts')),
  closeAlert: (alertId: string) => unwrap<void>(http.post(`/quality/alerts/${alertId}/close`)),
  recentResults: (ruleId: string) => unwrap<QualityRunResult[]>(http.get(`/quality/results/${ruleId}`)),
  rulesByTarget: (fqn: string) =>
    unwrap<QualityRule[]>(http.get('/quality/rules/by-target', { params: { fqn } })),
};

export const SecurityAPI = {
  listPiiScan: () =>
    unwrap<unknown[]>(http.get('/security/pii-scan')),
  listPiiPending: () =>
    unwrap<unknown[]>(http.get('/security/pii-scan/pending')),
  confirmPii: (recordIds: string[]) =>
    unwrap<void>(http.post('/security/pii-scan/confirm', recordIds)),

  myGrants: () => unwrap<AccessGrant[]>(http.get('/security/grants/me')),
  listGrants: (params?: { status?: string }) =>
    unwrap<AccessGrant[]>(http.get('/security/grants', { params })),
  createGrant: (subjectId: string, assetFqn: string, payload: Record<string, unknown>) =>
    unwrap<AccessGrant>(http.post('/security/grants', payload, { params: { subjectId, assetFqn } })),
  revokeGrant: (grantId: string, comment?: string) =>
    unwrap<AccessGrant>(http.post(`/security/grants/${grantId}/revoke`, undefined, { params: { comment } })),
  extendGrant: (grantId: string, durationDays = 30) =>
    unwrap<AccessGrant>(http.post(`/security/grants/${grantId}/extend`, undefined, { params: { durationDays } })),
  myApprovals: (params?: { status?: string; page?: number; size?: number }) =>
    unwrap<PageResult<ApprovalRequest>>(http.get('/security/approvals/me', { params })),
  pendingApprovals: () => unwrap<ApprovalRequest[]>(http.get('/security/approvals/pending')),
  processedApprovals: (params?: { status?: string; page?: number; size?: number }) =>
    unwrap<PageResult<ApprovalRequest>>(http.get('/security/approvals/processed', { params })),
  applyAccess: (assetFqn: string, payload: Record<string, unknown>) =>
    unwrap<ApprovalRequest>(http.post('/security/approvals', payload, { params: { assetFqn } })),
  applySchemaChange: (assetFqn: string, payload: SchemaChangeApprovalRequest) =>
    unwrap<ApprovalRequest>(http.post('/security/schema-change-approvals', payload, { params: { assetFqn } })),
  approveApproval: (approvalId: string, comment?: string) =>
    unwrap<AccessGrant | null>(http.post(`/security/approvals/${approvalId}/approve`, undefined, { params: { comment } })),
  rejectApproval: (approvalId: string, comment?: string) =>
    unwrap<void>(http.post(`/security/approvals/${approvalId}/reject`, undefined, { params: { comment } })),
  cancelApproval: (approvalId: string, comment?: string) =>
    unwrap<void>(http.post(`/security/approvals/${approvalId}/cancel`, undefined, { params: { comment } })),
  transferApproval: (approvalId: string, nextApproverId?: string, comment?: string) =>
    unwrap<ApprovalRequest>(http.post(`/security/approvals/${approvalId}/transfer`, undefined, { params: { nextApproverId, comment } })),
  addSignApproval: (approvalId: string, role?: string, comment?: string) =>
    unwrap<ApprovalRequest>(http.post(`/security/approvals/${approvalId}/add-sign`, undefined, { params: { role, comment } })),
};

export const DataserviceAPI = {
  listApis: () => unwrap<ApiDefinition[]>(http.get('/dataservice/apis')),
  getApi: (id: string) => unwrap<ApiDefinition>(http.get(`/dataservice/apis/${id}`)),
  createDraft: (payload: Partial<ApiDefinition>) =>
    unwrap<ApiDefinition>(http.post('/dataservice/apis/draft', payload)),
  debugApi: (id: string, params: Record<string, unknown>) =>
    unwrap<{
      columns: { name: string; type: string }[];
      rows: Record<string, unknown>[];
      durationMs: number;
      rowCount: number;
      truncated: boolean;
      maskedColumns?: string[];
      securityNotices?: string[];
    }>(http.post(`/dataservice/apis/${id}/debug`, params)),
  publishApi: (id: string) => unwrap<ApiDefinition>(http.post(`/dataservice/apis/${id}/publish`)),
  offlineApi: (id: string) => unwrap<void>(http.post(`/dataservice/apis/${id}/offline`)),
};

// ============================================================================
// 数据分析与可视化模块（对应 backend module-analytics）
// ============================================================================

export type AnalyticsSourceType = 'ASSET' | 'SQL' | 'API' | 'NOTEBOOK';

export interface AnalyticsFieldSchema {
  name: string;
  type: string;
  classification: string;  // L1..L4
}

export interface AnalyticsDataset {
  id: string;
  tenantId?: string;
  name: string;
  sourceType: AnalyticsSourceType;
  assetFqn?: string;
  selectSql?: string;
  apiId?: string;
  fieldSchema?: AnalyticsFieldSchema[];
  classification: string;
  cacheTtlSec: number;
  rowFilter?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AnalyticsDatasetRequest {
  name: string;
  sourceType: AnalyticsSourceType;
  assetFqn?: string;
  selectSql?: string;
  apiId?: string;
  fieldSchema?: AnalyticsFieldSchema[];
  classification?: string;
  cacheTtlSec?: number;
  rowFilter?: string;
}

export interface AnalyticsDataBinding {
  datasetId?: string;
  dimensions?: string[];
  measures?: { field: string; agg: 'sum' | 'avg' | 'max' | 'min' | 'count' }[];
  filters?: { field: string; op: string; value: unknown; fromVar?: string }[];
  refreshSec?: number;
  limit?: number;
}

export interface AnalyticsQueryResult {
  rows: Record<string, unknown>[];
  fields: AnalyticsFieldSchema[];
  cacheHit: boolean;
  durationMs: number;
}

export interface AnalyticsDashboard {
  id: string;
  name: string;
  description?: string;
  canvas: { width: number; height: number; theme: 'light' | 'dark'; background: string };
  spec: AnalyticsWidgetNode[] | unknown[];
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE';
  currentPublicationId?: string;
  version: number;
  thumbnail?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AnalyticsWidgetNode {
  id: string;
  type: string;
  layout: { x: number; y: number; w: number; h: number; z: number };
  title?: string;
  style?: Record<string, unknown>;
  option?: Record<string, unknown>;
  data?: AnalyticsDataBinding;
  supersetUuid?: string;
  events?: { type: 'jump' | 'filter'; target?: string }[];
}

export interface AnalyticsPublication {
  id: string;
  dashboardId: string;
  version: number;
  snapshot: { canvas: unknown; spec: unknown };
  shareToken?: string;
  isPublic: boolean;
  isCurrent: boolean;
  expireAt?: string;
  publishedAt: string;
}

export const AnalyticsAPI = {
  // 数据集
  listDatasets: (keyword?: string) =>
    unwrap<AnalyticsDataset[]>(http.get('/analytics/datasets', { params: { keyword } })),
  getDataset: (id: string) => unwrap<AnalyticsDataset>(http.get(`/analytics/datasets/${id}`)),
  createDataset: (payload: AnalyticsDatasetRequest) =>
    unwrap<AnalyticsDataset>(http.post('/analytics/datasets', payload)),
  updateDataset: (id: string, payload: AnalyticsDatasetRequest) =>
    unwrap<AnalyticsDataset>(http.put(`/analytics/datasets/${id}`, payload)),
  deleteDataset: (id: string) => unwrap<void>(http.delete(`/analytics/datasets/${id}`)),
  queryDataset: (id: string, binding: AnalyticsDataBinding, dashboardId?: string) =>
    unwrap<AnalyticsQueryResult>(http.post(`/analytics/datasets/${id}/query`, binding, { params: { dashboardId } })),

  // 大屏
  listDashboards: () => unwrap<AnalyticsDashboard[]>(http.get('/analytics/dashboards')),
  getDashboard: (id: string) => unwrap<AnalyticsDashboard>(http.get(`/analytics/dashboards/${id}`)),
  createDashboard: (name: string, description?: string) =>
    unwrap<AnalyticsDashboard>(http.post('/analytics/dashboards', undefined, { params: { name, description } })),
  saveDashboard: (id: string, payload: Partial<AnalyticsDashboard> & { expectedVersion?: number }) =>
    unwrap<AnalyticsDashboard>(http.put(`/analytics/dashboards/${id}`, payload)),
  deleteDashboard: (id: string) => unwrap<void>(http.delete(`/analytics/dashboards/${id}`)),
  publishDashboard: (id: string, isPublic: boolean, expireAt?: string) =>
    unwrap<AnalyticsPublication>(http.post(`/analytics/dashboards/${id}/publish`, { isPublic, expireAt })),
  currentPublication: (id: string) =>
    unwrap<{ version: number; current_publication_id: string; canvas: unknown; spec: unknown }>(
      http.get(`/analytics/dashboards/${id}/publication`),
    ),

  // Superset 嵌入
  supersetGuestToken: (uuid: string) =>
    unwrap<{ token: string }>(http.post('/analytics/superset/guest-token', { uuid })),

  // 公开分享
  shareSnapshot: (token: string) =>
    unwrap<{ snapshot: unknown; version: number; expireAt: string }>(
      http.get(`/analytics/share/screen/${token}`),
    ),
};

// ============================================================================
// 算法模板（P4d）
// ============================================================================

export type TemplateCategory = 'CLUSTERING' | 'REGRESSION' | 'FORECAST' | 'CORRELATION' | 'EDA' | 'RFM';

export interface NotebookTemplate {
  id: string;
  tenantId?: string;
  name: string;
  category: TemplateCategory;
  description?: string;
  storagePath: string;
  paramsSchema?: string;
  kernel: string;
  icon?: string;
  sortOrder: number;
}

export const TemplateAPI = {
  list: (category?: TemplateCategory) =>
    unwrap<NotebookTemplate[]>(http.get('/analytics/notebook-templates', { params: { category } })),
  get: (id: string) => unwrap<NotebookTemplate>(http.get(`/analytics/notebook-templates/${id}`)),
  create: (payload: {
    name: string;
    category: TemplateCategory;
    storagePath: string;
    description?: string;
    paramsSchemaJson?: string;
    kernel?: string;
  }) => unwrap<NotebookTemplate>(http.post('/analytics/notebook-templates', undefined, { params: payload })),
  delete: (id: string) => unwrap<void>(http.delete(`/analytics/notebook-templates/${id}`)),
};

// ============================================================================
// NL2SQL（P5-C 智能建数据集）
// ============================================================================

export interface Nl2SqlRequest {
  asset_fqn: string;
  question: string;
  field_schema?: { name: string; type: string; classification?: string }[];
  dataset_id?: string;
}

export const Nl2SqlAPI = {
  generate: (req: Nl2SqlRequest) =>
    unwrap<{ sql: string }>(http.post('/analytics/nl2sql', req)),
};
