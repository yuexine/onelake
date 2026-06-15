import { http } from './http';
import type { DataSource, SyncRun, SyncTask } from '../types';

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

export const SystemAPI = {
  context: () => unwrap<SystemContext>(http.get('/system/context')),

  projects: () => unwrap<ProjectOption[]>(http.get('/system/projects')),
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

  testDatasource: (id: string) =>
    unwrap<ConnectivityTestResult>(
      http.post(`/integration/datasources/${id}/test`),
    ),

  testDatasourceConfig: (body: unknown) =>
    unwrap<ConnectivityTestResult>(
      http.post('/integration/datasources/test-config', body),
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

  listSyncTasksBySource: (sourceId: string) =>
    unwrap<SyncTask[]>(http.get(`/integration/sync-tasks/by-source/${sourceId}`)),

  triggerSyncTask: (id: string) =>
    unwrap<{ runId: string }>(http.post(`/integration/sync-tasks/${id}/trigger`)),

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
  listDags: () => http.get('/orchestration/dags'),
  getDag: (id: string) => http.get(`/orchestration/dags/${id}`),
  triggerDag: (id: string, trigger = 'MANUAL') =>
    http.post<{ runId: string }>(`/orchestration/dags/${id}/run`, null, { params: { trigger } }),
};

export const CatalogAPI = {
  listAssets: (layer?: string) => http.get('/catalog/assets', { params: { layer } }),
  getAsset: (id: string) => http.get(`/catalog/assets/${id}`),
  downstream: (fqn: string) => http.get('/catalog/lineage/downstream', { params: { fqn } }),
  sync: () => http.post('/catalog/sync'),
};

export const ModelingAPI = {
  listDomains: () => http.get('/modeling/domains'),
  listMetricsByDomain: (domainId: string) =>
    http.get(`/modeling/metrics/by-domain/${domainId}`),
};

export const QualityAPI = {
  listRules: () => http.get('/quality/rules'),
  openAlerts: () => http.get('/quality/alerts'),
  recentResults: (ruleId: string) => http.get(`/quality/results/${ruleId}`),
};

export const SecurityAPI = {
  listPiiScan: () =>
    unwrap<unknown[]>(http.get('/security/pii-scan')),
  listPiiPending: () =>
    unwrap<unknown[]>(http.get('/security/pii-scan/pending')),
  confirmPii: (recordIds: string[]) =>
    unwrap<void>(http.post('/security/pii-scan/confirm', recordIds)),

  myGrants: () => http.get('/security/grants/me'),
  pendingApprovals: () => http.get('/security/approvals/pending'),
  applyAccess: (assetFqn: string, payload: Record<string, unknown>) =>
    http.post('/security/approvals', payload, { params: { assetFqn } }),
};

export const DataserviceAPI = {
  listApis: () => http.get('/dataservice/apis'),
  getApi: (id: string) => http.get(`/dataservice/apis/${id}`),
  publishApi: (id: string) => http.post(`/dataservice/apis/${id}/publish`),
  offlineApi: (id: string) => http.post(`/dataservice/apis/${id}/offline`),
};
