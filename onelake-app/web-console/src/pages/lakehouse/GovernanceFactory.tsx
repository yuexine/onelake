import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  App as AntdApp,
  Button,
  Checkbox,
  Col,
  Divider,
  Drawer,
  Input,
  Row,
  Select,
  Space,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  CodeOutlined,
  DatabaseOutlined,
  FieldTimeOutlined,
  FunctionOutlined,
  ReloadOutlined,
  RocketOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { CatalogAPI, ModelingAPI, OperatorAPI } from '../../api';
import { ClassificationBadge, PageHeader, SectionCard, StateView } from '../../components';
import type { Asset, AssetColumn, Codebook, DataModel, DataModelColumnMapping, DwdModelCompileResult, DwdModelValidation, RuntimeContract } from '../../types';
import { normalizeCatalogAssets } from './assetAdapter';

const { Text, Paragraph } = Typography;

type FieldOperator = 'DIRECT' | 'TRIM' | 'FILLNA' | 'MASK_PARTIAL' | 'SHA256' | 'DICT' | 'LOOKUP';

interface FieldRule {
  id: string;
  source: string;
  sourceType?: string;
  target: string;
  targetType?: string;
  classification?: string;
  piiType?: string;
  suggestLevel?: string;
  primaryKey?: boolean;
  operator: FieldOperator;
  fillValue?: string;
  dictionaryRef?: string;
  dictionaryName?: string;
  dictionaryVersion?: string;
  dictionaryText?: string;
  noMatchPolicy?: 'KEEP' | 'NULL' | 'FAIL';
  lookupFqn?: string;
  lookupLeftKey?: string;
  lookupRightKey?: string;
  lookupField?: string;
  lookupAlias?: string;
}

interface GovernanceGraphNode {
  id: string;
  type: string;
  nodeType: string;
  name: string;
  operatorRef: string;
  operatorVersion: string;
  config: Record<string, unknown>;
}

export interface GovernanceFactoryProps {
  embedded?: boolean;
  initialSourceAssetId?: string;
  initialModel?: DataModel;
  initialResourceGroup?: string;
  initialComputeProfile?: string;
  initialEngine?: string;
  onModelChange?: (model?: DataModel) => void;
}

const OPERATOR_OPTIONS: { value: FieldOperator; label: string }[] = [
  { value: 'DIRECT', label: '直通' },
  { value: 'TRIM', label: '去空格' },
  { value: 'FILLNA', label: '空值填充' },
  { value: 'MASK_PARTIAL', label: '部分脱敏' },
  { value: 'SHA256', label: 'SHA256 哈希' },
  { value: 'DICT', label: '字典匹配' },
  { value: 'LOOKUP', label: '关联查询' },
];

interface DictionaryPreset {
  code: string;
  name: string;
  version: string;
  domain?: string;
  description?: string;
  noMatchPolicy: 'KEEP' | 'NULL' | 'FAIL';
  pairs: { from: string; to: string }[];
  source?: 'BUILTIN' | 'CODEBOOK';
}

const DICTIONARY_PRESETS: DictionaryPreset[] = [
  {
    code: 'core.gender',
    name: '性别标准字典',
    version: '2026.06',
    domain: '会员',
    description: '统一常见性别编码、中文枚举与英文枚举。',
    noMatchPolicy: 'KEEP' as const,
    pairs: [
      { from: 'M', to: '男' },
      { from: 'F', to: '女' },
      { from: 'male', to: '男' },
      { from: 'female', to: '女' },
      { from: '1', to: '男' },
      { from: '2', to: '女' },
    ],
    source: 'BUILTIN',
  },
  {
    code: 'core.yes_no',
    name: '是否标识字典',
    version: '2026.06',
    domain: '通用',
    description: '将 Y/N、1/0、true/false 统一为 是/否。',
    noMatchPolicy: 'KEEP' as const,
    pairs: [
      { from: 'Y', to: '是' },
      { from: 'N', to: '否' },
      { from: '1', to: '是' },
      { from: '0', to: '否' },
      { from: 'true', to: '是' },
      { from: 'false', to: '否' },
    ],
    source: 'BUILTIN',
  },
  {
    code: 'trade.order_status',
    name: '订单状态字典',
    version: '2026.06',
    domain: '交易',
    description: '将交易订单状态码统一为业务可读状态。',
    noMatchPolicy: 'KEEP' as const,
    pairs: [
      { from: '10', to: '已创建' },
      { from: '20', to: '已支付' },
      { from: '30', to: '已发货' },
      { from: '40', to: '已完成' },
      { from: '90', to: '已取消' },
    ],
    source: 'BUILTIN',
  },
  {
    code: 'member.level',
    name: '会员等级字典',
    version: '2026.06',
    domain: '会员',
    description: '将会员等级编码统一为 Bronze/Silver/Gold/Platinum。',
    noMatchPolicy: 'KEEP' as const,
    pairs: [
      { from: '1', to: 'Bronze' },
      { from: '2', to: 'Silver' },
      { from: '3', to: 'Gold' },
      { from: '4', to: 'Platinum' },
    ],
    source: 'BUILTIN',
  },
];

function tableNameFromFqn(fqn: string) {
  const part = fqn.split('.').pop() || fqn;
  return part.replace(/^ods_/, '');
}

function normalizeIdentifier(value: string) {
  const text = value.trim().toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+|_+$/g, '');
  return text || 'governed_table';
}

function defaultTargetName(asset: Asset) {
  const base = normalizeIdentifier(tableNameFromFqn(asset.fqn));
  return base.startsWith('dwd_') ? base : `dwd_${base.endsWith('_df') ? base.slice(0, -3) : base}_df`;
}

function targetNameFromFqn(fqn?: string) {
  if (!fqn) return '';
  return normalizeIdentifier(fqn.split('.').pop() || fqn);
}

function quoteIdentifier(name?: string) {
  const text = name || '';
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(text) ? text : `"${text.replace(/"/g, '""')}"`;
}

function sourceExpr(column: string) {
  return `src.${quoteIdentifier(column)}`;
}

