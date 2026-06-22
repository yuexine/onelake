/**
 * 分层表浏览（对应原型 §8.3.1 升级版）。
 *   左侧分层/域树 + 右侧表清单
 */
import { Row, Col, Tree, Table, Button, Input, Typography } from 'antd';
import { PlusOutlined, ClusterOutlined, ApartmentOutlined, TableOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClassificationBadge, PageHeader, SectionCard, StateView } from '../../components';
import type { Asset } from '../../types';
import { CatalogAPI } from '../../api';
import { normalizeCatalogAssets } from './assetAdapter';

const { Text } = Typography;

const LAYER_COLOR: Record<string, { bg: string; fg: string }> = {
  ODS: { bg: 'var(--ol-brand-soft)', fg: 'var(--ol-brand)' },
  DWD: { bg: 'var(--ol-info-soft)',  fg: '#0369A1' },
  DWS: { bg: '#FEF3C7',              fg: '#B45309' },
  ADS: { bg: 'var(--ol-success-soft)',fg: 'var(--ol-success)' },
};

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
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [layer, setLayer] = useState<string>();
  const [domain, setDomain] = useState<string>();
  const [keyword, setKeyword] = useState('');

  const loadAssets = () => {
    setLoading(true);
    setLoadError(null);
    CatalogAPI.listAssets()
      .then((items) => setAssets(normalizeCatalogAssets(items)))
      .catch((e) => {
        setAssets([]);
        setLoadError(e.message || '分层表资产加载失败，请检查 Catalog 服务或稍后重试');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAssets();
  }, []);

  const rows = assets.filter((a) =>
    (!layer || a.layer === layer) &&
    (!domain || a.domain === domain) &&
    (!keyword || a.fqn.includes(keyword) || a.name.includes(keyword) || a.columns.some((c) => c.name.includes(keyword)))
  );

  const counts = {
    total: assets.length,
    ods: assets.filter((a) => a.layer === 'ODS').length,
    dwd: assets.filter((a) => a.layer === 'DWD').length,
    dws: assets.filter((a) => a.layer === 'DWS').length,
    ads: assets.filter((a) => a.layer === 'ADS').length,
    sensitive: assets.filter((a) => a.classification === 'L3' || a.classification === 'L4').length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<ClusterOutlined />}
        title="分层表浏览"
        subtitle={<span className="ol-chip">湖仓 · L2</span>}
        description="ODS / DWD / DWS / ADS 四层资产，支持按域、密级、质量分筛选"
        meta={[
          { label: '总表数', value: counts.total },
          { label: '敏感资产', value: counts.sensitive },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/lakehouse/tables/new')}>新建表（建模向导）</Button>}
      />

      <Row gutter={16}>
        <Col xs={24} lg={5}>
          <SectionCard title="分层 / 业务域" icon={<ClusterOutlined />}>
            <Tree
              className="ol-asset-tree"
              defaultExpandAll
              treeData={[
                { title: '贴源 ODS', key: 'ODS', children: assets.filter((a) => a.layer === 'ODS').map((a) => ({ title: renderAssetTreeTitle(a), key: a.id, isLeaf: true })) },
                { title: '明细 DWD', key: 'DWD', children: assets.filter((a) => a.layer === 'DWD').map((a) => ({ title: renderAssetTreeTitle(a, true), key: a.id, isLeaf: true })) },
                { title: '汇总 DWS', key: 'DWS', children: assets.filter((a) => a.layer === 'DWS').map((a) => ({ title: renderAssetTreeTitle(a), key: a.id, isLeaf: true })) },
                { title: '应用 ADS', key: 'ADS', children: assets.filter((a) => a.layer === 'ADS').map((a) => ({ title: renderAssetTreeTitle(a), key: a.id, isLeaf: true })) },
              ]}
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
                treeData={[
                  { title: '交易域', key: '交易' },
                  { title: '用户域', key: '用户' },
                  { title: '风控域', key: '风控' },
                ]}
                onSelect={(keys) => keys[0] && setDomain(keys[0] as string)}
              />
            </div>
          </SectionCard>
        </Col>
        <Col xs={24} lg={19}>
          <SectionCard
            title="资产清单"
            icon={<TableOutlined />}
            subtitle={`共 ${rows.length} 张表`}
            flatBody
            extra={
              <Input.Search
                placeholder="搜表名/字段"
                allowClear
                onSearch={setKeyword}
                style={{ width: 240 }}
              />
            }
          >
            <Table
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
                      title="暂无资产"
                      description="当前 Catalog 没有返回分层表资产，请先完成采集入湖或同步资产目录"
                      cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/lakehouse/tables/new')}>新建表</Button>}
                    />
                  )
                ),
              }}
              size="middle"
              pagination={{ pageSize: 20, showTotal: (t) => <span className="ol-quiet" style={{ fontSize: 12 }}>共 {t} 条</span> }}
              columns={[
                { title: '表名', dataIndex: 'fqn', render: (v: string, r: Asset) => (
                  <a className="ol-link" onClick={() => navigate(`/lakehouse/tables/${r.id}`)}>{v}</a>
                ) },
                { title: '层', dataIndex: 'layer', width: 80, render: (l: string) => {
                  const c = LAYER_COLOR[l] || LAYER_COLOR.ODS;
                  return <span style={{ padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600, background: c.bg, color: c.fg }}>{l}</span>;
                } },
                { title: '行数', dataIndex: 'rows', align: 'right' as const, render: (v?: number) => v == null ? '-' : <span className="mono tnum">{v.toLocaleString()}</span> },
                { title: '大小', dataIndex: 'sizeBytes', render: (v?: number) => v == null ? '-' : <span className="mono">{(v / 1e9).toFixed(2)} GB</span> },
                { title: '质量分', dataIndex: 'qualityScore', width: 90, render: (v?: number) => v == null ? '-' : (
                  <span style={{
                    padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                    background: v > 90 ? 'var(--ol-success-soft)' : v > 80 ? 'var(--ol-warning-soft)' : 'var(--ol-error-soft)',
                    color: v > 90 ? 'var(--ol-success)' : v > 80 ? '#B45309' : 'var(--ol-error)',
                  }}>{v}</span>
                ) },
                { title: '密级', dataIndex: 'classification', width: 110, render: (c: string) => <ClassificationBadge level={c as any} /> },
                { title: '负责人', dataIndex: 'ownerName', width: 100 },
              ]}
            />
          </SectionCard>
        </Col>
      </Row>
    </div>
  );
}
