/**
 * 资产详情（对应原型 §8.6.2 / §8.6.7）。
 * Tab: 概览 / Schema / 血缘 / 质量 / 访问·订阅 / 变更历史
 */
import { Link, useParams, useNavigate } from 'react-router-dom';
import { App as AntdApp, Table, Tag, Space, Button, Typography } from 'antd';
import { DatabaseOutlined, BranchesOutlined, SafetyOutlined, AppstoreOutlined, TableOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { lakehouseAssets, metadataChanges, accessGrants } from '../../mock';
import { DetailPageLayout, ClassificationBadge, StatusBadge, SectionCard } from '../../components';
import { AccessApplyDrawer } from './_AccessApplyDrawer';
import { CatalogAPI } from '../../api';
import type { Asset } from '../../types';

const { Text } = Typography;

export default function AssetDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { message } = AntdApp.useApp();
  const [asset, setAsset] = useState<Asset>(() => lakehouseAssets.find((a) => a.id === id) || lakehouseAssets[1]);
  const [applyOpen, setApplyOpen] = useState(false);
  const myGrant = accessGrants.find((g) => g.assetFqn === asset.fqn);

  useEffect(() => {
    if (!id) return;
    CatalogAPI.getAsset(id)
      .then(setAsset)
      .catch(() => message.error('资产详情加载失败，已显示本地示例数据'));
  }, [id, message]);

  const tabs = [
    { key: 'overview', label: '概览', children: (
      <SectionCard title="样例数据（按权限脱敏）" icon={<DatabaseOutlined />} flatBody>
        <Table size="middle" pagination={false}
          dataSource={[
            { key: 1, order_id: 1001, phone: '138****8888', amount: 99.0 },
            { key: 2, order_id: 1002, phone: '139****1234', amount: 158.5 },
          ]}
          columns={[
            { title: 'order_id', dataIndex: 'order_id', render: (v: number) => <Text code>{v}</Text> },
            { title: 'phone', dataIndex: 'phone', render: (v: string) => (
              <Space>
                <Text code style={{ fontSize: 12 }}>{v}</Text>
                <Tag color="warning" style={{ margin: 0 }}>无权 → 打码</Tag>
              </Space>
            ) },
            { title: 'amount', dataIndex: 'amount' },
          ]} />
        <div style={{ padding: 12, background: 'var(--ol-fill-soft)', borderTop: '1px solid var(--ol-line-soft)' }}>
          <Button type="link" onClick={() => setApplyOpen(true)}>申请访问完整数据 →</Button>
        </div>
      </SectionCard>
    ) },
    { key: 'schema', label: 'Schema', children: (
      <SectionCard title="字段定义" icon={<DatabaseOutlined />} flatBody>
        <Table size="middle" rowKey="name" dataSource={asset.columns} pagination={false}
          columns={[
            { title: '字段', dataIndex: 'name', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
            { title: '类型', dataIndex: 'type', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
            { title: '描述', dataIndex: 'description' },
            { title: '业务术语', dataIndex: 'terms', width: 180, render: (_: unknown, record: any) => (
              record.terms?.length ? (
                <Space size={4} wrap>
                  {record.terms.map((term: any) => (
                    <Link key={term.id} to="/catalog/glossary">
                      <Tag color={term.status === 'APPROVED' ? 'success' : 'blue'} style={{ margin: 0 }}>{term.code}</Tag>
                    </Link>
                  ))}
                </Space>
              ) : '-'
            ) },
            { title: 'PII类型', dataIndex: 'piiType', render: (v: string) => v || '-' },
            { title: '密级', dataIndex: 'classification', width: 120, render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
            { title: '建议密级', dataIndex: 'suggestLevel', width: 120, render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
            { title: '枚举分布', render: () => <Text type="secondary" style={{ fontSize: 12 }}>1000+ 唯一值</Text> },
          ]} />
      </SectionCard>
    ) },
    { key: 'lineage', label: '血缘', children: (
      <SectionCard title="上下游血缘" icon={<BranchesOutlined />}>
        <Space direction="vertical" size={8}>
          <Text>上游：<Text code>mysql.orders</Text> → <Text code>{asset.fqn}</Text></Text>
          <Text>下游：<Text code>{asset.fqn}</Text> → <Text code>ads.ads_sales_df</Text> → API <Text code>/api/order</Text></Text>
          <Button type="link" onClick={() => navigate(`/catalog/lineage?fqn=${encodeURIComponent(asset.fqn)}`)}>展开整页血缘 →</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'quality', label: '质量', children: (
      <SectionCard title="质量评分" icon={<SafetyOutlined />}>
        <Space>
          <Tag color="success" style={{ padding: '4px 10px' }}>质量分 {asset.qualityScore}</Tag>
          <Button type="link" onClick={() => navigate('/quality/results')}>查看稽核结果 →</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'access', label: '访问 · 订阅', children: (
      <SectionCard title="我的访问授权" icon={<AppstoreOutlined />}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>授权状态</Text>
            <div style={{ marginTop: 4 }}>
              {myGrant ? (
                <Space>
                  <StatusBadge status={myGrant.status === 'ACTIVE' ? 'ACTIVE' : 'EXPIRED'} />
                  {myGrant.expiresAt && <Text style={{ fontSize: 12 }}>剩余 {Math.ceil((+new Date(myGrant.expiresAt) - Date.now()) / 86400000)} 天</Text>}
                  <Button size="small">续期</Button>
                </Space>
              ) : (
                <Space>
                  <Tag>未授权</Tag>
                  <Button type="primary" size="small" onClick={() => setApplyOpen(true)}>申请访问</Button>
                </Space>
              )}
            </div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>字段授权</Text>
            <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {asset.columns.map((c: any) => (
                <Tag key={c.name} color={(myGrant?.columns || []).includes(c.name) || !myGrant ? 'success' : 'default'}>{c.name}</Tag>
              ))}
            </div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>凭据</Text>
            <div style={{ marginTop: 4 }}>
              <Button type="link" onClick={() => navigate('/dataservice/appkeys')}>查看我的凭据 →</Button>
            </div>
          </div>
        </Space>
      </SectionCard>
    ) },
    { key: 'changes', label: '变更历史', children: (
      <SectionCard title="元数据变更" icon={<AppstoreOutlined />} flatBody>
        <Table size="middle" rowKey="version" dataSource={metadataChanges}
          columns={[
            { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue" style={{ margin: 0 }}>v{v}</Tag> },
            { title: '时间', dataIndex: 'time', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '操作人', dataIndex: 'author' },
            { title: '来源', dataIndex: 'source', render: (s: string) => <span className="ol-chip">{s}</span> },
            { title: 'Diff', dataIndex: 'diff', render: (diff: any[]) => diff.length === 0 ? <Text type="secondary">无变更</Text> : (
              <Space direction="vertical" size={4}>
                {diff.map((d, i) => (
                  <Tag key={i} color={d.kind === 'add' ? 'success' : d.kind === 'remove' ? 'error' : 'warning'} style={{ margin: 0 }}>
                    {d.kind === 'add' ? '+' : d.kind === 'remove' ? '-' : '~'} {d.field} {d.type || `${d.from}→${d.to}`}
                  </Tag>
                ))}
              </Space>
            ) },
          ]} />
      </SectionCard>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        icon={<DatabaseOutlined />}
        title={asset.fqn}
        subtitle={<Space size={8}><Tag color="blue">{asset.layer}</Tag><Text type="secondary" style={{ fontSize: 13 }}>{asset.description} · 每日更新</Text></Space>}
        status={<ClassificationBadge level={asset.classification} />}
        breadcrumb={[{ path: '/catalog/search', label: '资产发现' }, { label: asset.fqn }]}
        tabs={tabs}
        actions={[
          <Button key="apply" type="primary" onClick={() => setApplyOpen(true)}>申请访问</Button>,
          <Button key="lakehouse" icon={<TableOutlined />} onClick={() => navigate(`/lakehouse/tables/${asset.id}?from=catalog`)}>湖仓治理详情</Button>,
          <Button key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${asset.fqn}`)}>发布为 API</Button>,
        ]}
        meta={[
          { label: '负责人', value: asset.ownerName },
          { label: '更新频率', value: '每日 02:00' },
          { label: '密级', value: <ClassificationBadge level={asset.classification} /> },
          { label: '标签', value: asset.tags.map((t: string) => <Tag key={t}>{t}</Tag>) },
          { label: '被订阅', value: asset.popularity },
          { label: '质量分', value: asset.qualityScore },
        ]}
      />
      <AccessApplyDrawer open={applyOpen} onClose={() => setApplyOpen(false)} asset={asset} />
    </>
  );
}
