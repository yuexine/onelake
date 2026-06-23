/**
 * 血缘图（对应原型 §8.6.3 升级版 + 《血缘图模块完善设计方案》§5.3）。
 *   底层改用 @antv/x6 渲染（已安装），dagre 布局（可选，未装时降级到网格布局）。
 *   UI 风格保持不变：节点视觉、配色、Tag、Drawer、网格背景全部沿用。
 */
import { Space, Switch, Tag, Button, Typography, Alert, Drawer, List, Spin, message, Radio, Select, Empty, Tooltip } from 'antd';
import { BranchesOutlined, ExportOutlined, NodeIndexOutlined, NotificationOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Graph, Shape } from '@antv/x6';
import { register } from '@antv/x6-react-shape';
import dagre from 'dagre';
import React from 'react';
import { lakehouseAssets, lineageEdges as mockEdges } from '../../mock';
import { CatalogAPI } from '../../api';
import { PageHeader, SectionCard, ClassificationBadge } from '../../components';
import type { ImpactReport, LineageGraphData, LineageGraphNode } from '../../types';

const { Text } = Typography;

interface GNode {
  id: string;
  layer: number;
  x: number;
  y: number;
  label: string;
  data?: LineageGraphNode;
}

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

const SEVERITY_COLOR: Record<string, string> = {
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'success',
};

const layerIndexOf = (fqn: string): number => {
  const upper = (fqn || '').toUpperCase();
  if (upper.startsWith('MYSQL') || upper.startsWith('POSTGRES') || upper.startsWith('SOURCE')) return 0;
  if (upper.startsWith('ODS')) return 1;
  if (upper.startsWith('DWD')) return 2;
  if (upper.startsWith('DWS')) return 3;
  if (upper.startsWith('ADS')) return 4;
  if (upper.startsWith('API')) return 5;
  return 3;
};

/** 判断某字段在某节点是否存在（用于字段级 hover 入口校验） */
function findEdgeForColumn(
  data: LineageGraphData | null,
  fqn: string,
  column: string,
): boolean {
  if (!data) return false;
  const node = data.nodes.find((n) => n.fqn === fqn);
  if (!node?.columns?.length) return false;
  return node.columns.some((c) => c.name === column);
}

