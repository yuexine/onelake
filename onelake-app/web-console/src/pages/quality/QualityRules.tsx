/**
 * 规则配置（对应原型 §8.5.1 升级版）。
 */
import { Table, Tag, Space, Button, Modal, Form, Select, Input, Typography, message } from 'antd';
import { PlusOutlined, SafetyOutlined, CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import { StatusBadge, PageHeader, SectionCard, StateView } from '../../components';
import { CatalogAPI, GlossaryAPI, QualityAPI } from '../../api';
import type { Asset, BusinessTerm, QualityRule } from '../../types';

const { Text } = Typography;

const RULE_LIBRARY = ['NOT_NULL', 'UNIQUE', 'RANGE', 'REGEX', 'ENUM', 'REFERENTIAL', 'DRIFT'];

export default function QualityRules() {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [rules, setRules] = useState<QualityRule[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [terms, setTerms] = useState<BusinessTerm[]>([]);
  const [selectedTermId, setSelectedTermId] = useState<string>();
  const [selectedAssetFqn, setSelectedAssetFqn] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [runningId, setRunningId] = useState<string>();

  const loadRules = async () => {
    setLoading(true);
    try {
      setRules(await QualityAPI.listRules());
    } catch (error) {
      message.error(error instanceof Error ? error.message : '质量规则加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadRules();
    CatalogAPI.listAssets()
      .then(setAssets)
      .catch(() => message.warning('资产列表加载失败，请稍后重试'));
    GlossaryAPI.listTerms({ status: 'APPROVED' })
      .then(setTerms)
      .catch(() => message.warning('已审定术语加载失败，请稍后重试'));
  }, []);

  const selectedAsset = assets.find((asset) => asset.fqn === selectedAssetFqn);
  const selectedTerm = terms.find((item) => item.id === selectedTermId);
  const termOptions = useMemo(() => {
    const boundTermIds = new Set<string>();
    assets.forEach((asset) => asset.columns.forEach((column) => column.terms?.forEach((term) => boundTermIds.add(term.id))));
    return terms
      .filter((term) => boundTermIds.has(term.id))
      .map((term) => ({ label: `${term.code} · ${term.name}`, value: term.id }));
  }, [assets, terms]);
  const assetsForTerm = selectedTermId
    ? assets.filter((asset) => asset.columns.some((column) => column.terms?.some((term) => term.id === selectedTermId)))
    : assets;
  const columnsForSelectedAsset = (selectedAsset?.columns || []).filter((column) => (
    !selectedTermId || column.terms?.some((term) => term.id === selectedTermId)
  ));

  const counts = {
    total: rules.length,
    enabled: rules.filter((r) => r.enabled).length,
    block: rules.filter((r) => r.severity === 'BLOCK').length,
    highPass: rules.filter((r) => (r.lastPassRate ?? 0) > 95).length,
  };

  const createRule = async () => {
    const values = await form.validateFields();
    const { businessTermId: _businessTermId, ...payload } = values;
    setSaving(true);
    try {
      await QualityAPI.createRule(payload);
      message.success('规则已创建');
      setOpen(false);
      form.resetFields();
      setSelectedAssetFqn(undefined);
      await loadRules();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '规则创建失败');
    } finally {
      setSaving(false);
    }
  };

  const runRule = async (rule: QualityRule) => {
    setRunningId(rule.id);
    try {
      const result = await QualityAPI.runRule(rule.id);
      message.success(result.passed ? '试跑通过' : `试跑未通过，失败 ${result.failedRows} 行`);
      await loadRules();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '试跑失败');
    } finally {
      setRunningId(undefined);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyOutlined />}
        title="规则配置"
        subtitle={<span className="ol-chip">质量 · L3-4</span>}
        description="按资产/字段绑定规则，支持 BLOCK / WARN 严重度，随加工就绪或 CRON 触发"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建规则</Button>}
      />

      <SectionCard title="规则列表" icon={<SafetyOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={rules}
          loading={loading}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无规则"
                description="新建质量规则，对资产和字段进行稽核"
                cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建规则</Button>}
              />
            ),
          }}
          size="middle"
          pagination={{ pageSize: 20 }}
          columns={[
            { title: '资产', dataIndex: 'targetFqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '字段', dataIndex: 'targetColumn', render: (c?: string) => c ? <Text code style={{ fontSize: 12 }}>{c}</Text> : <span className="ol-quiet">全表</span> },
            { title: '规则', dataIndex: 'ruleType', width: 110, render: (r: string) => <Tag color="blue" style={{ margin: 0 }}>{r}</Tag> },
            { title: '表达式', dataIndex: 'expression', ellipsis: true, render: (e: string) => <Text code style={{ fontSize: 11 }}>{e}</Text> },
            { title: '严重度', dataIndex: 'severity', width: 90, render: (s: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: s === 'BLOCK' ? 'var(--ol-error-soft)' : 'var(--ol-warning-soft)',
                color: s === 'BLOCK' ? 'var(--ol-error)' : '#B45309',
              }}>{s}</span>
            ) },
            { title: '最近通过率', dataIndex: 'lastPassRate', width: 110, render: (v?: number) => v ? (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: v > 95 ? 'var(--ol-success-soft)' : v > 90 ? 'var(--ol-warning-soft)' : 'var(--ol-error-soft)',
                color: v > 95 ? 'var(--ol-success)' : v > 90 ? '#B45309' : 'var(--ol-error)',
              }}>{v}%</span>
            ) : '-' },
            { title: '状态', dataIndex: 'enabled', width: 100, render: (e: boolean) => <StatusBadge status={e ? 'ACTIVE' : 'OFFLINE'} label={e ? '已启用' : '已停用'} /> },
            { title: '操作', width: 140, render: (_: unknown, rule: QualityRule) => (
              <Space>
                <Button size="small" type="link">编辑</Button>
                <Button
                  size="small"
                  type="link"
                  loading={runningId === rule.id}
                  onClick={() => runRule(rule)}
                >
                  试跑
                </Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <Modal
        open={open}
        title="新建规则"
        onCancel={() => setOpen(false)}
        width={680}
        onOk={createRule}
        confirmLoading={saving}
      >
        <Form form={form} layout="vertical" initialValues={{ ruleType: 'NOT_NULL', severity: 'BLOCK', schedule: 'ON_PARTITION' }}>
          <Form.Item label="业务术语" name="businessTermId">
            <Select
              showSearch
              allowClear
              optionFilterProp="label"
              placeholder="可先选择业务术语筛选字段"
              value={selectedTermId}
              onChange={(value) => {
                setSelectedTermId(value);
                const firstAsset = value
                  ? assets.find((asset) => asset.columns.some((column) => column.terms?.some((term) => term.id === value)))
                  : undefined;
                const firstColumn = firstAsset?.columns.find((column) => column.terms?.some((term) => term.id === value));
                setSelectedAssetFqn(firstAsset?.fqn);
                form.setFieldsValue({
                  targetFqn: firstAsset?.fqn,
                  targetColumn: firstColumn?.name,
                  expression: terms.find((term) => term.id === value)?.caliberSql || (firstColumn ? `${firstColumn.name} IS NOT NULL` : undefined),
                });
              }}
              options={termOptions}
            />
          </Form.Item>
          <Form.Item label="绑定资产" name="targetFqn" rules={[{ required: true, message: '请选择资产' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              onChange={(value) => {
                setSelectedAssetFqn(value);
                form.setFieldValue('targetColumn', undefined);
              }}
              options={assetsForTerm.map((asset) => ({ label: asset.fqn, value: asset.fqn }))}
            />
          </Form.Item>
          <Form.Item label="字段" name="targetColumn">
            <Select
              allowClear
              options={columnsForSelectedAsset.map((column) => ({ label: column.name, value: column.name }))}
              placeholder="不选择则绑定全表"
            />
          </Form.Item>
          {selectedTerm && (
            <div className="ol-section" style={{ padding: 12, marginBottom: 12, background: 'var(--ol-fill-soft)' }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 4 }}>{selectedTerm.code} · {selectedTerm.name}</div>
              <Text type="secondary" style={{ fontSize: 12 }}>{selectedTerm.definition || '已按该术语筛选可绑定字段'}</Text>
            </div>
          )}
          <Form.Item label="规则库（卡片选择）" name="ruleType" rules={[{ required: true, message: '请选择规则类型' }]}>
            <Select options={RULE_LIBRARY.map((v) => ({ label: v, value: v }))} />
          </Form.Item>
          <Form.Item label="阈值 / 表达式" name="expression" rules={[{ required: true, message: '请输入表达式' }]}><Input placeholder="0 ≤ amount ≤ 99999" /></Form.Item>
          <Form.Item label="严重度" name="severity">
            <Select options={['BLOCK', 'WARN'].map((v) => ({ label: v, value: v }))} defaultValue="BLOCK" />
          </Form.Item>
          <Form.Item label="绑定调度" name="schedule">
            <Select options={[{ label: '随加工就绪触发', value: 'ON_PARTITION' }, { label: 'CRON', value: 'CRON' }]} />
          </Form.Item>
        </Form>
        <div className="ol-section" style={{ padding: 12, marginTop: 8, background: 'var(--ol-fill-soft)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 4 }}>试跑命中行数</div>
          <Text type="secondary" style={{ fontSize: 12 }}>点击「试跑」实时计算</Text>
        </div>
      </Modal>
    </div>
  );
}
