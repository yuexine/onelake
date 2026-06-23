/**
 * 表详情（对应原型 §8.3.2 / §8.3.8 / §8.3.10）。
 * Tab: Schema / 快照 / 优化 / 血缘 / 权限
 */
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, App as AntdApp, Table, Tag, Space, Button, Typography } from 'antd';
import { ArrowRightOutlined, BranchesOutlined, CheckCircleOutlined, CodeOutlined, DatabaseOutlined, HistoryOutlined, PlayCircleOutlined, ReloadOutlined, TableOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { DetailPageLayout, ClassificationBadge, SectionCard, StateView } from '../../components';
import type { AssetDetail, AssetMaintenanceAssessment, AssetMaintenanceOperation, DataModel, DwdModelRun } from '../../types';
import { CatalogAPI, ModelingAPI } from '../../api';
import { normalizeCatalogAsset } from './assetAdapter';

const { Text } = Typography;
const TERMINAL_RUN_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

function runStatusColor(status?: string) {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'CANCELLED') return 'error';
  if (status === 'RUNNING') return 'processing';
  if (status === 'QUEUED') return 'warning';
  return 'default';
}

function modelStatusColor(status?: string) {
  if (status === 'VALIDATED' || status === 'PUBLISHED') return 'success';
  if (status === 'DRAFT') return 'warning';
  return 'default';
}

