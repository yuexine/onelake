/**
 * SQL 工作台（对应原型 §8.3.3 升级版）。
 *   表树 + Monaco SQL 编辑器 + 结果区
 */
import { Row, Col, Tree, Space, Button, Select, Tag, Table, Alert, Tabs, Tooltip, message, Typography } from 'antd';
import {
  PlayCircleOutlined, FormatPainterOutlined, SaveOutlined,
  ApiOutlined, ClusterOutlined, CodeOutlined, DatabaseOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import { PageHeader, SectionCard } from '../../components';
import { CatalogAPI, SqlWorkbenchAPI } from '../../api';
import type { Asset, SavedQuery, SqlExecuteResult, SqlQueryHistory } from '../../types';
import { normalizeCatalogAssets } from './assetAdapter';

const { Text } = Typography;

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

export default function SqlWorkbench() {
  const navigate = useNavigate();
  const [sql, setSql] = useState('SHOW SCHEMAS');
  const [assets, setAssets] = useState<Asset[]>([]);
  const [assetLoading, setAssetLoading] = useState(true);
  const [assetError, setAssetError] = useState<string>();
  const [history, setHistory] = useState<SqlQueryHistory[]>([]);
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>([]);
  const [result, setResult] = useState<SqlExecuteResult>();
  const [engine, setEngine] = useState('auto');
  const [resourceGroup, setResourceGroup] = useState('rg-default');
  const [estimateMessage, setEstimateMessage] = useState('运行前将进行只读校验；扫描量以执行引擎返回为准');
  const [queryError, setQueryError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [currentQueryId, setCurrentQueryId] = useState<string>();
  const [cancelling, setCancelling] = useState(false);
  const [selectedAssetFqn, setSelectedAssetFqn] = useState<string>();

  const loadAssets = () => {
    setAssetLoading(true);
    setAssetError(undefined);
    CatalogAPI.listAssets()
      .then((items) => setAssets(normalizeCatalogAssets(items)))
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

  useEffect(() => {
    loadAssets();
    loadHistory();
    loadSavedQueries();
  }, []);

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
            message.error(data.error || 'SQL 执行失败');
          }
        })
        .catch((e) => {
          setCurrentQueryId(undefined);
          setLoading(false);
          setCancelling(false);
          setQueryError(e.message || '查询状态获取失败');
          message.error(e.message || '查询状态获取失败');
          loadHistory();
        });
    }, 1500);
    return () => window.clearInterval(timer);
  }, [currentQueryId, loading]);

  const executeSql = (nextSql = sql) => {
    setLoading(true);
    setQueryError(undefined);
    setCancelling(false);
    SqlWorkbenchAPI.estimate({ sql: nextSql, engine, resourceGroup })
      .then((estimate) => setEstimateMessage(estimate.message))
      .then(() => SqlWorkbenchAPI.submit({ sql: nextSql, engine, resourceGroup }))
      .then((data) => {
        setResult(data);
        setCurrentQueryId(data.historyId);
        message.loading({ content: 'SQL 查询已提交，正在执行', key: 'sql-running', duration: 1.5 });
      })
      .catch((e) => {
        setCurrentQueryId(undefined);
        setQueryError(e.message || 'SQL 执行失败');
        message.error(e.message || 'SQL 执行失败');
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

  const resultColumns = result?.columns.map((column) => ({
    title: column.name,
    dataIndex: column.name,
    render: (value: unknown) => <span className="mono">{cellText(value)}</span>,
  })) || [];

  const resultRows = result?.rows.map((row, index) => ({ key: index, ...row })) || [];

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

        <Table
          size="middle"
          pagination={false}
          loading={loading}
          dataSource={resultRows}
          columns={resultColumns}
          locale={{ emptyText: queryError || '暂无查询结果' }}
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
            <Button size="small" icon={<SaveOutlined />} onClick={() => message.success('已另存为模型')}>另存为模型</Button>
            <Button size="small" icon={<ApiOutlined />} onClick={() => openApiWizard()}>发布为 API</Button>
            <Button size="small" icon={<ClusterOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>加入流水线</Button>
          </Space>
        </div>
      </div>
    ) },
    { key: 'chart', label: '图表', children: (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--ol-ink-3)' }}>图表区（ECharts 可选）</div>
    ) },
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
          { title: 'SQL', dataIndex: 'sql', ellipsis: true, render: (s: string) => <Text code style={{ fontSize: 11 }}>{s}</Text> },
          { title: '操作', render: (_: unknown, row: SqlQueryHistory) => <Space><a className="ol-link" onClick={() => { setSql(row.sql); executeSql(row.sql); }}>重新运行</a><a className="ol-link" onClick={() => openApiWizard(row.sql)}>发布为 API</a></Space> },
        ]} />
    ) },
    { key: 'saved', label: '保存的查询', children: (
      <Table size="middle" rowKey="id" dataSource={savedQueries}
        columns={[
          { title: '名称', dataIndex: 'name', render: (n: string) => <Text strong style={{ fontSize: 13 }}>{n}</Text> },
          { title: '负责人', dataIndex: 'owner' },
          { title: '共享', dataIndex: 'shared', render: (s: boolean) => (
            <Tag color={s ? 'processing' : 'default'} style={{ margin: 0 }}>{s ? '团队共享' : '私有'}</Tag>
          ) },
          { title: '操作', render: (_: unknown, row: SavedQuery) => <Space><a className="ol-link" onClick={() => { setSql(row.sql); executeSql(row.sql); }}>重新运行</a><a className="ol-link" onClick={() => message.info('共享设置已进入后端保存范围')}>分享</a></Space> },
        ]} />
    ) },
  ];

  return (
    <div className="ol-page">
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
          </>
        }
      />

      <Row gutter={16}>
        <Col xs={24} lg={5}>
          <SectionCard title="表树" icon={<DatabaseOutlined />} padded="sm">
            {assetError && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 10, borderRadius: 6 }}
                message={<span style={{ fontSize: 12 }}>{assetError}</span>}
                action={<Button size="small" onClick={loadAssets}>重试</Button>}
              />
            )}
            {!assetError && !assetLoading && assets.length === 0 && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 10, borderRadius: 6 }}
                message={<span style={{ fontSize: 12 }}>Catalog 暂无可查询表</span>}
              />
            )}
            {assetLoading && (
              <div style={{ marginBottom: 10, fontSize: 12, color: 'var(--ol-ink-3)' }}>正在加载 Catalog 资产...</div>
            )}
            <Tree
              blockNode
              style={{ fontSize: 13 }}
              onSelect={(_, info) => {
                const asset = assets.find((a) => a.id === info.node.key);
                if (asset) {
                  setSelectedAssetFqn(asset.fqn);
                  setSql(`SELECT * FROM ${asset.fqn}\nLIMIT 100`);
                }
              }}
              treeData={assets.map((a) => ({
                title: <TableTreeTitle fqn={a.fqn} />,
                key: a.id,
              }))}
            />
          </SectionCard>
        </Col>
        <Col xs={24} lg={19}>
          <SectionCard
            title={<Space><PlayCircleOutlined style={{ color: 'var(--ol-success)' }} /> SQL 编辑器</Space>}
            icon={<CodeOutlined />}
            subtitle="Monaco Editor"
            extra={
              <Space>
                <Tooltip title="格式化"><Button size="small" icon={<FormatPainterOutlined />} /></Tooltip>
                <Tooltip title="保存查询"><Button size="small" icon={<SaveOutlined />} onClick={saveCurrentQuery} /></Tooltip>
                {currentQueryId && (
                  <Button size="small" icon={<StopOutlined />} loading={cancelling} onClick={cancelCurrentQuery}>取消</Button>
                )}
                <Button type="primary" size="small" icon={<PlayCircleOutlined />} loading={loading} onClick={() => executeSql()}>运行</Button>
              </Space>
            }
            padded="sm"
          >
            <div style={{ height: 220, border: '1px solid var(--ol-line-soft)', borderRadius: 6, overflow: 'hidden' }}>
              <Editor defaultLanguage="sql" value={sql} onChange={(v) => setSql(v || '')} theme="vs" />
            </div>
          </SectionCard>

          <Alert
            type={queryError ? 'error' : 'warning'} showIcon
            style={{ marginTop: 12, borderRadius: 8 }}
            message={<span style={{ fontSize: 13 }}>{queryError || estimateMessage}</span>}
          />

          <SectionCard style={{ marginTop: 12 }} padded="none" bodyStyle={{ padding: 0 }}>
            <div style={{ padding: '0 16px 14px' }}>
              <Tabs items={tabs} tabBarStyle={{ marginBottom: 12 }} />
            </div>
          </SectionCard>
        </Col>
      </Row>
    </div>
  );
}
