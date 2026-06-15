import { http } from './http';

export interface DataSource {
  id: string;
  name: string;
  type: string;
  health: string;
  projectId?: string;
  networkMode: string;
  envLevel: string;
  lastCheckAt?: string;
  createdAt: string;
}

export const IntegrationAPI = {
  listDatasources: (type?: string) =>
    http.get<DataSource[]>('/integration/datasources', { params: { type } }),

  getDatasource: (id: string) =>
    http.get<DataSource>(`/integration/datasources/${id}`),

  createDatasource: (body: unknown) =>
    http.post<DataSource>('/integration/datasources', body),

  testDatasource: (id: string) =>
    http.post(`/integration/datasources/${id}/test`),

  deleteDatasource: (id: string) =>
    http.delete<void>(`/integration/datasources/${id}`),

  listSyncTasksBySource: (sourceId: string) =>
    http.get(`/integration/sync-tasks/by-source/${sourceId}`),

  triggerSyncTask: (id: string) =>
    http.post<{ runId: string }>(`/integration/sync-tasks/${id}/run`),

  listRuns: (taskId: string, page = 0, size = 20) =>
    http.get(`/integration/sync-tasks/${taskId}/runs`, { params: { page, size } }),
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
