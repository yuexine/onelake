/**
 * 建表 / 建模向导（对应原型 §8.3.6 升级版）。
 *   四步：① 选层与域 ② 字段定义 ③ 分区与格式 ④ 生命周期 + 校验
 *   - 实时命名规范校验（layer_domain_business_granularity）
 *   - 逆向依赖检测（ODS 不能依赖上层）
 *   - PII 自动识别 → 联动密级
 */
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Steps, Button, Space, Form, Input, Select, Radio, InputNumber,
  Typography, Tag, Alert, Table, Switch, Tooltip,
} from 'antd';
import {
  ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined,
  ApartmentOutlined, TableOutlined, SettingOutlined, FieldTimeOutlined,
  PlusOutlined, DeleteOutlined, WarningOutlined,
} from '@ant-design/icons';
import {
  PageHeader, SectionCard, ClassificationBadge, IntentBadge,
  useAsyncAction, DangerConfirm, layerColor,
} from '../../components';
import type { AssetDetail, Classification } from '../../types';
import { CatalogAPI, ModelingAPI } from '../../api';
import { normalizeCatalogAsset } from './assetAdapter';

const { Text } = Typography;

interface ColumnDef {
  key: string;
  name: string;
  type: string;
  pk: boolean;
  classification?: Classification;
  piiType?: string;
  suggestLevel?: Classification;
  sourceName?: string;
  sourceType?: string;
  comment: string;
}

const LAYERS = ['ODS', 'DWD', 'DWS', 'ADS'] as const;
const DOMAINS = ['交易', '用户', '风控', '营销', '商品'] as const;
const GRANULARITIES: Record<string, string> = {
  ODS: 'ods',
  DWD: '_df / _di / _dms',
  DWS: '_d / _m / _y',
  ADS: '_app',
};

// 命名规范：layer_domain_business_granularity
const NAME_PATTERN = /^(ods|dwd|dws|ads)_([a-z]+)_([a-z_]+)_(df|di|dms|d|w|m|y|app)$/;
const DOMAIN_CODE: Record<typeof DOMAINS[number], string> = {
  交易: 'trade',
  用户: 'user',
  风控: 'risk',
  营销: 'marketing',
  商品: 'product',
};

