/**
 * SQL 工作台（对应原型 §8.3.3 升级版）。
 *   表树 + Monaco SQL 编辑器 + 结果区
 */
import { Row, Col, Tree, Space, Button, Select, Tag, Table, Alert, Tabs, Tooltip, Typography, Popconfirm, Dropdown, Input, Segmented, Modal, Form, App as AntdApp, Checkbox } from 'antd';
import {
  PlayCircleOutlined, FormatPainterOutlined, SaveOutlined,
  ApiOutlined, ClusterOutlined, CodeOutlined, DatabaseOutlined,
  StopOutlined, DeleteOutlined, DownloadOutlined,
  FileTextOutlined, PlusOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import { format as formatSqlText } from 'sql-formatter';
import ReactECharts from 'echarts-for-react';
import { PageHeader, SectionCard } from '../../components';
import { CatalogAPI, OrchestrationAPI, SecurityAPI, SqlWorkbenchAPI } from '../../api';
import { BizError } from '../../api/http';
import { getAuthUser, getValidAccessToken } from '../../auth/oidc';
import type { AccessGrant, ApprovalRequest, Asset, QueryTemplate, QueryTemplatePlaceholder, SavedQuery, SqlExecuteResult, SqlQueryHistory } from '../../types';
import { normalizeCatalogAssets } from './assetAdapter';

const { Text } = Typography;

const EXPORT_MAX_ROWS_HINT = 50000;
const ROW_LIMIT_OPTIONS = [100, 500, 2000] as const;
const RESULT_COLUMN_WIDTH = 180;
const RESULT_TABLE_MIN_WIDTH = 720;

interface ErrorHint {
  title?: string;
  hint?: string;
}

const SQL_ERROR_CATALOG: Record<string, ErrorHint> = {
  '40000': { title: '参数校验失败' },
  '40040': { title: '只读校验失败', hint: '工作台只允许单条只读 SQL，写入、建表和多语句会在连接 Trino 前被拦截。' },
  '40041': { title: '查询名称过长', hint: '名称不能超过 128 个字符。' },
  '40042': { title: '引擎暂未接入', hint: '当前仅支持 Trino 查询执行，Spark/StarRocks 将在后续迭代接入。' },
  '40043': { title: 'SQL 过长', hint: 'SQL 长度上限 100000 字符，建议改用 SELECT 字段列表而非 SELECT *。' },
  '40044': { title: '需确认大查询', hint: '预计扫描量超过阈值，在提示框中确认后再执行。' },
  '40045': { title: '导出格式不支持', hint: '导出格式仅支持 csv 或 tsv。' },
  '40300': { title: '无权限', hint: '当前账号无权访问该资源，请联系管理员或在安全中心申请。' },
  '40302': { title: '无编辑权限', hint: '仅 owner 或被显式授予 EDIT 权限的用户可修改或删除该资源。' },
  '40340': { title: '缺少资产查询授权', hint: 'SQL 引用的资产已登记，但当前账号没有 query 授权。已有资产可在安全中心申请访问权限，或由管理员授予访问。' },
  '40341': { title: 'Catalog 资产未登记', hint: 'SQL 引用的表尚未纳入 Catalog。请先完成资产登记，再重新执行查询。' },
  '40404': { title: '查询不存在', hint: '查询任务可能已过期或被清理，请重新运行。' },
  '40405': { title: '保存查询不存在' },
  '42901': { title: '并发查询数超限', hint: '当前账号已有过多运行中查询，请先取消已完成或长时间运行的查询再提交。' },
  '49901': { title: '导出已取消', hint: '导出已被取消，可重新发起。' },
  '50041': { title: 'SQL 执行失败', hint: 'Trino 执行过程中出错，请根据错误消息调整 SQL 或联系管理员。' },
  '50042': { title: 'SQL 导出失败' },
  'SQL_EXECUTION_FAILED': { title: 'SQL 执行失败' },
  'SQL_EXPORT_FAILED': { title: 'SQL 导出失败' },
  'SQL_QUERY_CANCELLED': { title: '查询已取消' },
};

function resolveErrorHint(errorCode?: string, errorMessage?: string): ErrorHint | undefined {
  if (errorCode && SQL_ERROR_CATALOG[errorCode]) {
    return SQL_ERROR_CATALOG[errorCode];
  }
  if (!errorMessage) return undefined;
  if (errorMessage.includes('未登记到 Catalog')) return SQL_ERROR_CATALOG['40341'];
  if (errorMessage.includes('无权查询资产')) return SQL_ERROR_CATALOG['40340'];
  if (errorMessage.includes('只读查询') || errorMessage.includes('不允许一次提交多条语句')) return SQL_ERROR_CATALOG['40040'];
  return undefined;
}

function formatSqlError(errorMessage: string, errorCode?: string) {
  const hint = resolveErrorHint(errorCode, errorMessage);
  return {
    title: hint?.title || 'SQL 执行失败',
    message: errorMessage,
    hint: hint?.hint,
  };
}

function deniedAssetFromError(errorMessage?: string) {
  if (!errorMessage) return undefined;
  const match = errorMessage.match(/无权查询资产:\s*([^\s，,；;]+)/);
  return match?.[1];
}

function isNumericType(type?: string): boolean {
  if (!type) return false;
  const t = type.toLowerCase();
  return t.includes('int') || t.includes('long') || t.includes('double') || t.includes('float') || t.includes('decimal') || t.includes('numeric') || t === 'number' || t === 'real';
}

interface ChartPanelProps {
  result?: SqlExecuteResult;
}

function ChartPanel({ result }: ChartPanelProps) {
  const [chartType, setChartType] = useState<'bar' | 'line' | 'pie'>('bar');
  const [xAxis, setXAxis] = useState<string>();
  const [yAxis, setYAxis] = useState<string>();

  const numericColumns = useMemo(
    () => (result?.columns || []).filter((c) => isNumericType(c.type)).map((c) => c.name),
    [result],
  );
  const stringColumns = useMemo(
    () => (result?.columns || []).filter((c) => !isNumericType(c.type)).map((c) => c.name),
    [result],
  );

  useEffect(() => {
    if (!result || result.status !== 'SUCCEEDED') return;
    if (!xAxis && stringColumns.length > 0) setXAxis(stringColumns[0]);
    else if (xAxis && !stringColumns.includes(xAxis)) setXAxis(stringColumns[0] || undefined);
    if (!yAxis && numericColumns.length > 0) setYAxis(numericColumns[0]);
    else if (yAxis && !numericColumns.includes(yAxis)) setYAxis(numericColumns[0] || undefined);
  }, [result, stringColumns, numericColumns]);

  if (!result || result.status !== 'SUCCEEDED' || result.rows.length === 0) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--ol-ink-3)' }}>
        暂无可视化数据，请先运行查询并返回至少 1 行结果
      </div>
    );
  }

  if (numericColumns.length === 0) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--ol-ink-3)' }}>
        未检测到数值列，无法生成图表
      </div>
    );
  }

  const dimension = xAxis || stringColumns[0] || result.columns[0].name;
  const metric = yAxis || numericColumns[0];

  const rows = result.rows.slice(0, 500);
  const dimensionData = rows.map((r) => String(r[dimension] ?? ''));
  const metricData = rows.map((r) => Number(r[metric]) || 0);

  let option: Record<string, unknown>;
  if (chartType === 'pie') {
    option = {
      tooltip: { trigger: 'item' },
      legend: { type: 'scroll', top: 8, left: 'center' },
      series: [{
        name: metric,
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: true,
        label: { show: true, formatter: '{b}: {d}%' },
        data: rows.map((r, i) => ({ name: dimensionData[i], value: metricData[i] })),
      }],
    };
  } else {
    option = {
      tooltip: { trigger: 'axis' },
      legend: { data: [metric], top: 0 },
      grid: { left: 48, right: 24, top: 32, bottom: 48, containLabel: true },
      xAxis: { type: 'category', data: dimensionData, axisLabel: { interval: 'auto', rotate: dimensionData.length > 8 ? 30 : 0 } },
      yAxis: { type: 'value' },
      series: [{ name: metric, type: chartType, data: metricData, smooth: chartType === 'line' }],
    };
  }

  return (
    <div>
      <Space size={8} wrap style={{ marginBottom: 12 }}>
        <Select value={chartType} onChange={setChartType} options={[
          { label: '柱状', value: 'bar' },
          { label: '折线', value: 'line' },
          { label: '饼图', value: 'pie' },
        ]} style={{ width: 96 }} />
        <Select value={dimension} onChange={setXAxis} options={[...stringColumns, ...numericColumns].map((c) => ({ label: c, value: c }))} placeholder="维度" style={{ width: 180 }} />
        <Select value={metric} onChange={setYAxis} options={numericColumns.map((c) => ({ label: c, value: c }))} placeholder="度量" style={{ width: 180 }} />
        <Text type="secondary" style={{ fontSize: 12 }}>前 {Math.min(rows.length, 500)} 行参与绘图</Text>
      </Space>
      <ReactECharts option={option} style={{ height: 380 }} notMerge />
    </div>
  );
}

