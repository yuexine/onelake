/**
 * 表详情（对应原型 §8.3.2 / §8.3.8 / §8.3.10）。
 * Tab: Schema / 快照 / 优化 / 血缘 / 权限
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Table, Tag, Space, Button, Typography, Timeline, Descriptions, message, Popconfirm } from 'antd';
import { ArrowRightOutlined, BranchesOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { lakehouseAssets, tableSnapshots } from '../../mock';
import { DetailPageLayout, ClassificationBadge, DangerConfirm, ImpactAnalysis } from '../../components';

const { Text } = Typography;

export default function TableDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const asset = lakehouseAssets.find((a) => a.id === id) || lakehouseAssets[1];
  const [confirmDel, setConfirmDel] = useState(false);
  const [confirmRollback, setConfirmRollback] = useState<string | null>(null);

  const tabs = [
    { key: 'schema', label: 'Schema', children: (
      <Table size="small" rowKey="name" dataSource={asset.columns} pagination={false}
        columns={[
          { title: '字段', dataIndex: 'name' },
          { title: '类型', dataIndex: 'type' },
          { title: '描述', dataIndex: 'description' },
          { title: '密级', dataIndex: 'classification', render: (c: string) => c ? <ClassificationBadge level={c as any} /> : null },
          { title: '血缘', dataIndex: 'upstreamFqn', render: (u?: string) => u ? <Space><ArrowRightOutlined /><Text code>{u}</Text></Space> : null },
        ]} />
    ) },
    { key: 'snapshot', label: '快照', children: (
      <>
        <Timeline mode="left" items={tableSnapshots.map((s, i) => ({
          color: s.current ? 'blue' : 'gray',
          dot: s.current ? '★' : undefined,
          children: <Space><Text>{s.snapshotId}</Text><Text type="secondary">{s.time}</Text><Text>{s.rows.toLocaleString()} 行</Text>{i > 0 && <Button type="link" onClick={() => setConfirmRollback(s.snapshotId)}>回滚到此</Button>}</Space>,
        }))} />
        <Space><Button>清理过期快照</Button><Button>清理孤儿文件</Button></Space>
      </>
    ) },
    { key: 'optimize', label: '优化', children: (
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="分区策略"><Tag>隐藏分区 days(created_at)</Tag></Descriptions.Item>
        <Descriptions.Item label="小文件"><Tag color="warning">1200 个 (建议优化)</Tag> <Button size="small" type="primary" onClick={() => message.success('已触发 Compaction（进入全局任务条）')}>立即 Compaction</Button></Descriptions.Item>
        <Descriptions.Item label="冷热分层">热分区 30 天 → S3 Standard；冷分区下沉 Glacier</Descriptions.Item>
        <Descriptions.Item label="压缩">Parquet + ZSTD</Descriptions.Item>
      </Descriptions>
    ) },
    { key: 'lineage', label: '血缘', children: (
      <Card type="inner">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Text>上游：mysql.orders → <Text code>{asset.fqn}</Text></Text>
          <Text>下游：<Text code>{asset.fqn}</Text> → dws.dws_user_order → ads.ads_sales_df</Text>
          <Button icon={<BranchesOutlined />} onClick={() => navigate('/catalog/lineage')}>展开整页血缘</Button>
        </Space>
      </Card>
    ) },
    { key: 'permission', label: '权限', children: (
      <Card type="inner"><Space direction="vertical">
        <Text>18 个用户/订阅方有访问权</Text>
        <Button type="primary">配置权限</Button>
      </Space></Card>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        title={asset.fqn}
        subtitle={<Space><Tag color="blue">{asset.layer}</Tag>{asset.description}</Space>}
        status={<ClassificationBadge level={asset.classification} />}
        breadcrumb={[{ path: '/lakehouse/tables', label: '分层浏览' }, { label: asset.fqn }]}
        tabs={tabs}
        actions={[
          <Button key="opt" type="primary" onClick={() => message.success('已触发 Compaction')}>立即优化</Button>,
          <Button key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${asset.fqn}`)}>发布为 API</Button>,
          <Button key="add-col">加列</Button>,
          <Button key="del" danger onClick={() => setConfirmDel(true)}>删除字段</Button>,
        ]}
        meta={[
          { label: '行数', value: asset.rows?.toLocaleString() },
          { label: '大小', value: `${((asset.sizeBytes || 0) / 1e9).toFixed(2)} GB` },
          { label: '负责人', value: asset.ownerName },
          { label: '格式', value: asset.format },
          { label: '质量分', value: asset.qualityScore },
          { label: '被订阅', value: asset.popularity },
        ]}
      />

      <DangerConfirm
        open={confirmDel}
        title="删除字段 dwd_order_df.phone"
        description="该操作为破坏性变更，请确认下游影响"
        confirmName="phone"
        impactLevel="HIGH"
        impacts={[{ label: '受影响 API', value: 1 }, { label: '订阅方', value: 18 }]}
        onCancel={() => setConfirmDel(false)}
        onConfirm={() => { setConfirmDel(false); message.success('已提交破坏性变更审批'); }}
      />

      <DangerConfirm
        open={!!confirmRollback}
        title={`回滚到 ${confirmRollback}`}
        description="回滚后将生成新快照（可再次恢复）"
        confirmName={asset.name}
        impacts={[{ label: '受影响下游模型', value: 3 }]}
        impactLevel="HIGH"
        onCancel={() => setConfirmRollback(null)}
        onConfirm={() => { setConfirmRollback(null); message.success(`已回滚到 ${confirmRollback}`); }}
      />
    </>
  );
}
