/**
 * 资产详情（对应原型 §8.6.2 / §8.6.7）。
 * Tab: 概览 / Schema / 血缘 / 质量 / 访问·订阅 / 变更历史
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Table, Tag, Space, Button, Typography, Descriptions, message } from 'antd';
import { useState } from 'react';
import { lakehouseAssets, metadataChanges, accessGrants } from '../../mock';
import { DetailPageLayout, ClassificationBadge, StatusBadge } from '../../components';
import { AccessApplyDrawer } from './_AccessApplyDrawer';

const { Text } = Typography;

export default function AssetDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const asset = lakehouseAssets.find((a) => a.id === id) || lakehouseAssets[1];
  const [applyOpen, setApplyOpen] = useState(false);
  const myGrant = accessGrants.find((g) => g.assetFqn === asset.fqn);

  const tabs = [
    { key: 'overview', label: '概览', children: (
      <Card type="inner" title="样例数据（按权限脱敏）">
        <Table size="small" pagination={false}
          dataSource={[
            { key: 1, order_id: 1001, phone: '138****8888', amount: 99.0 },
            { key: 2, order_id: 1002, phone: '139****1234', amount: 158.5 },
          ]}
          columns={[
            { title: 'order_id', dataIndex: 'order_id' },
            { title: 'phone', dataIndex: 'phone', render: (v: string) => <Space>{v}<Tag color="orange">无权 → 打码</Tag></Space> },
            { title: 'amount', dataIndex: 'amount' },
          ]} />
        <div style={{ marginTop: 8 }}><Button type="link" onClick={() => setApplyOpen(true)}>申请访问</Button></div>
      </Card>
    ) },
    { key: 'schema', label: 'Schema', children: (
      <Table size="small" rowKey="name" dataSource={asset.columns} pagination={false}
        columns={[
          { title: '字段', dataIndex: 'name' },
          { title: '类型', dataIndex: 'type' },
          { title: '描述', dataIndex: 'description' },
          { title: '密级', dataIndex: 'classification', render: (c: string) => c ? <ClassificationBadge level={c as any} /> : null },
          { title: '枚举分布', render: () => <Text type="secondary">1000+ 唯一值</Text> },
        ]} />
    ) },
    { key: 'lineage', label: '血缘', children: (
      <Card type="inner">
        <Space direction="vertical">
          <Text>上游：mysql.orders → <Text code>{asset.fqn}</Text></Text>
          <Text>下游：<Text code>{asset.fqn}</Text> → ads.ads_sales_df → API:/api/order</Text>
          <Button type="link" onClick={() => navigate('/catalog/lineage')}>展开整页血缘 →</Button>
        </Space>
      </Card>
    ) },
    { key: 'quality', label: '质量', children: (
      <Card type="inner"><Space><Tag color="success">质量分 {asset.qualityScore}</Tag><Button type="link" onClick={() => navigate('/quality/results')}>查看稽核结果</Button></Space></Card>
    ) },
    { key: 'access', label: '访问·订阅', children: (
      <Card type="inner">
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="我的授权状态">
            {myGrant ? <Space><StatusBadge status={myGrant.status === 'ACTIVE' ? 'ACTIVE' : 'EXPIRED'} />{myGrant.expiresAt && <Text>剩余 {Math.ceil((+new Date(myGrant.expiresAt) - Date.now()) / 86400000)} 天</Text>}<Button size="small">续期</Button></Space>
              : <Space><Tag>未授权</Tag><Button type="primary" size="small" onClick={() => setApplyOpen(true)}>申请访问</Button></Space>}
          </Descriptions.Item>
          <Descriptions.Item label="字段授权">
            {asset.columns.map((c: any) => <Tag key={c.name} color={(myGrant?.columns || []).includes(c.name) || !myGrant ? 'success' : 'default'}>{c.name}</Tag>)}
          </Descriptions.Item>
          <Descriptions.Item label="凭据"><Button type="link" onClick={() => navigate('/dataservice/appkeys')}>查看我的凭据</Button></Descriptions.Item>
        </Descriptions>
      </Card>
    ) },
    { key: 'changes', label: '变更历史', children: (
      <Card type="inner">
        <Table size="small" rowKey="version" dataSource={metadataChanges}
          columns={[
            { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
            { title: '时间', dataIndex: 'time' },
            { title: '操作人', dataIndex: 'author' },
            { title: '来源', dataIndex: 'source' },
            { title: 'Diff', dataIndex: 'diff', render: (diff: any[]) => diff.length === 0 ? <Text type="secondary">无变更</Text> : (
              <Space direction="vertical">
                {diff.map((d, i) => <Tag key={i} color={d.kind === 'add' ? 'success' : d.kind === 'remove' ? 'error' : 'warning'}>{d.kind === 'add' ? '+' : d.kind === 'remove' ? '-' : '~'} {d.field} {d.type || `${d.from}→${d.to}`}</Tag>)}
              </Space>
            ) },
          ]} />
      </Card>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        title={asset.fqn}
        subtitle={<Space><Tag color="blue">{asset.layer}</Tag>{asset.description} · 更新：每日</Space>}
        status={<ClassificationBadge level={asset.classification} />}
        breadcrumb={[{ path: '/catalog/search', label: '数据目录' }, { label: asset.fqn }]}
        tabs={tabs}
        actions={[
          <Button key="apply" onClick={() => setApplyOpen(true)}>申请访问</Button>,
          <Button key="api" type="primary" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${asset.fqn}`)}>发布为 API</Button>,
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
