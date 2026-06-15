/**
 * DAG 画布编辑器（对应原型 §8.4.1 ~ §8.4.5）。
 * 三区：左算子面板 + 中画布（节点连线 SVG 模拟）+ 右属性面板。
 * 含试运行 / 版本管理 / DAG 校验结果 / 发布确认。
 */
import { Card, Row, Col, Space, Button, Select, Tag, Typography, Drawer, Descriptions, Form, Input, Alert, Modal, message } from 'antd';
import {
  PlayCircleOutlined, SaveOutlined, CheckCircleOutlined, WarningOutlined,
  DatabaseOutlined, FilterOutlined, LockOutlined, ExportOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { ClassificationBadge, StatusBadge } from '../../components';

const { Text, Title } = Typography;

interface Node { id: string; type: string; name: string; x: number; y: number; }
interface Edge { from: string; to: string; valid: boolean; }

const OPERATORS = [
  { category: '输入', items: [
    { key: 'input-table', icon: <DatabaseOutlined />, name: '表/查询', color: '#1677ff' },
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
  const [env, setEnv] = useState('dev');
  const [nodes] = useState<Node[]>(INITIAL_NODES);
  const [edges] = useState<Edge[]>(INITIAL_EDGES);
  const [selected, setSelected] = useState<Node | null>(INITIAL_NODES[2]);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [validateOpen, setValidateOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);

  const renderNode = (n: Node) => {
    const op = OPERATORS.flatMap((c) => c.items).find((i) => i.key === n.type)!;
    const isMask = n.type === 'mask';
    return (
      <div key={n.id} onClick={() => setSelected(n)}
        style={{
          position: 'absolute', left: n.x, top: n.y, width: 140, padding: 10,
          background: '#fff', border: `2px solid ${selected?.id === n.id ? '#1677ff' : op.color}`,
          borderRadius: 8, boxShadow: '0 2px 6px rgba(0,0,0,0.08)', cursor: 'pointer',
        }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: op.color, fontWeight: 600 }}>
          {op.icon}{n.name}
        </div>
        {isMask && <div style={{ marginTop: 4 }}><ClassificationBadge level="L3" size="small" /></div>}
        <Tag style={{ marginTop: 6 }} color="success">🚦质量门禁</Tag>
      </div>
    );
  };

  return (
    <Card title={
      <Space>
        <Title level={5} style={{ margin: 0 }}>流水线 / order_pipeline</Title>
        <Tag color="processing">已配置</Tag>
      </Space>
    } extra={
      <Space>
        <Select value={env} onChange={setEnv} style={{ width: 120 }}
          options={['dev', 'test', 'prod'].map((e) => ({ label: e, value: e }))} />
        <Button onClick={() => setValidateOpen(true)}>校验</Button>
        <Button onClick={() => setPreviewOpen(true)}>试运行</Button>
        <Button icon={<SaveOutlined />}>保存</Button>
        <Button onClick={() => setVersionOpen(true)}>版本</Button>
        <Button type="primary" onClick={() => setPublishOpen(true)}>发布</Button>
      </Space>
    }>
      <Row gutter={12}>
        {/* 左算子面板 */}
        <Col span={4}>
          <Card size="small" title="算子" style={{ height: '100%' }}>
            {OPERATORS.map((c) => (
              <div key={c.category}>
                <Text type="secondary">{c.category}</Text>
                <div style={{ marginBottom: 8 }}>
                  {c.items.map((it) => (
                    <div key={it.key} style={{ padding: '6px 8px', border: '1px dashed #d9d9d9', borderRadius: 4, marginBottom: 4, cursor: 'grab', display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ color: it.color }}>{it.icon}</span>{it.name}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </Card>
        </Col>

        {/* 中画布 */}
        <Col span={14}>
          <Card size="small" bodyStyle={{ height: 420, position: 'relative', background: '#fafafa', backgroundImage: 'radial-gradient(#e0e0e0 1px, transparent 1px)', backgroundSize: '20px 20px' }}>
            <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}>
              {edges.map((e, i) => {
                const from = nodes.find((n) => n.id === e.from)!;
                const to = nodes.find((n) => n.id === e.to)!;
                const x1 = from.x + 140, y1 = from.y + 24;
                const x2 = to.x, y2 = to.y + 24;
                return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke={e.valid ? '#1677ff' : '#ff4d4f'} strokeWidth={2} markerEnd="url(#arrow)" />;
              })}
              <defs>
                <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                  <path d="M0,0 L0,6 L9,3 z" fill="#1677ff" />
                </marker>
              </defs>
            </svg>
            {nodes.map(renderNode)}
          </Card>
        </Col>

        {/* 右属性面板 */}
        <Col span={6}>
          <Card size="small" title="属性" style={{ height: '100%' }}>
            {selected && (
              <>
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="节点">{selected.name}</Descriptions.Item>
                  <Descriptions.Item label="字段">phone</Descriptions.Item>
                  <Descriptions.Item label="算法">
                    <Select defaultValue="mask" style={{ width: '100%' }} options={['MASK', 'HASH', 'NULLIFY', 'PARTIAL'].map((v: any) => ({ label: v, value: v }))} />
                  </Descriptions.Item>
                  <Descriptions.Item label="默认按密级联动"><ClassificationBadge level="L3" /></Descriptions.Item>
                  <Descriptions.Item label="参数"><Input defaultValue={'${run_date}'} /></Descriptions.Item>
                </Descriptions>
              </>
            )}
          </Card>
        </Col>
      </Row>

      {/* 试运行抽屉（§8.4.2） */}
      <Drawer open={previewOpen} onClose={() => setPreviewOpen(false)} title="试运行 @dev" width={680}
        extra={<Space><Tag color="processing">脱敏 ⏳</Tag></Space>}>
        <Alert type="info" message="节点进度：ods✅ → 去重✅ → 脱敏⏳ → 输出○（试运行不落正式表）" style={{ marginBottom: 16 }} />
        <Title level={5}>选中"脱敏"节点 - 输出采样预览</Title>
        <Card size="small">
          <table style={{ width: '100%' }}>
            <thead><tr><th>order_id</th><th>phone</th><th>amount</th></tr></thead>
            <tbody>
              <tr><td>1001</td><td>138****8888</td><td>99.0</td></tr>
              <tr><td>1002</td><td>139****1234</td><td>158.5</td></tr>
            </tbody>
          </table>
        </Card>
      </Drawer>

      {/* 版本管理（§8.4.3） */}
      <Modal open={versionOpen} onCancel={() => setVersionOpen(false)} title="版本管理" footer={null} width={680}>
        <Space direction="vertical" style={{ width: '100%' }}>
          {[
            { v: 3, at: '06-14 10:00', author: '张三', cur: true },
            { v: 2, at: '06-10 18:00', author: '张三' },
            { v: 1, at: '05-15 09:00', author: '张三' },
          ].map((x) => (
            <Card key={x.v} size="small">
              <Space>
                <Tag color={x.cur ? 'blue' : 'default'}>v{x.v}{x.cur ? ' (当前)' : ''}</Tag>
                <Text>{x.at}</Text><Text type="secondary">{x.author}</Text>
                <Button size="small">diff 对比</Button>
                {!x.cur && <Button size="small" danger onClick={() => message.success(`已回滚到 v${x.v}`)}>回滚</Button>}
              </Space>
            </Card>
          ))}
        </Space>
      </Modal>

      {/* DAG 校验结果（§8.4.4） */}
      <Modal open={validateOpen} onCancel={() => setValidateOpen(false)} title="校验结果 - order_pipeline" footer={<Button type="primary" onClick={() => setValidateOpen(false)}>一键定位首个错误</Button>}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert type="error" message="✗ 2 错误" description={(
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              <li>环路检查：节点B→C→B 存在环 <a>定位</a></li>
              <li>Schema：脱敏节点输入缺 phone 字段 <a>定位</a></li>
            </ul>
          )} />
          <Alert type="warning" message="⚠ 1 警告" description="参数 run_date 变量未在 prod 定义 <a>定位</a>" />
          <Alert type="success" message="✓ 5 通过" description="权限 / 质量门禁 / 安全策略" />
        </Space>
      </Modal>

      {/* 发布确认（§8.4.5） */}
      <Modal open={publishOpen} onCancel={() => setPublishOpen(false)} title="发布确认 - order_pipeline v4"
        footer={[<Button key="c" onClick={() => setPublishOpen(false)}>取消</Button>,
                 <Button key="s" onClick={() => { setPublishOpen(false); message.success('已提交审批'); }}>提交审批</Button>,
                 <Button key="p" type="primary" onClick={() => { setPublishOpen(false); message.success('已发布到 prod (灰度 10%)'); }}>发布</Button>]}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="环境">prod</Descriptions.Item>
          <Descriptions.Item label="变更摘要">+脱敏节点, 改调度</Descriptions.Item>
          <Descriptions.Item label="影响任务">下游 2 流水线</Descriptions.Item>
          <Descriptions.Item label="质量门禁"><Tag color="success">✅ 通过</Tag></Descriptions.Item>
          <Descriptions.Item label="审批"><Tag color="processing">● 待审批</Tag></Descriptions.Item>
          <Descriptions.Item label="发布策略"><Select defaultValue="gray10" options={[{ label: '灰度 10%', value: 'gray10' }, { label: '全量', value: 'full' }].map((v: any) => ({ label: v, value: v }))} /></Descriptions.Item>
          <Descriptions.Item label="回滚点"><Text code>v3</Text></Descriptions.Item>
        </Descriptions>
      </Modal>
    </Card>
  );
}
