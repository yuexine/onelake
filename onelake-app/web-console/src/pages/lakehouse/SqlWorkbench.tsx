/**
 * SQL 工作台（对应原型 §8.3.3）。
 * 表树 + Monaco SQL 编辑器 + 结果区（含另存为模型/发布 API/加入流水线）。
 */
import { Card, Row, Col, Tree, Space, Button, Select, Tag, Table, Alert, Tabs, Tooltip, message } from 'antd';
import { PlayCircleOutlined, FormatPainterOutlined, SaveOutlined, ShareAltOutlined, ApiOutlined, ClusterOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { lakehouseAssets, sqlHistory, savedQueries } from '../../mock';
import Editor from '@monaco-editor/react';

export default function SqlWorkbench() {
  const navigate = useNavigate();
  const [sql, setSql] = useState(`SELECT * FROM dwd_order_df
WHERE dt = '2026-06-14'`);

  const tabs = [
    { key: 'result', label: '结果', children: (
      <>
        <Space style={{ marginBottom: 8 }}><Tag color="processing">耗时 48s</Tag><Tag>扫描 12 GB</Tag><Tag>行数 12,000</Tag></Space>
        <Table size="small" pagination={{ pageSize: 10 }}
          dataSource={[{ key: 1, order_id: 1001, amount: 99.0, dt: '2026-06-14' }, { key: 2, order_id: 1002, amount: 158.5, dt: '2026-06-14' }]}
          columns={[
            { title: 'order_id', dataIndex: 'order_id' },
            { title: 'amount', dataIndex: 'amount' },
            { title: 'dt', dataIndex: 'dt' },
          ]} />
        <Space style={{ marginTop: 12 }}>
          <Button icon={<SaveOutlined />} onClick={() => message.success('已另存为模型')}>另存为模型</Button>
          <Button icon={<ApiOutlined />} onClick={() => navigate('/dataservice/apis/new')}>发布为 API</Button>
          <Button icon={<ClusterOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>加入流水线</Button>
        </Space>
      </>
    ) },
    { key: 'chart', label: '图表', children: <Card>图表区（echarts 可选）</Card> },
    { key: 'history', label: '查询历史', children: (
      <Table size="small" rowKey="id" dataSource={sqlHistory}
        columns={[
          { title: '时间', dataIndex: 'at' },
          { title: '运行人', dataIndex: 'runner' },
          { title: '扫描量', dataIndex: 'scanBytes', render: (v: number) => `${(v / 1e9).toFixed(2)} GB` },
          { title: '耗时', dataIndex: 'durationMs', render: (d: number) => d ? `${(d/1000).toFixed(1)}s` : '-' },
          { title: '状态', dataIndex: 'ok', render: (o: boolean) => <Tag color={o ? 'success' : 'error'}>{o ? '✓' : '✗'}</Tag> },
          { title: 'SQL', dataIndex: 'sql', ellipsis: true },
          { title: '操作', render: (_: unknown, r: any) => <Space><a>重新运行</a><a onClick={() => navigate('/dataservice/apis/new')}>发布为 API</a></Space> },
        ]} />
    ) },
    { key: 'saved', label: '保存的查询', children: (
      <Table size="small" rowKey="id" dataSource={savedQueries}
        columns={[
          { title: '名称', dataIndex: 'name' },
          { title: '负责人', dataIndex: 'owner' },
          { title: '共享', dataIndex: 'shared', render: (s: boolean) => <Tag>{s ? '团队共享' : '私有'}</Tag> },
          { title: '操作', render: () => <Space><a>重新运行</a><a>分享</a></Space> },
        ]} />
    ) },
  ];

  return (
    <Card title="湖仓 / SQL 工作台" extra={
      <Space>
        <Select defaultValue="auto" options={[{ label: '自动路由 → Trino', value: 'auto' }, { label: 'Spark', value: 'spark' }]} style={{ width: 180 }} />
        <Select defaultValue="rg-default" options={[{ label: '资源组: 默认', value: 'rg-default' }, { label: '资源组: 大查询', value: 'rg-big' }]} style={{ width: 160 }} />
      </Space>
    }>
      <Row gutter={16}>
        <Col span={5}>
          <Card size="small" title="表树">
            <Tree treeData={lakehouseAssets.map((a) => ({ title: a.fqn, key: a.id }))} />
          </Card>
        </Col>
        <Col span={19}>
          <Card size="small" title={<Space><PlayCircleOutlined style={{ color: '#52c41a' }} />运行</Space>}
            extra={<Space>
              <Tooltip title="格式化"><Button size="small" icon={<FormatPainterOutlined />} /></Tooltip>
              <Button type="primary" size="small" icon={<PlayCircleOutlined />} onClick={() => message.success('SQL 已提交，48s 完成')}>运行</Button>
            </Space>}>
            <div style={{ height: 200, border: '1px solid #f0f0f0' }}>
              <Editor defaultLanguage="sql" value={sql} onChange={(v) => setSql(v || '')} theme="vs" />
            </div>
          </Card>
          <Alert type="warning" message="⚠ 预估扫描 1.2TB，超阈需确认" style={{ marginTop: 12 }} />
          <Card size="small" style={{ marginTop: 12 }}>
            <Tabs items={tabs} />
          </Card>
        </Col>
      </Row>
    </Card>
  );
}