function literal(value?: string) {
  return `'${(value || '').replace(/'/g, "''")}'`;
}

function parseDictionary(text?: string) {
  return (text || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [from, ...rest] = line.split('=');
      return { from: from?.trim(), to: rest.join('=').trim() };
    })
    .filter((item) => item.from && item.to);
}

function dictionaryTextFromPairs(pairs?: unknown) {
  if (!Array.isArray(pairs)) return '';
  return pairs
    .map((pair) => {
      if (!pair || typeof pair !== 'object') return '';
      const item = pair as { from?: unknown; to?: unknown };
      const from = typeof item.from === 'string' ? item.from : '';
      const to = typeof item.to === 'string' ? item.to : '';
      return from && to ? `${from}=${to}` : '';
    })
    .filter(Boolean)
    .join('\n');
}

function dictionaryPresetValue(preset: DictionaryPreset) {
  return `${preset.code}@${preset.version}`;
}

function findDictionaryPreset(code?: string, version?: string, presets: DictionaryPreset[] = DICTIONARY_PRESETS) {
  if (!code) return undefined;
  return presets.find((preset) => (
    preset.code === code && (!version || preset.version === version)
  ));
}

function codebookPreset(codebook: Codebook): DictionaryPreset | undefined {
  if (!codebook.latestVersion || !Array.isArray(codebook.entries) || codebook.entries.length === 0) return undefined;
  const pairs = codebook.entries
    .filter((entry) => entry.from && entry.to)
    .map((entry) => ({ from: entry.from, to: entry.to }));
  if (pairs.length === 0) return undefined;
  return {
    code: codebook.code,
    name: codebook.name,
    version: codebook.latestVersion,
    domain: codebook.domain,
    description: codebook.description,
    noMatchPolicy: codebook.noMatchPolicy === 'NULL' || codebook.noMatchPolicy === 'FAIL' ? codebook.noMatchPolicy : 'KEEP',
    pairs,
    source: 'CODEBOOK',
  };
}

function asConfigRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function textValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function noMatchPolicyValue(value: unknown): FieldRule['noMatchPolicy'] | undefined {
  return value === 'KEEP' || value === 'NULL' || value === 'FAIL' ? value : undefined;
}

function graphDictionaryNode(
  nodes: GovernanceGraphNode[],
  mapping: DataModelColumnMapping,
  graphRule?: Partial<FieldRule>,
) {
  return nodes.find((node) => {
    if (node.operatorRef !== 'standard.codebook_mapping') return false;
    const config = asConfigRecord(node.config);
    return textValue(config.outputColumn) === mapping.target
      || textValue(config.column) === mapping.source
      || textValue(config.outputColumn) === graphRule?.target
      || textValue(config.column) === graphRule?.source;
  });
}

function ruleExpression(rule: FieldRule) {
  const src = sourceExpr(rule.source);
  if (rule.operator === 'TRIM') return `trim(cast(${src} as varchar))`;
  if (rule.operator === 'FILLNA') return `coalesce(${src}, ${literal(rule.fillValue)})`;
  if (rule.operator === 'MASK_PARTIAL') {
    return `regexp_replace(cast(${src} as varchar), '^(.{3}).*(.{4})$', '$1****$2')`;
  }
  if (rule.operator === 'SHA256') {
    return `to_hex(sha256(to_utf8(cast(${src} as varchar))))`;
  }
  if (rule.operator === 'DICT') {
    const pairs = parseDictionary(rule.dictionaryText);
    if (pairs.length === 0) return src;
    const elseValue = rule.noMatchPolicy === 'NULL' ? 'null' : src;
    return `case ${pairs.map((item) => `when ${src} = ${literal(item.from)} then ${literal(item.to)}`).join(' ')} else ${elseValue} end`;
  }
  if (rule.operator === 'LOOKUP') {
    const alias = normalizeIdentifier(rule.lookupAlias || `lk_${rule.target}`);
    return `${alias}.${quoteIdentifier(rule.lookupField || rule.target)}`;
  }
  return undefined;
}

function fieldRulesFromAsset(asset: Asset): FieldRule[] {
  return (asset.columns || []).map((column: AssetColumn, index) => ({
    id: `${column.name}-${index}`,
    source: column.name,
    sourceType: column.type,
    target: column.name,
    targetType: column.type,
    classification: column.classification,
    piiType: column.piiType,
    suggestLevel: column.suggestLevel,
    primaryKey: column.name === 'id' || column.name.endsWith('_id'),
    operator: column.classification === 'L3' || column.classification === 'L4' ? 'MASK_PARTIAL' : 'DIRECT',
  }));
}

function normalizeFieldOperator(value: unknown): FieldOperator | undefined {
  return OPERATOR_OPTIONS.some((item) => item.value === value) ? value as FieldOperator : undefined;
}

function parseGovernanceGraph(raw?: string) {
  if (!raw) return { fieldRules: [], nodes: [] as GovernanceGraphNode[] };
  try {
    const graph = JSON.parse(raw) as { fieldRules?: Partial<FieldRule>[]; nodes?: GovernanceGraphNode[] };
    const nodes = Array.isArray(graph.nodes) ? graph.nodes : [];
    if (Array.isArray(graph.fieldRules)) return { fieldRules: graph.fieldRules, nodes };
    const mappingNode = graph.nodes?.find((node) => Array.isArray(node.config?.fieldRules));
    return {
      fieldRules: (mappingNode?.config?.fieldRules || []) as Partial<FieldRule>[],
      nodes,
    };
  } catch {
    return { fieldRules: [], nodes: [] as GovernanceGraphNode[] };
  }
}

function inferOperatorFromMapping(mapping: DataModelColumnMapping): FieldOperator {
  const expr = mapping.expression || '';
  if (!expr) return 'DIRECT';
  const lower = expr.toLowerCase();
  if (lower.startsWith('trim(')) return 'TRIM';
  if (lower.startsWith('coalesce(')) return 'FILLNA';
  if (lower.startsWith('regexp_replace(')) return 'MASK_PARTIAL';
  if (lower.includes('sha256(')) return 'SHA256';
  if (lower.startsWith('case ')) return 'DICT';
  return 'DIRECT';
}

