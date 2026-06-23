/**
 * 资产搜索浏览（对应原型 §8.6.1 升级版）。
 */
import { App as AntdApp, Input, Row, Col, Tag, List, Space, Avatar, Typography, Drawer, Form, Select, Checkbox, Button } from 'antd';
import { SearchOutlined, FireOutlined, AppstoreOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchHot } from '../../mock';
import { ClassificationBadge, PageHeader, SectionCard, StateView } from '../../components';
import type { Asset } from '../../types';
import { CatalogAPI } from '../../api';

const { Text } = Typography;

const LAYER_BG: Record<string, string> = {
  ODS: 'var(--ol-brand)',
  DWD: '#0369A1',
  DWS: '#B45309',
  ADS: 'var(--ol-success)',
};

const ASSET_TYPE_LABEL: Record<string, string> = {
  TABLE: '表',
  VIEW: '视图',
  TOPIC: '主题',
  API: 'API',
};

function qualityBandOf(score?: number) {
  if (score == null) return 'unknown';
  if (score >= 90) return '90';
  if (score >= 80) return '80';
  return 'lt80';
}

function qualityColor(score?: number) {
  if (score == null) return 'default';
  if (score >= 90) return 'success';
  if (score >= 80) return 'warning';
  return 'error';
}

function recommendationReasons(asset: Asset) {
  const reasons: string[] = [];
  if ((asset.qualityScore ?? 0) >= 90) reasons.push('质量分 90+');
  if ((asset.popularity ?? 0) > 0) reasons.push(`被订阅 ${asset.popularity}`);
  if ((asset.accessCount ?? 0) > 0) reasons.push(`访问 ${asset.accessCount}`);
  if (asset.classification === 'L1' || asset.classification === 'L2') reasons.push('低敏可读');
  if (asset.tags?.length) reasons.push(asset.tags[0]);
  return reasons.slice(0, 2);
}