function formatBytes(v?: number) {
  if (v == null) return '-';
  if (v >= 1e12) return `${(v / 1e12).toFixed(2)} TB`;
  if (v >= 1e9) return `${(v / 1e9).toFixed(2)} GB`;
  if (v >= 1e6) return `${(v / 1e6).toFixed(2)} MB`;
  return `${v} B`;
}

function formatDuration(v?: number) {
  if (v == null) return '-';
  return v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${v}ms`;
}

function cellText(value: unknown) {
  if (value == null) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function hasProtectedResult(result?: SqlExecuteResult) {
  if (!result || result.status !== 'SUCCEEDED') return false;
  return Boolean(result.securityNotices?.length || result.maskedColumns?.length);
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '-';
}

function approvalReason(payload?: Record<string, unknown> | string, fallback?: string) {
  if (fallback) return fallback;
  if (!payload) return '-';
  if (typeof payload === 'object') {
    return typeof payload.reason === 'string' ? payload.reason : '-';
  }
  try {
    const parsed = JSON.parse(payload);
    return typeof parsed?.reason === 'string' ? parsed.reason : '-';
  } catch {
    return '-';
  }
}

type GrantPermissionFlags = { query?: boolean; download?: boolean; api?: boolean };

function grantPermissions(grant: AccessGrant): GrantPermissionFlags {
  const raw = (grant as { permissions?: AccessGrant['permissions'] | string }).permissions;
  if (!raw) return {};
  if (typeof raw === 'object') return raw as GrantPermissionFlags;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed as GrantPermissionFlags : {};
  } catch {
    return {};
  }
}

function TableTreeTitle({ fqn }: { fqn: string }) {
  const [layer = '', name = fqn] = fqn.split('.');
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0, maxWidth: '100%' }}>
      <span
        className="mono"
        style={{
          minWidth: 28,
          padding: '1px 5px',
          borderRadius: 4,
          background: 'var(--ol-brand-soft)',
          color: 'var(--ol-brand)',
          fontSize: 11,
          lineHeight: '16px',
          fontWeight: 600,
          textAlign: 'center',
          textTransform: 'uppercase',
        }}
      >
        {layer}
      </span>
      <span
        className="mono ol-truncate"
        style={{
          maxWidth: 160,
          padding: '1px 6px',
          borderRadius: 4,
          border: '1px solid var(--ol-line-soft)',
          background: 'var(--ol-card)',
          color: 'var(--ol-ink)',
          fontSize: 13,
          lineHeight: '18px',
          fontWeight: 400,
        }}
      >
        {name}
      </span>
    </span>
  );
}

function FieldTreeTitle({ name, type, classification }: { name: string; type: string; classification?: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0, maxWidth: '100%' }}>
      <span className="mono ol-truncate" style={{ maxWidth: 108, fontSize: 12 }}>{name}</span>
      <Text type="secondary" className="mono" style={{ fontSize: 11 }}>{type}</Text>
      {classification && <Tag color="warning" style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>{classification}</Tag>}
    </span>
  );
}

function previewSqlForAsset(asset: Asset) {
  return `SELECT * FROM ${asset.fqn}\nLIMIT 100`;
}

export default function SqlWorkbench() {
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const editorRef = useRef<any>(null);
  const monacoRef = useRef<any>(null);
  const completionDisposeRef = useRef<{ dispose: () => void } | null>(null);
  const executeSqlRef = useRef<(nextSql?: string) => void>(() => {});
  const saveSqlRef = useRef<() => void>(() => {});
  const exportRef = useRef<(format: 'csv' | 'tsv') => void>(() => {});
  const abortRef = useRef<AbortController | null>(null);
  const initialAssetParamRef = useRef<string>();
  const [sql, setSql] = useState('SHOW SCHEMAS');
  const [assets, setAssets] = useState<Asset[]>([]);
  const [assetLoading, setAssetLoading] = useState(true);
  const [assetError, setAssetError] = useState<string>();
  const [history, setHistory] = useState<SqlQueryHistory[]>([]);
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>([]);
  const [result, setResult] = useState<SqlExecuteResult>();
  const [engine, setEngine] = useState('auto');
  const [resourceGroup, setResourceGroup] = useState('rg-default');
  const [rowLimit, setRowLimit] = useState<number>(100);
  const [estimateMessage, setEstimateMessage] = useState('运行前将进行只读校验；扫描量以执行引擎返回为准');
  const [queryError, setQueryError] = useState<string>();
  const [queryErrorCode, setQueryErrorCode] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [currentQueryId, setCurrentQueryId] = useState<string>();
  const [cancelling, setCancelling] = useState(false);
  const [selectedAssetFqn, setSelectedAssetFqn] = useState<string>();
  const [editorReady, setEditorReady] = useState(false);
  const [treeSearch, setTreeSearch] = useState('');
  const [expandedTreeKeys, setExpandedTreeKeys] = useState<string[]>([]);
  const [exporting, setExporting] = useState(false);
  const [exportHistoryId, setExportHistoryId] = useState<string>();
  const [leftView, setLeftView] = useState<'assets' | 'templates'>('assets');
  const [templates, setTemplates] = useState<QueryTemplate[]>([]);
  const [templateSearch, setTemplateSearch] = useState('');
  const [renderTarget, setRenderTarget] = useState<QueryTemplate | null>(null);
  const [renderForm] = Form.useForm<Record<string, string>>();
  const [accessForm] = Form.useForm<Record<string, unknown>>();
  const [rendering, setRendering] = useState(false);
  const [accessModalOpen, setAccessModalOpen] = useState(false);
  const [accessTargetFqn, setAccessTargetFqn] = useState<string>();
  const [accessApplying, setAccessApplying] = useState(false);
  const [submittedApprovalId, setSubmittedApprovalId] = useState<string>();
  const [myApprovalsOpen, setMyApprovalsOpen] = useState(false);
  const [myApprovals, setMyApprovals] = useState<ApprovalRequest[]>([]);
  const [myApprovalsLoading, setMyApprovalsLoading] = useState(false);
  const [myApprovalsStatus, setMyApprovalsStatus] = useState<'ALL' | ApprovalRequest['status']>('ALL');
  const [myApprovalsPage, setMyApprovalsPage] = useState(0);
  const [myApprovalsSize, setMyApprovalsSize] = useState(10);
  const [myApprovalsTotal, setMyApprovalsTotal] = useState(0);
  const [myGrants, setMyGrants] = useState<AccessGrant[]>([]);
  const [myGrantsLoading, setMyGrantsLoading] = useState(false);
  const requestedAssetId = searchParams.get('assetId') || undefined;
  const requestedAssetFqn = searchParams.get('assetFqn') || undefined;

  const showQueryError = useCallback((errorMessage: string, errorCode?: string) => {
    const formatted = formatSqlError(errorMessage, errorCode);
    message.error({
      duration: 4,
      content: (
        <span>
          <span style={{ fontWeight: 600 }}>{formatted.title}</span>
          <span>：{formatted.message}</span>
        </span>
      ),
    });
  }, [message]);

  const filteredAssets = useMemo(() => {
    const keyword = treeSearch.trim().toLowerCase();
    if (!keyword) return assets;
    const result: Asset[] = [];
    for (const asset of assets) {
      const tableMatch = asset.fqn.toLowerCase().includes(keyword);
      const matchingColumns = (asset.columns || []).filter((c) => {
        const nameHit = c.name.toLowerCase().includes(keyword);
        const clsHit = Boolean(c.classification && c.classification.toLowerCase().includes(keyword));
        return nameHit || clsHit;
      });
      if (tableMatch) {
        result.push(asset);
      } else if (matchingColumns.length > 0) {
        result.push({ ...asset, columns: matchingColumns });
      }
    }
    return result;
  }, [assets, treeSearch]);

  const filteredAssetColumnCount = useMemo(
    () => filteredAssets.reduce((sum, asset) => sum + (asset.columns?.length || 0), 0),
    [filteredAssets],
  );

  const filteredTemplates = useMemo(() => {
    const kw = templateSearch.trim().toLowerCase();
    if (!kw) return templates;
    return templates.filter((t) =>
      (t.name || '').toLowerCase().includes(kw)
      || (t.category || '').toLowerCase().includes(kw)
      || (t.description || '').toLowerCase().includes(kw)
    );
  }, [templates, templateSearch]);

  const isSavedQueryOwner = useCallback((row: SavedQuery) => {
    const user = getAuthUser();
    return Boolean(row.ownerId && user?.id && row.ownerId === user.id);
  }, []);

  const isTemplateOwner = useCallback((row: QueryTemplate) => {
    const user = getAuthUser();
    return Boolean(row.ownerId && user?.id && row.ownerId === user.id);
  }, []);

  const selectedAsset = useMemo(
    () => assets.find((asset) => asset.fqn === selectedAssetFqn),
    [assets, selectedAssetFqn],
  );

  const selectedTreeKeys = selectedAsset ? [selectedAsset.id] : [];

  const loadAssetPreviewSql = useCallback((asset: Asset) => {
    const previewSql = previewSqlForAsset(asset);
    setSelectedAssetFqn(asset.fqn);
    setSql(previewSql);
    editorRef.current?.setValue(previewSql);
  }, []);

  const loadAssets = (attemptRefresh = true) => {
    setAssetLoading(true);
    setAssetError(undefined);
    CatalogAPI.listAssets()
      .then(async (items) => {
        const normalized = normalizeCatalogAssets(items);
        const needsColumnRefresh = attemptRefresh && normalized.some((asset) => !asset.columns || asset.columns.length === 0);
        if (needsColumnRefresh) {
          try {
            const refreshed = await CatalogAPI.refreshColumns();
            if (refreshed.refreshed > 0) {
              const nextItems = await CatalogAPI.listAssets();
              setAssets(normalizeCatalogAssets(nextItems));
              return;
            }
          } catch (e) {
            message.warning(e instanceof Error && e.message ? `Catalog 字段补全失败：${e.message}` : 'Catalog 字段补全失败');
          }
        }
        setAssets(normalized);
      })
      .catch((e) => {
        setAssets([]);
        setAssetError(e.message || 'Catalog 资产加载失败');
      })
      .finally(() => setAssetLoading(false));
  };

  const loadHistory = () => {
    SqlWorkbenchAPI.history()
      .then(setHistory)
      .catch((e) => message.error(e.message || '查询历史加载失败'));
  };

  const loadSavedQueries = () => {
    SqlWorkbenchAPI.savedQueries()
      .then(setSavedQueries)
      .catch((e) => message.error(e.message || '保存查询加载失败'));
  };

  const loadTemplates = () => {
    SqlWorkbenchAPI.templates()
      .then(setTemplates)
      .catch((e) => message.error(e.message || '模板加载失败'));
  };

  useEffect(() => {
    loadAssets();
    loadHistory();
    loadSavedQueries();
    loadTemplates();
  }, []);

  useEffect(() => {
    if (treeSearch.trim()) {
      setExpandedTreeKeys(filteredAssets.map((asset) => asset.id));
      return;
    }

    const assetIds = new Set(assets.map((asset) => asset.id));
    setExpandedTreeKeys((keys) => keys.filter((key) => assetIds.has(key)));
  }, [assets, filteredAssets, treeSearch]);

  useEffect(() => {
    const requestKey = requestedAssetId || requestedAssetFqn;
    if (!requestKey || assetLoading || assetError || assets.length === 0) return;
    if (initialAssetParamRef.current === requestKey) return;

    const requestedAsset = assets.find((asset) => asset.id === requestedAssetId || asset.fqn === requestedAssetFqn);
    if (!requestedAsset) {
      if (requestedAssetFqn) setTreeSearch(requestedAssetFqn);
      return;
    }

    setLeftView('assets');
    setTreeSearch(requestedAsset.fqn);
    setExpandedTreeKeys([requestedAsset.id]);
    loadAssetPreviewSql(requestedAsset);
    initialAssetParamRef.current = requestKey;
  }, [assets, assetError, assetLoading, loadAssetPreviewSql, requestedAssetFqn, requestedAssetId]);

  useEffect(() => {
    if (myApprovalsOpen) {
      loadMyApprovals(0, myApprovalsSize, myApprovalsStatus);
    }
  }, [myApprovalsStatus]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey)) return;
      const key = event.key.toLowerCase();
      if (key === 's') {
        event.preventDefault();
        saveSqlRef.current();
      } else if (event.key === 'Enter') {
        event.preventDefault();
        executeSqlRef.current();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  useEffect(() => {
    if (!monacoRef.current) return undefined;
    completionDisposeRef.current?.dispose();
    const monaco = monacoRef.current;
    completionDisposeRef.current = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: () => {
        const suggestions = [
          {
            label: 'select-limit',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: 'SELECT *\nFROM ${1:catalog.schema.table}\nLIMIT 100',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            detail: 'SELECT preview snippet',
          },
          {
            label: 'where-date',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: "WHERE dt = '${1:2026-06-22}'",
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            detail: 'date filter snippet',
          },
          ...assets.flatMap((asset) => [
            {
              label: asset.fqn,
              kind: monaco.languages.CompletionItemKind.Struct,
              insertText: asset.fqn,
              detail: 'Catalog table',
            },
            ...(asset.columns || []).map((column) => ({
              label: `${asset.fqn}.${column.name}`,
              kind: monaco.languages.CompletionItemKind.Field,
              insertText: column.name,
              detail: `${column.type}${column.classification ? ` · ${column.classification}` : ''}`,
            })),
          ]),
        ];
        return { suggestions };
      },
    });
    return () => completionDisposeRef.current?.dispose();
  }, [assets, editorReady]);

  useEffect(() => {
    if (!currentQueryId || !loading) return undefined;
    const timer = window.setInterval(() => {
      SqlWorkbenchAPI.query(currentQueryId)
        .then((data) => {
          setResult(data);
          if (data.status === 'RUNNING') return;
          setCurrentQueryId(undefined);
          setLoading(false);
          setCancelling(false);
          loadHistory();
          if (data.status === 'SUCCEEDED') {
            message.success(`SQL 已完成，返回 ${data.rows.length} 行预览`);
          } else if (data.status === 'CANCELLED') {
            message.info('SQL 查询已取消');
          } else {
            setQueryError(data.error || 'SQL 执行失败');
            setQueryErrorCode(data.errorCode);
            showQueryError(data.error || 'SQL 执行失败', data.errorCode);
          }
        })
        .catch((e: Error) => {
          setCurrentQueryId(undefined);
          setLoading(false);
          setCancelling(false);
          setResult(undefined);
          const code = e instanceof BizError ? e.code?.toString() : undefined;
          setQueryError(e.message || '查询状态获取失败');
          if (code) setQueryErrorCode(code);
          showQueryError(e.message || '查询状态获取失败', code);
          loadHistory();
        });
    }, 1500);
    return () => window.clearInterval(timer);
  }, [currentQueryId, loading]);

  const insertSqlText = (text: string) => {
    const editor = editorRef.current;
    if (!editor) {
      setSql((prev) => `${prev}${prev.endsWith(' ') || prev.endsWith('\n') ? '' : ' '}${text}`);
      return;
    }
    const selection = editor.getSelection();
    editor.executeEdits('catalog-tree', [{ range: selection, text, forceMoveMarkers: true }]);
    const nextValue = editor.getValue();
    setSql(nextValue);
    editor.focus();
  };

  const formatSql = () => {
    try {
      const formatted = formatSqlText(sql, {
        language: 'trino',
        keywordCase: 'upper',
        indentStyle: 'standard',
        tabWidth: 2,
        linesBetweenQueries: 2,
      });
      setSql(formatted);
      editorRef.current?.setValue(formatted);
    } catch (e) {
      message.warning(e instanceof Error ? `SQL 格式化失败：${e.message}` : 'SQL 格式化失败');
    }
  };

  const executeSql = (nextSql = sql) => {
    setLoading(true);
    setResult(undefined);
    setQueryError(undefined);
    setQueryErrorCode(undefined);
    setSubmittedApprovalId(undefined);
    setCancelling(false);
    SqlWorkbenchAPI.estimate({ sql: nextSql, engine, resourceGroup })
      .then((estimate) => {
        setEstimateMessage(estimate.routeReason ? `${estimate.message}；${estimate.routeReason}` : estimate.message);
        if (estimate.thresholdExceeded) {
          const confirmed = window.confirm(`${estimate.message}\n\n确认继续执行这个大查询？`);
          if (!confirmed) throw new Error('已取消大查询执行');
          return true;
        }
        return false;
      })
      .then((confirmLargeQuery) => SqlWorkbenchAPI.submit({ sql: nextSql, engine, resourceGroup, confirmLargeQuery, maxRows: rowLimit }))
      .then((data) => {
        setResult(data);
        setCurrentQueryId(data.historyId);
        message.loading({ content: 'SQL 查询已提交，正在执行', key: 'sql-running', duration: 1.5 });
      })
      .catch((e: Error) => {
        setCurrentQueryId(undefined);
        setResult(undefined);
        const code = e instanceof BizError ? e.code?.toString() : undefined;
        setQueryError(e.message || 'SQL 执行失败');
        if (code) setQueryErrorCode(code);
        showQueryError(e.message || 'SQL 执行失败', code);
        setLoading(false);
        loadHistory();
      });
  };

  const cancelCurrentQuery = () => {
    if (!currentQueryId) return;
    setCancelling(true);
    SqlWorkbenchAPI.cancel(currentQueryId)
      .then((data) => {
        setResult(data);
        setCurrentQueryId(undefined);
        setLoading(false);
        setCancelling(false);
        message.info('SQL 查询已取消');
        loadHistory();
      })
      .catch((e) => {
        setCancelling(false);
        message.error(e.message || '取消查询失败');
      });
  };

  const openApiWizard = (nextSql = sql) => {
    navigate('/dataservice/apis/new', {
      state: {
        from: 'sql-workbench',
        sql: nextSql,
        sourceFqn: selectedAssetFqn,
        columns: result?.columns || [],
      },
    });
  };

  const exportCurrentResult = async (format: 'csv' | 'tsv') => {
    if (!sql.trim()) {
      message.warning('请先输入 SQL');
      return;
    }
    if (exporting) {
      message.warning('已有导出进行中，请先取消或等待完成');
      return;
    }

    setExporting(true);
    setExportHistoryId(undefined);
    const abortController = new AbortController();
    abortRef.current = abortController;
    const stopLoading = message.loading(`正在导出 ${format.toUpperCase()}...`, 0);

    try {
      const token = await getValidAccessToken();
      const traceId = Math.random().toString(36).slice(2, 12);
      const response = await fetch(`/api/v1/lakehouse/sql/export?format=${format}`, {
        method: 'POST',
        signal: abortController.signal,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          'X-Trace-Id': traceId,
          Accept: format === 'csv' ? 'text/csv' : 'text/tab-separated-values',
        },
        body: JSON.stringify({ sql, engine, resourceGroup }),
      });

      if (!response.ok) {
        const text = await response.text().catch(() => '');
        let msg = `导出失败 (HTTP ${response.status})`;
        try {
          const parsed = JSON.parse(text);
          if (parsed && typeof parsed.message === 'string') msg = parsed.message;
        } catch {
          if (text.trim()) msg = text.slice(0, 500);
        }
        throw new BizError(msg, undefined);
      }

      const historyId = response.headers.get('X-Onelake-History-Id');
      if (historyId) setExportHistoryId(historyId);
      const truncated = response.headers.get('X-Onelake-Export-Truncated') === 'true';

      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      a.download = `onelake-query-${ts}.${format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      if (truncated) {
        message.warning(`已截断：仅导出前 ${EXPORT_MAX_ROWS_HINT} 行（受 SQL_WORKBENCH_EXPORT_MAX_ROWS 限制）`);
      } else {
        message.success(`导出完成${historyId ? `（历史 ${historyId}）` : ''}`);
      }
    } catch (e) {
      const err = e as Error;
      if (err.name === 'AbortError') {
        message.info('导出已取消');
      } else {
        message.error(err.message || '导出失败');
      }
    } finally {
      setExporting(false);
      setExportHistoryId(undefined);
      abortRef.current = null;
      stopLoading();
    }
  };

  const cancelExport = () => {
    // 双通道取消：
    // 1. 前端 AbortController 立即中断 HTTP 连接
    // 2. 后端 /queries/{id}/cancel 调用 Statement.cancel() 终止 Trino SQL
    if (exportHistoryId) {
      SqlWorkbenchAPI.cancel(exportHistoryId)
        .then(() => message.info('已请求后台取消 SQL 执行'))
        .catch((e: Error) => message.error(e.message || '取消失败'));
    } else {
      message.info('正在终止导出…');
    }
    abortRef.current?.abort();
  };

  const saveCurrentQuery = () => {
    const name = window.prompt('保存查询名称', '未命名查询');
    if (!name) return;
    SqlWorkbenchAPI.saveQuery({ name, sql, shared: false })
      .then((saved) => {
        message.success(`已保存查询：${saved.name}`);
        loadSavedQueries();
      })
      .catch((e) => message.error(e.message || '保存查询失败'));
  };

  const saveCurrentAsTemplate = () => {
    if (!sql.trim()) {
      message.warning('请先输入 SQL');
      return;
    }
    const name = window.prompt('模板名称', `模板-${new Date().toISOString().slice(0, 10)}`);
    if (!name) return;
    SqlWorkbenchAPI.createTemplate({ name, sqlTemplate: sql, placeholders: [], shared: false })
      .then((tpl) => {
        message.success(`已保存模板：${tpl.name}`);
        loadTemplates();
      })
      .catch((e) => message.error(e.message || '保存模板失败'));
  };

  const insertTemplateSql = (tpl: QueryTemplate) => {
    setSql(tpl.sqlTemplate);
    editorRef.current?.setValue(tpl.sqlTemplate);
    setSelectedAssetFqn(undefined);
    message.info(`已载入模板：${tpl.name}${tpl.placeholders?.length ? `（含 ${tpl.placeholders.length} 个占位符，点击「使用」可填值）` : ''}`);
  };

  const openRenderDialog = (tpl: QueryTemplate) => {
    setRenderTarget(tpl);
    const initial: Record<string, string> = {};
    tpl.placeholders?.forEach((p) => {
      if (p.defaultValue) initial[p.name] = p.defaultValue;
    });
    renderForm.setFieldsValue(initial);
  };

  const submitRender = () => {
    if (!renderTarget) return;
    renderForm.validateFields()
      .then((values) => {
        setRendering(true);
        return SqlWorkbenchAPI.renderTemplate(renderTarget.id, values)
          .then((result) => {
            setSql(result.sql);
            editorRef.current?.setValue(result.sql);
            setSelectedAssetFqn(undefined);
            message.success(`已渲染模板（替换 ${result.replacedCount} 个占位符）`);
            setRenderTarget(null);
            renderForm.resetFields();
          });
      })
      .catch((e) => {
        if (e?.errorFields) return; // form validation error
        message.error(e?.message || '渲染失败');
      })
      .finally(() => setRendering(false));
  };

  const deleteTemplate = (tpl: QueryTemplate) => {
    SqlWorkbenchAPI.deleteTemplate(tpl.id)
      .then(() => {
        message.success(`已删除模板：${tpl.name}`);
        loadTemplates();
      })
      .catch((e) => message.error(e.message || '删除模板失败'));
  };

  const updateSavedQuery = (query: SavedQuery) => {
    SqlWorkbenchAPI.updateSavedQuery(query.id, { name: query.name, sql, shared: query.shared })
      .then((saved) => {
        message.success(`已更新查询：${saved.name}`);
        loadSavedQueries();
      })
      .catch((e) => message.error(e.message || '更新查询失败'));
  };

  const toggleSavedQueryShare = (query: SavedQuery) => {
    SqlWorkbenchAPI.updateSavedQuery(query.id, { name: query.name, sql: query.sql, shared: !query.shared })
      .then((saved) => {
        message.success(saved.shared ? '已设为团队共享' : '已设为私有');
        loadSavedQueries();
      })
      .catch((e) => message.error(e.message || '共享设置失败'));
  };

  const deleteSavedQuery = (query: SavedQuery) => {
    SqlWorkbenchAPI.deleteSavedQuery(query.id)
      .then(() => {
        message.success(`已删除查询：${query.name}`);
        loadSavedQueries();
      })
      .catch((e) => message.error(e.message || '删除查询失败'));
  };

  const createPipelineDraft = () => {
    const sqlText = sql.trim();
    const name = `sql_workbench_${Date.now()}`;
    const definition = {
      source: 'sql-workbench',
      nodes: [
        {
          id: 'sql-1',
          type: 'SQL',
          name: selectedAssetFqn ? `SQL: ${selectedAssetFqn}` : 'SQL 工作台查询',
          sql: sqlText,
          engine: 'TRINO',
          columns: result?.columns || [],
        },
      ],
      edges: [],
    };
    OrchestrationAPI.createDag({
      name,
      dagsterJob: 'sql_workbench_draft',
      definition,
      enabled: false,
    })
      .then((dag) => {
        message.success('已生成 SQL 流水线草稿');
        navigate(`/orchestration/pipelines/${dag.id}`, { state: { from: 'sql-workbench', dag, sql: sqlText } });
      })
      .catch((e) => message.error(e.message || '创建流水线草稿失败'));
  };

  executeSqlRef.current = executeSql;
  saveSqlRef.current = saveCurrentQuery;
  exportRef.current = exportCurrentResult;

  const resultColumns = result?.columns.map((column) => {
    const isMasked = Boolean(result.maskedColumns?.some((name) => name.toLowerCase() === column.name.toLowerCase()));
    return {
      title: (
        <Space size={6} style={{ minWidth: 0, maxWidth: '100%' }}>
          <span className="ol-truncate" title={column.name}>{column.name}</span>
          {isMasked && (
            <Tooltip title="该列已由后端按 Catalog 密级与 Security 脱敏策略处理">
              <Tag color="warning" style={{ margin: 0 }}>策略</Tag>
            </Tooltip>
          )}
        </Space>
      ),
      dataIndex: column.name,
      width: RESULT_COLUMN_WIDTH,
      ellipsis: true,
      render: (value: unknown) => {
        const text = cellText(value);
        return <span className="mono ol-truncate" title={text} style={{ display: 'block' }}>{text}</span>;
      },
    };
  }) || [];
  const resultTableScrollX = Math.max(RESULT_TABLE_MIN_WIDTH, resultColumns.length * RESULT_COLUMN_WIDTH);

  const resultRows = result?.rows.map((row, index) => ({ key: index, ...row })) || [];
  const protectedResult = hasProtectedResult(result);
  const queryErrorView = queryError ? formatSqlError(queryError, queryErrorCode) : undefined;
  const deniedAssetFqn = selectedAssetFqn || deniedAssetFromError(queryError);
  const canApplyAccess = Boolean(queryErrorView && deniedAssetFqn && (queryErrorCode === '40340' || queryError?.includes('无权查询资产')));

  const openAccessRequest = () => {
    if (!deniedAssetFqn) return;
    setAccessTargetFqn(deniedAssetFqn);
    setAccessModalOpen(true);
  };

  const loadMyApprovals = (page = myApprovalsPage, size = myApprovalsSize, status = myApprovalsStatus) => {
    setMyApprovalsLoading(true);
    SecurityAPI.myApprovals({
      status: status === 'ALL' ? undefined : status,
      page,
      size,
    })
      .then((data) => {
        setMyApprovals(data.content);
        setMyApprovalsPage(data.number);
        setMyApprovalsSize(data.size);
        setMyApprovalsTotal(data.totalElements);
      })
      .catch((e) => {
        setMyApprovals([]);
        setMyApprovalsTotal(0);
        message.error(e?.message || '我的申请加载失败');
      })
      .finally(() => setMyApprovalsLoading(false));
  };

  const loadMyGrants = () => {
    setMyGrantsLoading(true);
    SecurityAPI.myGrants()
      .then((items) => setMyGrants(items))
      .catch((e) => {
        setMyGrants([]);
        message.error(e?.message || '我的授权加载失败');
      })
      .finally(() => setMyGrantsLoading(false));
  };

  const openMyApprovals = () => {
    setMyApprovalsOpen(true);
    loadMyGrants();
    loadMyApprovals(0, myApprovalsSize, myApprovalsStatus);
  };

  const cancelMyApproval = (approval: ApprovalRequest) => {
    setMyApprovalsLoading(true);
    SecurityAPI.cancelApproval(approval.id, 'applicant-cancel')
      .then(() => {
        if (submittedApprovalId === approval.id) {
          setSubmittedApprovalId(undefined);
        }
        message.success('已撤回访问申请');
        loadMyGrants();
        loadMyApprovals(0, myApprovalsSize, myApprovalsStatus);
      })
      .catch((e) => {
        message.error(e?.message || '撤回申请失败');
        setMyApprovalsLoading(false);
      });
  };

  const submitAccessRequest = () => {
    accessForm.validateFields()
      .then((values) => {
        const assetFqn = String(values.assetFqn || deniedAssetFqn);
        setAccessApplying(true);
        return SecurityAPI.applyAccess(assetFqn, {
          reason: values.reason,
          source: 'SQL_WORKBENCH',
          sourcePath: '/lakehouse/sql',
          sql,
          permissions: {
            query: true,
            download: Boolean(values.download),
            api: Boolean(values.api),
          },
          durationDays: Number(values.durationDays || 30),
        });
      })
      .then((approval) => {
        setSubmittedApprovalId(approval.id);
        setAccessModalOpen(false);
        loadMyGrants();
        loadMyApprovals(0, myApprovalsSize, myApprovalsStatus);
        message.success(`访问申请已提交或已存在：${approval.id}`);
      })
      .catch((e) => {
        if (e?.errorFields) return;
        message.error(e?.message || '访问申请提交失败');
      })
      .finally(() => setAccessApplying(false));
  };

  const tabs = [
    { key: 'result', label: '结果', children: (
      <div
        style={{
          border: '1px solid var(--ol-line-soft)',
          borderRadius: 8,
          overflow: 'hidden',
          background: 'var(--ol-card)',
        }}
      >
        <div
          style={{
            padding: '10px 12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            borderBottom: '1px solid var(--ol-line-soft)',
            background: 'var(--ol-fill-soft)',
          }}
        >
          <Space size={8} wrap>
            {[
              { label: '耗时', value: formatDuration(result?.durationMs), intent: 'info' },
              { label: '扫描', value: formatBytes(result?.scanBytes), intent: 'neutral' },
              { label: '行数', value: result?.rowCount == null ? '-' : result.rowCount.toLocaleString(), intent: 'neutral' },
            ].map((m) => (
              <span
                key={m.label}
                style={{
                  display: 'inline-flex',
                  alignItems: 'baseline',
                  gap: 6,
                  padding: '3px 8px',
                  borderRadius: 6,
                  border: `1px solid ${m.intent === 'info' ? 'var(--ol-brand-border)' : 'var(--ol-line-soft)'}`,
                  background: m.intent === 'info' ? 'var(--ol-info-soft)' : 'var(--ol-card)',
                  color: m.intent === 'info' ? 'var(--ol-info)' : 'var(--ol-ink-2)',
                  fontSize: 12,
                  lineHeight: '18px',
                  fontWeight: 500,
                }}
              >
                <span style={{ color: 'var(--ol-ink-3)' }}>{m.label}</span>
                <span className="tnum" style={{ color: 'inherit', fontWeight: 600 }}>{m.value}</span>
              </span>
            ))}
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {result ? `预览 ${result.rows.length} 行${result.truncated ? '（已截断）' : ''}` : '尚未运行'}
          </Text>
        </div>

        {protectedResult && (
          <Alert
            type="info"
            showIcon
            style={{ margin: 12, borderRadius: 6 }}
            message={<span style={{ fontSize: 12 }}>{result?.securityNotices?.join('；') || '部分字段已按 Catalog 密级与 Security 脱敏策略处理，预览结果不代表源表明文。'}</span>}
          />
        )}

        <Table
          size="middle"
          pagination={false}
          loading={loading}
          dataSource={resultRows}
          columns={resultColumns}
          scroll={{ x: resultTableScrollX }}
          tableLayout="fixed"
          locale={{
            emptyText: queryErrorView ? (
              <div style={{ padding: '28px 16px', textAlign: 'center' }}>
                <div style={{ color: 'var(--ol-error)', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                  {queryErrorView.title}
                </div>
                <div style={{ color: 'var(--ol-ink-2)', fontSize: 12, lineHeight: 1.6 }}>
                  {queryErrorView.message}
                </div>
                {queryErrorView.hint && (
                  <div style={{ color: 'var(--ol-ink-3)', fontSize: 12, lineHeight: 1.6, marginTop: 6 }}>
                    {queryErrorView.hint}
                  </div>
                )}
                {submittedApprovalId && (
                  <div style={{ marginTop: 10, color: 'var(--ol-info)', fontSize: 12 }}>
                    <div>
                      访问申请已提交，审批单号：<span className="mono">{submittedApprovalId}</span>
                    </div>
                    <Button size="small" style={{ marginTop: 10 }} onClick={openMyApprovals}>
                      查看我的权限
                    </Button>
                  </div>
                )}
                {canApplyAccess && !submittedApprovalId && (
                  <Button size="small" type="primary" style={{ marginTop: 12 }} onClick={openAccessRequest}>
                    申请访问
                  </Button>
                )}
              </div>
            ) : '暂无查询结果',
          }}
        />

        <div
          style={{
            padding: '10px 12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            borderTop: '1px solid var(--ol-line-soft)',
            background: 'var(--ol-fill-soft)',
          }}
        >
          <Text type="secondary" style={{ fontSize: 12 }}>结果可保存为模型、API 或流水线节点</Text>
          <Space size={8}>
            <Dropdown menu={{ items: [
              { key: 'csv', label: '导出 CSV', onClick: () => exportCurrentResult('csv'), disabled: exporting || !result || result.status !== 'SUCCEEDED' },
              { key: 'tsv', label: '导出 TSV', onClick: () => exportCurrentResult('tsv'), disabled: exporting || !result || result.status !== 'SUCCEEDED' },
            ] }} disabled={!result || result.status !== 'SUCCEEDED' || exporting}>
              <Button size="small" icon={<DownloadOutlined />} loading={exporting} disabled={!result || result.status !== 'SUCCEEDED' || exporting}>导出结果</Button>
            </Dropdown>
            <Button size="small" icon={<SaveOutlined />} onClick={() => message.success('已另存为模型')}>另存为模型</Button>
            <Button size="small" icon={<ApiOutlined />} onClick={() => openApiWizard()}>发布为 API</Button>
            <Button size="small" icon={<ClusterOutlined />} onClick={createPipelineDraft}>加入流水线</Button>
          </Space>
        </div>
      </div>
    ) },
    { key: 'chart', label: '图表', children: <ChartPanel result={result} /> },
    { key: 'history', label: '查询历史', children: (
      <Table size="middle" rowKey="id" dataSource={history}
        columns={[
          { title: '时间', dataIndex: 'at', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{new Date(t).toLocaleString()}</span> },
          { title: '运行人', dataIndex: 'runner' },
          { title: '扫描量', dataIndex: 'scanBytes', render: (v?: number) => <span className="mono">{formatBytes(v)}</span> },
          { title: '耗时', dataIndex: 'durationMs', render: (d?: number) => <span className="mono">{formatDuration(d)}</span> },
          { title: '状态', dataIndex: 'ok', render: (o: boolean) => (
            <span style={{
              padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
              background: o ? 'var(--ol-success-soft)' : 'var(--ol-error-soft)',
              color: o ? 'var(--ol-success)' : 'var(--ol-error)',
            }}>{o ? '✓ 成功' : '✗ 失败'}</span>
          ) },
          { title: '失败原因', dataIndex: 'error', ellipsis: true, render: (error?: string) => error ? <Tooltip title={error}><Text type="danger" style={{ fontSize: 12 }}>{error}</Text></Tooltip> : '-' },
          { title: 'SQL', dataIndex: 'sql', ellipsis: true, render: (s: string) => <Text code style={{ fontSize: 11 }}>{s}</Text> },
          { title: '操作', render: (_: unknown, row: SqlQueryHistory) => <Space><a className="ol-link" onClick={() => { setSql(row.sql); executeSql(row.sql); }}>重新运行</a><a className="ol-link" onClick={() => openApiWizard(row.sql)}>发布为 API</a></Space> },
        ]} />
    ) },
    { key: 'saved', label: '保存的查询', children: (
      <Table size="middle" rowKey="id" dataSource={savedQueries}
        columns={[
          { title: '名称', dataIndex: 'name', render: (n: string, row: SavedQuery) => (
            <Space size={6}>
              <Text strong style={{ fontSize: 13 }}>{n}</Text>
              {isSavedQueryOwner(row) ? <Tag color="success" style={{ margin: 0, fontSize: 10 }}>我的</Tag> : <Tag color="processing" style={{ margin: 0, fontSize: 10 }}>共享给我</Tag>}
            </Space>
          ) },
          { title: '负责人', dataIndex: 'owner' },
          { title: '共享', dataIndex: 'shared', render: (s: boolean) => (
            <Tag color={s ? 'processing' : 'default'} style={{ margin: 0 }}>{s ? '团队共享' : '私有'}</Tag>
          ) },
          { title: '操作', render: (_: unknown, row: SavedQuery) => (
            <Space>
              <a className="ol-link" onClick={() => { setSql(row.sql); executeSql(row.sql); }}>重新运行</a>
              <a className="ol-link" onClick={() => { setSql(row.sql); editorRef.current?.setValue(row.sql); }}>载入</a>
              {isSavedQueryOwner(row) && (
                <>
                  <a className="ol-link" onClick={() => updateSavedQuery(row)}>更新</a>
                  <a className="ol-link" onClick={() => toggleSavedQueryShare(row)}>{row.shared ? '取消共享' : '共享'}</a>
                  <Popconfirm title="删除这个保存查询？" okText="删除" cancelText="取消" onConfirm={() => deleteSavedQuery(row)}>
                    <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </>
              )}
            </Space>
          ) },
        ]} />
    ) },
  ];

  return (
    <div className="ol-page ol-sql-workbench-page">
      <PageHeader
        icon={<CodeOutlined />}
        title="SQL 工作台"
        subtitle={<span className="ol-chip">湖仓 · L2-4</span>}
        description="Trino 交互式查询，支持表树、SQL 编辑、结果图表、查询历史"
        actions={
          <>
            <Select value={engine} onChange={setEngine} options={[
              { label: '自动路由 → Trino', value: 'auto' },
            ]} style={{ width: 180 }} />
            <Select value={resourceGroup} onChange={setResourceGroup} options={[
              { label: '资源组: 默认', value: 'rg-default' },
              { label: '资源组: 大查询', value: 'rg-big' },
            ]} style={{ width: 160 }} />
            <Tooltip title="预览行数上限（交互查询硬限 2000，更多请用导出）">
              <Select<number> value={rowLimit} onChange={setRowLimit} options={ROW_LIMIT_OPTIONS.map((v) => ({ label: `${v} 行`, value: v }))} style={{ width: 100 }} />
            </Tooltip>
          </>
        }
      />

      <Row gutter={16} className="ol-sql-workbench-layout">
        <Col xs={24} lg={5} className="ol-sql-workbench-sidebar-col">
          <div className="ol-sql-workbench-sidebar-shell">
            <SectionCard
              title={
                <Segmented
                  size="small"
                  value={leftView}
                  onChange={(v) => setLeftView(v as 'assets' | 'templates')}
                  options={[
                    { label: '表树', value: 'assets', icon: <DatabaseOutlined /> },
                    { label: '模板', value: 'templates', icon: <FileTextOutlined /> },
                  ]}
                />
              }
              icon={leftView === 'assets' ? <DatabaseOutlined /> : <FileTextOutlined />}
              padded="none"
              bodyStyle={{ minHeight: 0, display: 'flex', flexDirection: 'column' }}
            >
              {leftView === 'assets' && (
                <div className="ol-sql-workbench-left-panel">
                  <div className="ol-sql-workbench-left-tools">
                    <Input.Search
                      size="small"
                      allowClear
                      placeholder="搜索表名 / 字段 / 分级"
                      value={treeSearch}
                      onChange={(e) => setTreeSearch(e.target.value)}
                    />
                    <div className="ol-sql-workbench-left-meta">
                      <span>{treeSearch.trim() ? `匹配 ${filteredAssets.length} / ${assets.length} 张表` : `共 ${assets.length} 张表`}</span>
                      <span>{filteredAssetColumnCount} 个字段</span>
                    </div>
                    {assetError && (
                      <Alert
                        type="error"
                        showIcon
                        style={{ marginTop: 8, borderRadius: 6 }}
                        message={<span style={{ fontSize: 12 }}>{assetError}</span>}
                        action={<Button size="small" onClick={() => loadAssets()}>重试</Button>}
                      />
                    )}
                    {!assetError && !assetLoading && assets.length === 0 && (
                      <Alert
                        type="info"
                        showIcon
                        style={{ marginTop: 8, borderRadius: 6 }}
                        message={<span style={{ fontSize: 12 }}>Catalog 暂无可查询表</span>}
                      />
                    )}
                    {!assetError && assetLoading && (
                      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--ol-ink-3)' }}>正在加载 Catalog 资产...</div>
                    )}
                    {!assetLoading && !assetError && treeSearch.trim() && filteredAssets.length === 0 && (
                      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--ol-ink-3)' }}>
                        未匹配到表或字段，请调整关键字「{treeSearch}」
                      </div>
                    )}
                  </div>
                  <div className="ol-sql-workbench-left-scroll" aria-label="Catalog 表树">
                    <Tree
                      className="ol-asset-tree ol-sql-workbench-tree"
                      blockNode
                      expandedKeys={expandedTreeKeys}
                      selectedKeys={selectedTreeKeys}
                      autoExpandParent={Boolean(treeSearch.trim())}
                      onExpand={(keys) => {
                        setExpandedTreeKeys(keys.map(String));
                      }}
                      style={{ fontSize: 13, minWidth: 0 }}
                      onSelect={(_, info) => {
                        const key = String(info.node.key);
                        if (key.includes(':')) {
                          const [assetId, columnName] = key.split(':');
                          const asset = assets.find((a) => a.id === assetId);
                          if (asset) {
                            setSelectedAssetFqn(asset.fqn);
                            insertSqlText(columnName);
                          }
                          return;
                        }
                        const asset = assets.find((a) => a.id === key);
                        if (asset) {
                          loadAssetPreviewSql(asset);
                        }
                      }}
                      treeData={filteredAssets.map((a) => ({
                        title: <TableTreeTitle fqn={a.fqn} />,
                        key: a.id,
                        children: (a.columns || []).map((column) => ({
                          title: <FieldTreeTitle name={column.name} type={column.type} classification={column.classification || column.suggestLevel} />,
                          key: `${a.id}:${column.name}`,
                          isLeaf: true,
                        })),
                      }))}
                    />
                  </div>
                  {selectedAsset && (
                    <div className="ol-sql-workbench-left-footer">
                      <div className="ol-sql-workbench-selected-asset">
                        <Text type="secondary" style={{ fontSize: 11 }}>已选</Text>
                        <span className="mono ol-truncate" title={selectedAsset.fqn}>{selectedAsset.fqn}</span>
                      </div>
                      <Space size={4}>
                        <Tooltip title="插入表名到当前光标">
                          <Button size="small" icon={<CodeOutlined />} onClick={() => insertSqlText(selectedAsset.fqn)} />
                        </Tooltip>
                        <Tooltip title="载入 SELECT 预览 SQL">
                          <Button size="small" icon={<FileTextOutlined />} onClick={() => loadAssetPreviewSql(selectedAsset)} />
                        </Tooltip>
                      </Space>
                    </div>
                  )}
                </div>
              )}

              {leftView === 'templates' && (
                <div className="ol-sql-workbench-left-panel">
                  <Space className="ol-sql-workbench-left-tools" style={{ width: '100%' }} direction="vertical" size={6}>
                    <Input.Search
                      size="small"
                      allowClear
                      placeholder="搜索模板名称 / 分类"
                      value={templateSearch}
                      onChange={(e) => setTemplateSearch(e.target.value)}
                    />
                    <Button size="small" type="dashed" block icon={<PlusOutlined />} onClick={saveCurrentAsTemplate}>
                      将当前 SQL 存为模板
                    </Button>
                  </Space>
                  <div className="ol-sql-workbench-left-scroll">
                    {filteredTemplates.length === 0 && (
                      <div style={{ marginBottom: 10, fontSize: 12, color: 'var(--ol-ink-3)' }}>
                        {templates.length === 0 ? '暂无模板。点击上方按钮把当前 SQL 沉淀为团队模板。' : `未匹配到模板「${templateSearch}」`}
                      </div>
                    )}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      {filteredTemplates.map((tpl) => (
                        <div
                          key={tpl.id}
                          style={{
                            padding: 8,
                            borderRadius: 6,
                            border: '1px solid var(--ol-line-soft)',
                            background: 'var(--ol-card)',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                            <FileTextOutlined style={{ color: 'var(--ol-brand)', fontSize: 12 }} />
                            <span style={{ fontSize: 13, fontWeight: 600, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {tpl.name}
                            </span>
                            {isTemplateOwner(tpl)
                              ? <Tag color="success" style={{ margin: 0, fontSize: 10 }}>我的</Tag>
                              : <Tag color="processing" style={{ margin: 0, fontSize: 10 }}>共享给我</Tag>}
                            {tpl.shared && <Tag color="processing" style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>共享</Tag>}
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--ol-ink-3)', marginBottom: 6 }}>
                            {tpl.category && <Tag style={{ margin: 0, fontSize: 10 }}>{tpl.category}</Tag>}
                            {tpl.placeholders?.length > 0 && (
                              <Tooltip title={tpl.placeholders.map((p) => `${p.name}:${p.type}`).join(' · ')}>
                                <Tag color="warning" style={{ margin: 0, fontSize: 10 }}>{tpl.placeholders.length} 个占位符</Tag>
                              </Tooltip>
                            )}
                            {tpl.ownerName && <span>{tpl.ownerName}</span>}
                          </div>
                          <Space size={4} wrap>
                            {tpl.placeholders?.length > 0 ? (
                              <Button size="small" type="primary" icon={<ThunderboltOutlined />} onClick={() => openRenderDialog(tpl)}>使用</Button>
                            ) : (
                              <Button size="small" type="primary" onClick={() => insertTemplateSql(tpl)}>载入</Button>
                            )}
                            <Tooltip title="直接插入到编辑器（不替换占位符）">
                              <Button size="small" onClick={() => insertTemplateSql(tpl)}>插入</Button>
                            </Tooltip>
                            {isTemplateOwner(tpl) && (
                              <Popconfirm title="删除这个模板？" okText="删除" cancelText="取消" onConfirm={() => deleteTemplate(tpl)}>
                                <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                              </Popconfirm>
                            )}
                          </Space>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              )}
            </SectionCard>
          </div>
        </Col>
        <Col xs={24} lg={19} className="ol-sql-workbench-main">
          <SectionCard
            style={{ flex: '0 0 auto' }}
            title={<Space><PlayCircleOutlined style={{ color: 'var(--ol-success)' }} /> SQL 编辑器</Space>}
            icon={<CodeOutlined />}
            subtitle="Monaco Editor"
            extra={
              <Space>
                <Tooltip title="格式化"><Button size="small" icon={<FormatPainterOutlined />} onClick={formatSql} /></Tooltip>
                <Tooltip title="保存查询 (Ctrl/Cmd+S)"><Button size="small" icon={<SaveOutlined />} onClick={saveCurrentQuery} /></Tooltip>
                <Tooltip title="保存为模板"><Button size="small" icon={<FileTextOutlined />} onClick={saveCurrentAsTemplate} disabled={!sql.trim()} /></Tooltip>
                <Tooltip title="查看我的授权和访问申请">
                  <Button size="small" icon={<FileTextOutlined />} onClick={openMyApprovals}>我的权限</Button>
                </Tooltip>
                <Dropdown menu={{ items: [
                  { key: 'csv', label: '导出为 CSV', onClick: () => exportCurrentResult('csv'), disabled: exporting },
                  { key: 'tsv', label: '导出为 TSV', onClick: () => exportCurrentResult('tsv'), disabled: exporting },
                ] }}>
                  <Button size="small" icon={<DownloadOutlined />} loading={exporting} disabled={!sql.trim() || exporting}>导出</Button>
                </Dropdown>
                {exporting && (
                  <Tooltip title={exportHistoryId ? `查询 ID: ${exportHistoryId}（点击立即终止）` : '正在初始化，点击可中止 HTTP 连接'}>
                    <Button size="small" icon={<StopOutlined />} danger onClick={cancelExport}>取消导出</Button>
                  </Tooltip>
                )}
                {currentQueryId && (
                  <Button size="small" icon={<StopOutlined />} loading={cancelling} onClick={cancelCurrentQuery}>取消</Button>
                )}
                <Tooltip title="运行 (Ctrl/Cmd+Enter)">
                  <Button type="primary" size="small" icon={<PlayCircleOutlined />} loading={loading} onClick={() => executeSql()}>运行</Button>
                </Tooltip>
              </Space>
            }
            padded="sm"
          >
            <div style={{ height: 220, border: '1px solid var(--ol-line-soft)', borderRadius: 6, overflow: 'hidden' }}>
              <Editor
                defaultLanguage="sql"
                value={sql}
                onChange={(v) => setSql(v || '')}
                onMount={(editor, monaco) => {
                  editorRef.current = editor;
                  monacoRef.current = monaco;
                  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
                    executeSqlRef.current();
                  });
                  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
                    saveSqlRef.current();
                  });
                  setEditorReady(true);
                }}
                theme="vs"
              />
            </div>
            <div
              style={{
                marginTop: 8,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                color: queryError ? 'var(--ol-error)' : 'var(--ol-ink-3)',
                fontSize: 12,
                lineHeight: '18px',
              }}
            >
              <span className="ol-truncate">
                {queryError ? '上次运行失败，详情见结果区与查询历史' : estimateMessage}
              </span>
              {queryErrorCode && <Text type="secondary" className="mono" style={{ fontSize: 11 }}>code {queryErrorCode}</Text>}
            </div>
          </SectionCard>

          <SectionCard
            style={{ marginTop: 12, flex: '1 1 auto', minHeight: 0, overflow: 'visible' }}
            padded="none"
            bodyStyle={{ padding: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'visible' }}
          >
            <div className="ol-sql-workbench-result-tabs-shell">
              <Tabs className="ol-sql-workbench-result-tabs" items={tabs} tabBarStyle={{ marginBottom: 12 }} />
            </div>
          </SectionCard>
        </Col>
      </Row>

      <Modal
        open={accessModalOpen}
        title="申请资产访问"
        okText="提交申请"
        cancelText="取消"
        confirmLoading={accessApplying}
        onOk={submitAccessRequest}
        onCancel={() => setAccessModalOpen(false)}
        destroyOnHidden
        afterOpenChange={(open) => {
          if (open && accessTargetFqn) {
            accessForm.setFieldsValue({
              assetFqn: accessTargetFqn,
              reason: 'SQL 工作台查询分析',
              durationDays: 30,
              download: false,
              api: false,
            });
          }
        }}
      >
        <Form form={accessForm} layout="vertical" preserve={false}>
          <Form.Item name="assetFqn" label="申请资产">
            <Input disabled />
          </Form.Item>
          <Form.Item
            name="reason"
            label="用途说明"
            rules={[{ required: true, message: '请填写申请用途' }]}
          >
            <Input.TextArea rows={3} placeholder="说明查询用途、分析场景或业务背景" />
          </Form.Item>
          <Form.Item name="durationDays" label="有效期">
            <Select
              options={[
                { label: '7 天', value: 7 },
                { label: '30 天', value: 30 },
                { label: '90 天', value: 90 },
                { label: '长期', value: 0 },
              ]}
            />
          </Form.Item>
          <Form.Item label="权限范围">
            <Space direction="vertical" size={6}>
              <Checkbox checked disabled>查询 query</Checkbox>
              <Form.Item name="download" valuePropName="checked" noStyle>
                <Checkbox>下载 download</Checkbox>
              </Form.Item>
              <Form.Item name="api" valuePropName="checked" noStyle>
                <Checkbox>API 调用 api</Checkbox>
              </Form.Item>
            </Space>
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message={<span style={{ fontSize: 12 }}>审批通过后会立即生成访问授权；若该资产已有待审批申请，将复用原审批单。</span>}
          />
        </Form>
      </Modal>

      <Modal
        open={myApprovalsOpen}
        title="我的权限"
        footer={null}
        width={980}
        onCancel={() => setMyApprovalsOpen(false)}
      >
        <Tabs
          items={[
            {
              key: 'grants',
              label: `我的授权 (${myGrants.length})`,
              children: (
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <Button size="small" icon={<ThunderboltOutlined />} onClick={loadMyGrants} loading={myGrantsLoading}>
                      刷新
                    </Button>
                  </div>
                  <Table
                    size="middle"
                    rowKey="id"
                    loading={myGrantsLoading}
                    dataSource={myGrants}
                    pagination={false}
                    columns={[
                      { title: '资产', dataIndex: 'assetFqn', render: (value: string) => <Text code style={{ fontSize: 12 }}>{value}</Text> },
                      { title: '权限', width: 190, render: (_: unknown, row: AccessGrant) => {
                        const permissions = grantPermissions(row);
                        return (
                          <Space size={6} wrap>
                            {permissions.query && <Tag color="success" style={{ margin: 0 }}>query</Tag>}
                            {permissions.download && <Tag color="processing" style={{ margin: 0 }}>download</Tag>}
                            {permissions.api && <Tag color="purple" style={{ margin: 0 }}>api</Tag>}
                          </Space>
                        );
                      } },
                      { title: '状态', dataIndex: 'status', width: 100, render: (status: AccessGrant['status']) => (
                        <Tag color={status === 'ACTIVE' ? 'success' : status === 'EXPIRED' ? 'warning' : 'default'} style={{ margin: 0 }}>
                          {status}
                        </Tag>
                      ) },
                      { title: '授权时间', dataIndex: 'grantedAt', width: 170, render: (value: string) => <span style={{ fontSize: 12 }}>{formatTime(value)}</span> },
                      { title: '到期时间', dataIndex: 'expiresAt', width: 170, render: (value?: string) => <span style={{ fontSize: 12 }}>{value ? formatTime(value) : '长期'}</span> },
                    ]}
                  />
                </Space>
              ),
            },
            {
              key: 'approvals',
              label: `我的申请 (${myApprovalsTotal})`,
              children: (
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                    <Select
                      size="small"
                      value={myApprovalsStatus}
                      onChange={(value) => setMyApprovalsStatus(value as typeof myApprovalsStatus)}
                      style={{ width: 140 }}
                      options={[
                        { label: '全部状态', value: 'ALL' },
                        { label: '待审批', value: 'PENDING' },
                        { label: '已通过', value: 'APPROVED' },
                        { label: '已驳回', value: 'REJECTED' },
                        { label: '已取消', value: 'CANCELED' },
                      ]}
                    />
                    <Button size="small" icon={<ThunderboltOutlined />} onClick={() => loadMyApprovals(0, myApprovalsSize, myApprovalsStatus)} loading={myApprovalsLoading}>
                      刷新
                    </Button>
                  </div>
                  <Table
                    size="middle"
                    rowKey="id"
                    loading={myApprovalsLoading}
                    dataSource={myApprovals}
                    pagination={{
                      current: myApprovalsPage + 1,
                      pageSize: myApprovalsSize,
                      total: myApprovalsTotal,
                      showSizeChanger: true,
                      onChange: (page, size) => loadMyApprovals(page - 1, size, myApprovalsStatus),
                    }}
                    columns={[
                      { title: '申请对象', dataIndex: 'targetRef', render: (value: string) => <Text code style={{ fontSize: 12 }}>{value}</Text> },
                      { title: '状态', dataIndex: 'status', width: 110, render: (status: ApprovalRequest['status']) => (
                        <Tag
                          color={status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'error' : status === 'PENDING' ? 'processing' : 'default'}
                          style={{ margin: 0 }}
                        >
                          {status}
                        </Tag>
                      ) },
                      { title: '用途说明', ellipsis: true, render: (_: unknown, row: ApprovalRequest) => (
                        <span style={{ color: 'var(--ol-ink-2)', fontSize: 12 }}>
                          {approvalReason((row as { payload?: Record<string, unknown> | string }).payload, row.reason)}
                        </span>
                      ) },
                      { title: '提交时间', dataIndex: 'createdAt', width: 170, render: (value: string) => <span style={{ fontSize: 12 }}>{formatTime(value)}</span> },
                      { title: '决定时间', dataIndex: 'decidedAt', width: 170, render: (value?: string) => <span style={{ fontSize: 12 }}>{formatTime(value)}</span> },
                      { title: '操作', width: 90, render: (_: unknown, row: ApprovalRequest) => (
                        row.status === 'PENDING' ? (
                          <Popconfirm
                            title="撤回这条访问申请？"
                            okText="撤回"
                            cancelText="取消"
                            onConfirm={() => cancelMyApproval(row)}
                          >
                            <Button size="small" danger>撤回</Button>
                          </Popconfirm>
                        ) : '-'
                      ) },
                    ]}
                  />
                </Space>
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        open={!!renderTarget}
        title={renderTarget ? `使用模板：${renderTarget.name}` : ''}
        onCancel={() => { setRenderTarget(null); renderForm.resetFields(); }}
        onOk={submitRender}
        okText="渲染并填入编辑器"
        cancelText="取消"
        confirmLoading={rendering}
        destroyOnHidden
      >
        {renderTarget?.description && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }} message={<span style={{ fontSize: 12 }}>{renderTarget.description}</span>} />
        )}
        {renderTarget && renderTarget.placeholders?.length === 0 && (
          <Alert type="info" showIcon message="该模板没有声明占位符，可直接载入。" />
        )}
        <Form form={renderForm} layout="vertical" preserve={false}>
          {renderTarget?.placeholders?.map((p) => (
            <Form.Item
              key={p.name}
              name={p.name}
              label={
                <Space size={6}>
                  <span style={{ fontFamily: 'monospace' }}>{`{{${p.name}}}`}</span>
                  <Tag style={{ margin: 0, fontSize: 11 }}>{p.type}</Tag>
                  {p.required && <Tag color="error" style={{ margin: 0, fontSize: 11 }}>必填</Tag>}
                </Space>
              }
              tooltip={p.description}
              rules={p.required ? [{ required: true, message: `${p.name} 为必填` }] : []}
            >
              <Input placeholder={p.defaultValue ? `默认：${p.defaultValue}` : `请输入 ${p.type} 类型值`} />
            </Form.Item>
          ))}
        </Form>
      </Modal>
    </div>
  );
}
