/**
 * 采集任务向导（4 步，对应原型 §4.2.3 / §8.2.3 升级版）。
 *   ① 选源与模式  ② 映射与转换  ③ 增量/CDC 参数  ④ 调度与限流
 *   - 顶部步骤条
 *   - 步骤切换淡入动画
 *   - sticky 底部操作栏
 */
import {
  Steps, Button, Space, Form, Select, Input, Table, Switch, InputNumber,
  Typography, Tag, message, Tooltip, Radio, Divider, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined, DatabaseOutlined,
  ApartmentOutlined, FieldTimeOutlined, ScheduleOutlined, PlayCircleOutlined,
  HourglassOutlined, CloudSyncOutlined, FileTextOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ClassificationBadge, SectionCard, EntityTypeIcon,
  StateView,
} from '../../components';
import type { DataSource, FieldMapping } from '../../types';
import { IntegrationAPI } from '../../api';
import type { DiscoveredColumn } from '../../api';

const { Text } = Typography;

const MODES = [
  { value: 'FULL',        label: '全量抽取',  desc: '一次性整表抽取，适合小表与初始化', icon: <DatabaseOutlined /> },
  { value: 'INCREMENTAL', label: '增量水位',  desc: '按水位列增量抽取，自动回溯 5 分钟', icon: <HourglassOutlined /> },
  { value: 'CDC',         label: '实时 CDC',   desc: 'Binlog 订阅 + 初始快照 + 位点续传', icon: <CloudSyncOutlined /> },
  { value: 'FILE',        label: '文件采集',   desc: 'SFTP/S3 监听，分片上传 + MD5',     icon: <FileTextOutlined /> },
];

