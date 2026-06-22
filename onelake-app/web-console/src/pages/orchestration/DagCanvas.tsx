/**
 * DAG 画布编辑器（对应原型 §8.4.1 ~ §8.4.5）。
 * 三区：左算子面板 + 中画布（节点连线 SVG 模拟）+ 右属性面板。
 * 含试运行 / 版本管理 / DAG 校验结果 / 发布确认。
 */
import { Row, Col, Space, Button, Select, Tag, Typography, Drawer, Form, Input, Alert, Modal, message } from 'antd';
import {
  PlayCircleOutlined, SaveOutlined, CheckCircleOutlined, WarningOutlined,
  DatabaseOutlined, FilterOutlined, LockOutlined, ExportOutlined, AppstoreOutlined, CodeOutlined,
} from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { ClassificationBadge, PageHeader, SectionCard } from '../../components';
import { OrchestrationAPI } from '../../api';
import type { Dag } from '../../types';

const { Text, Title } = Typography;

interface Node { id: string; type: string; name: string; x: number; y: number; sql?: string; engine?: string; }
interface Edge { from: string; to: string; valid: boolean; }

const OPERATORS = [
  { category: '输入', items: [
    { key: 'input-table', icon: <DatabaseOutlined />, name: '表/查询', color: '#1677ff' },
    { key: 'SQL', icon: <CodeOutlined />, name: 'SQL 节点', color: '#0f766e' },
  ]},
  { category: '治理', items: [
    { key: 'clean', icon: <FilterOutlined />, name: '清洗去重', color: '#52c41a' },
    { key: 'mdm', icon: <FilterOutlined />, name: 'MDM 主数据', color: '#52c41a' },
  ]},
  { category: '脱敏/加密', items: [
    { key: 'mask', icon: <LockOutlined />, name: '脱敏', color: '#fa8c16' },
    { key: 'encrypt', icon: <LockOutlined />, name: '字段加密', color: '#fa541c' },
  ]},
  { category: '输出', items: [
    { key: 'output', icon: <ExportOutlined />, name: '输出', color: '#722ed1' },
  ]},
];

const INITIAL_NODES: Node[] = [
  { id: 'n1', type: 'input-table', name: 'ods.orders', x: 60, y: 80 },
  { id: 'n2', type: 'clean', name: '去重', x: 280, y: 80 },
  { id: 'n3', type: 'mask', name: '脱敏 phone', x: 500, y: 80 },
  { id: 'n4', type: 'output', name: 'dws_user', x: 720, y: 80 },
];

const INITIAL_EDGES: Edge[] = [
  { from: 'n1', to: 'n2', valid: true },
  { from: 'n2', to: 'n3', valid: true },
  { from: 'n3', to: 'n4', valid: true },
];

