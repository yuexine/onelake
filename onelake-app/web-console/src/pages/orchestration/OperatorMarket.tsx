/**
 * 算子市场（对应原型 §8.4.6 升级版）。
 */
import { Row, Col, Tag, Space, Button, Input, Typography, Modal, Segmented, Select, Divider, Tooltip, App as AntApp, Form, Alert } from 'antd';
import { SearchOutlined, AppstoreOutlined, ArrowRightOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { OperatorAPI } from '../../api';
import { PageHeader, SectionCard, IntentBadge, StateView, type Intent } from '../../components';
import type { Operator, OperatorManifest, OperatorPort } from '../../types';

const { Text } = Typography;

const CATEGORY_LABEL: Record<string, string> = {
  INPUT: '输入',
  TRANSFORM: '转换',
  GOVERN: '治理',
  STANDARD: '标准化',
  MASK: '脱敏',
  ENCRYPT: '加密',
  AGG: '聚合',
  JOIN: '关联',
  QUALITY_GATE: '质量门禁',
  OUTPUT: '输出',
};

const SCOPE_LABEL: Record<string, string> = {
  BUILTIN: '内置',
  CUSTOM: '自定义',
  TENANT_PRIVATE: '租户私有',
};

const CATEGORY_INTENT: Record<string, Intent> = {
  INPUT: 'neutral',
  TRANSFORM: 'brand',
  GOVERN: 'brand',
  STANDARD: 'success',
  MASK: 'warning',
  ENCRYPT: 'error',
  AGG: 'violet',
  JOIN: 'info',
  QUALITY_GATE: 'warning',
  OUTPUT: 'success',
};

const CATEGORY_OPTIONS = [
  { label: '全部分类', value: 'ALL' },
  ...Object.entries(CATEGORY_LABEL).map(([value, label]) => ({ value, label })),
];

const SCOPE_OPTIONS = [
  { label: '全部', value: 'ALL' },
  { label: '内置', value: 'BUILTIN' },
  { label: '自定义', value: 'CUSTOM' },
  { label: '租户私有', value: 'TENANT_PRIVATE' },
];

const EDITABLE_SCOPE_OPTIONS = [
  { label: '自定义', value: 'CUSTOM' },
  { label: '租户私有', value: 'TENANT_PRIVATE' },
];

const COMPILE_TARGET_OPTIONS = [
  { label: 'SQL_DBT', value: 'SQL_DBT' },
  { label: 'SPARK', value: 'SPARK' },
  { label: 'PYTHON', value: 'PYTHON' },
];

interface OperatorEditorValues {
  operatorRef: string;
  version: string;
  category: string;
  scope: 'CUSTOM' | 'TENANT_PRIVATE';
  displayName: string;
  description?: string;
  tags?: string;
  inputPorts: string;
  outputSchema: string;
  paramsSchema: string;
  compileTarget: string;
  template: string;
  resourceHint: string;
  policyActionOnViolation?: string;
  changelog?: string;
}

const SAMPLE_VALUES: OperatorEditorValues = {
  operatorRef: 'custom.normalize_phone',
  version: '1.0.0',
  category: 'TRANSFORM',
  scope: 'CUSTOM',
  displayName: '手机号规整扩展',
  description: '去除手机号中的非数字字符，作为自定义 SQL_DBT 转换算子样例。',
  tags: 'phone,normalize',
  inputPorts: JSON.stringify([{ name: 'input', accept: 'TABLE', cardinality: 'ONE' }], null, 2),
  outputSchema: JSON.stringify({ mode: 'ROW_MODIFY', modifies: ['phone'] }, null, 2),
  paramsSchema: JSON.stringify({
    type: 'object',
    properties: {
      column: { type: 'string', title: '字段名' },
    },
    required: ['column'],
  }, null, 2),
  compileTarget: 'SQL_DBT',
  template: JSON.stringify({
    kind: 'COLUMN_EXPR',
    sql: "regexp_replace({{ column }}, '[^0-9]', '')",
  }, null, 2),
  resourceHint: JSON.stringify({ defaultResourceGroup: 'default', engine: 'TRINO_DBT' }, null, 2),
  policyActionOnViolation: '',
  changelog: 'initial version',
};

export default function OperatorMarket() {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<OperatorEditorValues>();
  const [operators, setOperators] = useState<Operator[]>([]);
  const [selected, setSelected] = useState<Operator | null>(null);
  const [category, setCategory] = useState('ALL');
  const [scope, setScope] = useState('ALL');
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [installing, setInstalling] = useState(false);
  const [statusUpdating, setStatusUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorMode, setEditorMode] = useState<'create' | 'version'>('create');
  const [validation, setValidation] = useState<{ ok: boolean; errors: string[]; warnings: string[] } | null>(null);
  const [validating, setValidating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const editorCompileTarget = Form.useWatch('compileTarget', form);

  const loadOperators = useCallback(() => {
    setLoading(true);
    setError(null);
    OperatorAPI.listOperators({
      category: category === 'ALL' ? undefined : category,
      scope: scope === 'ALL' ? undefined : scope,
      keyword: keyword || undefined,
    })
      .then(setOperators)
      .catch((e) => setError(e.message || '算子市场加载失败'))
      .finally(() => setLoading(false));
  }, [category, scope, keyword]);

  useEffect(() => {
    loadOperators();
  }, [loadOperators]);

  const counts = useMemo(() => ({
    total: operators.length,
    builtin: operators.filter((op) => op.scope === 'BUILTIN').length,
    custom: operators.filter((op) => op.scope === 'CUSTOM').length,
    privateOps: operators.filter((op) => op.scope === 'TENANT_PRIVATE').length,
    deprecated: operators.filter((op) => op.status === 'DEPRECATED').length,
  }), [operators]);

  const openDetail = (operator: Operator) => {
    setSelected(operator);
    setDetailLoading(true);
    OperatorAPI.getOperator(operator.operatorRef)
      .then(setSelected)
      .catch((e) => message.error(e.message || '算子详情加载失败'))
      .finally(() => setDetailLoading(false));
  };

  const installSelected = () => {
    if (!selected) return;
    if (selected.status === 'DEPRECATED') {
      message.warning('该算子已废弃，不能安装或锁定版本');
      return;
    }
    setInstalling(true);
    OperatorAPI.installOperator(selected.operatorRef, { pinnedVersion: selected.latestVersion })
      .then((updated) => {
        setSelected(updated);
        message.success('算子已安装/锁定版本');
        loadOperators();
      })
      .catch((e) => message.error(e.message || '算子安装失败'))
      .finally(() => setInstalling(false));
  };

  const updateSelectedStatus = (status: 'ACTIVE' | 'DEPRECATED') => {
    if (!selected || selected.scope === 'BUILTIN') return;
    const deprecated = status === 'DEPRECATED';
    modal.confirm({
      title: deprecated ? '废弃算子' : '恢复算子',
      content: deprecated
        ? '废弃后该算子仍保留历史版本，但不应继续被新 DAG 安装或使用。'
        : '恢复后该算子会重新作为可用版本进入市场列表。',
      okText: deprecated ? '确认废弃' : '确认恢复',
      okButtonProps: { danger: deprecated },
      onOk: async () => {
        setStatusUpdating(true);
        try {
          const updated = await OperatorAPI.updateOperator(selected.operatorRef, { status });
          setSelected(updated);
          message.success(deprecated ? '算子已废弃' : '算子已恢复');
          loadOperators();
        } catch (e: any) {
          message.error(e.message || '算子状态更新失败');
        } finally {
          setStatusUpdating(false);
        }
      },
    });
  };

  const openCreateEditor = () => {
    setEditorMode('create');
    setValidation(null);
    setEditorOpen(true);
    window.setTimeout(() => form.setFieldsValue(SAMPLE_VALUES));
  };

  const openVersionEditor = () => {
    if (!selected?.manifest) return;
    const values = valuesFromManifest(selected.manifest, bumpPatchVersion(selected.latestVersion));
    setEditorMode('version');
    setValidation(null);
    setSelected(null);
    setEditorOpen(true);
    window.setTimeout(() => form.setFieldsValue(values));
  };

  const validateCurrentManifest = async () => {
    let values: OperatorEditorValues;
    try {
      values = await form.validateFields();
    } catch {
      return null;
    }
    setValidating(true);
    try {
      const manifest = manifestFromValues(values);
      const result = await OperatorAPI.validateOperator(manifest);
      setValidation(result);
      if (result.ok) {
        message.success('Manifest 校验通过');
      } else {
        message.error('Manifest 校验未通过');
      }
      return { manifest, result };
    } catch (e: any) {
      message.error(e.message || 'Manifest 解析失败');
      return null;
    } finally {
      setValidating(false);
    }
  };

  const submitEditor = async () => {
    let values: OperatorEditorValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      const manifest = manifestFromValues(values);
      const result = await OperatorAPI.validateOperator(manifest);
      setValidation(result);
      if (!result.ok) {
        message.error('Manifest 校验未通过，已阻止提交');
        return;
      }
      const updated = editorMode === 'create'
        ? await OperatorAPI.registerOperator(manifest)
        : await OperatorAPI.publishVersion(manifest.operatorRef, { manifest, changelog: values.changelog });
      setSelected(updated);
      setEditorOpen(false);
      message.success(editorMode === 'create' ? '自定义算子已注册' : '算子新版本已发布');
      loadOperators();
    } catch (e: any) {
      message.error(e.message || '算子提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title="算子市场"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="内置 / 自定义 / 租户私有三类算子，可在 DAG 画布中拖入使用"
        meta={[
          { label: '可见算子', value: counts.total },
          { label: '内置', value: counts.builtin },
          { label: '自定义', value: counts.custom + counts.privateOps },
          { label: '已废弃', value: counts.deprecated },
        ]}
        actions={
          <Space wrap>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateEditor}>注册算子</Button>
            <Input.Search
              allowClear
              placeholder="搜索算子"
              prefix={<SearchOutlined />}
              value={searchInput}
              onChange={(e) => {
                setSearchInput(e.target.value);
                if (!e.target.value) setKeyword('');
              }}
              onSearch={(value) => setKeyword(value.trim())}
              style={{ width: 240 }}
            />
          </Space>
        }
      />

      <SectionCard padded="sm">
        <Space wrap size={12}>
          <Segmented
            value={scope}
            options={SCOPE_OPTIONS}
            onChange={(value) => setScope(String(value))}
          />
          <Select
            value={category}
            options={CATEGORY_OPTIONS}
            onChange={setCategory}
            style={{ width: 148 }}
          />
          <Button icon={<ReloadOutlined />} onClick={loadOperators}>刷新</Button>
        </Space>
      </SectionCard>

      {loading ? (
        <SectionCard>
          <StateView state="loading" rows={6} />
        </SectionCard>
      ) : error ? (
        <SectionCard>
          <StateView state="error" title="算子市场加载失败" description={error} onRetry={loadOperators} />
        </SectionCard>
      ) : operators.length === 0 ? (
        <SectionCard>
          <StateView state="empty" title="暂无匹配算子" description="当前筛选条件下没有可见算子。" />
        </SectionCard>
      ) : (
        <Row gutter={[16, 16]}>
          {operators.map((op) => {
            const intent = CATEGORY_INTENT[op.category] || 'brand';
            const deprecated = op.status === 'DEPRECATED';
            return (
              <Col key={op.id} xs={24} sm={12} md={8} lg={6}>
                <div
                  onClick={() => openDetail(op)}
                  style={{
                    cursor: 'pointer',
                    background: 'var(--ol-card)',
                    border: deprecated ? '1px solid var(--ol-warning)' : '1px solid var(--ol-line-soft)',
                    borderRadius: 10,
                    padding: 18,
                    boxShadow: 'var(--ol-shadow-e1)',
                    transition: 'all var(--ol-dur-base) var(--ol-ease)',
                    height: '100%',
                    minHeight: 168,
                    display: 'flex',
                    flexDirection: 'column',
                    opacity: deprecated ? 0.72 : 1,
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = 'var(--ol-shadow-e2)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = 'var(--ol-shadow-e1)';
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8 }}>
                    <IntentBadge intent={intent}>{CATEGORY_LABEL[op.category] || op.category}</IntentBadge>
                    <Space size={4}>
                      {deprecated && <Tag color="warning" style={{ margin: 0, fontSize: 11 }}>已废弃</Tag>}
                      <Tag style={{ margin: 0, fontSize: 11 }}>{op.latestVersion}</Tag>
                    </Space>
                  </div>
                  <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 6, lineHeight: 1.35 }}>
                    {op.displayName}
                  </div>
                  <Tooltip title={op.description}>
                    <div style={{ color: 'var(--ol-ink-3)', fontSize: 12, lineHeight: 1.45, minHeight: 34, marginBottom: 10 }}>
                      {op.description || op.operatorRef}
                    </div>
                  </Tooltip>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--ol-ink-3)', marginTop: 'auto' }}>
                    <span>{inputSummary(op.manifest)}</span>
                    <ArrowRightOutlined style={{ fontSize: 10 }} />
                    <span>{outputSummary(op.manifest)}</span>
                  </div>
                  <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span className="ol-chip" style={{ fontSize: 11 }}>{SCOPE_LABEL[op.scope] || op.scope}</span>
                    {op.installed && !deprecated && <Tag color="success" style={{ margin: 0, fontSize: 11 }}>已可用</Tag>}
                  </div>
                </div>
              </Col>
            );
          })}
        </Row>
      )}

      <Modal
        open={!!selected}
        onCancel={() => setSelected(null)}
        title={selected?.displayName}
        width={680}
        footer={[
          selected && selected.scope !== 'BUILTIN' && (
            <Button key="v" onClick={openVersionEditor}>发布新版本</Button>
          ),
          selected && selected.scope !== 'BUILTIN' && (
            <Button
              key="status"
              danger={selected.status !== 'DEPRECATED'}
              loading={statusUpdating}
              onClick={() => updateSelectedStatus(selected.status === 'DEPRECATED' ? 'ACTIVE' : 'DEPRECATED')}
            >
              {selected.status === 'DEPRECATED' ? '恢复' : '废弃'}
            </Button>
          ),
          <Button key="i" loading={installing} disabled={selected?.status === 'DEPRECATED'} onClick={installSelected}>安装/锁定版本</Button>,
          <Button
            key="u"
            type="primary"
            disabled={selected?.status === 'DEPRECATED'}
            onClick={() => message.success('已作为画布算子准备使用')}
          >
            使用
          </Button>,
        ].filter(Boolean)}
      >
        {selected && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <IntentBadge intent={CATEGORY_INTENT[selected.category] || 'brand'}>
                {CATEGORY_LABEL[selected.category] || selected.category}
              </IntentBadge>
              <span className="ol-chip">{SCOPE_LABEL[selected.scope] || selected.scope}</span>
              <Tag style={{ margin: 0 }}>{selected.latestVersion}</Tag>
              <Tag color={selected.status === 'DEPRECATED' ? 'warning' : 'success'} style={{ margin: 0 }}>
                {selected.status === 'DEPRECATED' ? '已废弃' : '可用'}
              </Tag>
              {detailLoading && <Text type="secondary" style={{ fontSize: 12 }}>加载详情中...</Text>}
            </div>
            <Text style={{ color: 'var(--ol-ink-2)', fontSize: 13 }}>{selected.description || selected.operatorRef}</Text>

            <Divider style={{ margin: '4px 0' }} />

            <Row gutter={[12, 12]}>
              <Col xs={24} md={12}>
                <DetailBlock title="声明输入" value={inputSummary(selected.manifest)} />
              </Col>
              <Col xs={24} md={12}>
                <DetailBlock title="声明输出" value={outputSummary(selected.manifest)} />
              </Col>
              <Col xs={24} md={12}>
                <DetailBlock title="编译目标" value={selected.manifest?.compileTarget || '未声明'} />
              </Col>
              <Col xs={24} md={12}>
                <DetailBlock title="模板类型" value={selected.manifest?.template?.kind || '未声明'} />
              </Col>
            </Row>

            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>参数</Text>
              <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {paramEntries(selected.manifest).length === 0 ? (
                  <span className="ol-chip">无参数</span>
                ) : paramEntries(selected.manifest).map(([name, schema]) => (
                  <span key={name} className="ol-chip" style={{ fontSize: 11 }}>
                    {name}{schema.type ? ` · ${schema.type}` : ''}
                  </span>
                ))}
              </div>
            </div>

            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>版本</Text>
              <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {(selected.versions?.length ? selected.versions : [{ id: selected.id, version: selected.latestVersion }]).map((v) => (
                  <Tag key={v.id || v.version} style={{ margin: 0 }}>{v.version}</Tag>
                ))}
              </div>
            </div>

            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>使用示例</Text>
              <div style={{ marginTop: 6 }}>
                <Text code style={{ fontSize: 12 }}>
                  {exampleText(selected.manifest) || `${selected.operatorRef}(${paramEntries(selected.manifest).map(([name]) => name).join(', ')})`}
                </Text>
              </div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        title={editorMode === 'create' ? '注册自定义算子' : '发布算子新版本'}
        width={820}
        okText={editorMode === 'create' ? '校验并注册' : '校验并发布'}
        onOk={submitEditor}
        confirmLoading={submitting}
        footer={[
          <Button key="sample" onClick={() => form.setFieldsValue(SAMPLE_VALUES)}>填入样例</Button>,
          <Button key="validate" loading={validating} onClick={validateCurrentManifest}>仅校验</Button>,
          <Button key="cancel" onClick={() => setEditorOpen(false)}>取消</Button>,
          <Button key="submit" type="primary" loading={submitting} onClick={submitEditor}>
            {editorMode === 'create' ? '校验并注册' : '校验并发布'}
          </Button>,
        ]}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {validation && (
            <Alert
              type={validation.ok ? 'success' : 'error'}
              showIcon
              message={validation.ok ? 'Manifest 校验通过' : 'Manifest 校验未通过'}
              description={(
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {validation.errors.map((item) => <Text key={item} type="danger" style={{ fontSize: 12 }}>{item}</Text>)}
                  {validation.warnings.map((item) => <Text key={item} type="warning" style={{ fontSize: 12 }}>{item}</Text>)}
                  {validation.errors.length === 0 && validation.warnings.length === 0 && (
                    <Text type="secondary" style={{ fontSize: 12 }}>后端 Manifest 自校验未返回错误或警告。</Text>
                  )}
                </Space>
              )}
            />
          )}
          {editorCompileTarget && editorCompileTarget !== 'SQL_DBT' && (
            <Alert
              type="warning"
              showIcon
              message={`${editorCompileTarget} 为扩展态`}
              description="当前仅支持 Manifest 契约校验与版本管理；画布图级执行仍会阻断，直到对应 Dagster op 与部署契约落地。"
            />
          )}

          <Form form={form} layout="vertical" preserve={false}>
            <Row gutter={12}>
              <Col xs={24} md={12}>
                <Form.Item name="operatorRef" label="operatorRef" rules={[{ required: true }]}>
                  <Input disabled={editorMode === 'version'} placeholder="custom.normalize_phone" />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item name="version" label="版本" rules={[{ required: true }]}>
                  <Input placeholder="1.0.0" />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
                  <Select disabled={editorMode === 'version'} options={EDITABLE_SCOPE_OPTIONS} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="category" label="分类" rules={[{ required: true }]}>
                  <Select options={CATEGORY_OPTIONS.filter((item) => item.value !== 'ALL')} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="compileTarget" label="编译目标" rules={[{ required: true }]}>
                  <Select options={COMPILE_TARGET_OPTIONS} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
                  <Input placeholder="手机号规整扩展" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="template" label="template JSON" rules={[{ required: true }]}>
                  <Input.TextArea rows={6} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="tags" label="标签">
                  <Input placeholder="phone,normalize" />
                </Form.Item>
              </Col>
              <Col xs={24}>
                <Form.Item name="description" label="说明">
                  <Input.TextArea rows={2} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="inputPorts" label="inputPorts JSON" rules={[{ required: true }]}>
                  <Input.TextArea rows={6} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="paramsSchema" label="paramsSchema JSON" rules={[{ required: true }]}>
                  <Input.TextArea rows={6} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="outputSchema" label="outputSchema JSON" rules={[{ required: true }]}>
                  <Input.TextArea rows={4} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
                <Form.Item name="resourceHint" label="resourceHint JSON" rules={[{ required: true }]}>
                  <Input.TextArea rows={3} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="policyActionOnViolation" label="质量门禁失败策略">
                  <Select
                    allowClear
                    options={[
                      { label: 'BLOCK', value: 'BLOCK' },
                      { label: 'WARN', value: 'WARN' },
                      { label: 'DROP', value: 'DROP' },
                      { label: 'QUARANTINE', value: 'QUARANTINE' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="changelog" label="版本说明">
                  <Input.TextArea rows={2} />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Space>
      </Modal>
    </div>
  );
}

function DetailBlock({ title, value }: { title: string; value: string }) {
  return (
    <div style={{ border: '1px solid var(--ol-line-soft)', borderRadius: 8, padding: 10, background: 'var(--ol-fill-soft)' }}>
      <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>{title}</Text>
      <div style={{ marginTop: 4, color: 'var(--ol-ink)', fontSize: 13, fontWeight: 500 }}>{value}</div>
    </div>
  );
}

function inputSummary(manifest?: OperatorManifest): string {
  const ports = manifest?.inputPorts || [];
  if (!ports.length) return '无输入';
  return ports.map(portSummary).join('、');
}

function portSummary(port: OperatorPort): string {
  return `${port.name}:${port.accept}/${port.cardinality}`;
}

function outputSummary(manifest?: OperatorManifest): string {
  return String(manifest?.outputSchema?.mode || '未声明');
}

function paramEntries(manifest?: OperatorManifest): [string, { type?: string; title?: string }][] {
  const properties = manifest?.paramsSchema?.properties;
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return [];
  return Object.entries(properties as Record<string, { type?: string; title?: string }>);
}

function exampleText(manifest?: OperatorManifest): string {
  const example = manifest?.examples?.[0];
  if (!example) return '';
  return `${manifest?.operatorRef} ${JSON.stringify(example.params)}`;
}

function manifestFromValues(values: OperatorEditorValues): OperatorManifest {
  const policyAction = values.policyActionOnViolation?.trim();
  return {
    operatorRef: values.operatorRef.trim(),
    version: values.version.trim(),
    category: values.category,
    scope: values.scope,
    displayName: values.displayName.trim(),
    description: values.description?.trim(),
    tags: splitTags(values.tags),
    inputPorts: parseJsonField<OperatorPort[]>(values.inputPorts, 'inputPorts JSON'),
    outputSchema: parseJsonField<Record<string, unknown>>(values.outputSchema, 'outputSchema JSON'),
    paramsSchema: parseJsonField<Record<string, unknown>>(values.paramsSchema, 'paramsSchema JSON'),
    compileTarget: values.compileTarget,
    template: parseJsonField<Record<string, unknown>>(values.template, 'template JSON'),
    resourceHint: parseJsonField<Record<string, unknown>>(values.resourceHint, 'resourceHint JSON'),
    qualityEmit: values.category === 'QUALITY_GATE',
    policy: policyAction ? { actionOnViolation: policyAction } : undefined,
    examples: [
      {
        title: '默认示例',
        params: {},
      },
    ],
  };
}

function valuesFromManifest(manifest: OperatorManifest, version: string): OperatorEditorValues {
  return {
    operatorRef: manifest.operatorRef,
    version,
    category: manifest.category,
    scope: manifest.scope === 'TENANT_PRIVATE' ? 'TENANT_PRIVATE' : 'CUSTOM',
    displayName: manifest.displayName,
    description: manifest.description,
    tags: manifest.tags?.join(',') || '',
    inputPorts: JSON.stringify(manifest.inputPorts || [], null, 2),
    outputSchema: JSON.stringify(manifest.outputSchema || { mode: 'ROW_MODIFY' }, null, 2),
    paramsSchema: JSON.stringify(manifest.paramsSchema || { type: 'object', properties: {} }, null, 2),
    compileTarget: manifest.compileTarget || 'SQL_DBT',
    template: JSON.stringify(manifest.template || { kind: 'COLUMN_EXPR', sql: '' }, null, 2),
    resourceHint: JSON.stringify(manifest.resourceHint || { defaultResourceGroup: 'default', engine: 'TRINO_DBT' }, null, 2),
    policyActionOnViolation: String(manifest.policy?.actionOnViolation || ''),
    changelog: '',
  };
}

function parseJsonField<T>(value: string, label: string): T {
  try {
    return JSON.parse(value || 'null') as T;
  } catch (error) {
    throw new Error(`${label} 不是合法 JSON`);
  }
}

function splitTags(value?: string): string[] {
  return (value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function bumpPatchVersion(version: string): string {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(-.+)?$/);
  if (!match) return version;
  return `${match[1]}.${match[2]}.${Number(match[3]) + 1}`;
}
