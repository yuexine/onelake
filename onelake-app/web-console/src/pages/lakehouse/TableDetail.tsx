/**
 * 表详情（对应原型 §8.3.2 / §8.3.8 / §8.3.10）。
 * Tab: Schema / 快照 / 优化 / 血缘 / 权限
 */
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, App as AntdApp, Table, Tag, Space, Button, Typography } from 'antd';
import { ArrowRightOutlined, BranchesOutlined, CheckCircleOutlined, CodeOutlined, DatabaseOutlined, HistoryOutlined, ReloadOutlined, TableOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useEffect, useState, type ReactNode } from 'react';
import { DetailPageLayout, ClassificationBadge, SectionCard, StateView } from '../../components';
import type { AssetDetail, AssetMaintenanceAssessment, AssetMaintenanceOperation, DataModel } from '../../types';
import { CatalogAPI, ModelingAPI } from '../../api';
import { normalizeCatalogAsset } from './assetAdapter';
import {
  maintenanceFreshnessLabel,
  maintenanceOperationLabels,
  maintenanceStatusColor,
  maintenanceStatusLabel,
} from './maintenanceLabels';

const { Text } = Typography;

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

const allMaintenanceOperations: AssetMaintenanceOperation[] = ['OPTIMIZE', 'EXPIRE_SNAPSHOTS', 'REMOVE_ORPHAN_FILES'];
const DEFAULT_SMALL_FILE_RISK_COUNT = 10;

type OptimizeRiskLevel = 'critical' | 'warning' | 'info' | 'success';

interface OptimizeRiskRow {
  key: string;
  level: OptimizeRiskLevel;
  risk: string;
  evidence: string;
  remedy: string;
  validation: string;
  action: ReactNode;
}

function riskLevelColor(level: OptimizeRiskLevel) {
  if (level === 'critical') return 'error';
  if (level === 'warning') return 'warning';
  if (level === 'success') return 'success';
  return 'processing';
}

function hasMaintenanceRisk(maintenance: AssetMaintenanceAssessment | undefined, risk: string) {
  return Boolean(maintenance?.risks.includes(risk));
}

function lagText(maintenance: AssetMaintenanceAssessment) {
  return `${maintenance.freshnessLagMinutes ?? '-'} / ${maintenance.freshnessSlaMinutes} min`;
}

function smallFileRiskCount(maintenance: AssetMaintenanceAssessment) {
  return maintenance.smallFileRiskCount ?? DEFAULT_SMALL_FILE_RISK_COUNT;
}

function smallFileText(maintenance: AssetMaintenanceAssessment) {
  return `${maintenance.smallFileCount ?? '-'} / ${smallFileRiskCount(maintenance)} 个`;
}

