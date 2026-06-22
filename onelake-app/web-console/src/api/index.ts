import { http } from './http';
import type {
  ApiDefinition,
  AccessGrant,
  Asset,
  ApprovalRequest,
  DataSource,
  Notification,
  QualityAlert,
  QualityRule,
  QualityRunResult,
  SavedQuery,
  QueryTemplate,
  QueryTemplatePlaceholder,
  Dag,
  RunningTask,
  SqlExecuteResult,
  SqlQueryHistory,
  SyncRun,
  SyncTask,
} from '../types';

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const unwrap = <T>(request: Promise<unknown>) => request as Promise<T>;

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
  triggerDag: (id: string, trigger = 'MANUAL') =>
    unwrap<{ runId: string }>(http.post(`/orchestration/dags/${id}/run`, null, { params: { trigger } })),
};

export const CatalogAPI = {
  listAssets: (layer?: string) => unwrap<Asset[]>(http.get('/catalog/assets', { params: { layer } })),
  getAsset: (id: string) => unwrap<Asset>(http.get(`/catalog/assets/${id}`)),
  downstream: (fqn: string) => unwrap<string[]>(http.get('/catalog/lineage/downstream', { params: { fqn } })),
  sync: () => unwrap<{ synced: number }>(http.post('/catalog/sync')),
  refreshColumns: () => unwrap<{ refreshed: number }>(http.post('/catalog/assets/refresh-columns')),
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
  listDomains: () => http.get('/modeling/domains'),
  listMetricsByDomain: (domainId: string) =>
    http.get(`/modeling/metrics/by-domain/${domainId}`),
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