export default function SyncTaskWizard() {
  const navigate = useNavigate();
  const [sp] = useSearchParams();
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState('INCREMENTAL');
  const [tables, setTables] = useState<string[]>([]);
  const [mapping, setMapping] = useState<FieldMapping[]>([]);
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [schema, setSchema] = useState<string>();
  const [tableOptions, setTableOptions] = useState<string[]>([]);
  const [scheduleCron, setScheduleCron] = useState('0 2 * * *');
  const [rateLimit, setRateLimit] = useState<number | null>(2500);
  const [saving, setSaving] = useState(false);
  const [dryRunning, setDryRunning] = useState(false);
  const [dataSourceLoading, setDataSourceLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [columnLoading, setColumnLoading] = useState(false);
  const [dataSourceError, setDataSourceError] = useState<string | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [tableError, setTableError] = useState<string | null>(null);
  const [columnError, setColumnError] = useState<string | null>(null);

  const [sourceId, setSourceId] = useState(sp.get('sourceId') || '');
  const source = dataSources.find((d) => d.id === sourceId);
  const selectedTable = tables[0] || '';
  const targetTable = selectedTable ? `ods.${selectedTable.split('.').pop()}` : 'ods.untitled';
  const discoveryLoading = schemaLoading || tableLoading || columnLoading;
  const canChooseTable = Boolean(sourceId) && !schemaLoading && !schemaError;
  const canSubmit = Boolean(sourceId && selectedTable && mapping.length > 0) &&
    !dataSourceLoading && !discoveryLoading && !dataSourceError && !schemaError && !tableError && !columnError;

  const loadDataSources = () => {
    setDataSourceLoading(true);
    setDataSourceError(null);
    IntegrationAPI.listDatasources()
      .then((rows) => {
        setDataSources(rows);
        const requestedSourceId = sp.get('sourceId');
        if (requestedSourceId && rows.some((d) => d.id === requestedSourceId)) {
          setSourceId(requestedSourceId);
        } else if (!rows.some((d) => d.id === sourceId)) {
          setSourceId(rows[0]?.id || '');
        }
      })
      .catch((e) => {
        const errorMessage = friendlyDiscoveryError(e.message || '连接列表加载失败');
        setDataSources([]);
        setSourceId('');
        setDataSourceError(errorMessage);
        message.error(errorMessage);
      })
      .finally(() => setDataSourceLoading(false));
  };

  const loadTables = (currentSourceId: string, currentSchema?: string) => {
    setTableLoading(true);
    setTableError(null);
    setTableOptions([]);
    setTables([]);
    setMapping([]);
    setColumnError(null);
    IntegrationAPI.listDatasourceTables(currentSourceId, currentSchema)
      .then((rows) => {
        setTableOptions(rows);
        setTables(rows[0] ? [rows[0]] : []);
      })
      .catch((e) => {
        const errorMessage = friendlyDiscoveryError(e.message || '来源表加载失败');
        setTableError(errorMessage);
        message.error(errorMessage);
      })
      .finally(() => setTableLoading(false));
  };

  const loadColumns = (currentSourceId: string, objectName: string) => {
    setColumnLoading(true);
    setColumnError(null);
    setMapping([]);
    IntegrationAPI.describeDatasourceTable(currentSourceId, objectName)
      .then((columns) => {
        setMapping(columnsToMapping(columns));
        if (columns.length === 0) {
          const errorMessage = '未发现字段，请检查源表权限或选择其他表';
          setColumnError(errorMessage);
          message.warning(errorMessage);
        }
      })
      .catch((e) => {
        const errorMessage = friendlyDiscoveryError(e.message || '字段探查失败');
        setColumnError(errorMessage);
        message.error(errorMessage);
      })
      .finally(() => setColumnLoading(false));
  };

  const loadSchemas = (currentSourceId: string) => {
    setSchemaLoading(true);
    setSchemaError(null);
    setSchemas([]);
    setSchema(undefined);
    setTableOptions([]);
    setTables([]);
    setMapping([]);
    setTableError(null);
    setColumnError(null);
    IntegrationAPI.listDatasourceSchemas(currentSourceId)
      .then((rows) => {
        setSchemas(rows);
        const nextSchema = rows[0];
        setSchema(nextSchema);
        if (!nextSchema) {
          loadTables(currentSourceId);
        }
      })
      .catch((e) => {
        const errorMessage = friendlyDiscoveryError(e.message || 'Schema 探查失败');
        setSchemaError(errorMessage);
        message.error(errorMessage);
      })
      .finally(() => setSchemaLoading(false));
  };

  useEffect(() => {
    loadDataSources();
  }, []);

  useEffect(() => {
    if (!sourceId) return;
    loadSchemas(sourceId);
  }, [sourceId]);

  useEffect(() => {
    if (!sourceId || !schema) return;
    loadTables(sourceId, schema);
  }, [sourceId, schema]);

  useEffect(() => {
    if (!sourceId || !selectedTable) return;
    loadColumns(sourceId, selectedTable);
  }, [sourceId, selectedTable]);

  const buildTaskBody = () => ({
    sourceId,
    name: `${targetTable.split('.').pop()}_${mode.toLowerCase()}`,
    mode,
    sourceTable: selectedTable,
    targetTable,
    fieldMapping: mapping,
    scheduleCron: mode === 'CDC' ? '' : scheduleCron,
    rateLimit: rateLimit || undefined,
    dirtyThreshold: 0,
  });

  const saveTask = (publish: boolean) => {
    if (!sourceId || !selectedTable) {
      message.warning('请选择源连接和来源表');
      return;
    }
    if (mapping.length === 0) {
      message.warning('请先完成真实字段探查');
      return;
    }
    setSaving(true);
    IntegrationAPI.createSyncTask({
      ...buildTaskBody(),
    })
      .then((created) => publish ? IntegrationAPI.enableSyncTask(created.id) : created)
      .then((task) => {
        message.success(publish ? '采集任务已发布' : '草稿已保存');
        navigate('/integration/sync-tasks');
      })
      .catch((e) => message.error(e.message || (publish ? '采集任务发布失败' : '草稿保存失败')))
      .finally(() => setSaving(false));
  };

  const dryRunTask = () => {
    if (!canSubmit) {
      message.warning('请先完成源连接、来源表和字段映射配置');
      return;
    }
    setDryRunning(true);
    IntegrationAPI.dryRunSyncTaskDraft(buildTaskBody())
      .then((report) => {
        if (report.ready) {
          message.success('试跑检查通过，可以发布任务');
          return;
        }
        const failed = report.checks.filter((item) => !item.passed);
        message.warning(failed.map((item) => `${item.label}: ${item.message}`).join('；') || '试跑检查未通过');
      })
      .catch((e) => message.error(e.message || '试跑检查失败'))
      .finally(() => setDryRunning(false));
  };

  const steps = [
    { title: '选源与模式',   icon: <DatabaseOutlined /> },
    { title: '映射与转换',   icon: <ApartmentOutlined /> },
    { title: '增量 / CDC',   icon: <FieldTimeOutlined /> },
    { title: '调度与限流',   icon: <ScheduleOutlined /> },
  ];

  return (
    <div className="ol-page">
      {/* 顶部信息 */}
      <div className="ol-section" style={{ padding: '14px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Space size={12}>
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/integration/sync-tasks')} />
            <div>
              <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ol-ink)' }}>新建采集任务</div>
              <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>四步配置：源与模式 → 映射 → 增量 → 调度</div>
            </div>
          </Space>
          {source && <Space size={8}>
            <EntityTypeIcon kind={source.type} size={28} />
            <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
              源：<span style={{ color: 'var(--ol-ink)', fontWeight: 500 }}>{source.name}</span> · {source.host}
            </div>
          </Space>}
        </div>
      </div>

      {/* 步骤条 */}
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

      {/* 步骤内容 */}
      <SectionCard
        title={<span style={{ fontSize: 14, fontWeight: 600 }}>{`第 ${step + 1} 步 · ${steps[step].title}`}</span>}
        style={{ minHeight: 320 }}
      >
        <div key={step} className="ol-anim-fade" style={{ maxWidth: 820 }}>
          {step === 0 && (
            <Form layout="vertical" requiredMark="optional">
              <Form.Item label="源连接" required>
                <Select
                  value={sourceId || undefined}
                  onChange={setSourceId}
                  loading={dataSourceLoading}
                  disabled={dataSourceLoading || dataSources.length === 0}
                  placeholder="选择已创建的数据源连接"
                  options={dataSources.map((d) => ({
                  label: <Space size={8}><EntityTypeIcon kind={d.type} size={20} /> {d.name} <Text type="secondary" style={{ fontSize: 11 }}>{d.host}</Text></Space>,
                  value: d.id,
                }))}
                />
              </Form.Item>

              <Form.Item label="采集模式" required>
                <Radio.Group
                  value={mode} onChange={(e) => setMode(e.target.value)}
                  style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10, width: '100%' }}
                >
                  {MODES.map((m) => (
                    <Radio.Button
                      key={m.value} value={m.value}
                      style={{
                        height: 'auto', padding: '10px 12px', borderRadius: 8,
                        display: 'flex', alignItems: 'flex-start', gap: 10,
                        background: mode === m.value ? 'var(--ol-brand-soft)' : 'var(--ol-card)',
                        borderColor: mode === m.value ? 'var(--ol-brand)' : 'var(--ol-line)',
                      }}
                    >
                      <Space align="start" size={8}>
                        <span style={{ fontSize: 16, color: mode === m.value ? 'var(--ol-brand)' : 'var(--ol-ink-3)', marginTop: 2 }}>{m.icon}</span>
                        <div>
                          <div style={{ fontSize: 13, fontWeight: 600, color: mode === m.value ? 'var(--ol-brand)' : 'var(--ol-ink)' }}>{m.label}</div>
                          <div style={{ fontSize: 11, color: 'var(--ol-ink-3)', marginTop: 2 }}>{m.desc}</div>
                        </div>
                      </Space>
                    </Radio.Button>
                  ))}
                </Radio.Group>
              </Form.Item>

              {schemas.length > 0 && (
                <Form.Item label="Schema">
                  <Select
                    value={schema}
                    onChange={setSchema}
                    loading={schemaLoading}
                    options={schemas.map((s) => ({ label: s, value: s }))}
                  />
                </Form.Item>
              )}

              <Form.Item label="选择来源表" required tooltip="当前任务绑定一张来源表；批量建任务将在模板阶段接入">
                <Select
                  showSearch
                  value={selectedTable || undefined}
                  onChange={(value) => setTables(value ? [value] : [])}
                  loading={tableLoading}
                  disabled={!canChooseTable || tableLoading || tableOptions.length === 0}
                  placeholder={tableOptions.length === 0 ? '暂无可选来源表' : '选择来源表'}
                  options={tableOptions.map((t) => ({ label: t, value: t }))}
                />
              </Form.Item>
            </Form>
          )}

          {step === 1 && (
            columnLoading ? (
              <StateView state="loading" rows={6} />
            ) : mapping.length === 0 ? (
              <StateView
                state="empty"
                title="尚未生成字段映射"
                description="请选择真实来源表，系统会根据源端字段自动生成默认映射"
              />
            ) : (
              <Table size="middle" rowKey="source" dataSource={mapping} pagination={false}
                columns={[
                  { title: '源字段', dataIndex: 'source', render: (v: string, r: FieldMapping) => (
                    <Space size={6}><Text strong>{v}</Text>{r.classification && <ClassificationBadge level={r.classification} size="small" />}</Space>
                  ) },
                  { title: '源类型', dataIndex: 'sourceType', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
                  { title: '', render: () => <ArrowRightOutlined style={{ color: 'var(--ol-ink-4)' }} /> },
                  { title: '目标字段', dataIndex: 'target' },
                  { title: '目标类型', dataIndex: 'targetType', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
                  { title: '兼容性', dataIndex: 'compatible', render: (c: boolean) => c
                    ? <Tag color="success" style={{ margin: 0 }}>✓ 兼容</Tag>
                    : <Tooltip title="源端类型需要人工确认目标湖仓类型"><Tag color="warning" style={{ margin: 0 }}>⚠ 转换</Tag></Tooltip>
                  },
                  { title: '采集前脱敏', dataIndex: 'masked', render: (m?: boolean) => <Switch size="small" defaultChecked={m} /> },
                ]}
              />
            )
          )}

          {step === 2 && (
            <Form layout="vertical">
              {mode === 'INCREMENTAL' && (
                <>
                  <Form.Item label="水位列" required>
                    <Select options={mapping.map((f) => ({ label: f.source, value: f.source }))} defaultValue={mapping[0]?.source || 'id'} />
                  </Form.Item>
                  <Form.Item label="回溯 N 分钟（容错）" tooltip="防止水位回拨造成数据缺失">
                    <Space><InputNumber defaultValue={5} min={0} /> 分钟</Space>
                  </Form.Item>
                  <Alert type="info" showIcon
                    message="增量原理：每次抽取 max(水位列) > 上次记录位点的记录，并回溯 N 分钟内的记录以防乱序。"
                  />
                </>
              )}
              {mode === 'CDC' && (
                <>
                  <Alert type="success" showIcon
                    style={{ marginBottom: 16 }}
                    message={<><strong>初始快照 + 增量衔接</strong></>}
                    description="先锁定起始位点 (GTID/LSN)，全量快照期间增量进 Kafka 暂存，快照完成后从记录位点回放增量无缝衔接，保证不丢不重。"
                  />
                  <Space size={16} wrap>
                    <Tag color="processing" style={{ padding: '4px 10px' }}>位点持久化 · Flink checkpoint</Tag>
                    <Tag color="success" style={{ padding: '4px 10px' }}>Exactly-Once · 两阶段提交</Tag>
                    <Tag color="default" style={{ padding: '4px 10px' }}>背压保护</Tag>
                  </Space>
                </>
              )}
              {mode === 'FILE' && (
                <>
                  <Form.Item label="来源类型"><Select options={['SFTP', 'FTP', 'NAS', 'S3'].map((v) => ({ label: v, value: v }))} defaultValue="S3" /></Form.Item>
                  <Form.Item label="监听目录"><Input placeholder="/data/inbound/orders/" /></Form.Item>
                  <Form.Item label="监听方式"><Select options={[
                    { label: '事件通知（推荐）', value: 'event' },
                    { label: '定时轮询', value: 'poll' },
                  ]} defaultValue="event" /></Form.Item>
                  <Form.Item label="分片大小"><Select options={['64MB', '128MB'].map((v) => ({ label: v, value: v }))} defaultValue="64MB" /></Form.Item>
                  <Space direction="vertical" size={8}>
                    <Space><Switch defaultChecked /> MD5/SHA-256 校验</Space>
                    <Space><Switch defaultChecked /> 按内容哈希去重</Space>
                  </Space>
                </>
              )}
              {mode === 'FULL' && (
                <Alert type="info" showIcon message="全量抽取不涉及增量参数，请直接进入下一步。" />
              )}
            </Form>
          )}

          {step === 3 && (
            <>
              <Form layout="vertical">
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                  <Form.Item label="调度 Cron"><Input value={scheduleCron} onChange={(e) => setScheduleCron(e.target.value)} placeholder="0 2 * * *" /></Form.Item>
                  <Form.Item label="错峰时间窗"><Input placeholder="02:00-06:00" /></Form.Item>
                </div>
                <Form.Item label="依赖上游任务触发"><Switch defaultChecked /></Form.Item>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                  <Form.Item label="失败重试次数"><InputNumber defaultValue={3} min={0} style={{ width: '100%' }} /></Form.Item>
                  <Form.Item label="抽取限流 (rows/s)"><InputNumber value={rateLimit} onChange={setRateLimit} style={{ width: '100%' }} /></Form.Item>
                </div>
              </Form>

              <Divider style={{ margin: '16px 0' }} />
              <div
                style={{
                  padding: 16, borderRadius: 8,
                  background: 'var(--ol-brand-soft)',
                  border: '1px solid var(--ol-brand-border)',
                }}
              >
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-brand)', marginBottom: 8 }}>
                  预览汇总
                </div>
                <Space direction="vertical" size={4}>
                  <Text>源：<Text code>{source?.name || '-'}</Text> · <span className="ol-chip">{source?.type || '-'}</span></Text>
                  <Text>模式：<span className="ol-chip">{mode}</span> · 表数 {tables.length}</Text>
                  <Text style={{ fontSize: 12 }}>{tables.join(', ')}</Text>
                  <Text>目标：<Text code>{targetTable}</Text></Text>
                  <Text>限流：<Text code>{rateLimit || '-'} rows/s</Text> · 重试 3 次 · 错峰 02:00-06:00</Text>
                </Space>
              </div>
            </>
          )}
        </div>

        {/* Sticky 底部操作 */}
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
              <Button onClick={() => navigate('/integration/sync-tasks')}>取消</Button>
            ) : (
              <Button onClick={() => setStep(step - 1)}><ArrowLeftOutlined /> 上一步</Button>
            )}
            <Button loading={saving} disabled={!canSubmit} onClick={() => saveTask(false)}>保存草稿</Button>
          </Space>
          <Space>
            {step < 3 && (
              <Button
                type="primary"
                disabled={(step === 0 && (!selectedTable || columnLoading || mapping.length === 0 || Boolean(columnError))) || (step === 1 && mapping.length === 0)}
                onClick={() => setStep(step + 1)}
              >
                下一步 <ArrowRightOutlined />
              </Button>
            )}
            {step === 3 && (
              <>
                <Button icon={<PlayCircleOutlined />} loading={dryRunning} disabled={!canSubmit} onClick={dryRunTask}>试跑</Button>
                <Button type="primary" loading={saving} disabled={!canSubmit} icon={<CheckOutlined />} onClick={() => saveTask(true)}>发布</Button>
              </>
            )}
          </Space>
        </div>
      </SectionCard>
    </div>
  );
}

function columnsToMapping(columns: DiscoveredColumn[]): FieldMapping[] {
  return columns.map((column) => ({
    source: column.name,
    sourceType: column.type,
    target: column.name,
    targetType: toLakeType(column.type),
    compatible: true,
  }));
}

function toLakeType(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('INT')) return normalized.includes('BIG') ? 'BIGINT' : 'INT';
  if (normalized.includes('DECIMAL') || normalized.includes('NUMERIC')) return 'DECIMAL';
  if (normalized.includes('TIME') || normalized.includes('DATE')) return 'TIMESTAMP';
  if (normalized.includes('BOOL')) return 'BOOLEAN';
  return 'STRING';
}

function friendlyDiscoveryError(messageText: string) {
  if (/status code 500/i.test(messageText)) {
    return '后端探查服务返回异常，请检查连接配置、账号权限或稍后重新探查。';
  }
  if (/timeout|timed out/i.test(messageText)) {
    return '探查请求超时，请确认源端网络可达后重新探查。';
  }
  return messageText;
}
