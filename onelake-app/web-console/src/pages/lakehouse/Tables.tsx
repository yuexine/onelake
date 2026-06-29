/**
 * 分层表浏览（对应原型 §8.3.1 升级版）。
 *   左侧分层/域树 + 右侧表清单
 */
import { Row, Col, Tree, Table, Button, Input, Typography, Space, Tag } from 'antd';
import { PlusOutlined, ClusterOutlined, TableOutlined, BranchesOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClassificationBadge, PageHeader, SectionCard, StateView } from '../../components';
import type { Asset, AssetMaintenanceAssessment } from '../../types';
import { CatalogAPI } from '../../api';
import { normalizeCatalogAssets } from './assetAdapter';
import { maintenanceRiskLabel, maintenanceStatusColor, maintenanceStatusLabel } from './maintenanceLabels';

const { Text } = Typography;

const LAYER_COLOR: Record<string, { bg: string; fg: string }> = {
  ODS: { bg: 'var(--ol-brand-soft)', fg: 'var(--ol-brand)' },
  DWD: { bg: 'var(--ol-info-soft)',  fg: '#0369A1' },
  DWS: { bg: '#FEF3C7',              fg: '#B45309' },
  ADS: { bg: 'var(--ol-success-soft)',fg: 'var(--ol-success)' },
};

const LAYER_META: Record<string, { title: string; desc: string }> = {
  ODS: { title: '贴源 ODS', desc: '采集落湖' },
  DWD: { title: '明细 DWD', desc: '标准明细' },
  DWS: { title: '汇总 DWS', desc: '主题汇总' },
  ADS: { title: '应用 ADS', desc: '消费服务' },
};

function fmtBytes(value?: number) {
  if (value == null) return '-';
  if (value >= 1e12) return `${(value / 1e12).toFixed(2)} TB`;
  if (value >= 1e9) return `${(value / 1e9).toFixed(2)} GB`;
  if (value >= 1e6) return `${(value / 1e6).toFixed(2)} MB`;
  return `${value.toLocaleString()} B`;
}

