/**
 * 路由表 - 与 SideNav 菜单 key 一一对应。
 * App 作为 layout route（含 Outlet），所有业务页面作为其子路由嵌套，
 * 这样 Sider/TopBar/全局任务条渲染一次，页面切换只替换 Outlet 部分。
 */
import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate, useParams } from 'react-router-dom';
import { Spin } from 'antd';
import App from './App';

const Fallback = () => <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>;

// 工作台
const Dashboard = lazy(() => import('./pages/dashboard'));

// 数据集成
const DatasourceList = lazy(() => import('./pages/integration/DatasourceList'));
const DatasourceDetail = lazy(() => import('./pages/integration/DatasourceDetail'));
const SyncTaskList = lazy(() => import('./pages/integration/SyncTaskList'));
const SyncTaskWizard = lazy(() => import('./pages/integration/SyncTaskWizard'));
const SyncTaskDetail = lazy(() => import('./pages/integration/SyncTaskDetail'));
const CdcMonitor = lazy(() => import('./pages/integration/CdcMonitor'));
const FileCollect = lazy(() => import('./pages/integration/FileCollect'));
const CollectTemplates = lazy(() => import('./pages/integration/CollectTemplates'));
const FailureDiagnose = lazy(() => import('./pages/integration/FailureDiagnose'));
const SchemaChangeApproval = lazy(() => import('./pages/integration/SchemaChangeApproval'));
const CollectMonitor = lazy(() => import('./pages/integration/CollectMonitor'));

// 湖仓
const LakehouseTables = lazy(() => import('./pages/lakehouse/Tables'));
const TableWizard = lazy(() => import('./pages/lakehouse/TableWizard'));
const TableDetail = lazy(() => import('./pages/lakehouse/TableDetail'));
const SqlWorkbench = lazy(() => import('./pages/lakehouse/SqlWorkbench'));
const OptimizeCenter = lazy(() => import('./pages/lakehouse/OptimizeCenter'));
const GovernanceFactory = lazy(() => import('./pages/lakehouse/GovernanceFactory'));

// 编排
const PipelineList = lazy(() => import('./pages/orchestration/PipelineList'));
const DagCanvas = lazy(() => import('./pages/orchestration/DagCanvas'));
const UnifiedPipelineEditor = lazy(() => import('./pages/orchestration/unified-editor/UnifiedPipelineEditor'));
const BackfillProgress = lazy(() => import('./pages/orchestration/BackfillProgress'));
const OperatorMarket = lazy(() => import('./pages/orchestration/OperatorMarket'));
const RunInstances = lazy(() => import('./pages/orchestration/RunInstances'));

// 质量
const QualityRules = lazy(() => import('./pages/quality/QualityRules'));
const QualityResults = lazy(() => import('./pages/quality/QualityResults'));
const GateFailed = lazy(() => import('./pages/quality/GateFailed'));

// 目录与血缘
const CatalogSearch = lazy(() => import('./pages/catalog/CatalogSearch'));
const AssetDetail = lazy(() => import('./pages/catalog/AssetDetail'));
const LineageGraph = lazy(() => import('./pages/catalog/LineageGraph'));
const GlossaryPage = lazy(() => import('./pages/catalog/Glossary'));

// 资产与安全
const AssetMap = lazy(() => import('./pages/security/AssetMap'));
const PiiScan = lazy(() => import('./pages/security/PiiScan'));
const MaskingPage = lazy(() => import('./pages/security/Masking'));
const KmsPage = lazy(() => import('./pages/security/Kms'));

// DaaS
const ApiMarket = lazy(() => import('./pages/dataservice/ApiMarket'));
const ApiWizard = lazy(() => import('./pages/dataservice/ApiWizard'));
const ApiDetail = lazy(() => import('./pages/dataservice/ApiDetail'));
const AppKeysPage = lazy(() => import('./pages/dataservice/AppKeys'));
const GatewayPage = lazy(() => import('./pages/dataservice/Gateway'));
const SubscriptionsPage = lazy(() => import('./pages/dataservice/Subscriptions'));