/** X6 节点内渲染的 React 组件（保持与原 SVG 版本同样的视觉） */
const NodeView: React.FC<{
  node: GNode;
  selected: boolean;
  impacted: boolean;
  columnLevel: boolean;
  hoveredColumn?: string | null;
  highlightedColumns?: Set<string>;
  columnEdges?: { fromColumn: string; toColumn: string; transform?: string | null }[];
  onColumnHover?: (col: string | null) => void;
}> = ({
  node, selected, impacted, columnLevel, hoveredColumn, highlightedColumns, columnEdges, onColumnHover,
}) => {
  const border = LAYER_BORDER[node.layer] || 'var(--ol-brand)';
  const finalBorder = selected ? '#DC2626' : impacted ? '#F97316' : border;
  const shadow = selected ? 'var(--ol-shadow-e2)' : 'var(--ol-shadow-e1)';
  const classification = node.data?.classification;
  const columns = columnLevel ? (node.data?.columns ?? []) : [];
  const nodeHeight = 52 + columns.length * 18;

  // tooltip：找当前 hover 字段相关的转换表达式
  const transformHint = (() => {
    if (!hoveredColumn || !columnEdges || columnEdges.length === 0) return null;
    const edge = columnEdges.find((e) => e.toColumn === hoveredColumn || e.fromColumn === hoveredColumn);
    return edge?.transform || null;
  })();

  return (
    <Tooltip title={transformHint} open={transformHint ? undefined : false}>
      <div
        style={{
          width: 180, minHeight: 52, padding: 10, background: '#fff', borderRadius: 8,
          border: `2px solid ${finalBorder}`, boxShadow: shadow,
          fontSize: 12, cursor: 'pointer',
          transition: 'all var(--ol-dur-fast) var(--ol-ease)',
        }}
      >
        <Space size={6} style={{ marginBottom: 4 }}>
          <Tag style={{
            margin: 0, padding: '0 6px', fontSize: 10, fontWeight: 600,
            background: `${border}15`, color: border, border: `1px solid ${border}40`,
          }}>{LAYER_NAME[node.layer]}</Tag>
          {classification && <ClassificationBadge level={classification as any} />}
        </Space>
        <div style={{ fontWeight: 600, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {node.label}
        </div>
        {columns.length > 0 && (
          <div style={{ marginTop: 6, borderTop: '1px dashed var(--ol-line)', paddingTop: 4 }}>
            {columns.map((c, i) => {
              const isHovered = hoveredColumn === c.name;
              const isHighlighted = highlightedColumns?.has(c.name);
              const isDim = hoveredColumn && !isHovered && !isHighlighted;
              return (
                <div
                  key={i}
                  onMouseEnter={() => onColumnHover?.(c.name)}
                  onMouseLeave={() => onColumnHover?.(null)}
                  style={{
                    fontSize: 11, lineHeight: '18px', cursor: 'pointer',
                    color: isDim ? 'var(--ol-ink4)' : 'var(--ol-ink2)',
                    background: isHovered ? 'var(--ol-brandSoft)' : isHighlighted ? 'var(--ol-warningSoft)' : 'transparent',
                    padding: '0 4px', margin: '0 -4px', borderRadius: 3,
                    transition: 'background var(--ol-dur-fast) var(--ol-ease)',
                  }}
                >
                  <span style={{ fontWeight: isHovered || isHighlighted ? 600 : 400 }}>{c.name}</span>
                  <span style={{ color: 'var(--ol-ink4)', marginLeft: 6 }}>{c.type}</span>
                </div>
              );
            })}
          </div>
        )}
        <div style={{ height: 0 }} data-node-height={nodeHeight} />
      </div>
    </Tooltip>
  );
};

// X6 react-shape 只需注册一次
let registered = false;
function ensureRegistered() {
  if (registered) return;
  register({
    shape: 'ol-lineage-node',
    width: 180,
    height: 52,
    component: NodeView as any,
  });
  registered = true;
}

/** dagre 已是依赖（^0.8.5），直接使用 */
function loadDagre(): any | null {
  try {
    return (dagre as any).default || dagre;
  } catch {
    return null;
  }
}

/** 降级网格布局：按 layer 分列，每列纵向堆叠（与原 mock 实现一致） */
function gridLayout(nodes: GNode[]): GNode[] {
  return nodes.map((n) => n); // x/y 已在构造时赋值
}

/** dagre 分层布局；dagre 必装，失败时返回 null 触发降级 */
function dagreLayout(
  nodes: GNode[], edges: { source: string; target: string }[],
): Record<string, { x: number; y: number }> | null {
  const lib = loadDagre();
  if (!lib) return null;
  try {
    const g = new lib.graphlib.Graph();
    g.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 80 });
    g.setDefaultEdgeLabel(() => ({}));
    nodes.forEach((n) => g.setNode(n.id, { width: 180, height: 80 }));
    edges.forEach((e) => g.setEdge(e.source, e.target));
    lib.layout(g);
    const pos: Record<string, { x: number; y: number }> = {};
    nodes.forEach((n) => {
      const p = g.node(n.id);
      if (p) pos[n.id] = { x: p.x - 90, y: p.y - 40 };
    });
    return pos;
  } catch {
    return null;
  }
}