function fmtTime(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

function fmtBytes(value?: number) {
  if (value == null) return '-';
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
}

function fmtCost(value?: number) {
  if (value == null) return '-';
  return value.toFixed(2);
}

function maintenanceColor(status?: string) {
  if (status === 'OK') return 'success';
  if (status === 'CRITICAL') return 'error';
  return 'warning';
}

const operationLabels: Record<AssetMaintenanceOperation, string> = {
  OPTIMIZE: 'Compaction',
  EXPIRE_SNAPSHOTS: '清理快照',
  REMOVE_ORPHAN_FILES: '清理孤儿文件',
};

const allMaintenanceOperations: AssetMaintenanceOperation[] = ['OPTIMIZE', 'EXPIRE_SNAPSHOTS', 'REMOVE_ORPHAN_FILES'];

export default function TableDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { message } = AntdApp.useApp();
  const [searchParams] = useSearchParams();
  const [detail, setDetail] = useState<AssetDetail>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [models, setModels] = useState<DataModel[]>([]);
  const [runsByModel, setRunsByModel] = useState<Record<string, DwdModelRun[]>>({});
  const [modelingLoading, setModelingLoading] = useState(false);
  const [modelingError, setModelingError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [maintenance, setMaintenance] = useState<AssetMaintenanceAssessment>();
  const [maintenanceLoading, setMaintenanceLoading] = useState(false);
  const [maintenanceAction, setMaintenanceAction] = useState<AssetMaintenanceOperation | null>(null);

  const loadAsset = () => {
    if (!id) return;
    setLoading(true);
    setLoadError(null);
    CatalogAPI.getAssetDetail(id)
      .then((item) => setDetail({ ...item, asset: normalizeCatalogAsset(item.asset) }))
      .catch((e) => {
        setDetail(undefined);
        setLoadError(e.message || '表详情加载失败，请检查资产是否存在或稍后重试');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAsset();
  }, [id]);

  const loadMaintenance = () => {
    if (!id) return;
    setMaintenanceLoading(true);
    CatalogAPI.getMaintenance(id)
      .then(setMaintenance)
      .catch(() => setMaintenance(undefined))
      .finally(() => setMaintenanceLoading(false));
  };

  useEffect(() => {
    loadMaintenance();
  }, [id]);

  const loadModeling = (assetFqn: string, layer: string) => {
    if (!assetFqn) return;
    setModelingLoading(true);
    setModelingError(null);
    const params = layer === 'DWD' ? { targetFqn: assetFqn } : { sourceFqn: assetFqn };
    ModelingAPI.listModels(params)
      .then(async (items) => {
        setModels(items);
        const runEntries = await Promise.all(items.map(async (model) => {
          try {
            return [model.id, await ModelingAPI.listModelRuns(model.id)] as const;
          } catch (e) {
            setModelingError(e instanceof Error ? e.message : 'DWD 运行历史加载失败');
            return [model.id, []] as const;
          }
        }));
        setRunsByModel(Object.fromEntries(runEntries));
      })
      .catch((e) => {
        setModels([]);
        setRunsByModel({});
        setModelingError(e.message || 'DWD 模型加载失败');
      })
      .finally(() => setModelingLoading(false));
  };

  useEffect(() => {
    if (detail?.asset.fqn) {
      loadModeling(detail.asset.fqn, detail.asset.layer);
    }
  }, [detail?.asset.fqn, detail?.asset.layer]);

  if (loading) {
    return (
      <div className="ol-page">
        <SectionCard title="表详情" icon={<TableOutlined />}>
          <StateView state="loading" rows={6} />
        </SectionCard>
      </div>
    );
  }

  if (loadError || !detail) {
    return (
      <div className="ol-page">
        <SectionCard title="表详情" icon={<TableOutlined />}>
          <StateView
            state="error"
            title="表详情加载失败"
            description={loadError || '资产不存在或已被删除'}
            onRetry={loadAsset}
          />
        </SectionCard>
      </div>
    );
  }

  const { asset, lineage, quality, security, subscription } = detail;
  const downstreamFqns = lineage.downstreamFqns?.length
    ? lineage.downstreamFqns
    : lineage.downstream.map((edge) => edge.downstreamFqn);
  const focusedModelId = searchParams.get('dwdModelId');
  const columnLineageRows = lineage.upstream.flatMap((edge) => edge.columns.map((column, index) => ({
    key: `${edge.upstreamFqn}-${edge.downstreamFqn}-${column.from}-${column.to}-${index}`,
    upstreamFqn: edge.upstreamFqn,
    downstreamFqn: edge.downstreamFqn,
    ...column,
  })));

  const latestRunOf = (modelId: string) => runsByModel[modelId]?.[0];

  const refreshModeling = () => loadModeling(asset.fqn, asset.layer);

  const replaceRun = (modelId: string, run: DwdModelRun) => {
    setRunsByModel((prev) => {
      const current = prev[modelId] || [];
      const next = current.some((item) => item.id === run.id)
        ? current.map((item) => item.id === run.id ? run : item)
        : [run, ...current];
      return { ...prev, [modelId]: next };
    });
  };

  const pollRun = async (modelId: string, runId: string) => {
    for (let i = 0; i < 30; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      const run = await ModelingAPI.getModelRun(runId);
      replaceRun(modelId, run);
      if (TERMINAL_RUN_STATUSES.has(run.status)) {
        refreshModeling();
        return;
      }
    }
  };

  const handleCompile = async (model: DataModel) => {
    const key = `compile-${model.id}`;
    setActionLoading(key);
    try {
      await ModelingAPI.compileModel(model.id);
      message.success('DWD 模型已编译并校验通过');
      refreshModeling();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'DWD 模型编译失败');
    } finally {
      setActionLoading(null);
    }
  };

  const handleRun = async (model: DataModel) => {
    const key = `run-${model.id}`;
    setActionLoading(key);
    try {
      const run = await ModelingAPI.runModel(model.id, { triggerType: 'MANUAL' });
      replaceRun(model.id, run);
      message.success('DWD 运行已提交');
      void pollRun(model.id, run.id).catch((e) => {
        message.error(e instanceof Error ? e.message : 'DWD 运行状态刷新失败');
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'DWD 运行提交失败');
    } finally {
      setActionLoading(null);
    }
  };

  const handleMaintenance = async (operation: AssetMaintenanceOperation) => {
    setMaintenanceAction(operation);
    try {
      const result = await CatalogAPI.runMaintenance(asset.id, operation);
      message.success(result.message);
      loadMaintenance();
      loadAsset();
    } catch (e) {
      message.error(e instanceof Error ? e.message : `${operationLabels[operation]} 失败`);
    } finally {
      setMaintenanceAction(null);
    }
  };

  const tabs = [
    { key: 'schema', label: 'Schema', children: (
      <SectionCard title="字段定义" icon={<TableOutlined />} subtitle={`${asset.columns.length} 列`} flatBody>
        <Table size="middle" rowKey="name" dataSource={asset.columns} pagination={false}
          columns={[
            { title: '字段', dataIndex: 'name', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
            { title: '类型', dataIndex: 'type', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
            { title: '描述', dataIndex: 'description' },
            { title: '密级', dataIndex: 'classification', width: 120, render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
            { title: '血缘', dataIndex: 'upstreamFqn', render: (u?: string) => u ? <Space><ArrowRightOutlined style={{ color: 'var(--ol-ink-4)' }} /><Text code style={{ fontSize: 12 }}>{u}</Text></Space> : '-' },
          ]} />
      </SectionCard>
    ) },
    { key: 'dwd-model', label: 'DWD 模型', children: (
      <SectionCard
        title={asset.layer === 'ODS' ? '下游 DWD 模型' : '来源 DWD 模型'}
        icon={<CodeOutlined />}
        subtitle={models.length ? `${models.length} 个模型` : undefined}
        flatBody
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {modelingError && <Alert type="error" showIcon message="DWD 模型加载失败" description={modelingError} />}
          {modelingLoading ? (
            <StateView state="loading" rows={4} />
          ) : models.length === 0 ? (
            <StateView
              state="empty"
              title={asset.layer === 'ODS' ? '暂无下游 DWD 模型' : '未找到来源模型'}
              description={asset.layer === 'ODS' ? '当前资产还没有通过建模向导生成 DWD 草稿' : '当前 DWD 资产暂未关联到建模控制面的模型记录'}
              cta={asset.layer === 'ODS'
                ? <Button type="primary" icon={<BranchesOutlined />} onClick={() => navigate(`/lakehouse/tables/new?derive=dwd&sourceAssetId=${asset.id}`)}>派生 DWD</Button>
                : undefined}
            />
          ) : (
            <Table<DataModel>
              size="middle"
              rowKey="id"
              dataSource={models}
              pagination={false}
              expandable={{
                defaultExpandedRowKeys: focusedModelId ? [focusedModelId] : undefined,
                expandedRowRender: (model) => (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Space wrap>
                      <Tag>{model.materialization}</Tag>
                      <Tag>{model.dbtModelName || 'dbt 未生成'}</Tag>
                      <Tag>{model.resourceGroup || 'default'}</Tag>
                      <Tag>{model.computeProfile || 'trino-small'}</Tag>
                    </Space>
                    <pre style={{ margin: 0, padding: 12, overflowX: 'auto', background: 'var(--ol-bg-muted)', border: '1px solid var(--ol-border)', borderRadius: 6, fontSize: 12 }}>
                      {model.compiledSql || model.sqlText || '暂无 SQL/dbt 预览，请先编译校验模型'}
                    </pre>
                    <Table
                      size="small"
                      rowKey="id"
                      dataSource={model.columnMappings}
                      pagination={false}
                      columns={[
                        { title: '源字段', dataIndex: 'source', render: (v: string) => <Text code>{v}</Text> },
                        { title: '目标字段', dataIndex: 'target', render: (v: string) => <Text code>{v}</Text> },
                        { title: '类型', render: (_, row: any) => `${row.sourceType || '-'} -> ${row.targetType || '-'}` },
                        { title: '表达式', dataIndex: 'expression', render: (v?: string) => v ? <Text code>{v}</Text> : '-' },
                        { title: '主键', dataIndex: 'primaryKey', width: 80, render: (v?: boolean) => v ? <Tag color="blue">PK</Tag> : '-' },
                      ]}
                    />
                  </Space>
                ),
              }}
              columns={[
                {
                  title: '模型',
                  dataIndex: 'name',
                  render: (name: string, record) => (
                    <Space direction="vertical" size={2}>
                      <Text strong>{name}</Text>
                      <Text code style={{ fontSize: 12 }}>{record.targetFqn}</Text>
                    </Space>
                  ),
                },
                {
                  title: '输入 / 输出',
                  render: (_, record) => (
                    <Space direction="vertical" size={2}>
                      <Text code style={{ fontSize: 12 }}>{record.sourceFqn}</Text>
                      <Text code style={{ fontSize: 12 }}>{record.targetFqn}</Text>
                    </Space>
                  ),
                },
                {
                  title: '状态',
                  width: 110,
                  render: (_, record) => <Tag color={modelStatusColor(record.status)}>{record.status}</Tag>,
                },
                {
                  title: '最近运行',
                  width: 260,
                  render: (_, record) => {
                    const latestRun = latestRunOf(record.id);
                    if (!latestRun) return '-';
                    return (
                      <Space direction="vertical" size={2}>
                        <Space size={6} wrap>
                          <Tag color={runStatusColor(latestRun.status)}>{latestRun.status}</Tag>
                          <Tag>{latestRun.triggerType}</Tag>
                          {latestRun.rowsWritten != null && <Tag>写入 {latestRun.rowsWritten}</Tag>}
                          {latestRun.resourceGroup && <Tag>{latestRun.resourceGroup}</Tag>}
                          {latestRun.computeProfile && <Tag>{latestRun.computeProfile}</Tag>}
                        </Space>
                        <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{fmtTime(latestRun.finishedAt || latestRun.updatedAt || latestRun.createdAt)}</Text>
                        <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
                          扫描 {fmtBytes(latestRun.actualScanBytes ?? latestRun.estimatedScanBytes)}
                          {' / '}成本 {fmtCost(latestRun.costEstimate)}
                          {' / '}重试 {latestRun.retryCount ?? 0}
                        </Text>
                        {latestRun.errorMsg && <Text type="danger" style={{ fontSize: 12 }}>{latestRun.errorMsg}</Text>}
                      </Space>
                    );
                  },
                },
                {
                  title: '数据面',
                  width: 220,
                  render: (_, record) => {
                    const latestRun = latestRunOf(record.id);
                    return (
                      <Space direction="vertical" size={2}>
                        <Text style={{ fontSize: 12 }}>Dagster：<Text code>{latestRun?.dagsterRunId || '-'}</Text></Text>
                        <Text style={{ fontSize: 12 }}>产物：<Text code>{latestRun?.artifactsPath || record.artifactPath || '-'}</Text></Text>
                      </Space>
                    );
                  },
                },
                {
                  title: '操作',
                  width: 220,
                  render: (_, record) => (
                    <Space wrap>
                      <Button
                        size="small"
                        icon={<CheckCircleOutlined />}
                        loading={actionLoading === `compile-${record.id}`}
                        onClick={() => handleCompile(record)}
                      >
                        编译校验
                      </Button>
                      <Button
                        size="small"
                        type="primary"
                        icon={<PlayCircleOutlined />}
                        disabled={!['VALIDATED', 'PUBLISHED'].includes(record.status)}
                        loading={actionLoading === `run-${record.id}`}
                        onClick={() => handleRun(record)}
                      >
                        运行
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          )}
          <Button icon={<ReloadOutlined />} onClick={refreshModeling}>刷新模型状态</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'snapshot', label: '快照', children: (
      <SectionCard title="快照管理" icon={<TableOutlined />}>
        <StateView
          state="empty"
          title="快照能力暂未接入"
          description="当前后端尚未提供 Iceberg 快照列表、回滚、过期快照清理和孤儿文件清理接口"
        />
        <Space style={{ marginTop: 12 }}>
          <Button disabled title="暂未接入快照清理接口">清理过期快照</Button>
          <Button disabled title="暂未接入孤儿文件清理接口">清理孤儿文件</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'optimize', label: '优化', children: (
      <SectionCard title="存储与优化" icon={<TableOutlined />}>
        {maintenanceLoading && !maintenance ? (
          <StateView state="loading" rows={4} />
        ) : maintenance ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type={maintenance.status === 'CRITICAL' ? 'error' : maintenance.status === 'OK' ? 'success' : 'warning'}
              showIcon
              message={<Space><span>运维状态</span><Tag color={maintenanceColor(maintenance.status)}>{maintenance.status}</Tag></Space>}
              description={`文件 ${maintenance.fileCount ?? '-'} / 小文件 ${maintenance.smallFileCount ?? '-'} / 总大小 ${fmtBytes(maintenance.totalBytes)} / SLA ${maintenance.freshnessLagMinutes ?? '-'} min / ${maintenance.freshnessSlaMinutes} min`}
            />
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>小文件阈值</Text>
              <div style={{ marginTop: 4 }}>
                <Tag>{fmtBytes(maintenance.smallFileThresholdBytes)}</Tag>
                {maintenance.risks.map((risk) => <Tag key={risk} color="warning">{risk}</Tag>)}
              </div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>最近同步</Text>
              <div style={{ marginTop: 4, fontSize: 13 }}>{fmtTime(maintenance.lastSyncAt)}</div>
            </div>
            <Space wrap>
              {allMaintenanceOperations.map((operation) => (
                <Button
                  key={operation}
                  size="small"
                  type={maintenance.suggestedOperations.includes(operation) ? 'primary' : 'default'}
                  icon={<ThunderboltOutlined />}
                  loading={maintenanceAction === operation}
                  onClick={() => handleMaintenance(operation)}
                >
                  {operationLabels[operation]}
                </Button>
              ))}
              <Button size="small" icon={<ReloadOutlined />} onClick={loadMaintenance} loading={maintenanceLoading}>刷新</Button>
            </Space>
          </Space>
        ) : (
          <StateView
            state="error"
            title="运维状态加载失败"
            description="未能读取当前资产的 Iceberg 维护状态"
            onRetry={loadMaintenance}
          />
        )}
      </SectionCard>
    ) },
    { key: 'quality', label: '质量', children: (
      <SectionCard
        title="质量门禁"
        icon={<HistoryOutlined />}
        subtitle={`规则 ${quality.ruleCount} / 失败 ${quality.failedRuleCount}`}
        flatBody
      >
        {quality.rules.length === 0 ? (
          <StateView
            state="empty"
            title="暂无质量结果"
            description="当前资产还没有质量规则或模型运行结果"
            cta={<Button icon={<HistoryOutlined />} onClick={() => navigate('/quality/results')}>查看稽核结果</Button>}
          />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type={quality.failedRuleCount > 0 ? 'error' : 'success'}
              showIcon
              message={quality.failedRuleCount > 0 ? '存在未通过质量规则' : '最近质量门禁通过'}
              description={`质量分 ${quality.score ?? '-'}，最近检查 ${fmtTime(quality.latestCheckedAt)}`}
            />
            <Table
              size="middle"
              rowKey="ruleId"
              dataSource={quality.rules}
              pagination={false}
              columns={[
                { title: '规则', dataIndex: 'ruleType', render: (v: string) => <Tag>{v}</Tag> },
                { title: '字段', dataIndex: 'targetColumn', render: (v?: string) => v ? <Text code>{v}</Text> : '-' },
                { title: '级别', dataIndex: 'severity', render: (v: string) => <Tag color={v === 'BLOCK' ? 'red' : 'orange'}>{v}</Tag> },
                { title: '结果', dataIndex: 'passed', render: (v?: boolean) => v == null ? '-' : <Tag color={v ? 'success' : 'error'}>{v ? '通过' : '失败'}</Tag> },
                { title: '通过率', dataIndex: 'passRate', render: (v?: number) => v == null ? '-' : `${v}%` },
                { title: '失败行', dataIndex: 'failedRows', render: (v?: number) => v ?? '-' },
                { title: '检查时间', dataIndex: 'checkedAt', render: (v?: string) => fmtTime(v) },
              ]}
            />
            <Button icon={<HistoryOutlined />} onClick={() => navigate('/quality/results')}>查看稽核结果</Button>
          </Space>
        )}
      </SectionCard>
    ) },
    { key: 'lineage', label: '血缘', children: (
      <SectionCard title="上下游血缘" icon={<BranchesOutlined />}>
        {lineage.upstream.length === 0 && downstreamFqns.length === 0 ? (
          <StateView
            state="empty"
            title="暂无血缘"
            description="当前 Catalog 聚合接口没有返回该资产的上下游血缘"
            cta={<Button icon={<BranchesOutlined />} onClick={() => navigate(`/catalog/lineage?fqn=${encodeURIComponent(asset.fqn)}`)}>展开整页血缘</Button>}
          />
        ) : (
          <Space direction="vertical" style={{ width: '100%' }}>
            {lineage.upstream.map((edge) => (
              <Text key={`up-${edge.upstreamFqn}-${edge.downstreamFqn}`}>
                <Text code>{edge.upstreamFqn}</Text> <ArrowRightOutlined style={{ color: 'var(--ol-ink-4)' }} /> <Text code>{asset.fqn}</Text>
              </Text>
            ))}
            {downstreamFqns.map((fqn) => (
              <Text key={fqn}><Text code>{asset.fqn}</Text> <ArrowRightOutlined style={{ color: 'var(--ol-ink-4)' }} /> <Text code>{fqn}</Text></Text>
            ))}
            {columnLineageRows.length > 0 && (
              <Table
                size="small"
                rowKey="key"
                dataSource={columnLineageRows}
                pagination={false}
                columns={[
                  { title: '上游表', dataIndex: 'upstreamFqn', render: (v: string) => <Text code>{v}</Text> },
                  { title: '源字段', dataIndex: 'from', render: (v: string) => <Text code>{v}</Text> },
                  { title: '目标字段', dataIndex: 'to', render: (v: string) => <Text code>{v}</Text> },
                  { title: '转换', dataIndex: 'transform', render: (v?: string) => v ? <Text code>{v}</Text> : '-' },
                ]}
              />
            )}
            <Button icon={<BranchesOutlined />} onClick={() => navigate(`/catalog/lineage?fqn=${encodeURIComponent(asset.fqn)}`)}>展开整页血缘</Button>
          </Space>
        )}
      </SectionCard>
    ) },
    { key: 'permission', label: '权限', children: (
      <SectionCard title="访问权限" icon={<TableOutlined />}>
        <Space direction="vertical" size={8}>
          <Text>活跃授权：<Text strong>{security.activeGrantCount}</Text></Text>
          <Text>脱敏策略：<Text strong>{security.maskingPolicyCount}</Text></Text>
          <Text>PII 识别：<Text strong>{security.piiDetectionCount}</Text></Text>
          <Text>已批准订阅：<Text strong>{subscription.approvedSubscriptionCount}</Text></Text>
          <Text>发布 API：<Text strong>{subscription.publishedApiCount}</Text> / {subscription.apiCount}</Text>
          <Text>API 调用：<Text strong>{subscription.callCount.toLocaleString()}</Text></Text>
        </Space>
        <Button type="primary" disabled title="暂未接入权限配置接口">配置权限</Button>
      </SectionCard>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        icon={<TableOutlined />}
        title={asset.fqn}
        subtitle={<Space size={8}><Tag color="blue">{asset.layer}</Tag><Text type="secondary" style={{ fontSize: 13 }}>{asset.description}</Text></Space>}
        status={<ClassificationBadge level={asset.classification} />}
        breadcrumb={[{ path: '/lakehouse/tables', label: '分层表管理' }, { label: asset.fqn }]}
        tabs={tabs}
        actions={[
          ...(asset.layer === 'ODS' ? [
            <Button
              key="derive-dwd"
              type="primary"
              icon={<BranchesOutlined />}
              onClick={() => navigate(`/lakehouse/tables/new?derive=dwd&sourceAssetId=${asset.id}`)}
            >
              派生 DWD
            </Button>,
          ] : []),
          <Button
            key="opt"
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={maintenanceAction === 'OPTIMIZE'}
            onClick={() => handleMaintenance('OPTIMIZE')}
          >
            立即优化
          </Button>,
          <Button key="profile" icon={<DatabaseOutlined />} onClick={() => navigate(`/catalog/assets/${asset.id}?from=lakehouse`)}>资产画像</Button>,
          <Button key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${asset.fqn}`)}>发布为 API</Button>,
          <Button key="add-col" disabled title="暂未接入 Schema 变更接口">加列</Button>,
          <Button key="del" danger disabled title="暂未接入 Schema 变更审批接口">删除字段</Button>,
        ]}
        meta={[
          { label: '行数', value: asset.rows == null ? '-' : asset.rows.toLocaleString() },
          { label: '大小', value: asset.sizeBytes == null ? '-' : `${(asset.sizeBytes / 1e9).toFixed(2)} GB` },
          { label: '负责人', value: asset.ownerName },
          { label: '格式', value: asset.format },
          { label: '质量分', value: quality.score == null ? '-' : quality.score },
          { label: '质量规则', value: quality.ruleCount },
          { label: '敏感字段', value: security.sensitiveColumnCount },
          { label: '被订阅', value: subscription.popularity },
        ]}
      />
    </>
  );
}