function fmtTime(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function governanceFactoryPath(asset: Asset) {
  return `/lakehouse/governance-factory?${new URLSearchParams({ sourceAssetId: asset.id }).toString()}`;
}

function sqlWorkbenchPath(asset: Asset) {
  return `/lakehouse/sql?${new URLSearchParams({ assetId: asset.id, assetFqn: asset.fqn }).toString()}`;
}

function qualityColor(score?: number) {
  if (score == null) return 'default';
  if (score >= 90) return 'success';
  if (score >= 80) return 'warning';
  return 'error';
}

function renderAssetTreeTitle(asset: Asset, showClassification = false) {
  return (
    <span className="ol-tree-node-title" title={asset.name}>
      <span className="ol-tree-node-title__text">{asset.name}</span>
      {showClassification ? <ClassificationBadge level={asset.classification} size="small" /> : null}
    </span>
  );
}

export default function Tables() {
  const navigate = useNavigate();
  const [assets, setAssets] = useState<Asset[]>([]);
  const [maintenance, setMaintenance] = useState<AssetMaintenanceAssessment[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [layer, setLayer] = useState<string>();
  const [domain, setDomain] = useState<string>();
  const [keyword, setKeyword] = useState('');

  const loadAssets = () => {
    setLoading(true);
    setLoadError(null);
    CatalogAPI.listAssets()
      .then(async (items) => {
        setAssets(normalizeCatalogAssets(items));
        try {
          setMaintenance(await CatalogAPI.listMaintenance());
        } catch {
          setMaintenance([]);
        }
      })
      .catch((e) => {
        setAssets([]);
        setMaintenance([]);
        setLoadError(e.message || '分层表资产加载失败，请检查 Catalog 服务或稍后重试');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAssets();
  }, []);

  const rows = assets.filter((a) => {
    const q = keyword.trim().toLowerCase();
    return (!layer || a.layer === layer)
      && (!domain || a.domain === domain)
      && (!q || a.fqn.toLowerCase().includes(q)
        || a.name.toLowerCase().includes(q)
        || a.columns.some((c) => c.name.toLowerCase().includes(q))
        || (a.partitions || []).some((p) => p.toLowerCase().includes(q)));
  });

  const maintenanceByAssetId = new Map(maintenance.map((m) => [m.assetId, m]));
  const domains = Array.from(new Set(assets.map((a) => a.domain || '未归属'))).sort();
  const riskAssetCount = maintenance.filter((m) => m.status === 'WARN' || m.status === 'CRITICAL').length;

  const counts = {
    total: assets.length,
    ods: assets.filter((a) => a.layer === 'ODS').length,
    dwd: assets.filter((a) => a.layer === 'DWD').length,
    dws: assets.filter((a) => a.layer === 'DWS').length,
    ads: assets.filter((a) => a.layer === 'ADS').length,
    sensitive: assets.filter((a) => a.classification === 'L3' || a.classification === 'L4').length,
  };

  const layerTreeData = (['ODS', 'DWD', 'DWS', 'ADS'] as const).map((key) => {
    const layerAssets = assets.filter((a) => a.layer === key);
    const riskCount = layerAssets.filter((a) => {
      const item = maintenanceByAssetId.get(a.id);
      return item?.status === 'WARN' || item?.status === 'CRITICAL';
    }).length;
    return {
      title: (
        <Space size={6} wrap>
          <span>{LAYER_META[key].title}</span>
          <Tag style={{ margin: 0 }}>{layerAssets.length}</Tag>
          {riskCount > 0 && <Tag color="warning" style={{ margin: 0 }}>待治理 {riskCount}</Tag>}
        </Space>
      ),
      key,
      children: layerAssets.map((a) => ({
        title: renderAssetTreeTitle(a, key === 'DWD'),
        key: a.id,
        isLeaf: true,
      })),
    };
  });

  return (
    <div className="ol-page ol-lakehouse-tables-page">
      <PageHeader
        icon={<ClusterOutlined />}
        title="湖仓分层表"
        subtitle={<span className="ol-chip">湖仓 · L2</span>}
        description="按 ODS / DWD / DWS / ADS 管理表结构、建模链路、质量门禁与存储优化"
        meta={[
          { label: '总表数', value: counts.total },
          { label: '待治理', value: riskAssetCount },
          { label: '敏感资产', value: counts.sensitive },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/lakehouse/tables/new')}>新建表</Button>}
      />

      <Row gutter={16} className="ol-lakehouse-tables-layout">
        <Col xs={24} lg={5}>
          <div className="ol-lakehouse-tables-sidebar">
            <SectionCard title="分层 / 业务域" icon={<ClusterOutlined />}>
              <Tree
                className="ol-asset-tree"
                defaultExpandAll
                treeData={layerTreeData}
                onSelect={(keys, info) => {
                  const node = info.node;
                  if (node && (node as any).isLeaf && keys[0]) navigate(`/lakehouse/tables/${keys[0]}`);
                  else if (node) setLayer(node.key as string);
                }}
              />
              <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px dashed var(--ol-line-soft)' }}>
                <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>业务域</Text>
                <Tree
                  style={{ marginTop: 8 }}
                  treeData={domains.map((d) => ({
                    title: `${d}${d.endsWith('域') ? '' : '域'}`,
                    key: d,
                  }))}
                  onSelect={(keys) => keys[0] && setDomain(keys[0] as string)}
                />
              </div>
            </SectionCard>
          </div>
        </Col>
        <Col xs={24} lg={19} className="ol-lakehouse-tables-main">
          <SectionCard
            title="表治理清单"
            icon={<TableOutlined />}
            subtitle={`共 ${rows.length} 张表`}
            flatBody
            extra={
              <Input.Search
                placeholder="过滤表名/字段/分区"
                allowClear
                onSearch={setKeyword}
                style={{ width: 240 }}
              />
            }
          >
            <Table
              className="ol-lakehouse-governance-table"
              rowKey="id"
              dataSource={rows}
              loading={loading}
              locale={{
                emptyText: (
                  loadError ? (
                    <StateView
                      state="error"
                      title="分层表资产加载失败"
                      description={loadError}
                      onRetry={loadAssets}
                    />
                  ) : (
                    <StateView
                      state="empty"
                      title="暂无湖仓表"
                      description="当前 Catalog 没有返回湖仓分层表，请先完成采集入湖或新建表"
                      cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/lakehouse/tables/new')}>新建表</Button>}
                    />
                  )
                ),
              }}
              size="middle"
              tableLayout="fixed"
              pagination={{ pageSize: 20, showTotal: (t) => <span className="ol-quiet" style={{ fontSize: 12 }}>共 {t} 条</span> }}
              columns={[
                { title: '表名', dataIndex: 'fqn', width: 280, render: (v: string, r: Asset) => (
                  <Space direction="vertical" size={2} className="ol-lakehouse-table-name-cell">
                    <a
                      className="ol-link ol-truncate ol-lakehouse-table-name"
                      title={v}
                      onClick={() => navigate(`/lakehouse/tables/${r.id}`)}
                    >
                      {v}
                    </a>
                    <Text
                      type="secondary"
                      className="ol-truncate ol-lakehouse-table-desc"
                      title={r.description || LAYER_META[r.layer]?.desc || '-'}
                    >
                      {r.description || LAYER_META[r.layer]?.desc || '-'}
                    </Text>
                  </Space>
                ) },
                { title: '层 / 域', dataIndex: 'layer', width: 130, render: (l: string, r: Asset) => {
                  const c = LAYER_COLOR[l] || LAYER_COLOR.ODS;
                  return (
                    <Space direction="vertical" size={4}>
                      <span style={{ padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600, background: c.bg, color: c.fg }}>{l}</span>
                      <Tag style={{ margin: 0 }}>{r.domain || '未归属'}域</Tag>
                    </Space>
                  );
                } },
                { title: '格式 / 分区', width: 160, render: (_: unknown, r: Asset) => (
                  <Space direction="vertical" size={4}>
                    <Tag style={{ margin: 0 }}>{r.format || 'ICEBERG'}</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      分区 {r.partitions?.length || 0}
                    </Text>
                  </Space>
                ) },
                { title: '规模', width: 130, render: (_: unknown, r: Asset) => (
                  <Space direction="vertical" size={2}>
                    <span className="mono tnum">{r.rows == null ? '-' : r.rows.toLocaleString()}</span>
                    <Text type="secondary" className="mono" style={{ fontSize: 12 }}>{fmtBytes(r.sizeBytes)}</Text>
                  </Space>
                ) },
                { title: '质量 / 密级', width: 130, render: (_: unknown, r: Asset) => (
                  <Space direction="vertical" size={4}>
                    <Tag color={qualityColor(r.qualityScore)} style={{ margin: 0 }}>质量 {r.qualityScore ?? '-'}</Tag>
                    <ClassificationBadge level={r.classification as any} />
                  </Space>
                ) },
                { title: '同步 / 维护', width: 190, render: (_: unknown, r: Asset) => {
                  const item = maintenanceByAssetId.get(r.id);
                  const maintenanceStatus = item?.status || 'UNKNOWN';
                  return (
                    <Space direction="vertical" size={4}>
                      <Text type="secondary" style={{ fontSize: 12 }}>同步 {fmtTime(r.lastSyncAt || r.syncedAt)}</Text>
                      <Space size={4} wrap>
                        <Tag color={maintenanceStatusColor(maintenanceStatus)} title={maintenanceStatus} style={{ margin: 0 }}>
                          {maintenanceStatusLabel(maintenanceStatus)}
                        </Tag>
                        {(item?.risks || []).slice(0, 1).map((risk) => (
                          <Tag key={risk} color="warning" title={risk} style={{ margin: 0 }}>
                            {maintenanceRiskLabel(risk)}
                          </Tag>
                        ))}
                      </Space>
                    </Space>
                  );
                } },
                { title: '负责人', dataIndex: 'ownerName', width: 100 },
                { title: '操作', width: 220, fixed: 'right' as const, render: (_: unknown, r: Asset) => (
                  <Space size={4} wrap>
                    {r.layer === 'ODS' && (
                      <>
                        <Button
                          size="small"
                          type="primary"
                          icon={<BranchesOutlined />}
                          onClick={() => navigate(governanceFactoryPath(r))}
                        >
                          治理成表
                        </Button>
                        <Button
                          size="small"
                          icon={<BranchesOutlined />}
                          onClick={() => navigate(`/lakehouse/tables/new?derive=dwd&sourceAssetId=${r.id}`)}
                        >
                          派生 DWD
                        </Button>
                      </>
                    )}
                    <Button size="small" onClick={() => navigate(`/lakehouse/tables/${r.id}`)}>治理详情</Button>
                    <Button size="small" icon={<ThunderboltOutlined />} onClick={() => navigate(`/lakehouse/tables/${r.id}?tab=optimize`)}>优化</Button>
                    <Button size="small" type="link" onClick={() => navigate(sqlWorkbenchPath(r))}>SQL</Button>
                  </Space>
                ) },
              ]}
              scroll={{ x: 1340 }}
            />
          </SectionCard>
        </Col>
      </Row>
    </div>
  );
}
