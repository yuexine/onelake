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
  InboxOutlined, HourglassOutlined, CloudSyncOutlined, FileTextOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ClassificationBadge, SectionCard, EntityTypeIcon,
} from '../../components';
import { dataSources as mockDataSources } from '../../mock';
import type { DataSource, FieldMapping } from '../../types';
import { IntegrationAPI } from '../../api';

const { Text } = Typography;

const MODES = [
  { value: 'FULL',        label: '全量抽取',  desc: '一次性整表抽取，适合小表与初始化', icon: <DatabaseOutlined /> },
  { value: 'INCREMENTAL', label: '增量水位',  desc: '按水位列增量抽取，自动回溯 5 分钟', icon: <HourglassOutlined /> },
  { value: 'CDC',         label: '实时 CDC',   desc: 'Binlog 订阅 + 初始快照 + 位点续传', icon: <CloudSyncOutlined /> },
  { value: 'FILE',        label: '文件采集',   desc: 'SFTP/S3 监听，分片上传 + MD5',     icon: <FileTextOutlined /> },
];

const sampleMapping: FieldMapping[] = [
  { source: 'order_id', sourceType: 'BIGINT', target: 'order_id', targetType: 'BIGINT', compatible: true },
  { source: 'phone', sourceType: 'VARCHAR', target: 'phone', targetType: 'STRING', classification: 'L3', masked: true, compatible: true },
  { source: 'amount', sourceType: 'NUMBER(10,2)', target: 'amount', targetType: 'DECIMAL(18,2)', compatible: true },
  { source: 'status', sourceType: 'VARCHAR', target: 'status', targetType: 'STRING', compatible: true },
  { source: 'order_time', sourceType: 'DATETIME', target: 'order_time', targetType: 'TIMESTAMP', compatible: true },
  { source: 'memo', sourceType: 'TEXT', target: 'memo', targetType: 'STRING', compatible: false },
];

export default function SyncTaskWizard() {
  const navigate = useNavigate();
  const [sp] = useSearchParams();
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState('INCREMENTAL');
  const [tables, setTables] = useState<string[]>(['ods.orders']);
  const [mapping] = useState<FieldMapping[]>(sampleMapping);
  const [dataSources, setDataSources] = useState<DataSource[]>(mockDataSources);

  const [sourceId, setSourceId] = useState(sp.get('sourceId') || mockDataSources[0].id);
  const source = dataSources.find((d) => d.id === sourceId) || dataSources[0] || mockDataSources[0];

  useEffect(() => {
    IntegrationAPI.listDatasources()
      .then((rows) => {
        setDataSources(rows);
        if (!rows.some((d) => d.id === sourceId) && rows[0]) {
          setSourceId(rows[0].id);
        }
      })
      .catch((e) => message.error(e.message || '连接列表加载失败'));
  }, []);

  const publishTask = () => {
    const targetTable = tables[0] || 'ods.untitled';
    IntegrationAPI.createSyncTask({
      sourceId,
      name: `${targetTable.split('.').pop()}_${mode.toLowerCase()}`,
      mode,
      targetTable,
      fieldMapping: mapping,
      scheduleCron: mode === 'CDC' ? '' : '0 2 * * *',
      rateLimit: 2500,
      dirtyThreshold: 0,
    })
      .then((created) => IntegrationAPI.enableSyncTask(created.id))
      .then(() => {
        message.success('采集任务已创建');
        navigate('/integration/sync-tasks');
      })
      .catch((e) => message.error(e.message || '采集任务创建失败'));
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
          <Space size={8}>
            <EntityTypeIcon kind={source.type} size={28} />
            <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>
              源：<span style={{ color: 'var(--ol-ink)', fontWeight: 500 }}>{source.name}</span> · {source.host}
            </div>
          </Space>
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
                <Select value={source.id} onChange={setSourceId} options={dataSources.map((d) => ({
                  label: <Space size={8}><EntityTypeIcon kind={d.type} size={20} /> {d.name} <Text type="secondary" style={{ fontSize: 11 }}>{d.host}</Text></Space>,
                  value: d.id,
                }))} />
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

              <Form.Item label="选择来源表" tooltip="支持多选树 + 搜索">
                <Select
                  mode="multiple" value={tables} onChange={setTables}
                  options={['ods.orders', 'ods.order_items', 'ods.users', 'ods.payments'].map((t) => ({ label: t, value: t }))}
                  tagRender={(props) => (
                    <Tag closable onClose={props.onClose} style={{ margin: 2, fontSize: 11 }}>
                      {props.label}
                    </Tag>
                  )}
                />
              </Form.Item>
            </Form>
          )}

          {step === 1 && (
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
                  : <Tooltip title="TEXT → STRING 可能丢失多字节字符"><Tag color="warning" style={{ margin: 0 }}>⚠ 转换</Tag></Tooltip>
                },
                { title: '采集前脱敏', dataIndex: 'masked', render: (m?: boolean) => <Switch size="small" defaultChecked={m} /> },
              ]}
            />
          )}

          {step === 2 && (
            <Form layout="vertical">
              {mode === 'INCREMENTAL' && (
                <>
                  <Form.Item label="水位列" required>
                    <Select options={['updated_at', 'create_time', 'id'].map((v) => ({ label: v, value: v }))} defaultValue="updated_at" />
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
                  <Form.Item label="调度 Cron"><Input placeholder="0 2 * * *" /></Form.Item>
                  <Form.Item label="错峰时间窗"><Input placeholder="02:00-06:00" /></Form.Item>
                </div>
                <Form.Item label="依赖上游任务触发"><Switch defaultChecked /></Form.Item>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                  <Form.Item label="失败重试次数"><InputNumber defaultValue={3} min={0} style={{ width: '100%' }} /></Form.Item>
                  <Form.Item label="抽取限流 (rows/s)"><InputNumber defaultValue={2500} style={{ width: '100%' }} /></Form.Item>
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
                  <Text>源：<Text code>{source.name}</Text> · <span className="ol-chip">{source.type}</span></Text>
                  <Text>模式：<span className="ol-chip">{mode}</span> · 表数 {tables.length}</Text>
                  <Text style={{ fontSize: 12 }}>{tables.join(', ')}</Text>
                  <Text>限流：<Text code>2500 rows/s</Text> · 重试 3 次 · 错峰 02:00-06:00</Text>
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
            <Button onClick={() => message.success('已保存草稿')}>保存草稿</Button>
          </Space>
          <Space>
            {step < 3 && (
              <Button type="primary" onClick={() => setStep(step + 1)}>
                下一步 <ArrowRightOutlined />
              </Button>
            )}
            {step === 3 && (
              <>
                <Button icon={<PlayCircleOutlined />} onClick={() => message.success('已试跑，预览采样 100 行')}>试跑</Button>
                <Button type="primary" icon={<CheckOutlined />} onClick={publishTask}>发布</Button>
              </>
            )}
          </Space>
        </div>
      </SectionCard>
    </div>
  );
}