export default function LineageGraph() {
  const [searchParams, setSearchParams] = useSearchParams();
  const rootFromUrl = searchParams.get('fqn') || 'dwd.dwd_order_df';

  const [columnLevel, setColumnLevel] = useState(false);
  const [impactOpen, setImpactOpen] = useState(false);
  const [selected, setSelected] = useState<string>(rootFromUrl);
  const [direction, setDirection] = useState<'UP' | 'DOWN' | 'BOTH'>('BOTH');
  const [depth, setDepth] = useState(3);
  const [loading, setLoading] = useState(false);
  const [usingMock, setUsingMock] = useState(false);
  const [impact, setImpact] = useState<ImpactReport | null>(null);
  const [impactLoading, setImpactLoading] = useState(false);
  const [hoveredColumn, setHoveredColumn] = useState<string | null>(null);
  const [notifying, setNotifying] = useState(false);
  const [notifyHint, setNotifyHint] = useState<{ type: 'success' | 'info' | 'warning' | 'error'; text: string } | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);

  const [graphData, setGraphData] = useState<LineageGraphData | null>(null);

  // 根节点切换时同步 URL
  useEffect(() => {
    setSearchParams(selected === 'dwd.dwd_order_df' && !searchParams.get('fqn') ? {} : { fqn: selected }, { replace: true });
  }, [selected]);

  // 拉血缘图
  const fetchGraph = async (root: string, dir: 'UP' | 'DOWN' | 'BOTH', d: number) => {
    setLoading(true);
    try {
      const data = await CatalogAPI.lineageGraph(root, dir, d);
      setGraphData(data);
      setUsingMock(false);
    } catch (err) {
      // 后端未就绪时降级到 mock 数据
      setGraphData(buildMockData(root));
      setUsingMock(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchGraph(selected, direction, depth);
  }, [selected, direction, depth]);

  // 拉影响分析
  const fetchImpact = async (root: string) => {
    setImpactLoading(true);
    try {
      const r = await CatalogAPI.lineageImpact(root);
      setImpact(r);
    } catch {
      setImpact(null);
    } finally {
      setImpactLoading(false);
    }
  };

  useEffect(() => {
    if (impactOpen) fetchImpact(selected);
  }, [impactOpen, selected]);

  const onExport = () => {
    // 浏览器原生下载（带 auth cookie / token 由 axios 拦截器统一处理在 link 上不够简洁，
    // 此处直接打开导出 URL，axios 实例的 baseURL 已是 /api/v1，所以相对路径）
    window.open(CatalogAPI.exportImpactUrl(selected), '_blank');
  };

  const onNotify = async () => {
    setNotifying(true);
    setNotifyHint(null);
    try {
      const r = await CatalogAPI.notifyImpact(selected);
      setNotifyHint({
        type: r.notified ? 'success' : 'info',
        text: r.message,
      });
    } catch (e: any) {
      const code = e?.response?.data?.code;
      const msg = code === 403 ? '权限不足：需要 DE / ADMIN 角色' : (e?.response?.data?.message || '通知发送失败');
      setNotifyHint({ type: 'error', text: msg });
    } finally {
      setNotifying(false);
    }
  };

  // 受影响下游（用于节点边框高亮）
  const impactedSet = useMemo(() => {
    if (!graphData) return new Set<string>();
    const visited = new Set<string>();
    const queue = [selected];
    visited.add(selected);
    while (queue.length) {
      const cur = queue.shift()!;
      for (const e of graphData.edges) {
        if (e.fromFqn === cur && !visited.has(e.toFqn)) {
          visited.add(e.toFqn);
          queue.push(e.toFqn);
        }
      }
    }
    visited.delete(selected);
    return visited;
  }, [graphData, selected]);

  // 字段贯穿链路：hover 某节点某字段时，沿下游 columnEdge 串联出相关字段（按节点分组）
  const highlightedColumnsByNode = useMemo(() => {
    const result: Record<string, Set<string>> = {};
    if (!graphData || !hoveredColumn) return result;
    // BFS 字段级：从 selected 节点的 hoveredColumn 出发，沿 columnEdges 串联
    const startEdge = findEdgeForColumn(graphData, selected, hoveredColumn);
    if (!startEdge) return result;
    const queue: { fqn: string; column: string }[] = [{ fqn: selected, column: hoveredColumn }];
    const visited = new Set<string>();
    while (queue.length) {
      const { fqn, column } = queue.shift()!;
      (result[fqn] = result[fqn] || new Set()).add(column);
      for (const e of graphData.edges) {
        if (e.fromFqn !== fqn) continue;
        for (const ce of e.columnEdges ?? []) {
          if (ce.fromColumn !== column) continue;
          const key = `${e.toFqn}:${ce.toColumn}`;
          if (visited.has(key)) continue;
          visited.add(key);
          queue.push({ fqn: e.toFqn, column: ce.toColumn });
        }
      }
    }
    return result;
  }, [graphData, hoveredColumn, selected]);

  // 当前 hover 字段所在节点相关边的 transform（tooltip 用）
  const hoverColumnEdges = useMemo(() => {
    if (!graphData || !hoveredColumn) return [];
    return graphData.edges
      .filter((e) => e.fromFqn === selected)
      .flatMap((e) => e.columnEdges ?? []);
  }, [graphData, hoveredColumn, selected]);

  // 渲染 X6 图
  useEffect(() => {
    if (!containerRef.current || !graphData) return;
    ensureRegistered();

    // 构造 GNode 列表（带降级网格坐标）
    const byLayer: Record<number, string[]> = {};
    graphData.nodes.forEach((n) => {
      const l = layerIndexOf(n.fqn);
      (byLayer[l] = byLayer[l] || []).push(n.fqn);
    });
    const nodeMap = new Map<string, LineageGraphNode>();
    graphData.nodes.forEach((n) => nodeMap.set(n.fqn, n));

    const gNodes: GNode[] = [];
    Object.entries(byLayer).forEach(([l, list]) => {
      list.forEach((fqn, i) => {
        const data = nodeMap.get(fqn);
        gNodes.push({
          id: fqn, layer: +l,
          x: 40 + +l * 220, y: 60 + i * 90,
          label: data?.name || fqn,
          data,
        });
      });
    });

    // 重建 graph
    if (graphRef.current) {
      graphRef.current.dispose();
      graphRef.current = null;
    }
    const graph = new Graph({
      container: containerRef.current,
      background: { color: 'var(--ol-fill-soft)' },
      grid: {
        visible: true, type: 'dot',
        args: { color: 'var(--ol-line)', thickness: 1 },
      },
      interacting: { nodeMovable: true, edgeMovable: false },
      mousewheel: { enabled: true, modifiers: ['ctrl'], minScale: 0.4, maxScale: 2 },
      connecting: { allowBlank: false },
    });
    graphRef.current = graph;

    // 同步 dagre 布局，失败降级网格
    const edgesForLayout = graphData.edges.map((e) => ({ source: e.fromFqn, target: e.toFqn }));
    const pos = dagreLayout(gNodes, edgesForLayout);
    const finalNodes = pos
      ? gNodes.map((n) => ({ ...n, x: pos[n.id]?.x ?? n.x, y: pos[n.id]?.y ?? n.y }))
      : gridLayout(gNodes);

    finalNodes.forEach((n) => {
      graph.addNode({
        id: n.id,
        shape: 'ol-lineage-node',
        x: n.x, y: n.y,
        width: 180,
        data: {
          node: n,
          selected: n.id === selected,
          impacted: impactedSet.has(n.id),
          columnLevel,
          hoveredColumn: n.id === selected ? hoveredColumn : null,
          highlightedColumns: highlightedColumnsByNode[n.id] || new Set<string>(),
          columnEdges: n.id === selected ? hoverColumnEdges : [],
          onColumnHover: n.id === selected ? ((col: string | null) => setHoveredColumn(col)) : undefined,
        },
      });
    });
    graphData.edges.forEach((e, i) => {
      if (!graph.hasCell?.(e.fromFqn) && !graph.getCellById(e.fromFqn)) return;
      if (!graph.getCellById(e.toFqn)) return;
      graph.addEdge({
        id: `edge-${i}`,
        source: e.fromFqn,
        target: e.toFqn,
        router: { name: 'manhattan' },
        attrs: {
          line: {
            stroke: 'var(--ol-brand)',
            strokeWidth: 2,
            opacity: 0.7,
            targetMarker: { name: 'block', d: 8 },
          },
        },
      });
    });
    graph.on('node:click', ({ node }) => {
      const id = String(node.id);
      setSelected(id);
    });
    // 选中节点视觉刷新
    refreshNodeVisuals(graph, finalNodes, selected, impactedSet, columnLevel,
      hoveredColumn, highlightedColumnsByNode, hoverColumnEdges);

    return () => {
      graph.dispose();
      graphRef.current = null;
    };
  }, [graphData, selected, impactedSet, columnLevel, hoveredColumn, highlightedColumnsByNode, hoverColumnEdges]);

  // 选中/高亮/字段级/hover 变化时，刷新所有节点的 data 触发 React 重渲染
  const refreshNodeVisuals = (
    graph: Graph, nodes: GNode[], selectedId: string, impacted: Set<string>, columnLevel: boolean,
    hoveredColumn: string | null,
    highlightedColumnsByNode: Record<string, Set<string>>,
    hoverColumnEdges: { fromColumn: string; toColumn: string; transform?: string | null }[],
  ) => {
    nodes.forEach((n) => {
      const cell = graph.getCellById(n.id);
      if (cell) cell.setData({
        node: n,
        selected: n.id === selectedId,
        impacted: impacted.has(n.id),
        columnLevel,
        hoveredColumn: n.id === selectedId ? hoveredColumn : null,
        highlightedColumns: highlightedColumnsByNode[n.id] || new Set<string>(),
        columnEdges: n.id === selectedId ? hoverColumnEdges : [],
        onColumnHover: n.id === selectedId ? ((col: string | null) => setHoveredColumn(col)) : undefined,
      });
    });
  };

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
            <Radio.Group size="small" value={direction} onChange={(e) => setDirection(e.target.value)}>
              <Radio.Button value="UP">上游</Radio.Button>
              <Radio.Button value="DOWN">下游</Radio.Button>
              <Radio.Button value="BOTH">双向</Radio.Button>
            </Radio.Group>
            <Select size="small" value={depth} onChange={(v) => setDepth(v)} style={{ width: 80 }}>
              <Select.Option value={1}>1 跳</Select.Option>
              <Select.Option value={2}>2 跳</Select.Option>
              <Select.Option value={3}>3 跳</Select.Option>
              <Select.Option value={5}>全部</Select.Option>
            </Select>
            <Button onClick={() => setImpactOpen(true)} icon={<NodeIndexOutlined />}>分析下游影响</Button>
            <Button icon={<ExportOutlined />} onClick={() => onExport()}>导出影响报告</Button>
          </>
        }
      />

      {usingMock && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message={<span style={{ fontSize: 13 }}>后端血缘图接口未就绪，当前展示 mock 数据</span>} />
      )}
      {!usingMock && (
        <Alert type="info" showIcon message={<span style={{ fontSize: 13 }}>点击节点选中，再次点击「分析下游影响」查看完整链路（支持滚轮缩放、拖拽平移）</span>} />
      )}

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div
          ref={containerRef}
          style={{
            height: 500, position: 'relative', background: 'var(--ol-fill-soft)',
            borderRadius: 'inherit', overflow: 'hidden',
          }}
        />
        {loading && (
          <div style={{ position: 'absolute', top: 12, right: 16 }}>
            <Spin />
          </div>
        )}
        {graphData && graphData.nodes.length === 0 && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty description={`无血缘数据（${selected}）`} />
          </div>
        )}
      </SectionCard>

      <Drawer open={impactOpen} onClose={() => setImpactOpen(false)} title="下游影响分析" width={520}>
        <Alert type="warning" showIcon message={<Space><Text>选中：</Text><Text code style={{ fontSize: 12 }}>{selected}</Text></Space>} style={{ marginBottom: 16 }} />
        {impactLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : impact ? (
          <>
            <List
              header={<Text strong style={{ fontSize: 13 }}>直接下游（{impact.directDownstream.length}）</Text>}
              size="small" bordered
              dataSource={impact.directDownstream}
              renderItem={(i) => <List.Item><Text code style={{ fontSize: 12 }}>{i}</Text></List.Item>}
            />
            <List
              style={{ marginTop: 12 }}
              header={<Text strong style={{ fontSize: 13 }}>间接下游（{impact.indirectDownstream.length}）</Text>}
              size="small" bordered
              dataSource={impact.indirectDownstream}
              renderItem={(i) => <List.Item><Text code style={{ fontSize: 12 }}>{i}</Text></List.Item>}
            />
            <div className="ol-section" style={{ marginTop: 16, padding: 14 }}>
              <Space direction="vertical" size={4}>
                <Text>受影响任务：<Text strong>{impact.affectedJobs}</Text></Text>
                <Text>受影响 API：<Text strong>{impact.affectedApis}</Text></Text>
                <Text>受影响订阅方：<Text strong>{impact.affectedSubscribers}</Text></Text>
                <Tag color={SEVERITY_COLOR[impact.severity] as any} style={{ marginTop: 6 }}>
                  严重程度：{impact.severity}
                </Tag>
                {impact.severityReasons.length > 0 && (
                  <div style={{ marginTop: 6 }}>
                    {impact.severityReasons.map((r, i) => (
                      <div key={i} style={{ fontSize: 12, color: 'var(--ol-ink3)' }}>· {r}</div>
                    ))}
                  </div>
                )}
              </Space>
            </div>
            <Button
              block
              icon={<NotificationOutlined />}
              loading={notifying}
              onClick={() => onNotify()}
              style={{ marginTop: 12 }}
            >
              通知受影响负责人
            </Button>
            {notifyHint && (
              <Alert
                type={notifyHint.type}
                message={notifyHint.text}
                showIcon
                style={{ marginTop: 8 }}
              />
            )}
          </>
        ) : (
          <Empty description="影响分析数据暂未就绪" />
        )}
      </Drawer>
    </div>
  );
}