// 数据分析与可视化
const AnalyticsDatasetList = lazy(() => import('./pages/analytics/datasets/DatasetList'));
const AnalyticsDatasetDetail = lazy(() => import('./pages/analytics/datasets/DatasetDetail'));
const AnalyticsDashboardList = lazy(() => import('./pages/analytics/dashboards/DashboardList'));
const AnalyticsScreenDesigner = lazy(() => import('./pages/analytics/screen/ScreenDesigner'));
const AnalyticsNotebooks = lazy(() => import('./pages/analytics/notebooks/Notebooks'));
const AnalyticsLibrary = lazy(() => import('./pages/analytics/library/Library'));

// 公开分享（无鉴权布局）
const ScreenShare = lazy(() => import('./pages/share/ScreenShare'));

// 运营
const MonitorOverview = lazy(() => import('./pages/monitor/Overview'));
const AlertCenter = lazy(() => import('./pages/monitor/AlertCenter'));
const IncidentReview = lazy(() => import('./pages/monitor/Incidents'));
const SlaBoard = lazy(() => import('./pages/monitor/Sla'));

// 系统
const TenantProject = lazy(() => import('./pages/system/Tenants'));
const RbacMatrix = lazy(() => import('./pages/system/Rbac'));
const ApprovalCenter = lazy(() => import('./pages/system/Approvals'));
const AuditLogs = lazy(() => import('./pages/system/Audit'));
const Channels = lazy(() => import('./pages/system/Channels'));
const AuthCallback = lazy(() => import('./pages/auth/AuthCallback'));
const AuthLogin = lazy(() => import('./pages/auth/AuthCallback').then((module) => ({ default: module.AuthLogin })));