function fieldRulesFromModel(model: DataModel, asset?: Asset): FieldRule[] {
  const graph = parseGovernanceGraph(model.operatorGraph);
  const graphRules = graph.fieldRules;
  const graphNodes = graph.nodes || [];
  return (model.columnMappings || []).map((mapping, index) => {
    const graphRule = graphRules.find((item) => item.target === mapping.target || item.source === mapping.source);
    const sourceColumn = asset?.columns.find((column) => column.name === mapping.source);
    const operator = normalizeFieldOperator(graphRule?.operator) || inferOperatorFromMapping(mapping);
    const dictionaryNode = graphDictionaryNode(graphNodes, mapping, graphRule);
    const dictionaryConfig = asConfigRecord(dictionaryNode?.config);
    const dictionaryRef = textValue(graphRule?.dictionaryRef) || textValue(dictionaryConfig.dictionaryRef) || textValue(dictionaryConfig.dictionaryCode);
    const dictionaryVersion = textValue(graphRule?.dictionaryVersion) || textValue(dictionaryConfig.dictionaryVersion);
    const dictionaryPreset = findDictionaryPreset(dictionaryRef, dictionaryVersion);
    return {
      id: mapping.id || `${mapping.source || mapping.target}-${index}`,
      source: mapping.source,
      sourceType: mapping.sourceType || sourceColumn?.type,
      target: mapping.target,
      targetType: mapping.targetType || sourceColumn?.type,
      classification: mapping.classification || sourceColumn?.classification,
      piiType: mapping.piiType || sourceColumn?.piiType,
      suggestLevel: mapping.suggestLevel || sourceColumn?.suggestLevel,
      primaryKey: mapping.primaryKey,
      operator,
      dictionaryRef,
      dictionaryName: textValue(graphRule?.dictionaryName) || textValue(dictionaryConfig.dictionaryName) || dictionaryPreset?.name,
      dictionaryVersion,
      dictionaryText: operator === 'DICT'
        ? textValue(graphRule?.dictionaryText) || dictionaryTextFromPairs(dictionaryConfig.pairs)
        : undefined,
      noMatchPolicy: noMatchPolicyValue(graphRule?.noMatchPolicy) || noMatchPolicyValue(dictionaryConfig.noMatchPolicy),
    };
  });
}

function buildOperatorGraph(source: Asset, targetFqn: string, rules: FieldRule[]) {
  const advancedNodes: GovernanceGraphNode[] = [];
  rules.forEach((rule) => {
    if (rule.operator === 'DICT') {
      advancedNodes.push({
        id: `dict_${rule.id}`,
        type: 'GOVERN',
        nodeType: 'GOVERN',
        name: `${rule.source} 字典匹配`,
        operatorRef: 'standard.codebook_mapping',
        operatorVersion: '1.0.0',
        config: {
          type: 'DICTIONARY_MAPPING',
          column: rule.source,
          outputColumn: rule.target,
          dictionaryRef: rule.dictionaryRef,
          dictionaryName: rule.dictionaryName,
          dictionaryVersion: rule.dictionaryVersion,
          dictionarySource: rule.dictionaryRef ? 'PRESET' : 'INLINE',
          pairs: parseDictionary(rule.dictionaryText),
          noMatchPolicy: rule.noMatchPolicy || 'KEEP',
        },
      });
      return;
    }
    if (rule.operator === 'LOOKUP') {
      advancedNodes.push({
        id: `lookup_${rule.id}`,
        type: 'TRANSFORM',
        nodeType: 'TRANSFORM',
        name: `${rule.target} 关联补充`,
        operatorRef: 'join.lookup_enrich',
        operatorVersion: '1.0.0',
        config: {
          type: 'LOOKUP_JOIN',
          lookupFqn: rule.lookupFqn,
          alias: normalizeIdentifier(rule.lookupAlias || `lk_${rule.target}`),
          leftKey: rule.lookupLeftKey || rule.source,
          rightKey: rule.lookupRightKey,
          fields: [{ source: rule.lookupField, target: rule.target }],
        },
      });
    }
  });

  return {
    version: 1,
    pipelineMode: 'SPARK_GOVERNANCE',
    sourceFqn: source.fqn,
    targetFqn,
    fieldRules: rules.map((rule) => ({
      source: rule.source,
      target: rule.target,
      operator: rule.operator,
      classification: rule.classification,
      piiType: rule.piiType,
      suggestLevel: rule.suggestLevel,
      dictionaryRef: rule.dictionaryRef,
      dictionaryName: rule.dictionaryName,
      dictionaryVersion: rule.dictionaryVersion,
      noMatchPolicy: rule.noMatchPolicy,
      lookupFqn: rule.lookupFqn,
      lookupLeftKey: rule.lookupLeftKey,
      lookupRightKey: rule.lookupRightKey,
      lookupField: rule.lookupField,
      lookupAlias: rule.lookupAlias,
    })),
    nodes: advancedNodes,
    edges: advancedNodes.map((node) => ({ source: 'transform_mapping', target: node.id })),
  };
}