export default function TableDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { message } = AntdApp.useApp();
  const [searchParams, setSearchParams] = useSearchParams();
  const [detail, setDetail] = useState<AssetDetail>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [models, setModels] = useState<DataModel[]>([]);
  const [modelingLoading, setModelingLoading] = useState(false);
  const [modelingError, setModelingError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [maintenance, setMaintenance] = useState<AssetMaintenanceAssessment>();
  const [maintenanceLoading, setMaintenanceLoading] = useState(false);
  const [maintenanceAction, setMaintenanceAction] = useState<AssetMaintenanceOperation | null>(null);
  const [pipelineResolving, setPipelineResolving] = useState(false);

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
      .then(setModels)
      .catch((e) => {
        setModels([]);
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

  const refreshModeling = () => loadModeling(asset.fqn, asset.layer);

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

  const handleMaintenance = async (operation: AssetMaintenanceOperation) => {
    setMaintenanceAction(operation);
    try {
      const result = await CatalogAPI.runMaintenance(asset.id, operation);
      message.success(result.message);
      loadMaintenance();
      loadAsset();
    } catch (e) {
      message.error(e instanceof Error ? e.message : `${maintenanceOperationLabels[operation]} 失败`);
    } finally {
      setMaintenanceAction(null);
    }
  };

  const targetPipelineModel = (items = models) => items.find((model) => (
    model.targetFqn === asset.fqn && Boolean(model.orchestrationDagId)
  ));

  const targetPipelineFallbackPath = () => `/orchestration/pipelines?${new URLSearchParams({
    targetFqn: asset.fqn,
  }).toString()}`;

  const openTargetPipeline = async () => {
    const localModel = targetPipelineModel();
    if (localModel?.orchestrationDagId) {
      navigate(`/orchestration/pipelines/${localModel.orchestrationDagId}`);
      return;
    }

    setPipelineResolving(true);
    try {
      const refreshedModels = await ModelingAPI.listModels({ targetFqn: asset.fqn });
      setModels(refreshedModels);
      const matched = targetPipelineModel(refreshedModels);
      if (matched?.orchestrationDagId) {
        navigate(`/orchestration/pipelines/${matched.orchestrationDagId}`);
        return;
      }
      message.warning('未找到当前表已绑定的目标流水线，已按目标表过滤流水线列表');
      navigate(targetPipelineFallbackPath());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '目标流水线定位失败');
      navigate(targetPipelineFallbackPath());
    } finally {
      setPipelineResolving(false);
    }
  };
  const freshnessRequiresRun = hasMaintenanceRisk(maintenance, 'FRESHNESS_SLA_BREACHED')
    || hasMaintenanceRisk(maintenance, 'FRESHNESS_UNKNOWN');

  const renderOptimizeTab = () => {
    if (maintenanceLoading && !maintenance) {
      return (
        <SectionCard title="当前表运维诊断" icon={<TableOutlined />}>
          <StateView state="loading" rows={4} />
        </SectionCard>
      );
    }

    if (!maintenance) {
      return (
        <SectionCard title="当前表运维诊断" icon={<TableOutlined />}>
          <StateView
            state="error"
            title="运维状态加载失败"
            description="未能读取当前资产的 Iceberg 维护状态"
            onRetry={loadMaintenance}
          />
        </SectionCard>
      );
    }

    const freshnessBreached = hasMaintenanceRisk(maintenance, 'FRESHNESS_SLA_BREACHED');
    const freshnessUnknown = hasMaintenanceRisk(maintenance, 'FRESHNESS_UNKNOWN');
    const metadataUnavailable = hasMaintenanceRisk(maintenance, 'ICEBERG_METADATA_UNAVAILABLE');
    const smallFileRisk = hasMaintenanceRisk(maintenance, 'SMALL_FILE_RISK');
    const hasBlockingRisk = freshnessBreached || metadataUnavailable;
    const primaryTitle = freshnessBreached
      ? '新鲜度超 SLA 是当前严重风险来源'
      : metadataUnavailable
        ? 'Iceberg 元数据暂不可用'
        : smallFileRisk
          ? '小文件数量达到优化阈值'
          : freshnessUnknown
            ? '缺少最近同步时间'
            : '当前表暂无阻断性运维风险';
    const primaryDescription = freshnessBreached
      ? `已滞后 ${lagText(maintenance)}，需要运行上游采集或 DWD 流水线刷新数据。`
      : metadataUnavailable
        ? '当前无法读取 Iceberg $files 元数据，请先确认 Trino、Hive Metastore 和表 FQN 是否可用。'
        : smallFileRisk
          ? `小文件数量 ${smallFileText(maintenance)}，达到当前表的小文件风险阈值。`
          : freshnessUnknown
            ? '当前资产没有最近同步时间，需先完成一次采集或建模运行并回写 Catalog。'
            : '评估结果没有命中严重风险；可按需执行存储维护动作。';

    const riskRows: OptimizeRiskRow[] = [];

    if (freshnessBreached) {
      riskRows.push({
        key: 'freshness-breached',
        level: 'critical',
        risk: '新鲜度超 SLA',
        evidence: `滞后 ${lagText(maintenance)}，超过 DWD 新鲜度 SLA。`,
        remedy: '运行上游采集或 DWD 流水线，让成功运行回写最近同步时间。',
        validation: `刷新评估后滞后时间不超过 ${maintenance.freshnessSlaMinutes} min。`,
        action: (
          <Button
            size="small"
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={pipelineResolving}
            onClick={openTargetPipeline}
          >
            去运行流水线
          </Button>
        ),
      });
    }

    if (freshnessUnknown) {
      riskRows.push({
        key: 'freshness-unknown',
        level: 'warning',
        risk: '新鲜度未知',
        evidence: '当前资产缺少最近同步时间。',
        remedy: '完成一次采集或 DWD 模型运行，确认 Catalog 收到同步回写。',
        validation: '刷新评估后出现最近同步时间和明确的新鲜度状态。',
        action: (
          <Button size="small" icon={<ThunderboltOutlined />} loading={pipelineResolving} onClick={openTargetPipeline}>
            查看运行入口
          </Button>
        ),
      });
    }

    if (metadataUnavailable) {
      riskRows.push({
        key: 'metadata-unavailable',
        level: 'warning',
        risk: '元数据不可用',
        evidence: '后端没有读取到 Iceberg $files 统计。',
        remedy: '检查表是否存在、FQN 是否正确，以及 Trino / Hive Metastore 是否可访问。',
        validation: '刷新评估后文件数、小文件数和总大小恢复为具体数值。',
        action: (
          <Button size="small" icon={<ReloadOutlined />} onClick={loadMaintenance} loading={maintenanceLoading}>
            刷新评估
          </Button>
        ),
      });
    }

    if (smallFileRisk) {
      riskRows.push({
        key: 'small-file-risk',
        level: 'warning',
        risk: '小文件风险',
        evidence: `小文件 ${smallFileText(maintenance)}，小文件阈值 ${fmtBytes(maintenance.smallFileThresholdBytes)}。`,
        remedy: '执行 Compaction 合并 Iceberg 小文件。',
        validation: `刷新评估后小文件数量低于 ${smallFileRiskCount(maintenance)} 个。`,
        action: (
          <Button
            size="small"
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={maintenanceAction === 'OPTIMIZE'}
            onClick={() => handleMaintenance('OPTIMIZE')}
          >
            Compaction
          </Button>
        ),
      });
    }

    if (riskRows.length === 0) {
      riskRows.push({
        key: 'healthy',
        level: 'success',
        risk: '未命中风险',
        evidence: '新鲜度、Iceberg 元数据和小文件数量均未触发风险规则。',
        remedy: '无需解除风险，可按维护窗口执行快照或孤儿文件清理。',
        validation: '刷新评估后仍保持维护正常。',
        action: (
          <Button size="small" icon={<ReloadOutlined />} onClick={loadMaintenance} loading={maintenanceLoading}>
            刷新评估
          </Button>
        ),
      });
    }

    return (
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <SectionCard
          title="当前表运维诊断"
          icon={<TableOutlined />}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadMaintenance} loading={maintenanceLoading}>刷新评估</Button>}
        >
          <div className="ol-maintenance-diagnosis">
            <div className="ol-maintenance-diagnosis__copy">
              <Space size={8} wrap>
                <Tag color={maintenanceStatusColor(maintenance.status)} title={maintenance.status}>
                  {maintenanceStatusLabel(maintenance.status)}
                </Tag>
                {hasBlockingRisk && <Tag color="error">需先解除数据新鲜度/元数据风险</Tag>}
                {!hasBlockingRisk && smallFileRisk && <Tag color="warning">建议存储优化</Tag>}
              </Space>
              <div className="ol-maintenance-diagnosis__title">{primaryTitle}</div>
              <div className="ol-maintenance-diagnosis__desc">{primaryDescription}</div>
            </div>
            <div className="ol-maintenance-diagnosis__action">
              {freshnessBreached || freshnessUnknown ? (
                <Button type="primary" icon={<ThunderboltOutlined />} loading={pipelineResolving} onClick={openTargetPipeline}>
                  去运行流水线
                </Button>
              ) : smallFileRisk ? (
                <Button
                  type="primary"
                  icon={<ThunderboltOutlined />}
                  loading={maintenanceAction === 'OPTIMIZE'}
                  onClick={() => handleMaintenance('OPTIMIZE')}
                >
                  执行 Compaction
                </Button>
              ) : (
                <Button icon={<ReloadOutlined />} onClick={loadMaintenance} loading={maintenanceLoading}>
                  刷新评估
                </Button>
              )}
            </div>
          </div>

          <div className="ol-maintenance-facts">
            <div className="ol-maintenance-fact">
              <span>新鲜度</span>
              <strong>{maintenanceFreshnessLabel(maintenance.freshnessStatus)}</strong>
              <em>{lagText(maintenance)}</em>
            </div>
            <div className="ol-maintenance-fact">
              <span>小文件</span>
              <strong>{maintenance.smallFileCount ?? '-'}</strong>
              <em>{smallFileRisk ? `达到 ${smallFileRiskCount(maintenance)} 个阈值` : `风险阈值 ${smallFileRiskCount(maintenance)} 个`}</em>
            </div>
            <div className="ol-maintenance-fact">
              <span>文件 / 大小</span>
              <strong>{maintenance.fileCount ?? '-'}</strong>
              <em>{fmtBytes(maintenance.totalBytes)}</em>
            </div>
            <div className="ol-maintenance-fact">
              <span>最近同步</span>
              <strong>{fmtTime(maintenance.lastSyncAt)}</strong>
              <em>资产同步时间</em>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="风险原因与解除路径" icon={<HistoryOutlined />} flatBody>
          <Table<OptimizeRiskRow>
            size="middle"
            rowKey="key"
            dataSource={riskRows}
            pagination={false}
            columns={[
              {
                title: '风险项',
                dataIndex: 'risk',
                width: 150,
                render: (value: string, row) => <Tag color={riskLevelColor(row.level)}>{value}</Tag>,
              },
              { title: '判断依据', dataIndex: 'evidence' },
              { title: '解除方式', dataIndex: 'remedy' },
              { title: '验证标准', dataIndex: 'validation' },
              { title: '操作', dataIndex: 'action', width: 150, render: (value: ReactNode) => value },
            ]}
          />
        </SectionCard>

        <SectionCard
          title="存储维护操作"
          icon={<ThunderboltOutlined />}
          subtitle="这些操作处理 Iceberg 文件与元数据维护，不会刷新 DWD 数据新鲜度"
        >
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Space wrap>
              {allMaintenanceOperations.map((operation) => {
                const recommended = operation === 'OPTIMIZE' && smallFileRisk;
                return (
                  <Button
                    key={operation}
                    size="small"
                    type={recommended ? 'primary' : 'default'}
                    icon={<ThunderboltOutlined />}
                    loading={maintenanceAction === operation}
                    onClick={() => handleMaintenance(operation)}
                  >
                    {maintenanceOperationLabels[operation]}
                  </Button>
                );
              })}
            </Space>
            <Text type="secondary" style={{ fontSize: 12 }}>
              当前小文件 {smallFileText(maintenance)}；只有达到风险阈值时，Compaction 才会作为解除风险的主操作。
            </Text>
          </Space>
        </SectionCard>
      </Space>
    );
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
                      <Tag>{model.engine || 'SPARK'}</Tag>
                      <Tag>{model.resourceGroup || 'spark-default'}</Tag>
                      <Tag>{model.computeProfile || 'spark-small'}</Tag>
                    </Space>
                    <pre style={{ margin: 0, padding: 12, overflowX: 'auto', background: 'var(--ol-bg-muted)', border: '1px solid var(--ol-border)', borderRadius: 6, fontSize: 12 }}>
                      {model.compiledSql || model.sqlText || '暂无 Spark SQL 预览，请先编译校验模型'}
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
                  title: 'Spark 产物',
                  width: 220,
                  render: (_, record) => (
                    <Space direction="vertical" size={2}>
                      <Text style={{ fontSize: 12 }}>产物：<Text code>{record.artifactPath || '-'}</Text></Text>
                      <Text style={{ fontSize: 12 }}>资源：{record.resourceGroup || 'spark-default'} / {record.computeProfile || 'spark-small'}</Text>
                    </Space>
                  ),
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
                        icon={<ThunderboltOutlined />}
                        disabled={!['VALIDATED', 'PUBLISHED'].includes(record.status)}
                        onClick={() => navigate('/orchestration/pipelines')}
                      >
                        去流水线运行
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
          title="快照列表与回滚暂未接入"
          description="当前后端尚未提供 Iceberg 快照列表和回滚；过期快照清理、孤儿文件清理已归入优化页的维护动作"
        />
        <Space style={{ marginTop: 12 }}>
          <Button disabled title="请在优化 Tab 使用清理快照维护动作">清理过期快照</Button>
          <Button disabled title="请在优化 Tab 使用清理孤儿文件维护动作">清理孤儿文件</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'optimize', label: '优化', children: (
      renderOptimizeTab()
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
  const requestedTab = searchParams.get('tab');
  const activeTab = tabs.some((tab) => tab.key === requestedTab) ? requestedTab || undefined : undefined;
  const handleTabChange = (key: string) => {
    const next = new URLSearchParams(searchParams);
    if (key === tabs[0]?.key) {
      next.delete('tab');
    } else {
      next.set('tab', key);
    }
    setSearchParams(next, { replace: true });
  };

  return (
    <>
      <DetailPageLayout
        icon={<TableOutlined />}
        title={asset.fqn}
        subtitle={<Space size={8}><Tag color="blue">{asset.layer}</Tag><Text type="secondary" style={{ fontSize: 13 }}>{asset.description}</Text></Space>}
        status={<ClassificationBadge level={asset.classification} />}
        breadcrumb={[{ path: '/lakehouse/tables', label: '分层表管理' }, { label: asset.fqn }]}
        tabs={tabs}
        activeTab={activeTab}
        onTabChange={handleTabChange}
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
          freshnessRequiresRun ? (
            <Button
              key="run-pipeline"
              type="primary"
              icon={<ThunderboltOutlined />}
              loading={pipelineResolving}
              onClick={openTargetPipeline}
            >
              运行流水线
            </Button>
          ) : (
            <Button
              key="opt"
              type="primary"
              icon={<ThunderboltOutlined />}
              loading={maintenanceAction === 'OPTIMIZE'}
              onClick={() => handleMaintenance('OPTIMIZE')}
            >
              立即优化
            </Button>
          ),
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
