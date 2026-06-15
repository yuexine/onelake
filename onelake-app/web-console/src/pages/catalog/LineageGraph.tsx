/**
 * 血缘图（对应原型 §8.6.3）。
 * SVG 节点连线，支持字段级切换 + 影响分析。
 */
import { Card, Space, Switch, Tag, Button, Typography, Alert, Drawer, List } from 'antd';
import { useState } from 'react';
import { lakehouseAssets, lineageEdges } from '../../mock';

const { Text } = Typography;

interface GNode { id: string; layer: number; x: number; y: number; label: string; }

export default function LineageGraph() {
  const [columnLevel, setColumnLevel] = useState(false);
  const [impactOpen, setImpactOpen] = useState(false);
  const [selected, setSelected] = useState<string>('dwd.dwd_order_df');

  // 简单层级布局
  const layerOf = (fqn: string) => {
    if (fqn.startsWith('mysql')) return 0;
    if (fqn.startsWith('ods')) return 1;
    if (fqn.startsWith('dwd')) return 2;
    if (fqn.startsWith('dws')) return 3;
    if (fqn.startsWith('ads')) return 4;
    if (fqn.startsWith('API')) return 5;
    return 3;
  };

  const fqnSet = new Set<string>();
  lineageEdges.forEach((e) => { fqnSet.add(e.upstreamFqn); fqnSet.add(e.downstreamFqn); });
  const byLayer: Record<number, string[]> = {};
  Array.from(fqnSet).forEach((f) => {
    const l = layerOf(f);
    byLayer[l] = byLayer[l] || [];
    byLayer[l].push(f);
  });

  const nodes: GNode[] = Object.entries(byLayer).flatMap(([l, list]) =>
    list.map((fqn, i) => ({
      id: fqn, layer: +l,
      x: 40 + +l * 220,
      y: 60 + i * 90,
      label: fqn,
    }))
  );

  const downstream = (root: string): string[] => {
    const direct = lineageEdges.filter((e) => e.upstreamFqn === root).map((e) => e.downstreamFqn);
    const indirect = direct.flatMap((d) => downstream(d)).filter((x) => x !== root);
    return Array.from(new Set([...direct, ...indirect]));
  };

  const impact = downstream(selected);

  return (
    <Card title={<Space><Text strong>血缘图</Text> · {selected}<Tag>字段级 <Switch size="small" checked={columnLevel} onChange={setColumnLevel} /></Tag></Space>}
      extra={<Space>
        <Button onClick={() => setImpactOpen(true)}>分析下游影响</Button>
        <Button>导出影响报告</Button>
      </Space>}>
      <Alert type="info" message="点击节点选中，再次点击「分析下游影响」查看完整链路" style={{ marginBottom: 12 }} />

      <div style={{ height: 480, position: 'relative', background: '#fafafa', backgroundImage: 'radial-gradient(#e0e0e0 1px, transparent 1px)', backgroundSize: '24px 24px', overflow: 'auto' }}>
        <svg style={{ position: 'absolute', inset: 0, width: 1400, height: 500, pointerEvents: 'none' }}>
          {lineageEdges.map((e, i) => {
            const from = nodes.find((n) => n.id === e.upstreamFqn)!;
            const to = nodes.find((n) => n.id === e.downstreamFqn)!;
            if (!from || !to) return null;
            return <line key={i} x1={from.x + 160} y1={from.y + 16} x2={to.x} y2={to.y + 16} stroke="#1677ff" strokeWidth={2} markerEnd="url(#arr)" />;
          })}
          <defs>
            <marker id="arr" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L0,6 L9,3 z" fill="#1677ff" />
            </marker>
          </defs>
        </svg>
        {nodes.map((n) => (
          <div key={n.id} onClick={() => setSelected(n.id)}
            style={{
              position: 'absolute', left: n.x, top: n.y, width: 160, padding: 10,
              background: '#fff', borderRadius: 6,
              border: `2px solid ${selected === n.id ? '#fa541c' : impact.includes(n.id) ? '#ff7a45' : '#1677ff'}`,
              cursor: 'pointer', fontSize: 12,
            }}>
            <Tag color={n.layer === 0 ? 'default' : n.layer === 5 ? 'purple' : 'blue'}>L{n.layer}</Tag>
            <div style={{ fontWeight: 600 }}>{n.label}</div>
          </div>
        ))}
      </div>

      <Drawer open={impactOpen} onClose={() => setImpactOpen(false)} title="影响分析" width={520}>
        <Alert type="warning" message={<>选中：<Text code>{selected}</Text></>} style={{ marginBottom: 12 }} />
        <List header={<Text strong>直接下游</Text>} size="small" bordered dataSource={lineageEdges.filter((e) => e.upstreamFqn === selected).map((e) => e.downstreamFqn)} renderItem={(i) => <List.Item>{i}</List.Item>} />
        <List style={{ marginTop: 12 }} header={<Text strong>间接下游</Text>} size="small" bordered dataSource={impact} renderItem={(i) => <List.Item>{i}</List.Item>} />
        <Card size="small" style={{ marginTop: 12 }}>
          <Space direction="vertical">
            <Text>受影响任务：3</Text>
            <Text>受影响 API：1</Text>
            <Text>受影响订阅方：18</Text>
            <Tag color="red">严重程度：高（含对外 API）</Tag>
          </Space>
        </Card>
      </Drawer>
    </Card>
  );
}
