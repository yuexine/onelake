/**
 * 血缘图（对应原型 §8.6.3 升级版）。
 *   SVG 节点连线，支持字段级切换 + 影响分析
 */
import { Space, Switch, Tag, Button, Typography, Alert, Drawer, List } from 'antd';
import { BranchesOutlined, ExportOutlined, NodeIndexOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { lakehouseAssets, lineageEdges } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

interface GNode { id: string; layer: number; x: number; y: number; label: string; }

const LAYER_BORDER: Record<number, string> = {
  0: 'var(--ol-ink-3)',
  1: 'var(--ol-brand)',
  2: '#0369A1',
  3: '#B45309',
  4: 'var(--ol-success)',
  5: '#7C3AED',
};

const LAYER_NAME: Record<number, string> = {
  0: '源', 1: 'ODS', 2: 'DWD', 3: 'DWS', 4: 'ADS', 5: 'API',
};

export default function LineageGraph() {
  const [columnLevel, setColumnLevel] = useState(false);
  const [impactOpen, setImpactOpen] = useState(false);
  const [selected, setSelected] = useState<string>('dwd.dwd_order_df');

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
    <div className="ol-page">
      <PageHeader
        icon={<BranchesOutlined />}
        title={
          <Space size={8}>
            血缘图
            <Text code style={{ fontSize: 13 }}>{selected}</Text>
          </Space>
        }
        subtitle={<span className="ol-chip">数据目录 · L3-3</span>}
        description="表级 / 字段级血缘切换，选中节点查看下游影响"
        actions={
          <>
            <Space size={6} style={{ padding: '4px 10px', background: 'var(--ol-fill)', borderRadius: 6 }}>
              <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>字段级</Text>
              <Switch size="small" checked={columnLevel} onChange={setColumnLevel} />
            </Space>
            <Button onClick={() => setImpactOpen(true)} icon={<NodeIndexOutlined />}>分析下游影响</Button>
            <Button icon={<ExportOutlined />}>导出影响报告</Button>
          </>
        }
      />

      <Alert type="info" showIcon message={<span style={{ fontSize: 13 }}>点击节点选中，再次点击「分析下游影响」查看完整链路</span>} />

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div
          style={{
            height: 500, position: 'relative', background: 'var(--ol-fill-soft)',
            backgroundImage: 'radial-gradient(var(--ol-line) 1px, transparent 1px)',
            backgroundSize: '24px 24px', overflow: 'auto',
            borderRadius: 'inherit',
          }}
        >
          <svg style={{ position: 'absolute', inset: 0, width: 1400, height: 520, pointerEvents: 'none' }}>
            {lineageEdges.map((e, i) => {
              const from = nodes.find((n) => n.id === e.upstreamFqn)!;
              const to = nodes.find((n) => n.id === e.downstreamFqn)!;
              if (!from || !to) return null;
              return <line key={i} x1={from.x + 160} y1={from.y + 16} x2={to.x} y2={to.y + 16}
                stroke="var(--ol-brand)" strokeWidth={2} markerEnd="url(#arr)" opacity={0.7} />;
            })}
            <defs>
              <marker id="arr" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L9,3 z" fill="var(--ol-brand)" />
              </marker>
            </defs>
          </svg>
          {nodes.map((n) => {
            const border = LAYER_BORDER[n.layer] || 'var(--ol-brand)';
            const isSelected = selected === n.id;
            const isImpacted = impact.includes(n.id);
            return (
              <div
                key={n.id}
                onClick={() => setSelected(n.id)}
                style={{
                  position: 'absolute', left: n.x, top: n.y, width: 160, padding: 10,
                  background: '#fff', borderRadius: 8,
                  border: `2px solid ${isSelected ? '#DC2626' : isImpacted ? '#F97316' : border}`,
                  boxShadow: isSelected ? 'var(--ol-shadow-e2)' : 'var(--ol-shadow-e1)',
                  cursor: 'pointer', fontSize: 12,
                  transition: 'all var(--ol-dur-fast) var(--ol-ease)',
                }}
              >
                <Tag style={{
                  margin: 0, padding: '0 6px', fontSize: 10, fontWeight: 600,
                  background: `${border}15`, color: border, border: `1px solid ${border}40`,
                }}>{LAYER_NAME[n.layer]}</Tag>
                <div style={{ fontWeight: 600, marginTop: 4 }}>{n.label}</div>
              </div>
            );
          })}
        </div>
      </SectionCard>

      <Drawer open={impactOpen} onClose={() => setImpactOpen(false)} title="下游影响分析" width={520}>
        <Alert type="warning" showIcon message={<Space><Text>选中：</Text><Text code style={{ fontSize: 12 }}>{selected}</Text></Space>} style={{ marginBottom: 16 }} />
        <List
          header={<Text strong style={{ fontSize: 13 }}>直接下游</Text>}
          size="small"
          bordered
          dataSource={lineageEdges.filter((e) => e.upstreamFqn === selected).map((e) => e.downstreamFqn)}
          renderItem={(i) => <List.Item><Text code style={{ fontSize: 12 }}>{i}</Text></List.Item>}
        />
        <List
          style={{ marginTop: 12 }}
          header={<Text strong style={{ fontSize: 13 }}>间接下游</Text>}
          size="small"
          bordered
          dataSource={impact}
          renderItem={(i) => <List.Item><Text code style={{ fontSize: 12 }}>{i}</Text></List.Item>}
        />
        <div className="ol-section" style={{ marginTop: 16, padding: 14 }}>
          <Space direction="vertical" size={4}>
            <Text>受影响任务：<Text strong>3</Text></Text>
            <Text>受影响 API：<Text strong>1</Text></Text>
            <Text>受影响订阅方：<Text strong>18</Text></Text>
            <Tag color="error" style={{ marginTop: 6 }}>严重程度：高（含对外 API）</Tag>
          </Space>
        </div>
      </Drawer>
    </div>
  );
}