/** Old DagCanvas route — redirect to the unified editor. */
function DagCanvasRedirect() {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/orchestration/pipelines/${id}`} replace />;
}

function PipelineCreateRoute() {
  // All pipelines use the Unified Pipeline Editor.
  return <UnifiedPipelineEditor />;
}

export function AppRoutes() {
  return (
    <Suspense fallback={<Fallback />}>
      <Routes>
        <Route path="/sso/login" element={<AuthLogin />} />
        <Route path="/sso/callback" element={<AuthCallback />} />

        {/* 公开分享大屏：无鉴权，不挂 App layout */}
        <Route path="/share/screen/:token" element={<ScreenShare />} />

        {/* App 作为 layout route，渲染 Sider/TopBar/全局任务条，Outlet 输出业务页面 */}
        <Route element={<App />}>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard />} />

          {/* 数据集成 */}
          <Route path="/integration" element={<Navigate to="/integration/datasources" replace />} />
          <Route path="/integration/datasources" element={<DatasourceList />} />
          <Route path="/integration/datasources/:id" element={<DatasourceDetail />} />
          <Route path="/integration/sync-tasks" element={<SyncTaskList />} />
          <Route path="/integration/sync-tasks/new" element={<SyncTaskWizard />} />
          <Route path="/integration/sync-tasks/:id" element={<SyncTaskDetail />} />
          <Route path="/integration/sync-tasks/:id/runs/:runId" element={<FailureDiagnose />} />
          <Route path="/integration/cdc" element={<CdcMonitor />} />
          <Route path="/integration/files" element={<FileCollect />} />
          <Route path="/integration/templates" element={<CollectTemplates />} />
          <Route path="/integration/schema-change/:id" element={<SchemaChangeApproval />} />
          <Route path="/integration/monitor" element={<CollectMonitor />} />

          {/* 湖仓 */}
          <Route path="/lakehouse" element={<Navigate to="/lakehouse/tables" replace />} />
          <Route path="/lakehouse/tables" element={<LakehouseTables />} />
          <Route path="/lakehouse/tables/new" element={<TableWizard />} />
          <Route path="/lakehouse/tables/:id" element={<TableDetail />} />
          <Route path="/lakehouse/governance-factory" element={<GovernanceFactory />} />
          <Route path="/lakehouse/sql" element={<SqlWorkbench />} />
          <Route path="/lakehouse/optimize" element={<OptimizeCenter />} />

          {/* 编排 */}
          <Route path="/orchestration" element={<Navigate to="/orchestration/pipelines" replace />} />
          <Route path="/orchestration/pipelines" element={<PipelineList />} />
          <Route path="/orchestration/pipelines/new" element={<PipelineCreateRoute />} />
          <Route path="/orchestration/pipelines/:id" element={<UnifiedPipelineEditor />} />
          <Route path="/orchestration/pipelines/:pipelineId/backfills/:backfillId" element={<BackfillProgress />} />
          {/* Historical graph route redirects to the unified editor. */}
          <Route path="/orchestration/pipelines/:id/graph" element={<DagCanvasRedirect />} />
          <Route path="/orchestration/pipelines/:pipelineId/editor" element={<UnifiedPipelineEditor />} />
          <Route path="/orchestration/operators" element={<OperatorMarket />} />
          <Route path="/orchestration/runs" element={<RunInstances />} />
          <Route path="/orchestration/runs/:runId" element={<RunInstances />} />

          {/* 质量 */}
          <Route path="/quality" element={<Navigate to="/quality/rules" replace />} />
          <Route path="/quality/rules" element={<QualityRules />} />
          <Route path="/quality/results" element={<QualityResults />} />
          <Route path="/quality/gate" element={<GateFailed />} />

          {/* 目录 */}
          <Route path="/catalog" element={<Navigate to="/catalog/search" replace />} />
          <Route path="/catalog/search" element={<CatalogSearch />} />
          <Route path="/catalog/assets/:id" element={<AssetDetail />} />
          <Route path="/catalog/lineage" element={<LineageGraph />} />
          <Route path="/catalog/glossary" element={<GlossaryPage />} />

          {/* 安全 */}
          <Route path="/security" element={<Navigate to="/security/map" replace />} />
          <Route path="/security/map" element={<AssetMap />} />
          <Route path="/security/pii" element={<PiiScan />} />
          <Route path="/security/masking" element={<MaskingPage />} />
          <Route path="/security/kms" element={<KmsPage />} />

          {/* DaaS */}
          <Route path="/dataservice" element={<Navigate to="/dataservice/apis" replace />} />
          <Route path="/dataservice/apis" element={<ApiMarket />} />
          <Route path="/dataservice/apis/new" element={<ApiWizard />} />
          <Route path="/dataservice/apis/:id" element={<ApiDetail />} />
          <Route path="/dataservice/appkeys" element={<AppKeysPage />} />
          <Route path="/dataservice/gateway" element={<GatewayPage />} />
          <Route path="/dataservice/subscriptions" element={<SubscriptionsPage />} />

          {/* 数据分析与可视化 */}
          <Route path="/analytics" element={<Navigate to="/analytics/dashboards" replace />} />
          <Route path="/analytics/dashboards" element={<AnalyticsDashboardList />} />
          <Route path="/analytics/dashboards/:id" element={<AnalyticsScreenDesigner />} />
          <Route path="/analytics/dashboards/:id/view" element={<AnalyticsScreenDesigner readOnly />} />
          <Route path="/analytics/notebooks" element={<AnalyticsNotebooks />} />
          <Route path="/analytics/datasets" element={<AnalyticsDatasetList />} />
          <Route path="/analytics/datasets/:id" element={<AnalyticsDatasetDetail />} />
          <Route path="/analytics/library" element={<AnalyticsLibrary />} />

          {/* 运营 */}
          <Route path="/monitor" element={<Navigate to="/monitor/overview" replace />} />
          <Route path="/monitor/overview" element={<MonitorOverview />} />
          <Route path="/monitor/alerts" element={<AlertCenter />} />
          <Route path="/monitor/alerts/:id" element={<AlertCenter />} />
          <Route path="/monitor/incidents" element={<IncidentReview />} />
          <Route path="/monitor/sla" element={<SlaBoard />} />

          {/* 系统 */}
          <Route path="/system" element={<Navigate to="/system/approvals" replace />} />
          <Route path="/system/tenants" element={<TenantProject />} />
          <Route path="/system/rbac" element={<RbacMatrix />} />
          <Route path="/system/approvals" element={<ApprovalCenter />} />
          <Route path="/system/approvals/:id" element={<ApprovalCenter />} />
          <Route path="/system/audit" element={<AuditLogs />} />
          <Route path="/system/channels" element={<Channels />} />

          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