/** 后端不可用时降级到 mock 数据，保持同样的 LineageGraphData 结构 */
function buildMockData(root: string): LineageGraphData {
  const nodeSet = new Set<string>([root]);
  mockEdges.forEach((e) => {
    nodeSet.add(e.upstreamFqn);
    nodeSet.add(e.downstreamFqn);
  });
  const nodes: LineageGraphNode[] = Array.from(nodeSet).map((fqn) => {
    const asset = lakehouseAssets.find((a) => a.fqn === fqn);
    return {
      fqn,
      name: asset?.name || fqn.split('.').pop() || fqn,
      layer: ['', 'SOURCE', 'ODS', 'DWD', 'DWS', 'ADS', 'API'][layerIndexOf(fqn)] || 'DWD',
      nodeType: 'TABLE',
      classification: asset?.classification || null,
      qualityScore: asset?.qualityScore ?? null,
      ownerName: asset?.ownerName || null,
      rowCount: asset?.rows ?? null,
      syncedAt: null,
      columns: asset?.columns?.map((c: any) => ({ name: c.name, type: c.type, classification: c.classification })) || [],
    };
  });
  const edges = mockEdges.map((e) => ({
    fromFqn: e.upstreamFqn,
    toFqn: e.downstreamFqn,
    jobRef: e.jobRef ?? null,
    columnEdges: (e.columnMapping || []).map((c) => ({ fromColumn: c.from, toColumn: c.to, transform: c.transform ?? null })),
  }));
  return { rootFqn: root, nodes, edges };
}