function inferDwdName(sourceFqn: string, domainValue: typeof DOMAINS[number]) {
  const sourceName = sourceFqn.split('.').pop() || 'detail';
  const business = sourceName
    .replace(/^(ods|dwd|dws|ads)_/, '')
    .replace(/_(df|di|dms|d|w|m|y|app)$/, '')
    .replace(/[^a-z0-9_]/gi, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toLowerCase() || 'detail';
  const domainCode = DOMAIN_CODE[domainValue] || 'trade';
  return `dwd_${domainCode}_${business}_df`;
}

function pickPartitionStrategy(columnNames: string[]) {
  const candidates = ['order_time', 'created_at', 'updated_at', 'event_time', 'dt'];
  const lower = new Set(columnNames.map((name) => name.toLowerCase()));
  const found = candidates.find((name) => lower.has(name));
  return found ? `days(${found})` : 'none';
}

export default function TableWizard() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const deriveMode = searchParams.get('derive') === 'dwd';
  const sourceAssetId = searchParams.get('sourceAssetId');
  const { run, isLoading } = useAsyncAction();
  const [step, setStep] = useState(0);
  const [publishOpen, setPublishOpen] = useState(false);
  const [sourceDetail, setSourceDetail] = useState<AssetDetail>();
  const [sourceLoading, setSourceLoading] = useState(false);
  const [sourceError, setSourceError] = useState<string | null>(null);

  // step 1 state
  const [layer, setLayer] = useState<typeof LAYERS[number]>('DWD');
  const [domain, setDomain] = useState<typeof DOMAINS[number]>('交易');
  const [name, setName] = useState('dwd_trade_order_df');

  // step 2 state
  const [columns, setColumns] = useState<ColumnDef[]>([
    { key: '1', name: 'order_id', type: 'BIGINT', pk: true, comment: '订单号' },
    { key: '2', name: 'phone', type: 'STRING', pk: false, classification: 'L3', comment: '手机号（自动识别 PII）' },
    { key: '3', name: 'amount', type: 'DECIMAL(18,2)', pk: false, comment: '订单金额' },
    { key: '4', name: 'created_at', type: 'TIMESTAMP', pk: false, comment: '创建时间' },
  ]);

  // step 3 state
  const [partitionStrategy, setPartitionStrategy] = useState('days(created_at)');
  const [format, setFormat] = useState<'ICEBERG' | 'PARQUET' | 'ORC'>('ICEBERG');
  const [compression, setCompression] = useState<'ZSTD' | 'SNAPPY' | 'GZIP'>('ZSTD');

  // step 4 state
  const [ttl, setTtl] = useState(365);
  const [coldStorageAfter, setColdStorageAfter] = useState(90);

  useEffect(() => {
    if (!deriveMode || !sourceAssetId) return;
    let cancelled = false;
    setSourceLoading(true);
    setSourceError(null);
    CatalogAPI.getAssetDetail(sourceAssetId)
      .then((item) => {
        if (cancelled) return;
        const normalized = { ...item, asset: normalizeCatalogAsset(item.asset) };
        setSourceDetail(normalized);
        if (normalized.asset.layer !== 'ODS') {
          setSourceError('DWD 草稿只能从 ODS 资产派生');
          return;
        }
        const nextDomain = DOMAINS.includes(normalized.asset.domain as typeof DOMAINS[number])
          ? normalized.asset.domain as typeof DOMAINS[number]
          : '交易';
        const nextColumns = normalized.asset.columns.map((column, index) => ({
          key: `${column.name}-${index}`,
          name: column.name,
          type: column.type || 'STRING',
          pk: ['id', 'order_id'].includes(column.name.toLowerCase()),
          classification: column.suggestLevel || column.classification,
          piiType: column.piiType,
          suggestLevel: column.suggestLevel,
          sourceName: column.name,
          sourceType: column.type || 'STRING',
          comment: column.description || `源字段 ${column.name}`,
        }));
        setLayer('DWD');
        setDomain(nextDomain);
        setName(inferDwdName(normalized.asset.fqn, nextDomain));
        if (nextColumns.length > 0) {
          setColumns(nextColumns);
          setPartitionStrategy(pickPartitionStrategy(nextColumns.map((column) => column.name)));
        }
        setFormat(normalized.asset.format || 'ICEBERG');
      })
      .catch((e) => {
        if (!cancelled) {
          setSourceError(e instanceof Error ? e.message : '源 ODS 资产加载失败');
        }
      })
      .finally(() => {
        if (!cancelled) setSourceLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [deriveMode, sourceAssetId]);

  // 校验
  const nameValidation = (() => {
    if (!name) return { ok: false, msg: '请输入表名' };
    if (!NAME_PATTERN.test(name)) {
      return {
        ok: false,
        msg: `命名规范：layer_domain_business_granularity，例 dwd_trade_order_df`,
      };
    }
    const [_, nameLayer] = name.match(NAME_PATTERN)!;
    if (nameLayer.toUpperCase() !== layer) {
      return {
        ok: false,
        msg: `表名前缀 ${nameLayer} 与所选分层 ${layer} 不一致`,
      };
    }
    return { ok: true, msg: '✓ 命名规范校验通过' };
  })();

  // 逆向依赖检测：ODS 不能依赖 ADS
  const reverseDepCheck = (() => {
    if (layer === 'ODS') {
      return {
        ok: false,
        msg: 'ODS 表禁止读取上层 (DWD/DWS/ADS) 数据，请调整分层或重构链路',
      };
    }
    return { ok: true, msg: '✓ 依赖关系合法' };
  })();

  const sourceAsset = sourceDetail?.asset;
  const actionKey = deriveMode ? 'create-dwd-draft' : 'create-table';
  const cancelPath = deriveMode && sourceAssetId ? `/lakehouse/tables/${sourceAssetId}` : '/lakehouse/tables';
  const deriveUnavailable = deriveMode && (!sourceAssetId || sourceLoading || !!sourceError);
  const partitionOptions = [
    { label: '按天分区 days(created_at)', value: 'days(created_at)' },
    { label: '按月分区 months(created_at)', value: 'months(created_at)' },
    { label: '按小时分区 hours(created_at)', value: 'hours(created_at)' },
    { label: '不分区（仅小表）', value: 'none' },
  ];
  if (partitionStrategy !== 'none' && !partitionOptions.some((item) => item.value === partitionStrategy)) {
    partitionOptions.unshift({ label: `按天分区 ${partitionStrategy}`, value: partitionStrategy });
  }

  const steps = [
    { title: '选层与域', icon: <ApartmentOutlined /> },
    { title: '字段定义', icon: <TableOutlined /> },
    { title: '分区与格式', icon: <SettingOutlined /> },
    { title: '生命周期', icon: <FieldTimeOutlined /> },
  ];

  const addColumn = () => {
    setColumns([...columns, {
      key: String(Date.now()),
      name: '',
      type: 'STRING',
      pk: false,
      comment: '',
    }]);
  };

  const removeColumn = (key: string) => {
    setColumns(columns.filter((c) => c.key !== key));
  };

  const updateColumn = (key: string, patch: Partial<ColumnDef>) => {
    setColumns(columns.map((c) => (c.key === key ? { ...c, ...patch } : c)));
  };

  // 模拟 PII 自动识别：当字段名匹配 phone/id_card/email 等时自动设 L3
  const autoPiiCheck = (col: ColumnDef): Classification | undefined => {
    if (/phone|mobile/i.test(col.name)) return 'L3';
    if (/id_card|idcard/i.test(col.name)) return 'L4';
    if (/email|mail/i.test(col.name)) return 'L3';
    if (/bank|card_no/i.test(col.name)) return 'L4';
    return col.classification;
  };

  return (
    <div className="ol-page">
      <div className="ol-section" style={{ padding: '14px 20px' }}>
        <Space size={12}>
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(cancelPath)} />
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ol-ink)' }}>
              {deriveMode ? 'ODS 派生 DWD 草稿' : '建表 / 建模向导'}
            </div>
            <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
              {deriveMode ? '四步配置：源表确认 → 字段映射 → 分区与格式 → 生命周期' : '四步配置：分层与域 → 字段 → 分区与格式 → 生命周期'}
            </div>
          </div>
        </Space>
      </div>

      <div className="ol-section" style={{ padding: '20px 24px' }}>
        <Steps
          current={step}
          size="default"
          labelPlacement="vertical"
          items={steps.map((s, i) => ({
            title: <span style={{ fontSize: 13, fontWeight: i === step ? 600 : 500, color: i === step ? 'var(--ol-brand)' : i < step ? 'var(--ol-ink)' : 'var(--ol-ink-3)' }}>{s.title}</span>,
            icon: <span style={{ fontSize: 16 }}>{s.icon}</span>,
          }))}
        />
      </div>

      <SectionCard
        title={<span style={{ fontSize: 14, fontWeight: 600 }}>{`第 ${step + 1} 步 · ${steps[step].title}`}</span>}
        style={{ minHeight: 320 }}
      >
        <div key={step} className="ol-anim-fade" style={{ maxWidth: 880 }}>

          {step === 0 && (
            <>
            {deriveMode && (
              <Alert
                type={sourceError ? 'error' : 'info'}
                showIcon
                style={{ marginBottom: 16 }}
                message={sourceError || (sourceLoading
                  ? '正在加载源 ODS 资产'
                  : `源 ODS：${sourceAsset?.fqn || sourceAssetId || '-'}`)}
              />
            )}
            <Form layout="vertical" requiredMark="optional">
              <Form.Item label="分层" required>
                <Radio.Group value={layer} disabled={deriveMode} onChange={(e) => setLayer(e.target.value)}>
                  <Space wrap>
                    {LAYERS.map((l) => (
                      <Radio.Button key={l} value={l} style={{
                        height: 'auto', padding: '8px 14px', borderRadius: 6,
                        background: layer === l ? 'var(--ol-brand-soft)' : 'var(--ol-card)',
                        borderColor: layer === l ? 'var(--ol-brand)' : 'var(--ol-line)',
                        fontWeight: layer === l ? 600 : 400,
                      }}>
                        <Space size={6}>
                          <IntentBadge intent={layerColor[l]}>{l}</IntentBadge>
                          <span style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>
                            {l === 'ODS' ? '贴源层' : l === 'DWD' ? '明细层' : l === 'DWS' ? '汇总层' : '应用层'}
                          </span>
                        </Space>
                      </Radio.Button>
                    ))}
                  </Space>
                </Radio.Group>
              </Form.Item>

              <Form.Item label="业务域" required>
                <Select value={domain} onChange={setDomain} style={{ width: 200 }}
                  options={DOMAINS.map((d) => ({ label: d + ' 域', value: d }))} />
              </Form.Item>

              <Form.Item label="表名" required
                validateStatus={nameValidation.ok ? 'success' : 'error'}
                help={<span style={{ color: nameValidation.ok ? 'var(--ol-success)' : 'var(--ol-error)', fontSize: 12 }}>{nameValidation.msg}</span>}
                extra={<Text type="secondary" style={{ fontSize: 12 }}>命名规范：layer_domain_business_granularity，例 {layer.toLowerCase()}_{domain === '交易' ? 'trade' : 'user'}_order_df</Text>}
              >
                <Input value={name} onChange={(e) => setName(e.target.value.toLowerCase())} style={{ maxWidth: 360 }} />
              </Form.Item>

              {!reverseDepCheck.ok && (
                <Alert
                  type="error" showIcon
                  icon={<WarningOutlined />}
                  message={<span style={{ fontSize: 13 }}>{reverseDepCheck.msg}</span>}
                />
              )}
            </Form>
            </>
          )}

          {step === 1 && (
            <>
              <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {deriveMode ? `源字段 ${sourceAsset?.fqn || '-'} → DWD 目标字段，共 ${columns.length} 列` : `共 ${columns.length} 列，自动识别 PII 字段会自动联动密级`}
                </Text>
                <Button type="primary" ghost size="small" icon={<PlusOutlined />} onClick={addColumn}>加字段</Button>
              </div>
              <Table
                size="middle"
                rowKey="key"
                dataSource={columns}
                pagination={false}
                columns={[
                  ...(deriveMode ? [{
                    title: '源字段',
                    dataIndex: 'sourceName',
                    width: 180,
                    render: (v: string, r: ColumnDef) => <Text code style={{ fontSize: 12 }}>{v || r.name}</Text>,
                  }] : []),
                  { title: '字段名', dataIndex: 'name', width: 200, render: (v: string, r: ColumnDef) => (
                    <Input value={v} size="small" placeholder="field_name"
                      onChange={(e) => {
                        const updated = { ...r, name: e.target.value };
                        updated.classification = autoPiiCheck(updated);
                        updateColumn(r.key, updated);
                      }}
                    />
                  ) },
                  { title: '类型', dataIndex: 'type', width: 180, render: (v: string, r: ColumnDef) => (
                    <Select value={v} size="small" style={{ width: '100%' }}
                      options={['BIGINT', 'STRING', 'INT', 'DECIMAL(18,2)', 'DOUBLE', 'TIMESTAMP', 'DATE', 'BOOLEAN'].map((t) => ({ label: t, value: t }))}
                      onChange={(val) => updateColumn(r.key, { type: val })}
                    />
                  ) },
                  { title: '主键', dataIndex: 'pk', width: 70, render: (v: boolean, r: ColumnDef) => (
                    <Switch size="small" checked={v} onChange={(checked) => updateColumn(r.key, { pk: checked })} />
                  ) },
                  { title: '密级', dataIndex: 'classification', width: 130, render: (v: Classification | undefined, r: ColumnDef) => (
                    <Select
                      value={v} size="small" allowClear placeholder="-"
                      style={{ width: '100%' }}
                      options={[
                        { label: 'L1 公开', value: 'L1' },
                        { label: 'L2 内部', value: 'L2' },
                        { label: 'L3 敏感', value: 'L3' },
                        { label: 'L4 机密', value: 'L4' },
                      ]}
                      onChange={(val) => updateColumn(r.key, { classification: val })}
                    />
                  ) },
                  { title: '描述', dataIndex: 'comment', render: (v: string, r: ColumnDef) => (
                    <Input value={v} size="small" placeholder="字段业务含义"
                      onChange={(e) => updateColumn(r.key, { comment: e.target.value })}
                    />
                  ) },
                  { title: '', width: 40, render: (_: unknown, r: ColumnDef) => (
                    <Tooltip title="删除字段">
                      <Button type="text" size="small" danger icon={<DeleteOutlined />}
                        disabled={columns.length <= 1}
                        onClick={() => removeColumn(r.key)} />
                    </Tooltip>
                  ) },
                ]}
              />
              {columns.some((c) => c.classification === 'L3' || c.classification === 'L4') && (
                <Alert
                  type="warning" showIcon
                  style={{ marginTop: 12 }}
                  message={
                    <span style={{ fontSize: 13 }}>
                      检测到敏感字段：
                      {columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').map((c) => (
                        <span key={c.key} style={{ marginLeft: 6 }}>
                          <ClassificationBadge level={c.classification} size="small" /> {c.name}
                        </span>
                      ))}
                      ；将自动联动目录徽章、采集脱敏、API 返回脱敏
                    </span>
                  }
                />
              )}
            </>
          )}

          {step === 2 && (
            <Form layout="vertical">
              <Form.Item label="分区策略" tooltip="隐藏分区可显著提升查询性能">
                <Select value={partitionStrategy} onChange={setPartitionStrategy} style={{ width: 280 }}
                  options={partitionOptions}
                />
              </Form.Item>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item label="表格式">
                  <Select value={format} onChange={(v: any) => setFormat(v)}
                    options={[
                      { label: 'Iceberg（推荐）— 支持 ACID + 时间旅行', value: 'ICEBERG' },
                      { label: 'Parquet', value: 'PARQUET' },
                      { label: 'ORC', value: 'ORC' },
                    ]}
                  />
                </Form.Item>
                <Form.Item label="压缩算法">
                  <Select value={compression} onChange={(v: any) => setCompression(v)}
                    options={[
                      { label: 'ZSTD（推荐）', value: 'ZSTD' },
                      { label: 'SNAPPY', value: 'SNAPPY' },
                      { label: 'GZIP', value: 'GZIP' },
                    ]}
                  />
                </Form.Item>
              </div>
            </Form>
          )}

          {step === 3 && (
            <Form layout="vertical">
              <Alert
                type="info" showIcon
                style={{ marginBottom: 16 }}
                message={<span style={{ fontSize: 13 }}>生命周期策略：热数据保留 N 天可改，超期下沉到冷存储（Glacier），到期归档/删除</span>}
              />
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item label="热存储 (Trino/S3 Standard) 保留天数">
                  <InputNumber value={coldStorageAfter} onChange={(v) => setColdStorageAfter(v ?? 0)} min={1} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item label="总生命周期 (TTL) 天数">
                  <InputNumber value={ttl} onChange={(v) => setTtl(v ?? 0)} min={coldStorageAfter} style={{ width: '100%' }} />
                </Form.Item>
              </div>
              <div className="ol-section" style={{ padding: 14, background: 'var(--ol-brand-soft)', border: '1px solid var(--ol-brand-border)' }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-brand)', marginBottom: 8 }}>建表预览汇总</div>
                <Space direction="vertical" size={4}>
                  <Text>表名：<Text code>{name}</Text> · <IntentBadge intent={layerColor[layer]}>{layer}</IntentBadge> · <span className="ol-chip">{domain} 域</span></Text>
                  <Text>字段：{columns.length} 列，主键 {columns.filter((c) => c.pk).map((c) => c.name).join(', ') || '无'}，敏感 {columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').length} 个</Text>
                  <Text>分区：{partitionStrategy === 'none' ? '无' : partitionStrategy}</Text>
                  <Text>格式：<span className="ol-chip">{format} + {compression}</span></Text>
                  <Text>生命周期：热 {coldStorageAfter} 天 → 冷 {ttl - coldStorageAfter} 天 → 归档</Text>
                </Space>
              </div>
            </Form>
          )}
        </div>

        <div
          style={{
            position: 'sticky', bottom: 0, marginTop: 20,
            padding: '12px 0 0', borderTop: '1px solid var(--ol-line-soft)',
            background: 'var(--ol-card)',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          }}
        >
          <Space>
            {step === 0 ? (
              <Button onClick={() => navigate(cancelPath)}>取消</Button>
            ) : (
              <Button onClick={() => setStep(step - 1)}><ArrowLeftOutlined /> 上一步</Button>
            )}
          </Space>
          <Space>
            {step < 3 && (
              <Button type="primary"
                disabled={(step === 0 && (!nameValidation.ok || deriveUnavailable))}
                onClick={() => setStep(step + 1)}
              >
                下一步 <ArrowRightOutlined />
              </Button>
            )}
            {step === 3 && (
              <Button type="primary" icon={<CheckOutlined />}
                loading={isLoading(actionKey)}
                disabled={!nameValidation.ok || !reverseDepCheck.ok || deriveUnavailable}
                onClick={() => setPublishOpen(true)}
              >
                {deriveMode ? '保存草稿' : '发布'}
              </Button>
            )}
          </Space>
        </div>
      </SectionCard>

      <DangerConfirm
        open={publishOpen}
        title={deriveMode ? `保存 DWD 草稿：${name}` : `发布建表：${name}`}
        description={deriveMode
          ? '保存后将在建模控制面生成 DWD 模型草稿，记录 ODS 源表、字段映射、分区与编排占位信息。'
          : '建表后将在湖仓注册新表，并自动创建对应 Iceberg 表与分区策略。表创建后字段变更需走 Schema 变更审批。'}
        impacts={[
          { label: '分层 / 域', value: `${layer} · ${domain} 域` },
          ...(deriveMode ? [{ label: '源 ODS', value: sourceAsset?.fqn || sourceAssetId || '-' }] : []),
          { label: '字段数', value: columns.length },
          { label: '敏感字段', value: columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').length },
          { label: '总存储', value: `${ttl} 天` },
        ]}
        impactLevel="MEDIUM"
        confirmName={name}
        okText={deriveMode ? '确认保存' : '确认建表'}
        okType="primary"
        onCancel={() => setPublishOpen(false)}
        onConfirm={() => run(actionKey, async () => {
          if (isLoading(actionKey)) return;
          if (deriveMode) {
            if (!sourceAsset) {
              throw new Error('源 ODS 资产尚未加载完成');
            }
            const created = await ModelingAPI.createDwdDraft({
              name,
              domain,
              sourceFqn: sourceAsset.fqn,
              targetFqn: `dwd.${name}`,
              materialization: 'TABLE',
              uniqueKey: columns.find((c) => c.pk)?.name,
              partitionExpr: partitionStrategy === 'none' ? undefined : partitionStrategy,
              columnMappings: columns.map((c) => ({
                source: c.sourceName || c.name,
                target: c.name,
                sourceType: c.sourceType || c.type,
                targetType: c.type,
                primaryKey: c.pk,
                classification: c.classification,
                piiType: c.piiType,
                suggestLevel: c.suggestLevel || c.classification,
              })),
            });
            setPublishOpen(false);
            navigate(`/lakehouse/tables/${sourceAsset.id}?dwdModelId=${created.id}`);
            return;
          }
          const created = await CatalogAPI.createTable({
            layer,
            domain,
            name,
            description: `${domain} 域 ${layer} 表`,
            columns: columns.map((c) => ({
              name: c.name,
              type: c.type,
              primaryKey: c.pk,
              classification: c.classification,
              comment: c.comment,
            })),
            partitionStrategy,
            format,
            compression,
            ttlDays: ttl,
            coldStorageAfterDays: coldStorageAfter,
          });
          setPublishOpen(false);
          navigate(`/lakehouse/tables/${created.id}`);
        }, {
          successMsg: deriveMode ? 'DWD 模型草稿已保存' : '建表成功，已注册到目录',
          errorMsg: (e) => e instanceof Error ? e.message : deriveMode ? 'DWD 草稿保存失败' : '建表失败，请检查 Trino/Iceberg 运行状态',
          duration: 3,
        })}
      />
    </div>
  );
}