export default function DagCanvas() {
  const { id } = useParams();
  const location = useLocation();
  const incoming = (location.state || {}) as { dag?: Dag; sql?: string };
  const [env, setEnv] = useState('dev');
  const [pipelineName, setPipelineName] = useState('order_pipeline');
  const [nodes, setNodes] = useState<Node[]>(INITIAL_NODES);
  const [edges, setEdges] = useState<Edge[]>(INITIAL_EDGES);
  const [selected, setSelected] = useState<Node | null>(INITIAL_NODES[2]);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [validateOpen, setValidateOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);

  useEffect(() => {
    const applyDag = (dag: Dag) => {
      setPipelineName(dag.name);
      const definition = Array.isArray(dag.definition) ? { nodes: dag.definition, edges: dag.edges || [] } : dag.definition;
      const draftNodes = ((definition.nodes || []) as any[]).map((node, index) => ({
        id: String(node.id || `n-${index + 1}`),
        type: String(node.type || 'SQL'),
        name: String(node.name || node.type || `节点 ${index + 1}`),
        sql: typeof node.sql === 'string' ? node.sql : undefined,
        engine: typeof node.engine === 'string' ? node.engine : undefined,
        x: typeof node.x === 'number' ? node.x : 80 + index * 220,
        y: typeof node.y === 'number' ? node.y : 100,
      }));
      const draftEdges = ((definition.edges || []) as any[]).map((edge) => ({
        from: String(edge.from || edge.source),
        to: String(edge.to || edge.target),
        valid: edge.valid !== false,
      }));
      if (draftNodes.length > 0) {
        setNodes(draftNodes);
        setEdges(draftEdges);
        setSelected(draftNodes[0]);
      }
    };
    if (incoming.dag) {
      applyDag(incoming.dag);
      return;
    }
    if (id && id !== 'new' && !id.startsWith('p-')) {
      OrchestrationAPI.getDag(id)
        .then(applyDag)
        .catch((e) => message.error(e.message || '流水线草稿加载失败'));
    }
  }, [id, incoming.dag]);

  const renderNode = (n: Node) => {
    const op = OPERATORS.flatMap((c) => c.items).find((i) => i.key === n.type)
      || { key: n.type, icon: <CodeOutlined />, name: n.type, color: '#0f766e' };
    const isMask = n.type === 'mask';
    return (
      <div key={n.id} onClick={() => setSelected(n)}
        style={{
          position: 'absolute', left: n.x, top: n.y, width: 140, padding: 10,
          background: '#fff', border: `2px solid ${selected?.id === n.id ? 'var(--ol-brand)' : op.color}`,
          borderRadius: 8, boxShadow: 'var(--ol-shadow-e2)', cursor: 'pointer',
          transition: 'box-shadow var(--ol-dur-fast) var(--ol-ease)',
        }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: op.color, fontWeight: 600 }}>
          {op.icon}{n.name}
        </div>
        {isMask && <div style={{ marginTop: 4 }}><ClassificationBadge level="L3" size="small" /></div>}
        <Tag style={{ marginTop: 6 }} color="success">质量门禁</Tag>
      </div>
    );
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title={
          <Space size={8}>
            流水线
            <Text code style={{ fontSize: 14 }}>{pipelineName}</Text>
            <Tag color={pipelineName.startsWith('sql_workbench_') ? 'default' : 'processing'} style={{ margin: 0 }}>
              {pipelineName.startsWith('sql_workbench_') ? '草稿' : '已配置'}
            </Tag>
          </Space>
        }
        subtitle={<span className="ol-chip">编排 · L4-4.1</span>}
        description="三区编辑：算子面板 / DAG 画布 / 属性面板，含试运行、版本管理、校验、发布"
        actions={
          <>
            <Select value={env} onChange={setEnv} style={{ width: 110 }}
              options={['dev', 'test', 'prod'].map((e) => ({ label: e, value: e }))} />
            <Button onClick={() => setValidateOpen(true)}>校验</Button>
            <Button onClick={() => setPreviewOpen(true)} icon={<PlayCircleOutlined />}>试运行</Button>
            <Button icon={<SaveOutlined />}>保存</Button>
            <Button onClick={() => setVersionOpen(true)}>版本</Button>
            <Button type="primary" onClick={() => setPublishOpen(true)}>发布</Button>
          </>
        }
      />

      <Row gutter={12}>
        {/* 左算子面板 */}
        <Col xs={24} lg={4}>
          <SectionCard title="算子" icon={<AppstoreOutlined />} padded="sm" style={{ height: '100%' }}>
            {OPERATORS.map((c) => (
              <div key={c.category} style={{ marginBottom: 12 }}>
                <Text type="secondary" style={{ fontSize: 11, fontWeight: 600 }}>{c.category}</Text>
                <div style={{ marginTop: 6 }}>
                  {c.items.map((it) => (
                    <div key={it.key} style={{
                      padding: '6px 8px', border: '1px dashed var(--ol-line)',
                      borderRadius: 6, marginBottom: 4, cursor: 'grab',
                      display: 'flex', alignItems: 'center', gap: 6,
                      fontSize: 12, transition: 'all var(--ol-dur-fast) var(--ol-ease)',
                    }}
                      onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--ol-brand)'; e.currentTarget.style.background = 'var(--ol-brand-soft)'; }}
                      onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--ol-line)'; e.currentTarget.style.background = 'transparent'; }}
                    >
                      <span style={{ color: it.color }}>{it.icon}</span>{it.name}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </SectionCard>
        </Col>

        {/* 中画布 */}
        <Col xs={24} lg={14}>
          <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
            <div style={{
              height: 460, position: 'relative', background: 'var(--ol-fill-soft)',
              backgroundImage: 'radial-gradient(var(--ol-line) 1px, transparent 1px)',
              backgroundSize: '20px 20px', borderRadius: 'inherit',
            }}>
              <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}>
                {edges.map((e, i) => {
                  const from = nodes.find((n) => n.id === e.from)!;
                  const to = nodes.find((n) => n.id === e.to)!;
                  const x1 = from.x + 140, y1 = from.y + 24;
                  const x2 = to.x, y2 = to.y + 24;
                  return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
                    stroke={e.valid ? 'var(--ol-brand)' : 'var(--ol-error)'}
                    strokeWidth={2} markerEnd="url(#arrow)" />;
                })}
                <defs>
                  <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                    <path d="M0,0 L0,6 L9,3 z" fill="var(--ol-brand)" />
                  </marker>
                </defs>
              </svg>
              {nodes.map(renderNode)}
            </div>
          </SectionCard>
        </Col>

        {/* 右属性面板 */}
        <Col xs={24} lg={6}>
          <SectionCard title="属性" icon={<FilterOutlined />} padded="sm" style={{ height: '100%' }}>
            {selected && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>节点</Text>
                  <div style={{ marginTop: 4 }}><Text strong>{selected.name}</Text></div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>字段</Text>
                  <div style={{ marginTop: 4 }}><Text code style={{ fontSize: 12 }}>{selected.type === 'SQL' ? selected.engine || 'TRINO' : 'phone'}</Text></div>
                </div>
                {selected.sql && (
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>SQL</Text>
                    <pre style={{ marginTop: 4, padding: 10, background: 'var(--ol-fill-soft)', borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap' }}>{selected.sql}</pre>
                  </div>
                )}
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>算法</Text>
                  <div style={{ marginTop: 4 }}>
                    <Select defaultValue="mask" style={{ width: '100%' }}
                      options={['MASK', 'HASH', 'NULLIFY', 'PARTIAL'].map((v) => ({ label: v, value: v }))} />
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>默认按密级联动</Text>
                  <div style={{ marginTop: 4 }}><ClassificationBadge level="L3" /></div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>参数</Text>
                  <div style={{ marginTop: 4 }}><Input defaultValue={'${run_date}'} /></div>
                </div>
              </div>
            )}
          </SectionCard>
        </Col>
      </Row>

      {/* 试运行抽屉 */}
      <Drawer open={previewOpen} onClose={() => setPreviewOpen(false)} title="试运行 @dev" width={680}>
        <Alert type="info" showIcon message="节点进度：ods ✅ → 去重 ✅ → 脱敏 ⏳ → 输出 ○（试运行不落正式表）" style={{ marginBottom: 16 }} />
        <Title level={5}>选中"脱敏"节点 - 输出采样预览</Title>
        <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
          <table style={{ width: '100%', fontSize: 12 }}>
            <thead><tr style={{ background: 'var(--ol-fill-soft)' }}>
              <th style={{ padding: '8px 12px', textAlign: 'left' }}>order_id</th>
              <th style={{ padding: '8px 12px', textAlign: 'left' }}>phone</th>
              <th style={{ padding: '8px 12px', textAlign: 'left' }}>amount</th>
            </tr></thead>
            <tbody>
              <tr style={{ borderTop: '1px solid var(--ol-line-soft)' }}>
                <td style={{ padding: '8px 12px' }}>1001</td>
                <td style={{ padding: '8px 12px' }}><Text code style={{ fontSize: 12 }}>138****8888</Text></td>
                <td style={{ padding: '8px 12px' }}>99.0</td>
              </tr>
              <tr style={{ borderTop: '1px solid var(--ol-line-soft)' }}>
                <td style={{ padding: '8px 12px' }}>1002</td>
                <td style={{ padding: '8px 12px' }}><Text code style={{ fontSize: 12 }}>139****1234</Text></td>
                <td style={{ padding: '8px 12px' }}>158.5</td>
              </tr>
            </tbody>
          </table>
        </SectionCard>
      </Drawer>

      {/* 版本管理 */}
      <Modal open={versionOpen} onCancel={() => setVersionOpen(false)} title="版本管理" footer={null} width={680}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          {[
            { v: 3, at: '06-14 10:00', author: '张三', cur: true },
            { v: 2, at: '06-10 18:00', author: '张三' },
            { v: 1, at: '05-15 09:00', author: '张三' },
          ].map((x) => (
            <div key={x.v} className="ol-section" style={{ padding: 12 }}>
              <Space>
                <Tag color={x.cur ? 'blue' : 'default'} style={{ margin: 0 }}>v{x.v}{x.cur ? ' (当前)' : ''}</Tag>
                <Text style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{x.at}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>{x.author}</Text>
                <Button size="small">diff 对比</Button>
                {!x.cur && <Button size="small" danger onClick={() => message.success(`已回滚到 v${x.v}`)}>回滚</Button>}
              </Space>
            </div>
          ))}
        </Space>
      </Modal>

      {/* DAG 校验结果 */}
      <Modal open={validateOpen} onCancel={() => setValidateOpen(false)} title="校验结果 - order_pipeline"
        footer={<Button type="primary" onClick={() => setValidateOpen(false)}>一键定位首个错误</Button>}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert type="error" showIcon message="✗ 2 错误" description={(
            <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12 }}>
              <li>环路检查：节点 B→C→B 存在环 <a className="ol-link">定位</a></li>
              <li>Schema：脱敏节点输入缺 phone 字段 <a className="ol-link">定位</a></li>
            </ul>
          )} />
          <Alert type="warning" showIcon message="⚠ 1 警告" description={<span style={{ fontSize: 12 }}>参数 run_date 变量未在 prod 定义</span>} />
          <Alert type="success" showIcon message="✓ 5 通过" description={<span style={{ fontSize: 12 }}>权限 / 质量门禁 / 安全策略</span>} />
        </Space>
      </Modal>

      {/* 发布确认 */}
      <Modal open={publishOpen} onCancel={() => setPublishOpen(false)} title="发布确认 - order_pipeline v4"
        footer={[
          <Button key="c" onClick={() => setPublishOpen(false)}>取消</Button>,
          <Button key="s" onClick={() => { setPublishOpen(false); message.success('已提交审批'); }}>提交审批</Button>,
          <Button key="p" type="primary" onClick={() => { setPublishOpen(false); message.success('已发布到 prod (灰度 10%)'); }}>发布</Button>,
        ]}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>环境</Text>
            <div style={{ marginTop: 4 }}><Tag color="error">prod</Tag></div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>变更摘要</Text>
            <div style={{ marginTop: 4, fontSize: 13 }}>+脱敏节点, 改调度</div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>影响任务</Text>
            <div style={{ marginTop: 4 }}>下游 2 流水线</div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>质量门禁</Text>
            <div style={{ marginTop: 4 }}><Tag color="success">✓ 通过</Tag></div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>发布策略</Text>
            <div style={{ marginTop: 4 }}>
              <Select defaultValue="gray10" style={{ width: '100%' }}
                options={[{ label: '灰度 10%', value: 'gray10' }, { label: '全量', value: 'full' }]} />
            </div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>回滚点</Text>
            <div style={{ marginTop: 4 }}><Text code>v3</Text></div>
          </div>
        </div>
      </Modal>
    </div>
  );
}
