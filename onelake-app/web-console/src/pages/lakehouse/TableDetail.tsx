/**
 * 表详情（对应原型 §8.3.2 / §8.3.8 / §8.3.10）。
 * Tab: Schema / 快照 / 优化 / 血缘 / 权限
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Table, Tag, Space, Button, Typography, Timeline, message } from 'antd';
import { ArrowRightOutlined, BranchesOutlined, TableOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { lakehouseAssets, tableSnapshots } from '../../mock';
import { DetailPageLayout, ClassificationBadge, DangerConfirm, SectionCard } from '../../components';

const { Text } = Typography;

export default function TableDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const asset = lakehouseAssets.find((a) => a.id === id) || lakehouseAssets[1];
  const [confirmDel, setConfirmDel] = useState(false);
  const [confirmRollback, setConfirmRollback] = useState<string | null>(null);

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
    { key: 'snapshot', label: '快照', children: (
      <SectionCard title="快照管理" icon={<TableOutlined />}>
        <Timeline mode="left" items={tableSnapshots.map((s, i) => ({
          color: s.current ? 'blue' : 'gray',
          dot: s.current ? '★' : undefined,
          children: <Space>
            <Text code>{s.snapshotId}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>{s.time}</Text>
            <Text style={{ fontSize: 12 }}>{s.rows.toLocaleString()} 行</Text>
            {i > 0 && <Button type="link" onClick={() => setConfirmRollback(s.snapshotId)}>回滚到此</Button>}
          </Space>,
        }))} />
        <Space style={{ marginTop: 12 }}>
          <Button>清理过期快照</Button>
          <Button>清理孤儿文件</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'optimize', label: '优化', children: (
      <SectionCard title="存储与优化" icon={<TableOutlined />}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>分区策略</Text>
            <div style={{ marginTop: 4 }}><Tag>隐藏分区 days(created_at)</Tag></div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>小文件</Text>
            <div style={{ marginTop: 4 }}>
              <Space>
                <Tag color="warning">1200 个（建议优化）</Tag>
                <Button size="small" type="primary" onClick={() => message.success('已触发 Compaction（进入全局任务条）')}>立即 Compaction</Button>
              </Space>
            </div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>冷热分层</Text>
            <div style={{ marginTop: 4, fontSize: 13 }}>热分区 30 天 → S3 Standard；冷分区下沉 Glacier</div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>压缩</Text>
            <div style={{ marginTop: 4 }}><Tag color="processing">Parquet + ZSTD</Tag></div>
          </div>
        </Space>
      </SectionCard>
    ) },
    { key: 'lineage', label: '血缘', children: (
      <SectionCard title="上下游血缘" icon={<BranchesOutlined />}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Text>上游：<Text code>mysql.orders</Text> → <Text code>{asset.fqn}</Text></Text>
          <Text>下游：<Text code>{asset.fqn}</Text> → <Text code>dws.dws_user_order</Text> → <Text code>ads.ads_sales_df</Text></Text>
          <Button icon={<BranchesOutlined />} onClick={() => navigate('/catalog/lineage')}>展开整页血缘</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'permission', label: '权限', children: (
      <SectionCard title="访问权限" icon={<TableOutlined />}>
        <Space direction="vertical">
          <Text>18 个用户 / 订阅方有访问权</Text>
          <Button type="primary">配置权限</Button>
        </Space>
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
