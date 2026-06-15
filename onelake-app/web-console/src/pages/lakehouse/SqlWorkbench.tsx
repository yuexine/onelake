/**
 * SQL 工作台（对应原型 §8.3.3 升级版）。
 *   表树 + Monaco SQL 编辑器 + 结果区
 */
import { Row, Col, Tree, Space, Button, Select, Tag, Table, Alert, Tabs, Tooltip, message, Typography } from 'antd';
import {
  PlayCircleOutlined, FormatPainterOutlined, SaveOutlined,
  ApiOutlined, ClusterOutlined, CodeOutlined, DatabaseOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { lakehouseAssets, sqlHistory, savedQueries } from '../../mock';
import Editor from '@monaco-editor/react';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

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
  const [sql, setSql] = useState(`SELECT * FROM dwd_order_df
WHERE dt = '2026-06-14'`);

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
              { label: '耗时', value: '48s', intent: 'info' },
              { label: '扫描', value: '12 GB', intent: 'neutral' },
              { label: '行数', value: '12,000', intent: 'neutral' },
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
          <Text type="secondary" style={{ fontSize: 12 }}>预览 2 / 12,000 行</Text>
        </div>

        <Table
          size="middle"
          pagination={false}
          dataSource={[
            { key: 1, order_id: 1001, amount: 99.0, dt: '2026-06-14' },
            { key: 2, order_id: 1002, amount: 158.5, dt: '2026-06-14' },
          ]}
          columns={[
            { title: 'order_id', dataIndex: 'order_id', render: (v: number) => <span className="mono tnum">{v}</span> },
            { title: 'amount', dataIndex: 'amount', render: (v: number) => <span className="mono tnum">{v}</span> },
            { title: 'dt', dataIndex: 'dt', render: (v: string) => <span className="mono">{v}</span> },
          ]}
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
            <Button size="small" icon={<ApiOutlined />} onClick={() => navigate('/dataservice/apis/new')}>发布为 API</Button>
            <Button size="small" icon={<ClusterOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>加入流水线</Button>
          </Space>
        </div>
      </div>
    ) },
    { key: 'chart', label: '图表', children: (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--ol-ink-3)' }}>图表区（ECharts 可选）</div>
    ) },
    { key: 'history', label: '查询历史', children: (
      <Table size="middle" rowKey="id" dataSource={sqlHistory}
        columns={[
          { title: '时间', dataIndex: 'at', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{t}</span> },
          { title: '运行人', dataIndex: 'runner' },
          { title: '扫描量', dataIndex: 'scanBytes', render: (v: number) => <span className="mono">{(v / 1e9).toFixed(2)} GB</span> },
          { title: '耗时', dataIndex: 'durationMs', render: (d: number) => d ? <span className="mono">{(d/1000).toFixed(1)}s</span> : '-' },
          { title: '状态', dataIndex: 'ok', render: (o: boolean) => (
            <span style={{
              padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
              background: o ? 'var(--ol-success-soft)' : 'var(--ol-error-soft)',
              color: o ? 'var(--ol-success)' : 'var(--ol-error)',
            }}>{o ? '✓ 成功' : '✗ 失败'}</span>
          ) },
          { title: 'SQL', dataIndex: 'sql', ellipsis: true, render: (s: string) => <Text code style={{ fontSize: 11 }}>{s}</Text> },
          { title: '操作', render: () => <Space><a className="ol-link">重新运行</a><a className="ol-link" onClick={() => navigate('/dataservice/apis/new')}>发布为 API</a></Space> },
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
          { title: '操作', render: () => <Space><a className="ol-link">重新运行</a><a className="ol-link">分享</a></Space> },
        ]} />
    ) },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CodeOutlined />}
        title="SQL 工作台"
        subtitle={<span className="ol-chip">湖仓 · L2-4</span>}
        description="Trino / Spark 双引擎，支持表树、SQL 编辑、结果图表、查询历史"
        actions={
          <>
            <Select defaultValue="auto" options={[
              { label: '自动路由 → Trino', value: 'auto' },
              { label: 'Spark', value: 'spark' },
            ]} style={{ width: 180 }} />
            <Select defaultValue="rg-default" options={[
              { label: '资源组: 默认', value: 'rg-default' },
              { label: '资源组: 大查询', value: 'rg-big' },
            ]} style={{ width: 160 }} />
          </>
        }
      />

      <Row gutter={16}>
        <Col xs={24} lg={5}>
          <SectionCard title="表树" icon={<DatabaseOutlined />} padded="sm">
            <Tree
              blockNode
              style={{ fontSize: 13 }}
              treeData={lakehouseAssets.map((a) => ({
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
                <Button type="primary" size="small" icon={<PlayCircleOutlined />} onClick={() => message.success('SQL 已提交，48s 完成')}>运行</Button>
              </Space>
            }
            padded="sm"
          >
            <div style={{ height: 220, border: '1px solid var(--ol-line-soft)', borderRadius: 6, overflow: 'hidden' }}>
              <Editor defaultLanguage="sql" value={sql} onChange={(v) => setSql(v || '')} theme="vs" />
            </div>
          </SectionCard>

          <Alert
            type="warning" showIcon
            style={{ marginTop: 12, borderRadius: 8 }}
            message={<span style={{ fontSize: 13 }}>预估扫描 <Text code style={{ fontSize: 12 }}>1.2 TB</Text>，超阈值需确认</span>}
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
