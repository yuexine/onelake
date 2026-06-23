/**
 * DAG 画布编辑器（对应原型 §8.4.1 ~ §8.4.5）。
 * 三区：左算子面板 + 中画布（节点连线 SVG 模拟）+ 右属性面板。
 * 含试运行 / 版本管理 / DAG 校验结果 / 发布确认。
 */
import {
  Row, Col, Space, Button, Select, Tag, Typography, Drawer, Form, Input, Alert, Modal,
  App as AntApp, Checkbox, InputNumber,
} from 'antd';
import {
  PlayCircleOutlined, SaveOutlined, CheckCircleOutlined, WarningOutlined,
  DatabaseOutlined, FilterOutlined, LockOutlined, ExportOutlined, AppstoreOutlined, CodeOutlined,
  DeleteOutlined, LinkOutlined,
} from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ClassificationBadge, PageHeader, SectionCard } from '../../components';
import { OperatorAPI, OrchestrationAPI } from '../../api';
import type { Dag, Operator, OperatorManifest, OperatorValidationResult } from '../../types';

const { Text, Title } = Typography;

interface Node {
  id: string;
  type: string;
  name: string;
  x: number;
  y: number;
  sql?: string;
  engine?: string;
  operatorRef?: string;
  operatorVersion?: string;
  config?: Record<string, unknown>;
}
interface Edge {
  id: string;
  from: string;
  to: string;
  valid: boolean;
  sourcePort?: string;
  targetPort?: string;
  validationMessage?: string;
}

interface DragState {
  nodeId: string;
  offsetX: number;
  offsetY: number;
}

interface ConnectionState {
  from: string;
  sourcePort: string;
}

interface CanvasPort {
  name: string;
  cardinality: string;
  accept: string;
}

interface ParamField {
  key: string;
  label: string;
  required: boolean;
  property: ParamSchemaProperty;
}

interface ParamSchemaProperty {
  type?: string | string[];
  title?: string;
  description?: string;
  enum?: Array<string | number | boolean>;
  default?: unknown;
  items?: { type?: string };
}

const CATEGORY_META: Record<string, { label: string; color: string; icon: JSX.Element }> = {
  INPUT: { label: '输入', color: '#1677ff', icon: <DatabaseOutlined /> },
  TRANSFORM: { label: '转换', color: '#0f766e', icon: <CodeOutlined /> },
  GOVERN: { label: '治理', color: '#52c41a', icon: <FilterOutlined /> },
  STANDARD: { label: '标准化', color: '#13c2c2', icon: <CodeOutlined /> },
  MASK: { label: '脱敏', color: '#fa8c16', icon: <LockOutlined /> },
  ENCRYPT: { label: '加密', color: '#fa541c', icon: <LockOutlined /> },
  AGG: { label: '聚合', color: '#722ed1', icon: <CodeOutlined /> },
  JOIN: { label: '关联', color: '#2f54eb', icon: <CodeOutlined /> },
  QUALITY_GATE: { label: '质量门禁', color: '#d48806', icon: <CheckCircleOutlined /> },
  OUTPUT: { label: '输出', color: '#389e0d', icon: <ExportOutlined /> },
};

const NODE_TYPE_TO_CATEGORY: Record<string, string> = {
  'input-table': 'INPUT',
  INPUT: 'INPUT',
  SQL: 'TRANSFORM',
  clean: 'GOVERN',
  mdm: 'GOVERN',
  GOVERN: 'GOVERN',
  mask: 'MASK',
  MASK: 'MASK',
  encrypt: 'ENCRYPT',
  ENCRYPT: 'ENCRYPT',
  output: 'OUTPUT',
  OUTPUT: 'OUTPUT',
};

const INITIAL_NODES: Node[] = [
  {
    id: 'n1',
    type: 'INPUT',
    name: 'ods.orders',
    x: 60,
    y: 80,
    operatorRef: 'input.ods_table',
    operatorVersion: '1.0.0',
    config: { sourceFqn: 'ods.orders' },
  },
  {
    id: 'n2',
    type: 'GOVERN',
    name: '去重',
    x: 280,
    y: 80,
    operatorRef: 'govern.drop_required_missing',
    operatorVersion: '1.0.0',
    config: { requiredColumns: ['order_id'] },
  },
  {
    id: 'n3',
    type: 'MASK',
    name: '脱敏 phone',
    x: 500,
    y: 80,
    operatorRef: 'mask.partial',
    operatorVersion: '1.0.0',
    config: { column: 'phone', keepHead: 3, keepTail: 4, maskChar: '*' },
  },
  {
    id: 'n4',
    type: 'OUTPUT',
    name: 'dws_user',
    x: 720,
    y: 80,
    operatorRef: 'output.iceberg_table',
    operatorVersion: '1.0.0',
    config: { targetFqn: 'dws_user', partitionBy: 'days(order_time)' },
  },
];

const INITIAL_EDGES: Edge[] = [
  { id: 'e1', from: 'n1', to: 'n2', sourcePort: 'out', targetPort: 'in', valid: true },
  { id: 'e2', from: 'n2', to: 'n3', sourcePort: 'out', targetPort: 'in', valid: true },
  { id: 'e3', from: 'n3', to: 'n4', sourcePort: 'out', targetPort: 'in', valid: true },
];

