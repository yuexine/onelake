/**
 * 采集任务向导（4 步，对应原型 §4.2.3 / §8.2.3）。
 * ① 选源与模式  ② 映射与转换  ③ 增量/CDC 参数  ④ 调度与限流
 */
import { Card, Steps, Button, Space, Form, Select, Input, Table, Switch, InputNumber, Typography, Tag, message } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ClassificationBadge } from '../../components';
import { dataSources } from '../../mock';
import type { FieldMapping } from '../../types';

const { Text } = Typography;

const MODES = [
  { value: 'FULL', label: '全量抽取' },
  { value: 'INCREMENTAL', label: '增量（水位线）' },
  { value: 'CDC', label: '实时 CDC' },
  { value: 'FILE', label: '文件采集' },
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

  const sourceId = sp.get('sourceId') || dataSources[0].id;
  const source = dataSources.find((d) => d.id === sourceId)!;

  const steps = ['选源与模式', '映射与转换', '增量 / CDC', '调度与限流'];

  return (
    <Card title={
      <Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/integration/sync-tasks')} />新建采集任务</Space>
    }>
      <Steps current={step} items={steps.map((s) => ({ title: s }))} style={{ marginBottom: 24 }} />

      {step === 0 && (
        <Form layout="vertical">
          <Form.Item label="源连接" required>
            <Select defaultValue={source.id} options={dataSources.map((d) => ({ label: `${d.name} (${d.type} ${d.host})`, value: d.id }))} />
          </Form.Item>
          <Form.Item label="采集模式" required>
            <Select value={mode} onChange={setMode} options={MODES} />
          </Form.Item>
          <Form.Item label="选择来源表（多选树 + 搜索）">
            <Select mode="multiple" value={tables} onChange={setTables}
              options={['ods.orders', 'ods.order_items', 'ods.users', 'ods.payments'].map((t) => ({ label: t, value: t }))} />
          </Form.Item>
        </Form>
      )}

      {step === 1 && (
        <Table size="small" rowKey="source" dataSource={mapping} pagination={false}
          columns={[
            { title: '源字段', dataIndex: 'source', render: (v: string, r: FieldMapping) => <Space><Text strong>{v}</Text>{r.classification && <ClassificationBadge level={r.classification} />}</Space> },
            { title: '源类型', dataIndex: 'sourceType' },
            { title: '', render: () => <ArrowRightOutlined /> },
            { title: '目标字段', dataIndex: 'target' },
            { title: '目标类型', dataIndex: 'targetType' },
            { title: '兼容性', dataIndex: 'compatible', render: (c: boolean) => c ? <Tag color="success">✓</Tag> : <Tag color="warning">⚠ 类型转换建议</Tag> },
            { title: '采集前脱敏', dataIndex: 'masked', render: (m?: boolean, _r?: any) => <Switch size="small" defaultChecked={m} /> },
          ]} />
      )}

      {step === 2 && (
        <Form layout="vertical">
          {mode === 'INCREMENTAL' && (
            <>
              <Form.Item label="水位列" required><Select options={['updated_at', 'create_time', 'id'].map((v: any) => ({ label: v, value: v }))} defaultValue="updated_at" /></Form.Item>
              <Form.Item label="回溯 N 分钟（容错）"><InputNumber defaultValue={5} min={0} /> 分钟</Form.Item>
            </>
          )}
          {mode === 'CDC' && (
            <>
              <Form.Item label="初始快照 + 增量衔接">
                <Tag color="processing">已启用</Tag>
                <Text type="secondary">先锁定起始位点 (GTID/LSN)，全量快照期间增量进 Kafka 暂存，快照完成后从记录位点回放增量无缝衔接，保证不丢不重。</Text>
              </Form.Item>
              <Form.Item label="位点持久化"><Tag color="success">随 Flink checkpoint</Tag></Form.Item>
              <Form.Item label="Exactly-Once"><Tag color="success">两阶段提交 ✓</Tag></Form.Item>
            </>
          )}
          {mode === 'FILE' && (
            <>
              <Form.Item label="来源类型"><Select options={['SFTP', 'FTP', 'NAS', 'S3'].map((v: any) => ({ label: v, value: v }))} defaultValue="S3" /></Form.Item>
              <Form.Item label="监听目录"><Input placeholder="/data/inbound/orders/" /></Form.Item>
              <Form.Item label="监听方式"><Select options={[{ label: '事件通知（推荐）', value: 'event' }, { label: '定时轮询', value: 'poll' }].map((v: any) => ({ label: v, value: v }))} defaultValue="event" /></Form.Item>
              <Form.Item label="分片大小"><Select options={['64MB', '128MB'].map((v: any) => ({ label: v, value: v }))} defaultValue="64MB" /></Form.Item>
              <Space><Switch defaultChecked /> MD5/SHA-256 校验</Space><br />
              <Space><Switch defaultChecked /> 按内容哈希去重</Space>
            </>
          )}
        </Form>
      )}

      {step === 3 && (
        <>
          <Form layout="vertical">
            <Form.Item label="调度 Cron"><Input placeholder="0 2 * * *" /></Form.Item>
            <Form.Item label="依赖上游任务触发"><Switch defaultChecked /></Form.Item>
            <Form.Item label="失败重试次数"><InputNumber defaultValue={3} min={0} /></Form.Item>
            <Form.Item label="抽取限流（rows/s）"><InputNumber defaultValue={2500} /></Form.Item>
            <Form.Item label="错峰时间窗"><Input placeholder="02:00-06:00" /></Form.Item>
          </Form>
          <Card size="small" title="预览汇总" style={{ marginTop: 16 }}>
            <Space direction="vertical">
              <Text>源：{source.name}（{source.type}）</Text>
              <Text>模式：{mode}</Text>
              <Text>表：{tables.join(', ')}</Text>
              <Text>限流：2500 rows/s · 重试 3 次 · 错峰 02:00-06:00</Text>
            </Space>
          </Card>
        </>
      )}

      <div style={{ marginTop: 24, textAlign: 'right' }}>
        <Space>
          <Button onClick={() => navigate('/integration/sync-tasks')}>取消</Button>
          <Button>保存草稿</Button>
          {step > 0 && <Button onClick={() => setStep(step - 1)}><ArrowLeftOutlined /> 上一步</Button>}
          {step < 3 && <Button type="primary" onClick={() => setStep(step + 1)}>下一步 <ArrowRightOutlined /></Button>}
          {step === 3 && <>
            <Button onClick={() => message.success('已试跑，预览采样 100 行')}>试跑</Button>
            <Button type="primary" icon={<CheckOutlined />} onClick={() => { message.success('采集任务已创建'); navigate('/integration/sync-tasks'); }}>发布</Button>
          </>}
        </Space>
      </div>
    </Card>
  );
}