export default function CatalogSearch() {
  const navigate = useNavigate();
  const { message } = AntdApp.useApp();
  const [keyword, setKeyword] = useState('');
  const [assetType, setAssetType] = useState<string>();
  const [layer, setLayer] = useState<string>();
  const [classification, setClassification] = useState<string>();
  const [owner, setOwner] = useState<string>();
  const [tag, setTag] = useState<string>();
  const [qualityBand, setQualityBand] = useState<string>();
  const [applyOpen, setApplyOpen] = useState<Asset | null>(null);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();

  const loadAssets = () => {
    setLoading(true);
    setLoadError(undefined);
    CatalogAPI.listAssets()
      .then(setAssets)
      .catch((e) => {
        const msg = e instanceof Error ? e.message : '目录资产加载失败';
        setLoadError(msg);
        message.error(msg);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAssets();
  }, []);

  const ownerOptions = Array.from(new Set(assets.map((a) => a.ownerName).filter(Boolean))).map((v) => ({ label: v, value: v }));
  const tagOptions = Array.from(new Set(assets.flatMap((a) => a.tags || []).filter(Boolean))).map((v) => ({ label: v, value: v }));
  const suggestedAssets = [...assets]
    .sort((a, b) => ((b.popularity ?? 0) + (b.accessCount ?? 0)) - ((a.popularity ?? 0) + (a.accessCount ?? 0)))
    .slice(0, 4);

  const filtered = assets.filter((a) => {
    const q = keyword.trim().toLowerCase();
    const textMatched = !q
      || a.fqn.toLowerCase().includes(q)
      || a.name.toLowerCase().includes(q)
      || (a.description || '').toLowerCase().includes(q)
      || (a.tags || []).some((t) => t.toLowerCase().includes(q))
      || a.columns.some((c) =>
        c.name.toLowerCase().includes(q)
        || (c.description || '').toLowerCase().includes(q)
        || (c.terms || []).some((term) =>
          term.code.toLowerCase().includes(q) || term.name.toLowerCase().includes(q)
        )
      );

    return textMatched
      && (!assetType || a.type === assetType)
      && (!layer || a.layer === layer)
      && (!classification || a.classification === classification)
      && (!owner || a.ownerName === owner)
      && (!tag || (a.tags || []).includes(tag))
      && (!qualityBand || qualityBandOf(a.qualityScore) === qualityBand);
  });

  const highQualityCount = assets.filter((a) => (a.qualityScore ?? 0) >= 90).length;
  const subscribedCount = assets.filter((a) => (a.popularity ?? 0) > 0).length;

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SearchOutlined />}
        title="数据资产发现"
        subtitle={<span className="ol-chip">数据目录 · L3-2</span>}
        description="搜索、理解并申请可用数据资产，面向找数、授权和数据消费场景"
        meta={[
          { label: '可发现资产', value: assets.length },
          { label: '质量 90+', value: highQualityCount },
          { label: '已有订阅', value: subscribedCount },
        ]}
      />

      {/* 全局搜索框 */}
      <SectionCard padded="md">
        <Input
          size="large"
          prefix={<SearchOutlined style={{ color: 'var(--ol-ink-3)' }} />}
          placeholder="搜索表、字段、术语、标签、API…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ borderRadius: 8 }}
        />
        <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', gap: 6 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>热门：</Text>
          {searchHot.map((s) => (
            <Tag key={s} color="blue" style={{ cursor: 'pointer' }} onClick={() => setKeyword(s)}>{s}</Tag>
          ))}
        </div>
      </SectionCard>

      <Row gutter={16}>
        <Col xs={24} lg={5}>
          <SectionCard title="发现筛选" icon={<AppstoreOutlined />} padded="sm">
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Select
                allowClear
                placeholder="资产类型"
                value={assetType}
                onChange={setAssetType}
                style={{ width: '100%' }}
                options={['TABLE', 'VIEW', 'TOPIC', 'API'].map((v) => ({ label: ASSET_TYPE_LABEL[v], value: v }))}
              />
              <Select
                allowClear
                placeholder="业务标签"
                value={tag}
                onChange={setTag}
                style={{ width: '100%' }}
                options={tagOptions}
              />
              <Select
                allowClear
                placeholder="密级"
                value={classification}
                onChange={setClassification}
                style={{ width: '100%' }}
                options={['L1', 'L2', 'L3', 'L4'].map((v) => ({ label: v, value: v }))}
              />
              <Select
                allowClear
                placeholder="质量分"
                value={qualityBand}
                onChange={setQualityBand}
                style={{ width: '100%' }}
                options={[
                  { label: '90+', value: '90' },
                  { label: '80-89', value: '80' },
                  { label: '<80', value: 'lt80' },
                ]}
              />
              <Select
                allowClear
                placeholder="负责人"
                value={owner}
                onChange={setOwner}
                style={{ width: '100%' }}
                options={ownerOptions}
              />
              <Select
                allowClear
                placeholder="湖仓分层"
                value={layer}
                onChange={setLayer}
                style={{ width: '100%' }}
                options={['ODS', 'DWD', 'DWS', 'ADS'].map((v) => ({ label: v, value: v }))}
              />
            </Space>
          </SectionCard>

          <SectionCard title="热门资产" icon={<FireOutlined />} padded="sm" style={{ marginTop: 12 }}>
            <List
              size="small"
              dataSource={suggestedAssets}
              renderItem={(a) => (
                <List.Item style={{ padding: '6px 0' }}>
                  <Space direction="vertical" size={2} style={{ minWidth: 0 }}>
                    <Space size={8} style={{ minWidth: 0 }}>
                    <Avatar size="small" style={{ background: LAYER_BG[a.layer || 'ODS'], fontSize: 11 }}>{(a.layer || 'ODS')[0]}</Avatar>
                    <a className="ol-link ol-truncate" style={{ fontSize: 12 }} onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a>
                    </Space>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      订阅 {a.popularity ?? 0} · 访问 {a.accessCount ?? 0}
                    </Text>
                  </Space>
                </List.Item>
              )}
            />
          </SectionCard>
        </Col>

        <Col xs={24} lg={19}>
          <SectionCard title={`可用资产 (${filtered.length})`} icon={<SearchOutlined />} flatBody>
            {loading ? (
              <StateView state="loading" rows={6} />
            ) : loadError ? (
              <StateView
                state="error"
                title="目录资产加载失败"
                description={loadError}
                onRetry={loadAssets}
              />
            ) : filtered.length === 0 ? (
              <StateView
                state="empty"
                title="没有找到匹配的资产"
                description="尝试调整关键字、标签、密级、质量分或负责人筛选"
              />
            ) : (
            <List
              dataSource={filtered}
              renderItem={(a) => (
                <List.Item
                  style={{ padding: '14px 16px', borderBottom: '1px solid var(--ol-line-soft)' }}
                  actions={[
                    <Button key="apply" type="primary" size="small" onClick={() => setApplyOpen(a)}>申请访问</Button>,
                    <Button key="profile" size="small" onClick={() => navigate(`/catalog/assets/${a.id}`)}>资产画像</Button>,
                    <Button type="link" key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${a.fqn}`)}>发布 API</Button>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={<Avatar style={{ background: LAYER_BG[a.layer || 'ODS'] }}>{(a.layer || 'ODS')[0]}</Avatar>}
                    title={
                      <Space size={8} wrap>
                        <a className="ol-link" style={{ fontSize: 14, fontWeight: 500 }} onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a>
                        <Tag>{ASSET_TYPE_LABEL[a.type] || a.type}</Tag>
                        <Tag>{a.domain || '未归属'}域</Tag>
                        <ClassificationBadge level={a.classification} />
                        <Tag color={qualityColor(a.qualityScore)}>质量 {a.qualityScore ?? '-'}</Tag>
                        <Tag>订阅 {a.popularity ?? 0}</Tag>
                      </Space>
                    }
                    description={
                      <Space direction="vertical" size={6} style={{ width: '100%' }}>
                        <Text type="secondary" style={{ fontSize: 12 }}>{a.description || '暂无业务说明'}</Text>
                        <Space split={<span className="ol-divider-v" />} wrap>
                          <Text type="secondary" style={{ fontSize: 12 }}>负责：{a.ownerName}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>分层：{a.layer}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>访问：{a.accessCount ?? 0}</Text>
                        </Space>
                        <Space size={4} wrap>
                          {recommendationReasons(a).map((reason, index) => <Tag key={`reason-${index}-${reason}`} color="blue">{reason}</Tag>)}
                          {(a.tags || []).slice(0, 3).map((t, index) => <Tag key={`tag-${index}-${t}`}>{t}</Tag>)}
                        </Space>
                      </Space>
                    }
                  />
                </List.Item>
              )}
            />
            )}
          </SectionCard>
        </Col>
      </Row>

      {/* 申请访问抽屉 */}
      <Drawer
        open={!!applyOpen}
        onClose={() => setApplyOpen(null)}
        title="申请访问"
        width={520}
        extra={
          <Space>
            <Button onClick={() => setApplyOpen(null)}>取消</Button>
            <Button type="primary" onClick={() => { setApplyOpen(null); message.success('已提交申请，可在「我的申请」跟踪'); }}>提交申请</Button>
          </Space>
        }
      >
        {applyOpen && (
          <Form layout="vertical" requiredMark="optional">
            <Form.Item label="资产">
              <Space>
                <Text strong>{applyOpen.fqn}</Text>
                <ClassificationBadge level={applyOpen.classification} />
              </Space>
            </Form.Item>
            <Form.Item label="字段范围">
              <Checkbox.Group
                options={['全部', ...applyOpen.columns.map((c) => c.name)].map((v) => ({ label: v, value: v }))}
                defaultValue={['全部']}
              />
            </Form.Item>
            {applyOpen.columns.some((c) => c.classification === 'L3' || c.classification === 'L4') && (
              <Tag color="warning" style={{ marginBottom: 16 }}>
                含敏感字段：{applyOpen.columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').map((c) => c.name).join(', ')}
              </Tag>
            )}
            <Form.Item label="用途">
              <Select options={['报表分析', '风控模型', '产品功能', '其他'].map((v) => ({ label: v, value: v }))} />
            </Form.Item>
            <Form.Item label="使用周期">
              <Select options={['30 天', '90 天', '1 年'].map((v) => ({ label: v, value: v }))} defaultValue="90 天" />
            </Form.Item>
            <Form.Item label="权限">
              <Checkbox.Group
                options={['查样例', '查询', '下载', 'API'].map((v) => ({ label: v, value: v }))}
                defaultValue={['查样例', '查询']}
              />
            </Form.Item>
            <Form.Item label="审批链">
              <Text type="secondary" style={{ fontSize: 12 }}>资产负责人 → 安全合规</Text>
            </Form.Item>
          </Form>
        )}
      </Drawer>
    </div>
  );
}