function inferredOperator(node: Node) {
  if (node.operatorRef) return node.operatorRef;
  if (node.type === 'input-table' || node.type === 'INPUT') return 'input.ods_table';
  if (node.type === 'clean' || node.type === 'GOVERN') return 'govern.drop_required_missing';
  if (node.type === 'mask' || node.type === 'MASK') return 'mask.partial';
  if (node.type === 'encrypt' || node.type === 'ENCRYPT') return 'encrypt.sha256';
  if (node.type === 'output' || node.type === 'OUTPUT') return 'output.iceberg_table';
  if (node.type === 'SQL') return 'input.sql_query';
  return undefined;
}

export default function DagCanvas() {
  const { message } = AntApp.useApp();
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const incoming = (location.state || {}) as { dag?: Dag; sql?: string };
  const routeDagId = id && id !== 'new' && !id.startsWith('p-') ? id : undefined;
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<DragState | null>(null);
  const [env, setEnv] = useState('dev');
  const [pipelineName, setPipelineName] = useState('order_pipeline');
  const [savedDagId, setSavedDagId] = useState<string | undefined>(routeDagId);
  const [nodes, setNodes] = useState<Node[]>(INITIAL_NODES);
  const [edges, setEdges] = useState<Edge[]>(INITIAL_EDGES);
  const [selected, setSelected] = useState<Node | null>(INITIAL_NODES[2]);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [validateOpen, setValidateOpen] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<OperatorValidationResult | null>(null);
  const [publishOpen, setPublishOpen] = useState(false);
  const [marketOperators, setMarketOperators] = useState<Operator[]>([]);
  const [operatorLoading, setOperatorLoading] = useState(false);
  const [operatorError, setOperatorError] = useState<string | null>(null);
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);
  const [pendingConnection, setPendingConnection] = useState<ConnectionState | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);

  const operatorGroups = useMemo(() => {
    const groups = new Map<string, Operator[]>();
    marketOperators.forEach((operator) => {
      const category = operator.category || 'TRANSFORM';
      groups.set(category, [...(groups.get(category) || []), operator]);
    });
    return Array.from(groups.entries())
      .sort(([left], [right]) => (CATEGORY_META[left]?.label || left).localeCompare(CATEGORY_META[right]?.label || right, 'zh-CN'))
      .map(([category, items]) => ({
        category,
        label: CATEGORY_META[category]?.label || category,
        items,
      }));
  }, [marketOperators]);

  const operatorCatalog = useMemo(() => {
    const catalog = new Map<string, Operator>();
    marketOperators.forEach((operator) => catalog.set(operator.operatorRef, operator));
    return catalog;
  }, [marketOperators]);

  const selectedOperatorRef = selected ? selected.operatorRef || inferredOperator(selected) : undefined;
  const selectedOperator = selectedOperatorRef ? operatorCatalog.get(selectedOperatorRef) : undefined;
  const selectedParamFields = useMemo(
    () => paramFieldsFromManifest(selectedOperator?.manifest),
    [selectedOperator?.manifest],
  );

  const loadMarketOperators = () => {
    setOperatorLoading(true);
    setOperatorError(null);
    OperatorAPI.listOperators()
      .then(setMarketOperators)
      .catch((e) => setOperatorError(e.message || '算子面板加载失败'))
      .finally(() => setOperatorLoading(false));
  };

  const addOperatorNode = (operator: Operator) => {
    const nextId = `market-${Date.now()}`;
    const nextNode: Node = {
      id: nextId,
      type: operator.category,
      name: operator.displayName,
      x: 80 + (nodes.length % 4) * 220,
      y: 80 + Math.floor(nodes.length / 4) * 120,
      operatorRef: operator.operatorRef,
      operatorVersion: operator.latestVersion,
      config: defaultConfigFromManifest(operator.manifest),
    };
    const needsInput = (operator.manifest?.inputPorts || []).length > 0;
    setNodes((prev) => [...prev, nextNode]);
    if (selected && needsInput) {
      setEdges((prev) => [...prev, {
        id: `edge-${Date.now()}`,
        from: selected.id,
        to: nextId,
        sourcePort: 'out',
        targetPort: firstInputPortName(nextNode, operator),
        valid: true,
      }]);
    }
    setSelected(nextNode);
    setSelectedEdgeId(null);
    message.success(`已添加算子：${operator.displayName}`);
  };

  const patchSelectedNode = (patch: Partial<Node>) => {
    if (!selected) return;
    const next = { ...selected, ...patch };
    setSelected(next);
    setNodes((prev) => prev.map((node) => (node.id === selected.id ? next : node)));
  };

  const updateSelectedConfig = (key: string, value: unknown) => {
    if (!selected) return;
    patchSelectedNode({
      config: {
        ...(selected.config || {}),
        [key]: value,
      },
    });
  };

  const moveNode = (nodeId: string, x: number, y: number) => {
    setNodes((prev) => prev.map((node) => (node.id === nodeId ? { ...node, x, y } : node)));
    setSelected((prev) => (prev?.id === nodeId ? { ...prev, x, y } : prev));
  };

  const startNodeDrag = (event: React.PointerEvent<HTMLDivElement>, node: Node) => {
    event.preventDefault();
    event.stopPropagation();
    const rect = event.currentTarget.getBoundingClientRect();
    dragRef.current = {
      nodeId: node.id,
      offsetX: event.clientX - rect.left,
      offsetY: event.clientY - rect.top,
    };
    setDraggingNodeId(node.id);
    setSelected(node);
    setSelectedEdgeId(null);
  };

  useEffect(() => {
    loadMarketOperators();
  }, []);

  useEffect(() => {
    const handleMove = (event: PointerEvent) => {
      const drag = dragRef.current;
      const canvas = canvasRef.current;
      if (!drag || !canvas) return;
      const rect = canvas.getBoundingClientRect();
      const maxX = Math.max(rect.width - 150, 8);
      const maxY = Math.max(rect.height - 86, 8);
      const nextX = Math.round(Math.min(Math.max(event.clientX - rect.left - drag.offsetX, 8), maxX));
      const nextY = Math.round(Math.min(Math.max(event.clientY - rect.top - drag.offsetY, 8), maxY));
      moveNode(drag.nodeId, nextX, nextY);
    };
    const handleUp = () => {
      dragRef.current = null;
      setDraggingNodeId(null);
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
    window.addEventListener('pointercancel', handleUp);
    return () => {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      window.removeEventListener('pointercancel', handleUp);
    };
  }, []);

  useEffect(() => {
    const applyDag = (dag: Dag) => {
      setSavedDagId(dag.id);
      setPipelineName(dag.name);
      const definition = Array.isArray(dag.definition) ? { nodes: dag.definition, edges: dag.edges || [] } : dag.definition;
      const graph = typeof definition.operatorGraph === 'object' && definition.operatorGraph
        ? definition.operatorGraph as { nodes?: unknown[]; edges?: unknown[] }
        : definition;
      const draftNodes = ((graph.nodes || []) as any[]).map((node, index) => ({
        id: String(node.id || `n-${index + 1}`),
        type: String(node.nodeType || node.type || 'SQL'),
        name: String(node.name || node.displayName || node.operatorRef || node.type || `节点 ${index + 1}`),
        sql: typeof node.sql === 'string' ? node.sql : undefined,
        engine: typeof node.engine === 'string' ? node.engine : undefined,
        operatorRef: typeof node.operatorRef === 'string' ? node.operatorRef : undefined,
        operatorVersion: typeof node.operatorVersion === 'string' ? node.operatorVersion : undefined,
        config: typeof node.config === 'object' && node.config ? node.config : undefined,
        x: typeof node.x === 'number' ? node.x : 80 + index * 220,
        y: typeof node.y === 'number' ? node.y : 100,
      }));
      const draftEdges = ((graph.edges || []) as any[]).map((edge) => ({
        id: String(edge.id || `edge-${edge.from || edge.source || 'source'}-${edge.to || edge.target || 'target'}-${Math.random().toString(36).slice(2, 8)}`),
        from: String(edge.from || edge.source),
        to: String(edge.to || edge.target),
        sourcePort: typeof edge.sourcePort === 'string' ? edge.sourcePort : 'out',
        targetPort: typeof edge.targetPort === 'string' ? edge.targetPort : 'in',
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
  }, [id, incoming.dag, message]);

  const nodeTypeForGraph = (node: Node) => {
    if (node.type === 'input-table') return 'INPUT';
    if (node.type === 'clean' || node.type === 'mdm') return 'GOVERN';
    if (node.type === 'mask') return 'MASK';
    if (node.type === 'encrypt') return 'ENCRYPT';
    if (node.type === 'output') return 'OUTPUT';
    return node.type;
  };

  const inferredConfig = (node: Node) => {
    if (node.config) return node.config;
    if (node.type === 'SQL') return { sql: node.sql || 'select 1' };
    if (node.type === 'INPUT' || node.type === 'input-table') return { sourceFqn: node.name };
    if (node.type === 'GOVERN' || node.type === 'clean') return { requiredColumns: ['order_id'] };
    if (node.type === 'MASK' || node.type === 'mask') return { column: 'phone', keepHead: 3, keepTail: 4, maskChar: '*' };
    if (node.type === 'ENCRYPT' || node.type === 'encrypt') return { column: 'phone', salt: '' };
    if (node.type === 'OUTPUT' || node.type === 'output') return { targetFqn: node.name, partitionBy: 'days(order_time)' };
    return {};
  };

  const nodeById = (nodeId: string) => nodes.find((node) => node.id === nodeId);

  const operatorForNode = (node?: Node) => {
    if (!node) return undefined;
    return operatorCatalog.get(node.operatorRef || inferredOperator(node) || '');
  };

  const inputPortsForNode = (node?: Node): CanvasPort[] => {
    if (!node) return [];
    const operator = operatorForNode(node);
    const ports = operator?.manifest?.inputPorts;
    if (ports) {
      return ports.map((port) => ({
        name: String(port.name || 'in'),
        cardinality: String(port.cardinality || 'ONE'),
        accept: String(port.accept || 'TABLE'),
      }));
    }
    const category = NODE_TYPE_TO_CATEGORY[node.type] || node.type;
    if (category === 'INPUT') return [];
    return [{ name: 'in', cardinality: 'ONE', accept: 'TABLE' }];
  };

  const firstInputPortName = (node?: Node, operator?: Operator) => {
    const ports = operator?.manifest?.inputPorts
      ? operator.manifest.inputPorts.map((port) => ({ name: port.name }))
      : inputPortsForNode(node);
    return ports[0]?.name || 'in';
  };

  const hasPath = (from: string, target: string, edgeList: Edge[], visited = new Set<string>()): boolean => {
    if (from === target) return true;
    if (visited.has(from)) return false;
    visited.add(from);
    return edgeList
      .filter((edge) => edge.from === from)
      .some((edge) => hasPath(edge.to, target, edgeList, visited));
  };

  const annotateEdges = (edgeList: Edge[]): Edge[] => {
    const exactCounts = new Map<string, number>();
    const inboundCounts = new Map<string, number>();
    edgeList.forEach((edge) => {
      const sourcePort = edge.sourcePort || 'out';
      const targetPort = edge.targetPort || firstInputPortName(nodeById(edge.to));
      const exactKey = `${edge.from}:${sourcePort}->${edge.to}:${targetPort}`;
      const inboundKey = `${edge.to}:${targetPort}`;
      exactCounts.set(exactKey, (exactCounts.get(exactKey) || 0) + 1);
      inboundCounts.set(inboundKey, (inboundCounts.get(inboundKey) || 0) + 1);
    });

    return edgeList.map((edge) => {
      const source = nodeById(edge.from);
      const target = nodeById(edge.to);
      const sourcePort = edge.sourcePort || 'out';
      const targetPort = edge.targetPort || firstInputPortName(target);
      const targetPorts = inputPortsForNode(target);
      const targetPortDef = targetPorts.find((port) => port.name === targetPort);
      const exactKey = `${edge.from}:${sourcePort}->${edge.to}:${targetPort}`;
      const inboundKey = `${edge.to}:${targetPort}`;
      let valid = true;
      let validationMessage = '连线有效';

      if (!source || !target) {
        valid = false;
        validationMessage = '节点不存在';
      } else if (source.id === target.id) {
        valid = false;
        validationMessage = '不能连接到自身';
      } else if (targetPorts.length === 0) {
        valid = false;
        validationMessage = '目标节点不接受输入';
      } else if (!targetPortDef) {
        valid = false;
        validationMessage = `目标端口 ${targetPort} 不存在`;
      } else if (targetPortDef.cardinality !== 'MANY' && (inboundCounts.get(inboundKey) || 0) > 1) {
        valid = false;
        validationMessage = `目标端口 ${targetPort} 只允许单输入`;
      } else if ((exactCounts.get(exactKey) || 0) > 1) {
        valid = false;
        validationMessage = '重复连线';
      } else if (hasPath(target.id, source.id, edgeList.filter((item) => item.id !== edge.id))) {
        valid = false;
        validationMessage = '连线会形成环路';
      }

      return {
        ...edge,
        sourcePort,
        targetPort,
        valid,
        validationMessage,
      };
    });
  };

  const annotatedEdges = useMemo(
    () => annotateEdges(edges),
    [edges, nodes, operatorCatalog],
  );

  const selectedEdge = selectedEdgeId ? annotatedEdges.find((edge) => edge.id === selectedEdgeId) : undefined;

  const startConnection = (event: React.MouseEvent, node: Node, sourcePort = 'out') => {
    event.stopPropagation();
    setPendingConnection({ from: node.id, sourcePort });
    setSelected(node);
    setSelectedEdgeId(null);
    message.info('已选择输出端口');
  };

  const completeConnection = (event: React.MouseEvent, node: Node, targetPort: string) => {
    event.stopPropagation();
    if (!pendingConnection) {
      setSelected(node);
      return;
    }
    const duplicate = edges.some((edge) => (
      edge.from === pendingConnection.from
      && edge.to === node.id
      && (edge.sourcePort || 'out') === pendingConnection.sourcePort
      && (edge.targetPort || 'in') === targetPort
    ));
    if (duplicate) {
      message.warning('连线已存在');
      setPendingConnection(null);
      return;
    }
    const nextEdge: Edge = {
      id: `edge-${Date.now()}`,
      from: pendingConnection.from,
      to: node.id,
      sourcePort: pendingConnection.sourcePort,
      targetPort,
      valid: true,
    };
    setEdges((prev) => [...prev, nextEdge]);
    setSelected(node);
    setSelectedEdgeId(nextEdge.id);
    setPendingConnection(null);
  };

  const patchEdge = (edgeId: string, patch: Partial<Edge>) => {
    setEdges((prev) => prev.map((edge) => (edge.id === edgeId ? { ...edge, ...patch } : edge)));
  };

  const removeSelectedEdge = () => {
    if (!selectedEdge) return;
    setEdges((prev) => prev.filter((edge) => edge.id !== selectedEdge.id));
    setSelectedEdgeId(null);
    message.success('连线已删除');
  };

  const buildValidationGraph = () => ({
    nodes: nodes.map((node) => {
      const nodeType = nodeTypeForGraph(node);
      const operatorRef = inferredOperator(node);
      return {
        id: node.id,
        nodeType,
        type: nodeType,
        name: node.name,
        operatorRef,
        operatorVersion: node.operatorVersion || (operatorRef ? '1.0.0' : undefined),
        config: inferredConfig(node),
        x: node.x,
        y: node.y,
      };
    }),
    edges: annotatedEdges.map((edge) => ({
      id: edge.id,
      source: edge.from,
      target: edge.to,
      sourcePort: edge.sourcePort,
      targetPort: edge.targetPort,
      valid: edge.valid,
    })),
  });

  const buildDagDefinition = () => {
    const graph = buildValidationGraph();
    return {
      kind: 'operator_graph',
      source: 'dag-canvas',
      version: '1.0',
      nodes: graph.nodes,
      edges: graph.edges,
      operatorGraph: {
        version: '1.0',
        pipelineMode: 'CUSTOM_DAG',
        engine: 'TRINO_DBT',
        resourceGroup: 'default',
        computeProfile: 'trino-small',
        nodes: graph.nodes,
        edges: graph.edges,
      },
    };
  };

  const runGraphValidation = async () => {
    setValidateOpen(true);
    setValidating(true);
    try {
      const result = await OperatorAPI.validateGraph(buildValidationGraph());
      setValidation(result);
      return result;
    } catch (e: any) {
      const fallback = { ok: false, errors: [e.message || '图级校验失败'], warnings: [] };
      setValidation(fallback);
      return fallback;
    } finally {
      setValidating(false);
    }
  };

  const handleSave = async () => {
    const result = await runGraphValidation();
    if (result.ok) {
      try {
        const payload = {
          name: pipelineName,
          dagsterJob: 'sql_workbench_draft',
          definition: buildDagDefinition(),
          enabled: false,
        };
        const saved = savedDagId
          ? await OrchestrationAPI.updateDag(savedDagId, payload)
          : await OrchestrationAPI.createDag(payload);
        setSavedDagId(saved.id);
        setPipelineName(saved.name);
        message.success(savedDagId ? '流水线草稿已保存' : '流水线草稿已创建');
        if (!savedDagId) {
          navigate(`/orchestration/pipelines/${saved.id}`, { replace: true, state: { dag: saved } });
        }
      } catch (e: any) {
        message.error(e.message || '流水线草稿保存失败');
      }
    } else {
      message.error('图级校验未通过');
    }
  };

  const renderNode = (n: Node) => {
    const operator = operatorCatalog.get(n.operatorRef || inferredOperator(n) || '');
    const category = operator?.category || NODE_TYPE_TO_CATEGORY[n.type] || 'TRANSFORM';
    const meta = CATEGORY_META[category] || CATEGORY_META.TRANSFORM;
    const isMask = n.type === 'mask' || n.type === 'MASK';
    const dragging = draggingNodeId === n.id;
    const inputPorts = inputPortsForNode(n);
    const pendingTarget = Boolean(pendingConnection && pendingConnection.from !== n.id && inputPorts.length > 0);
    return (
      <div key={n.id} onClick={() => { setSelected(n); setSelectedEdgeId(null); }} onPointerDown={(event) => startNodeDrag(event, n)}
        style={{
          position: 'absolute', left: n.x, top: n.y, width: 140, padding: 10,
          background: '#fff', border: `2px solid ${pendingTarget ? 'var(--ol-warning)' : selected?.id === n.id && !selectedEdge ? 'var(--ol-brand)' : meta.color}`,
          borderRadius: 8, boxShadow: dragging ? 'var(--ol-shadow-e3)' : 'var(--ol-shadow-e2)',
          cursor: dragging ? 'grabbing' : 'grab', userSelect: 'none',
          transition: 'box-shadow var(--ol-dur-fast) var(--ol-ease)',
        }}>
        {inputPorts.map((port, index) => (
          <button
            key={port.name}
            type="button"
            title={`输入端口 ${port.name}`}
            onClick={(event) => completeConnection(event, n, port.name)}
            onPointerDown={(event) => event.stopPropagation()}
            style={{
              position: 'absolute',
              left: -7,
              top: 18 + index * 18,
              width: 14,
              height: 14,
              borderRadius: '50%',
              border: `2px solid ${pendingTarget ? 'var(--ol-warning)' : 'var(--ol-brand)'}`,
              background: '#fff',
              cursor: pendingConnection ? 'crosshair' : 'pointer',
              padding: 0,
            }}
          />
        ))}
        <button
          type="button"
          title="输出端口 out"
          onClick={(event) => startConnection(event, n)}
          onPointerDown={(event) => event.stopPropagation()}
          style={{
            position: 'absolute',
            right: -7,
            top: 18,
            width: 14,
            height: 14,
            borderRadius: '50%',
            border: `2px solid ${pendingConnection?.from === n.id ? 'var(--ol-warning)' : meta.color}`,
            background: pendingConnection?.from === n.id ? 'var(--ol-warning)' : '#fff',
            cursor: 'crosshair',
            padding: 0,
          }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: meta.color, fontWeight: 600 }}>
          {meta.icon}{n.name}
        </div>
        {isMask && <div style={{ marginTop: 4 }}><ClassificationBadge level="L3" size="small" /></div>}
        <Tag style={{ marginTop: 6 }} color="success">{operator?.operatorRef || inferredOperator(n) || meta.label}</Tag>
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
            <Button onClick={runGraphValidation} loading={validating}>校验</Button>
            <Button onClick={() => setPreviewOpen(true)} icon={<PlayCircleOutlined />}>试运行</Button>
            <Button icon={<SaveOutlined />} onClick={handleSave} loading={validating}>保存</Button>
            <Button onClick={() => setVersionOpen(true)}>版本</Button>
            <Button type="primary" onClick={() => setPublishOpen(true)}>发布</Button>
          </>
        }
      />

      <Row gutter={12}>
        {/* 左算子面板 */}
        <Col xs={24} lg={4}>
          <SectionCard title="算子" icon={<AppstoreOutlined />} padded="sm" style={{ height: '100%' }}>
            {operatorLoading ? (
              <Alert type="info" showIcon message="正在加载算子市场" />
            ) : operatorError ? (
              <Alert
                type="error"
                showIcon
                message={operatorError}
                action={<Button size="small" onClick={loadMarketOperators}>重试</Button>}
              />
            ) : operatorGroups.length === 0 ? (
              <Alert type="warning" showIcon message="暂无可见算子" />
            ) : operatorGroups.map((group) => (
              <div key={group.category} style={{ marginBottom: 12 }}>
                <Text type="secondary" style={{ fontSize: 11, fontWeight: 600 }}>{group.label}</Text>
                <div style={{ marginTop: 6 }}>
                  {group.items.map((operator) => {
                    const meta = CATEGORY_META[operator.category] || CATEGORY_META.TRANSFORM;
                    return (
                      <div key={operator.operatorRef} style={{
                        padding: '6px 8px', border: '1px dashed var(--ol-line)',
                        borderRadius: 6, marginBottom: 4, cursor: 'pointer',
                        display: 'flex', alignItems: 'center', gap: 6,
                        fontSize: 12, transition: 'all var(--ol-dur-fast) var(--ol-ease)',
                      }}
                        title={`${operator.operatorRef} · ${operator.latestVersion}`}
                        onClick={() => addOperatorNode(operator)}
                        onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--ol-brand)'; e.currentTarget.style.background = 'var(--ol-brand-soft)'; }}
                        onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--ol-line)'; e.currentTarget.style.background = 'transparent'; }}
                      >
                        <span style={{ color: meta.color }}>{meta.icon}</span>
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{operator.displayName}</span>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </SectionCard>
        </Col>

        {/* 中画布 */}
        <Col xs={24} lg={14}>
          <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
            <div ref={canvasRef} style={{
              height: 460, position: 'relative', background: 'var(--ol-fill-soft)',
              backgroundImage: 'radial-gradient(var(--ol-line) 1px, transparent 1px)',
              backgroundSize: '20px 20px', borderRadius: 'inherit',
            }}>
              <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'auto' }}>
                {annotatedEdges.map((e) => {
                  const from = nodes.find((n) => n.id === e.from);
                  const to = nodes.find((n) => n.id === e.to);
                  if (!from || !to) return null;
                  const selectedLine = selectedEdgeId === e.id;
                  const x1 = from.x + 140, y1 = from.y + 24;
                  const x2 = to.x, y2 = to.y + 24;
                  const stroke = e.valid ? 'var(--ol-brand)' : 'var(--ol-error)';
                  return (
                    <g key={e.id}>
                      <line
                        x1={x1}
                        y1={y1}
                        x2={x2}
                        y2={y2}
                        stroke="transparent"
                        strokeWidth={12}
                        style={{ cursor: 'pointer', pointerEvents: 'stroke' }}
                        onClick={(event) => {
                          event.stopPropagation();
                          setSelectedEdgeId(e.id);
                          setSelected(null);
                        }}
                      />
                      <line
                        x1={x1}
                        y1={y1}
                        x2={x2}
                        y2={y2}
                        stroke={stroke}
                        strokeDasharray={e.valid ? undefined : '5 4'}
                        strokeWidth={selectedLine ? 3 : 2}
                        markerEnd={e.valid ? 'url(#arrow-valid)' : 'url(#arrow-invalid)'}
                        style={{ pointerEvents: 'none' }}
                      />
                      <circle
                        cx={x1}
                        cy={y1}
                        r={4}
                        fill={stroke}
                        style={{ pointerEvents: 'none' }}
                      />
                      <circle
                        cx={x2}
                        cy={y2}
                        r={4}
                        fill={selectedLine ? 'var(--ol-brand)' : '#fff'}
                        stroke={stroke}
                        strokeWidth={2}
                        style={{ pointerEvents: 'none' }}
                      />
                    </g>
                  );
                })}
                <defs>
                  <marker id="arrow-valid" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                    <path d="M0,0 L0,6 L9,3 z" fill="var(--ol-brand)" />
                  </marker>
                  <marker id="arrow-invalid" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                    <path d="M0,0 L0,6 L9,3 z" fill="var(--ol-error)" />
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
            {selectedEdge ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div>
                  <Space size={6}>
                    <LinkOutlined style={{ color: 'var(--ol-brand)' }} />
                    <Text strong>连线</Text>
                    <Tag color={selectedEdge.valid ? 'success' : 'error'} style={{ margin: 0 }}>
                      {selectedEdge.valid ? '有效' : '无效'}
                    </Tag>
                  </Space>
                  <div style={{ marginTop: 4 }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>{selectedEdge.validationMessage}</Text>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>来源节点</Text>
                  <Select
                    value={selectedEdge.from}
                    onChange={(value) => patchEdge(selectedEdge.id, { from: value, sourcePort: 'out' })}
                    style={{ width: '100%', marginTop: 4 }}
                    options={nodes.map((node) => ({ label: node.name, value: node.id }))}
                  />
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>来源端口</Text>
                  <Select
                    value={selectedEdge.sourcePort || 'out'}
                    onChange={(value) => patchEdge(selectedEdge.id, { sourcePort: value })}
                    style={{ width: '100%', marginTop: 4 }}
                    options={[{ label: 'out · TABLE', value: 'out' }]}
                  />
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>目标节点</Text>
                  <Select
                    value={selectedEdge.to}
                    onChange={(value) => {
                      const target = nodeById(value);
                      patchEdge(selectedEdge.id, { to: value, targetPort: firstInputPortName(target) });
                    }}
                    style={{ width: '100%', marginTop: 4 }}
                    options={nodes.map((node) => ({ label: node.name, value: node.id }))}
                  />
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>目标端口</Text>
                  <Select
                    value={selectedEdge.targetPort || 'in'}
                    onChange={(value) => patchEdge(selectedEdge.id, { targetPort: value })}
                    style={{ width: '100%', marginTop: 4 }}
                    options={inputPortsForNode(nodeById(selectedEdge.to)).map((port) => ({
                      label: `${port.name} · ${port.cardinality}`,
                      value: port.name,
                    }))}
                    notFoundContent="无输入端口"
                  />
                </div>
                <Button danger icon={<DeleteOutlined />} onClick={removeSelectedEdge}>删除连线</Button>
              </div>
            ) : selected ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>节点</Text>
                  <div style={{ marginTop: 4 }}>
                    <Input
                      value={selected.name}
                      onChange={(event) => patchSelectedNode({ name: event.target.value })}
                    />
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>算子引用</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space size={6} wrap>
                      <Text code style={{ fontSize: 12 }}>{selectedOperatorRef || '-'}</Text>
                      {selected.operatorVersion && <Tag style={{ margin: 0 }}>{selected.operatorVersion}</Tag>}
                    </Space>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>画布坐标</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space size={6}>
                      <Tag style={{ margin: 0 }}>x {selected.x}</Tag>
                      <Tag style={{ margin: 0 }}>y {selected.y}</Tag>
                    </Space>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>分类 / 编译目标</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space size={6} wrap>
                      <Tag color="processing" style={{ margin: 0 }}>{selectedOperator?.category || nodeTypeForGraph(selected)}</Tag>
                      <Tag style={{ margin: 0 }}>{selectedOperator?.manifest?.compileTarget || selected.engine || 'TRINO_DBT'}</Tag>
                    </Space>
                  </div>
                </div>
                {selected.sql && (
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>SQL</Text>
                    <Input.TextArea
                      rows={4}
                      value={selected.sql}
                      onChange={(event) => patchSelectedNode({ sql: event.target.value })}
                      style={{ marginTop: 4, fontFamily: 'monospace', fontSize: 12 }}
                    />
                  </div>
                )}
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>输入端口</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space size={[4, 4]} wrap>
                      {(selectedOperator?.manifest?.inputPorts || []).length > 0 ? selectedOperator?.manifest?.inputPorts?.map((port) => (
                        <Tag key={port.name} style={{ margin: 0 }}>{port.name} · {port.cardinality}</Tag>
                      )) : <Tag style={{ margin: 0 }}>无输入</Tag>}
                    </Space>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>密级联动</Text>
                  <div style={{ marginTop: 4 }}>
                    {selected.type === 'MASK' || selected.type === 'ENCRYPT' || selectedOperator?.category === 'MASK' || selectedOperator?.category === 'ENCRYPT'
                      ? <ClassificationBadge level="L3" />
                      : <Tag style={{ margin: 0 }}>按上游字段继承</Tag>}
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>参数</Text>
                  <div style={{ marginTop: 6 }}>
                    {selectedParamFields.length === 0 ? (
                      <Alert type="info" showIcon message="当前算子没有可编辑参数" />
                    ) : (
                      <Space direction="vertical" size={8} style={{ width: '100%' }}>
                        {selectedParamFields.map((field) => (
                          <div key={field.key}>
                            <Space size={4} style={{ marginBottom: 4 }}>
                              <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
                                {field.label}
                              </Text>
                              {field.required && <Tag color="red" style={{ margin: 0 }}>必填</Tag>}
                            </Space>
                            {renderParamInput(
                              field,
                              selected.config?.[field.key] ?? defaultValueForParam(field.property),
                              (value) => updateSelectedConfig(field.key, value),
                            )}
                            {field.property.description && (
                              <div style={{ marginTop: 3 }}>
                                <Text type="secondary" style={{ fontSize: 11 }}>{field.property.description}</Text>
                              </div>
                            )}
                          </div>
                        ))}
                      </Space>
                    )}
                  </div>
                </div>
              </div>
            ) : null}
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
      <Modal open={validateOpen} onCancel={() => setValidateOpen(false)} title={`校验结果 - ${pipelineName}`}
        footer={<Button type="primary" onClick={() => setValidateOpen(false)}>{validation?.ok ? '关闭' : '一键定位首个错误'}</Button>}>
        <Space direction="vertical" style={{ width: '100%' }}>
          {validating && <Alert type="info" showIcon message="正在执行图级校验" />}
          {!validating && validation && validation.errors.length > 0 && (
            <Alert type="error" showIcon message={`✗ ${validation.errors.length} 错误`} description={(
              <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12 }}>
                {validation.errors.map((error) => <li key={error}>{error}</li>)}
              </ul>
            )} />
          )}
          {!validating && validation && validation.warnings.length > 0 && (
            <Alert type="warning" showIcon message={`⚠ ${validation.warnings.length} 警告`} description={(
              <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12 }}>
                {validation.warnings.map((warning) => <li key={warning}>{warning}</li>)}
              </ul>
            )} />
          )}
          {!validating && validation?.ok && (
            <Alert type="success" showIcon message={`✓ ${nodes.length} 节点通过`} description={<span style={{ fontSize: 12 }}>环路 / 端口 / Manifest / 必填参数 / 编译目标</span>} />
          )}
          {!validating && !validation && <Alert type="info" showIcon message="点击校验后展示后端返回结果" />}
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

function defaultConfigFromManifest(manifest?: OperatorManifest): Record<string, unknown> {
  return paramFieldsFromManifest(manifest).reduce<Record<string, unknown>>((config, field) => {
    if (!field.required) return config;
    config[field.key] = defaultValueForParam(field.property);
    return config;
  }, {});
}

function paramFieldsFromManifest(manifest?: OperatorManifest): ParamField[] {
  const schema = manifest?.paramsSchema;
  const properties = isRecord(schema?.properties) ? schema.properties : {};
  const required = Array.isArray(schema?.required) ? schema.required.map(String) : [];
  return Object.entries(properties).map(([key, value]) => {
    const property = normalizeParamProperty(value);
    return {
      key,
      label: property.title || key,
      required: required.includes(key),
      property,
    };
  });
}

function normalizeParamProperty(value: unknown): ParamSchemaProperty {
  if (!isRecord(value)) {
    return {};
  }
  return value as unknown as ParamSchemaProperty;
}

function renderParamInput(field: ParamField, value: unknown, onChange: (value: unknown) => void) {
  const type = schemaType(field.property);
  if (field.property.enum && field.property.enum.length > 0) {
    return (
      <Select
        value={primitiveValue(value)}
        onChange={onChange}
        style={{ width: '100%' }}
        options={field.property.enum.map((option) => ({ label: String(option), value: option }))}
      />
    );
  }
  if (type === 'boolean') {
    return <Checkbox checked={Boolean(value)} onChange={(event) => onChange(event.target.checked)}>启用</Checkbox>;
  }
  if (type === 'number' || type === 'integer') {
    return (
      <InputNumber
        value={numberValue(value)}
        precision={type === 'integer' ? 0 : undefined}
        onChange={(next) => onChange(next ?? 0)}
        style={{ width: '100%' }}
      />
    );
  }
  if (type === 'array') {
    return (
      <Input.TextArea
        rows={2}
        value={arrayText(value)}
        onChange={(event) => onChange(parseArrayText(event.target.value))}
        placeholder="每行一个值，或用逗号分隔"
      />
    );
  }
  if (type === 'object') {
    return (
      <Input.TextArea
        rows={4}
        value={objectText(value)}
        onChange={(event) => onChange(parseObjectText(event.target.value))}
        style={{ fontFamily: 'monospace', fontSize: 12 }}
      />
    );
  }
  return <Input value={stringValue(value)} onChange={(event) => onChange(event.target.value)} />;
}

function defaultValueForParam(property?: ParamSchemaProperty): unknown {
  if (property && Object.prototype.hasOwnProperty.call(property, 'default')) {
    return property.default;
  }
  const type = schemaType(property);
  if (type === 'boolean') return false;
  if (type === 'number' || type === 'integer') return 0;
  if (type === 'array') return [];
  if (type === 'object') return {};
  return '';
}

function schemaType(property?: ParamSchemaProperty): string {
  const type = property?.type;
  if (Array.isArray(type)) {
    return type.find((item) => item !== 'null') || 'string';
  }
  return type || 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function primitiveValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? value : undefined;
}

function numberValue(value: unknown) {
  if (typeof value === 'number') return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function stringValue(value: unknown) {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  return String(value);
}

function arrayText(value: unknown) {
  if (Array.isArray(value)) return value.map(String).join(', ');
  if (typeof value === 'string') return value;
  return '';
}

function parseArrayText(value: string) {
  return value
    .split(/[,\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function objectText(value: unknown) {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function parseObjectText(value: string) {
  if (!value.trim()) return {};
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}