export default function GovernanceFactory({
  embedded = false,
  initialSourceAssetId,
  initialModel,
  initialResourceGroup,
  initialComputeProfile,
  initialEngine,
  onModelChange,
}: GovernanceFactoryProps = {}) {
  const { message } = AntdApp.useApp();
  const [searchParams] = useSearchParams();
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sourceAssetId, setSourceAssetId] = useState(initialSourceAssetId || searchParams.get('sourceAssetId') || '');
  const [targetName, setTargetName] = useState('');
  const [domain, setDomain] = useState('');
  const [materialization, setMaterialization] = useState<'TABLE' | 'VIEW' | 'INCREMENTAL'>('TABLE');
  const [partitionExpr, setPartitionExpr] = useState('');
  const [rules, setRules] = useState<FieldRule[]>([]);
  const [selectedRuleId, setSelectedRuleId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [compiling, setCompiling] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [model, setModel] = useState<DataModel>();
  const [validation, setValidation] = useState<DwdModelValidation>();
  const [compileResult, setCompileResult] = useState<DwdModelCompileResult>();
  const [runtimeContracts, setRuntimeContracts] = useState<RuntimeContract[]>([]);
  const [codebookPresets, setCodebookPresets] = useState<DictionaryPreset[]>([]);
  const [hydratedModelId, setHydratedModelId] = useState<string>();

  const sourceAsset = assets.find((item) => item.id === sourceAssetId);
  const odsAssets = assets.filter((item) => item.layer === 'ODS');
  const targetFqn = `dwd.${normalizeIdentifier(targetName)}`;
  const selectedRule = rules.find((rule) => rule.id === selectedRuleId);
  const selectedSourceColumn = sourceAsset?.columns.find((column) => column.name === selectedRule?.source);
  const advancedCount = rules.filter((rule) => rule.operator === 'DICT' || rule.operator === 'LOOKUP').length;
  const governedCount = rules.filter((rule) => rule.operator !== 'DIRECT').length;
  const sparkContract = runtimeContracts.find((item) => item.compileTarget === 'SPARK');
  const advancedContracts = runtimeContracts.filter((item) => item.compileTarget === 'SPARK');
  const issueItems = useMemo(() => {
    const items: { level: 'error' | 'warning'; text: string }[] = [];
    if (rules.length > 0 && !rules.some((rule) => rule.primaryKey)) {
      items.push({ level: 'warning', text: '未设置主键，质量门禁将退回输出字段非空校验' });
    }
    rules.forEach((rule) => {
      if ((rule.classification === 'L3' || rule.classification === 'L4') && rule.operator === 'DIRECT') {
        items.push({ level: 'warning', text: `${rule.source} 是敏感字段但仍为直通` });
      }
      if (rule.operator === 'DICT' && parseDictionary(rule.dictionaryText).length === 0) {
        items.push({ level: 'error', text: `${rule.target} 字典匹配缺少映射值` });
      }
      if (rule.operator === 'LOOKUP' && (!rule.lookupFqn || !rule.lookupLeftKey || !rule.lookupRightKey || !rule.lookupField)) {
        items.push({ level: 'error', text: `${rule.target} 关联查询配置不完整` });
      }
      if (rule.operator === 'LOOKUP' && rule.lookupFqn === sourceAsset?.fqn) {
        items.push({ level: 'warning', text: `${rule.target} 关联表与源表相同，请确认不是误选` });
      }
    });
    return items;
  }, [rules, sourceAsset?.fqn]);

  const loadAssets = () => {
    setLoading(true);
    setLoadError(null);
    CatalogAPI.listAssets()
      .then((items) => {
        const normalized = normalizeCatalogAssets(items);
        setAssets(normalized);
        const initialModelSource = initialModel
          ? normalized.find((item) => item.fqn === initialModel.sourceFqn)
          : undefined;
        const preferredId = sourceAssetId
          || searchParams.get('sourceAssetId')
          || initialSourceAssetId
          || initialModelSource?.id
          || normalized.find((item) => item.layer === 'ODS')?.id
          || '';
        if (preferredId && !sourceAssetId) {
          setSourceAssetId(preferredId);
        }
      })
      .catch((e) => {
        setAssets([]);
        setLoadError(e instanceof Error ? e.message : '资产加载失败');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAssets();
    OperatorAPI.listRuntimeContracts()
      .then(setRuntimeContracts)
      .catch(() => setRuntimeContracts([]));
    ModelingAPI.listCodebooks({ status: 'PUBLISHED' })
      .then((items) => setCodebookPresets(items.map(codebookPreset).filter((item): item is DictionaryPreset => Boolean(item))))
      .catch(() => setCodebookPresets([]));
  }, []);

  useEffect(() => {
    if (initialSourceAssetId && initialSourceAssetId !== sourceAssetId) {
      setSourceAssetId(initialSourceAssetId);
    }
  }, [initialSourceAssetId]);

  useEffect(() => {
    if (!sourceAsset) return;
    if (initialModel && initialModel.sourceFqn === sourceAsset.fqn) {
      if (hydratedModelId !== initialModel.id) {
        setTargetName(targetNameFromFqn(initialModel.targetFqn));
        setDomain(initialModel.domain || sourceAsset.domain || '交易');
        setMaterialization(initialModel.materialization as any || 'TABLE');
        setPartitionExpr(initialModel.partitionExpr || '');
        setRules(fieldRulesFromModel(initialModel, sourceAsset));
        setSelectedRuleId(undefined);
        setModel(initialModel);
        setValidation(undefined);
        setCompileResult(undefined);
        setHydratedModelId(initialModel.id);
      }
      return;
    }
    setTargetName(defaultTargetName(sourceAsset));
    setDomain(sourceAsset.domain || '交易');
    setRules(fieldRulesFromAsset(sourceAsset));
    setSelectedRuleId(undefined);
    setModel(undefined);
    setValidation(undefined);
    setCompileResult(undefined);
    setHydratedModelId(undefined);
  }, [sourceAssetId, sourceAsset?.id]);

  const resourceGroup = initialModel?.resourceGroup || initialResourceGroup || 'spark-default';
  const computeProfile = initialModel?.computeProfile || initialComputeProfile || 'spark-small';
  const engine = initialModel?.engine || initialEngine || 'SPARK';

  useEffect(() => {
    if (!initialModel) return;
    setModel(initialModel);
    setHydratedModelId(undefined);
    const matchedSource = assets.find((asset) => asset.fqn === initialModel.sourceFqn);
    if (matchedSource && matchedSource.id !== sourceAssetId) {
      setSourceAssetId(matchedSource.id);
    }
  }, [initialModel?.id]);

  const sourceColumnOptions = useMemo(
    () => (sourceAsset?.columns || []).map((column) => ({ value: column.name, label: `${column.name} · ${column.type}` })),
    [sourceAsset],
  );

  const assetOptions = useMemo(
    () => odsAssets.map((asset) => ({ value: asset.id, label: `${asset.fqn} · ${asset.domain || '未归属'}域` })),
    [odsAssets],
  );

  const lookupAssetOptions = useMemo(
    () => assets.map((asset) => ({ value: asset.fqn, label: `${asset.fqn} · ${asset.layer}` })),
    [assets],
  );

  const dictionaryPresets = useMemo(() => {
    const seen = new Set<string>();
    return [...codebookPresets, ...DICTIONARY_PRESETS].filter((preset) => {
      const key = dictionaryPresetValue(preset);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [codebookPresets]);

  const dictionaryPresetOptions = useMemo(
    () => dictionaryPresets.map((preset) => ({
      value: dictionaryPresetValue(preset),
      label: `${preset.name} · ${preset.version} · ${preset.domain || '通用'}${preset.source === 'CODEBOOK' ? ' · 已发布' : ''}`,
    })),
    [dictionaryPresets],
  );

  const selectedDictionaryPreset = useMemo(
    () => selectedRule ? findDictionaryPreset(selectedRule.dictionaryRef, selectedRule.dictionaryVersion, dictionaryPresets) : undefined,
    [dictionaryPresets, selectedRule?.dictionaryRef, selectedRule?.dictionaryVersion],
  );

  const lookupColumns = useMemo(() => {
    const lookupAsset = assets.find((asset) => asset.fqn === selectedRule?.lookupFqn);
    return (lookupAsset?.columns || []).map((column) => ({ value: column.name, label: `${column.name} · ${column.type}` }));
  }, [assets, selectedRule?.lookupFqn]);

  const updateRule = (id: string, patch: Partial<FieldRule>) => {
    setRules((items) => items.map((item) => item.id === id ? { ...item, ...patch } : item));
    setValidation(undefined);
    setCompileResult(undefined);
  };

  const applyDictionaryPreset = (id: string, value?: string) => {
    const preset = dictionaryPresets.find((item) => dictionaryPresetValue(item) === value);
    if (!preset) {
      updateRule(id, {
        dictionaryRef: undefined,
        dictionaryName: undefined,
        dictionaryVersion: undefined,
      });
      return;
    }
    updateRule(id, {
      dictionaryRef: preset.code,
      dictionaryName: preset.name,
      dictionaryVersion: preset.version,
      dictionaryText: dictionaryTextFromPairs(preset.pairs),
      noMatchPolicy: preset.noMatchPolicy,
    });
  };

  const addLookupField = () => {
    const source = sourceAsset?.columns.find((column) => column.name.endsWith('_id')) || sourceAsset?.columns[0];
    const lookup = assets.find((asset) => asset.layer === 'DWD' || asset.layer === 'ODS');
    const next: FieldRule = {
      id: `lookup-${Date.now()}`,
      source: source?.name || '',
      sourceType: source?.type || 'VARCHAR',
      target: 'lookup_value',
      targetType: 'VARCHAR',
      operator: 'LOOKUP',
      lookupFqn: lookup?.fqn,
      lookupLeftKey: source?.name,
      lookupRightKey: source?.name,
      lookupField: lookup?.columns?.[0]?.name,
      lookupAlias: 'lk_lookup_value',
    };
    setRules((items) => [...items, next]);
    setSelectedRuleId(next.id);
  };

  const buildPayload = () => {
    if (!sourceAsset) throw new Error('请选择源表');
    const primary = rules.find((rule) => rule.primaryKey)?.target;
    return {
      name: normalizeIdentifier(targetName),
      domain,
      sourceFqn: sourceAsset.fqn,
      targetFqn,
      materialization,
      uniqueKey: primary,
      partitionExpr: partitionExpr || undefined,
      columnMappings: rules
        .filter((rule) => rule.source && rule.target)
        .map((rule) => ({
          source: rule.operator === 'LOOKUP' ? (rule.lookupLeftKey || rule.source) : rule.source,
          target: normalizeIdentifier(rule.target),
          sourceType: rule.sourceType,
          targetType: rule.targetType,
          expression: ruleExpression(rule),
          primaryKey: rule.primaryKey,
          classification: rule.classification as any,
          piiType: rule.piiType,
          suggestLevel: rule.suggestLevel as any,
        })),
      pipelineMode: 'SPARK_GOVERNANCE',
      operatorGraphVersion: 1,
      operatorGraph: JSON.stringify(buildOperatorGraph(sourceAsset, targetFqn, rules)),
      resourceGroup,
      computeProfile,
      engine,
    };
  };

  const persistDraft = async () => {
    const payload = buildPayload();
    const saved = model?.id
      ? await ModelingAPI.updateModel(model.id, payload)
      : await ModelingAPI.createDwdDraft(payload);
    setModel(saved);
    onModelChange?.(saved);
    const checked = await ModelingAPI.validateModel(saved.id);
    setValidation(checked);
    return saved;
  };

  const saveDraft = async () => {
    setSaving(true);
    try {
      await persistDraft();
      message.success('治理表草稿已保存并完成校验');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '治理表草稿保存失败');
    } finally {
      setSaving(false);
    }
  };

  const compileDraft = async () => {
    setCompiling(true);
    try {
      const saved = await persistDraft();
      const result = await ModelingAPI.compileModel(saved.id);
      setCompileResult(result);
      const refreshed = await ModelingAPI.getModel(saved.id);
      setModel(refreshed);
      onModelChange?.(refreshed);
      message.success('Spark 编译产物已生成');
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Spark 编译失败');
    } finally {
      setCompiling(false);
    }
  };

  const publishCompiledModel = async () => {
    if (!model?.id) return;
    setPublishing(true);
    try {
      const published = await ModelingAPI.publishModel(model.id, { comment: 'DWD 治理模型发布' });
      setModel(published);
      onModelChange?.(published);
      message.success('治理表已发布');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '治理表发布失败');
    } finally {
      setPublishing(false);
    }
  };

  const canPersist = Boolean(sourceAsset && targetName.trim() && rules.length > 0);
  const canPublish = Boolean(model?.id && (model.status === 'VALIDATED' || model.status === 'PUBLISHED' || compileResult));

  return (
    <div className={embedded ? undefined : 'ol-page'} style={embedded ? { padding: 0 } : undefined}>
      {!embedded && (
        <PageHeader
          icon={<FunctionOutlined />}
          title="DWD 治理设计器"
          subtitle={<span className="ol-chip">表级治理模型 · 字段级 Recipe</span>}
          description="以源表和目标表为边界，在模型内部维护字段映射、字典匹配、关联补充、质量门禁与 Spark 编译产物"
          meta={[
            { label: '源字段', value: sourceAsset?.columns.length || 0 },
            { label: '治理字段', value: governedCount },
            { label: '高级治理', value: advancedCount },
          ]}
          actions={(
            <>
              <Button icon={<ReloadOutlined />} onClick={loadAssets}>刷新资产</Button>
              <Button icon={<SaveOutlined />} loading={saving} disabled={!canPersist} onClick={saveDraft}>保存校验</Button>
              <Button type="primary" icon={<CodeOutlined />} loading={compiling} disabled={!canPersist} onClick={compileDraft}>编译 Spark</Button>
            </>
          )}
        />
      )}

      {embedded && (
        <Space size={8} wrap style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
          <Space size={8} wrap>
            <Tag color="processing" style={{ margin: 0 }}>DWD 治理模型</Tag>
            <Tag style={{ margin: 0 }}>源字段 {sourceAsset?.columns.length || 0}</Tag>
            <Tag style={{ margin: 0 }}>治理字段 {governedCount}</Tag>
            <Tag style={{ margin: 0 }}>高级治理 {advancedCount}</Tag>
          </Space>
          <Space size={8} wrap>
            <Button icon={<ReloadOutlined />} onClick={loadAssets}>刷新资产</Button>
            <Button icon={<SaveOutlined />} loading={saving} disabled={!canPersist} onClick={saveDraft}>保存校验</Button>
            <Button type="primary" icon={<CodeOutlined />} loading={compiling} disabled={!canPersist} onClick={compileDraft}>编译 Spark</Button>
          </Space>
        </Space>
      )}

      <Steps
        size="small"
        current={compileResult ? 3 : validation ? 2 : rules.length > 0 ? 1 : 0}
        items={[
          { title: '源表与目标' },
          { title: '字段映射' },
          { title: '高级治理' },
          { title: 'Spark 编译' },
        ]}
        style={{ marginTop: 16, marginBottom: 16 }}
      />

      {loadError && (
        <Alert
          type="error"
          showIcon
          message="资产加载失败"
          description={loadError}
          action={<Button size="small" onClick={loadAssets}>重试</Button>}
          style={{ marginBottom: 16 }}
        />
      )}

      <Row gutter={16}>
        <Col xs={24} xl={6}>
          <SectionCard title="源表与目标" icon={<DatabaseOutlined />}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>源 ODS 表</Text>
                <Select
                  showSearch
                  loading={loading}
                  value={sourceAssetId || undefined}
                  placeholder="选择源表"
                  options={assetOptions}
                  optionFilterProp="label"
                  onChange={setSourceAssetId}
                  style={{ width: '100%', marginTop: 6 }}
                />
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>目标表名</Text>
                <Space.Compact style={{ width: '100%', marginTop: 6 }}>
                  <Input value="dwd." readOnly style={{ width: 64, color: 'var(--ol-ink-3)' }} />
                  <Input
                    value={targetName}
                    onChange={(e) => setTargetName(normalizeIdentifier(e.target.value))}
                  />
                </Space.Compact>
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>业务域</Text>
                <Input value={domain} onChange={(e) => setDomain(e.target.value)} style={{ marginTop: 6 }} />
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>物化方式</Text>
                <Select
                  value={materialization}
                  onChange={setMaterialization}
                  options={[
                    { value: 'TABLE', label: '表' },
                    { value: 'VIEW', label: '视图' },
                    { value: 'INCREMENTAL', label: '增量表' },
                  ]}
                  style={{ width: '100%', marginTop: 6 }}
                />
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>分区表达式</Text>
                <Input
                  value={partitionExpr}
                  onChange={(e) => setPartitionExpr(e.target.value)}
                  placeholder="days(order_time)"
                  style={{ marginTop: 6 }}
                />
              </div>
              <Divider style={{ margin: '4px 0' }} />
              {sourceAsset ? (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Text strong>{sourceAsset.fqn}</Text>
                  <Space size={4} wrap>
                    <Tag>{sourceAsset.layer}</Tag>
                    <Tag>{sourceAsset.domain || '未归属'}域</Tag>
                    <ClassificationBadge level={sourceAsset.classification as any} />
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>目标：{targetFqn}</Text>
                </Space>
              ) : (
                <StateView state="empty" title="暂无源表" description="Catalog 中没有可用 ODS 资产" />
              )}
            </Space>
          </SectionCard>
        </Col>

        <Col xs={24} xl={12}>
          <SectionCard
            title="字段映射与处理 Recipe"
            icon={<FieldTimeOutlined />}
            subtitle={`${rules.length} 个输出字段`}
            extra={<Button size="small" icon={<ApartmentOutlined />} onClick={addLookupField} disabled={!sourceAsset}>添加关联字段</Button>}
            flatBody
          >
            <Table
              rowKey="id"
              dataSource={rules}
              loading={loading}
              size="small"
              pagination={false}
              locale={{ emptyText: <StateView state="empty" title="请选择源表" description="选择 ODS 表后生成字段映射 Recipe" /> }}
              columns={[
                {
                  title: '源字段',
                  dataIndex: 'source',
                  width: 160,
                  render: (_: string, record: FieldRule) => (
                    <Select
                      size="small"
                      value={record.source}
                      options={sourceColumnOptions}
                      optionFilterProp="label"
                      showSearch
                      style={{ width: '100%' }}
                      onFocus={() => setSelectedRuleId(record.id)}
                      onChange={(value) => {
                        const column = sourceAsset?.columns.find((item) => item.name === value);
                        updateRule(record.id, {
                          source: value,
                          sourceType: column?.type,
                          targetType: record.targetType || column?.type,
                          lookupLeftKey: record.operator === 'LOOKUP' ? value : record.lookupLeftKey,
                        });
                      }}
                    />
                  ),
                },
                {
                  title: '输出字段',
                  dataIndex: 'target',
                  width: 150,
                  render: (_: string, record: FieldRule) => (
                    <Input
                      size="small"
                      value={record.target}
                      onFocus={() => setSelectedRuleId(record.id)}
                      onChange={(e) => updateRule(record.id, { target: normalizeIdentifier(e.target.value) })}
                    />
                  ),
                },
                {
                  title: '字段处理',
                  dataIndex: 'operator',
                  width: 140,
                  render: (_: FieldOperator, record: FieldRule) => (
                    <Select
                      size="small"
                      value={record.operator}
                      options={OPERATOR_OPTIONS}
                      style={{ width: '100%' }}
                      onFocus={() => setSelectedRuleId(record.id)}
                      onChange={(value) => {
                        updateRule(record.id, {
                          operator: value,
                          lookupLeftKey: value === 'LOOKUP' ? record.source : record.lookupLeftKey,
                          lookupAlias: value === 'LOOKUP' ? normalizeIdentifier(`lk_${record.target}`) : record.lookupAlias,
                        });
                        setSelectedRuleId(record.id);
                      }}
                    />
                  ),
                },
                {
                  title: '质量/安全',
                  width: 120,
                  render: (_: unknown, record: FieldRule) => (
                    <Space direction="vertical" size={4}>
                      <Checkbox
                        checked={record.primaryKey}
                        onChange={(e) => {
                          setSelectedRuleId(record.id);
                          updateRule(record.id, { primaryKey: e.target.checked });
                        }}
                      >
                        主键
                      </Checkbox>
                      <ClassificationBadge level={record.suggestLevel as any || record.classification as any} size="small" />
                    </Space>
                  ),
                },
                {
                  title: '状态',
                  width: 130,
                  render: (_: unknown, record: FieldRule) => (
                    <Space direction="vertical" size={4}>
                      <Tag color={record.operator === 'LOOKUP' || record.operator === 'DICT' ? 'blue' : record.operator === 'DIRECT' ? 'default' : 'green'} style={{ margin: 0 }}>
                        {OPERATOR_OPTIONS.find((item) => item.value === record.operator)?.label}
                      </Tag>
                      {(record.operator === 'DICT' || record.operator === 'LOOKUP') && (
                        <Button size="small" type="link" style={{ padding: 0 }} onClick={() => setSelectedRuleId(record.id)}>配置</Button>
                      )}
                      {record.operator === 'DICT' && record.dictionaryRef && (
                        <Text type="secondary" style={{ fontSize: 12 }}>{record.dictionaryVersion || '未标版本'}</Text>
                      )}
                    </Space>
                  ),
                },
              ]}
              onRow={(record) => ({ onClick: () => setSelectedRuleId(record.id) })}
              scroll={{ x: 700, y: 520 }}
            />
          </SectionCard>
        </Col>

        <Col xs={24} xl={6}>
          <SectionCard title="校验、运行与发布" icon={<CheckCircleOutlined />}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div>
                <Space size={6} wrap>
                  <Tag color={issueItems.some((item) => item.level === 'error') ? 'error' : issueItems.length > 0 ? 'warning' : 'success'}>
                    异常 {issueItems.length}
                  </Tag>
                  <Tag color={sparkContract?.dagsterJobAvailable ? 'success' : 'warning'}>
                    SPARK {sparkContract?.status || 'UNKNOWN'}
                  </Tag>
                  <Tag color={model?.status === 'PUBLISHED' ? 'success' : model?.status === 'VALIDATED' ? 'processing' : 'default'}>
                    {model?.status || 'DRAFT'}
                  </Tag>
                </Space>
                {issueItems.length > 0 && (
                  <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
                    {issueItems.slice(0, 4).map((item) => (
                      <Alert
                        key={item.text}
                        type={item.level}
                        showIcon
                        message={item.text}
                        style={{ padding: '5px 8px', fontSize: 12 }}
                      />
                    ))}
                  </div>
                )}
              </div>

              {validation ? (
                <Alert
                  type={validation.ok ? 'success' : 'error'}
                  showIcon
                  message={validation.ok ? '静态校验通过' : '静态校验失败'}
                  description={[
                    ...validation.errors,
                    ...validation.warnings,
                  ].join('；') || `输出 ${validation.outputColumns.length} 个字段`}
                />
              ) : (
                <Alert type="info" showIcon message="草稿尚未校验" />
              )}

              {compileResult ? (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Tag color="success" style={{ width: 'fit-content' }}>已生成 Spark 编译产物</Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>SQL：{compileResult.sqlPath}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>DAG：{compileResult.orchestrationDagId}</Text>
                </Space>
              ) : (
                <Text type="secondary" style={{ fontSize: 12 }}>保存并校验后可生成 Spark 编译产物，并由流水线执行。</Text>
              )}

              <Space size={8} wrap>
                <Button
                  type="primary"
                  icon={<RocketOutlined />}
                  loading={publishing}
                  disabled={!canPublish || issueItems.some((item) => item.level === 'error')}
                  onClick={publishCompiledModel}
                >
                  发布
                </Button>
              </Space>

              {advancedContracts.length > 0 && (
                <Space size={4} wrap>
                  {advancedContracts.map((contract) => (
                    <Tag
                      key={`${contract.compileTarget}-${contract.engine}`}
                      color={contract.graphExecutionSupported && contract.dagsterJobAvailable ? 'success' : 'default'}
                      style={{ margin: 0 }}
                    >
                      {contract.compileTarget}: {contract.status}
                    </Tag>
                  ))}
                </Space>
              )}

              <Divider style={{ margin: '4px 0' }} />
              <Paragraph
                code
                style={{
                  maxHeight: 280,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  fontSize: 12,
                  marginBottom: 0,
                }}
              >
                {compileResult?.compiledSql || validation?.compiledSql || 'select ...'}
              </Paragraph>
            </Space>
          </SectionCard>
        </Col>
      </Row>

      <Drawer
        title={selectedRule ? `${selectedRule.target} 字段处理配置` : '字段处理配置'}
        open={Boolean(selectedRule)}
        onClose={() => setSelectedRuleId(undefined)}
        width={460}
        mask={false}
        push={false}
        destroyOnClose={false}
      >
        {selectedRule && (
          <Space direction="vertical" size={14} style={{ width: '100%' }}>
            <Alert
              type={selectedRule.operator === 'DICT' || selectedRule.operator === 'LOOKUP' ? 'info' : 'success'}
              showIcon
              message={OPERATOR_OPTIONS.find((item) => item.value === selectedRule.operator)?.label}
              description={selectedSourceColumn ? `${selectedSourceColumn.name} · ${selectedSourceColumn.type}` : selectedRule.source}
            />

            {selectedRule.operator === 'FILLNA' && (
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>填充值</Text>
                <Input value={selectedRule.fillValue} onChange={(e) => updateRule(selectedRule.id, { fillValue: e.target.value })} style={{ marginTop: 6 }} />
              </div>
            )}

            {selectedRule.operator === 'DICT' && (
              <>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>字典集</Text>
                  <Select
                    allowClear
                    showSearch
                    placeholder="选择标准字典，或保留为空使用自定义映射"
                    value={selectedDictionaryPreset ? dictionaryPresetValue(selectedDictionaryPreset) : undefined}
                    options={dictionaryPresetOptions}
                    optionFilterProp="label"
                    onChange={(value) => applyDictionaryPreset(selectedRule.id, value)}
                    style={{ width: '100%', marginTop: 6 }}
                  />
                  {selectedRule.dictionaryRef && (
                    <Space size={6} wrap style={{ marginTop: 8 }}>
                      <Tag color="blue" style={{ margin: 0 }}>{selectedRule.dictionaryName || selectedRule.dictionaryRef}</Tag>
                      <Tag style={{ margin: 0 }}>版本 {selectedRule.dictionaryVersion || '未标记'}</Tag>
                      <Tag style={{ margin: 0 }}>{parseDictionary(selectedRule.dictionaryText).length} 项映射</Tag>
                    </Space>
                  )}
                  {selectedDictionaryPreset?.description && (
                    <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
                      {selectedDictionaryPreset.description}
                    </Text>
                  )}
                </div>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>映射内容</Text>
                  <Input.TextArea
                    value={selectedRule.dictionaryText}
                    onChange={(e) => updateRule(selectedRule.id, { dictionaryText: e.target.value })}
                    placeholder={'01=正常\n02=冻结'}
                    rows={8}
                    style={{ marginTop: 6 }}
                  />
                </div>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>未命中策略</Text>
                  <Select
                    value={selectedRule.noMatchPolicy || 'KEEP'}
                    options={[
                      { value: 'KEEP', label: '保留原值' },
                      { value: 'NULL', label: '置空' },
                      { value: 'FAIL', label: '门禁阻断' },
                    ]}
                    onChange={(value) => updateRule(selectedRule.id, { noMatchPolicy: value })}
                    style={{ width: '100%', marginTop: 6 }}
                  />
                </div>
              </>
            )}

            {selectedRule.operator === 'LOOKUP' && (
              <>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>关联表</Text>
                  <Select
                    showSearch
                    value={selectedRule.lookupFqn}
                    options={lookupAssetOptions}
                    optionFilterProp="label"
                    onChange={(value) => updateRule(selectedRule.id, { lookupFqn: value, lookupField: undefined, lookupRightKey: undefined })}
                    style={{ width: '100%', marginTop: 6 }}
                  />
                </div>
                <Row gutter={8}>
                  <Col span={12}>
                    <Text type="secondary" style={{ fontSize: 12 }}>源表关联键</Text>
                    <Select
                      value={selectedRule.lookupLeftKey || selectedRule.source}
                      options={sourceColumnOptions}
                      onChange={(value) => updateRule(selectedRule.id, { lookupLeftKey: value, source: value })}
                      style={{ width: '100%', marginTop: 6 }}
                    />
                  </Col>
                  <Col span={12}>
                    <Text type="secondary" style={{ fontSize: 12 }}>关联表键</Text>
                    <Select
                      showSearch
                      value={selectedRule.lookupRightKey}
                      options={lookupColumns}
                      optionFilterProp="label"
                      onChange={(value) => updateRule(selectedRule.id, { lookupRightKey: value })}
                      style={{ width: '100%', marginTop: 6 }}
                    />
                  </Col>
                </Row>
                <Row gutter={8}>
                  <Col span={12}>
                    <Text type="secondary" style={{ fontSize: 12 }}>补充字段</Text>
                    <Select
                      showSearch
                      value={selectedRule.lookupField}
                      options={lookupColumns}
                      optionFilterProp="label"
                      onChange={(value) => updateRule(selectedRule.id, { lookupField: value, target: normalizeIdentifier(value) })}
                      style={{ width: '100%', marginTop: 6 }}
                    />
                  </Col>
                  <Col span={12}>
                    <Text type="secondary" style={{ fontSize: 12 }}>Join alias</Text>
                    <Input
                      value={selectedRule.lookupAlias}
                      onChange={(e) => updateRule(selectedRule.id, { lookupAlias: normalizeIdentifier(e.target.value) })}
                      style={{ marginTop: 6 }}
                    />
                  </Col>
                </Row>
              </>
            )}

            <Divider style={{ margin: '4px 0' }} />
            <Text type="secondary" style={{ fontSize: 12 }}>SQL 表达式</Text>
            <Paragraph code style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
              {ruleExpression(selectedRule) || sourceExpr(selectedRule.source)}
            </Paragraph>
          </Space>
        )}
      </Drawer>
    </div>
  );
}
