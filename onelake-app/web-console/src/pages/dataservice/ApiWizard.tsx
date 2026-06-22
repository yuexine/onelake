/**
 * API 构建向导（5 步，对应原型 §8.8.2 升级版）。
 *   ①选源 → ②参数与响应 → ③缓存与性能 → ④鉴权与限流 → ⑤预览与发布
 */
import { Steps, Form, Select, Input, Table, Switch, InputNumber, Button, Space, Typography, Tag, message } from 'antd';
import {
  ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined, CloudOutlined,
  DatabaseOutlined, SettingOutlined, LockOutlined, CodeOutlined, ApiOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { lakehouseAssets } from '../../mock';
import { DataserviceAPI } from '../../api';
import { ClassificationBadge, SectionCard } from '../../components';

const { Text } = Typography;

function paramsFromSql(sql: string) {
  return Array.from(sql.matchAll(/:([a-zA-Z_][a-zA-Z0-9_]*)/g))
    .map((match) => match[1])
    .filter((name, index, all) => all.indexOf(name) === index)
    .map((name) => ({ name, type: 'STRING', required: true }));
}

export default function ApiWizard() {
  const navigate = useNavigate();
  const location = useLocation();
  const [sp] = useSearchParams();
  const [step, setStep] = useState(0);
  const [saving, setSaving] = useState(false);
  const incoming = (location.state || {}) as {
    from?: string;
    sql?: string;
    sourceFqn?: string;
    columns?: { name: string; type: string }[];
  };
  const sourceFqn = sp.get('sourceFqn') || incoming.sourceFqn;
  const initialSql = incoming.sql || 'SELECT order_id, phone, amount FROM ads.ads_sales_df WHERE order_id = :order_id';
  const [draft, setDraft] = useState({
    apiPath: `/sql/${(sourceFqn || 'query').split('.').pop() || 'query'}`,
    viewName: `v_${((sourceFqn || 'sql_query').split('.').pop() || 'sql_query').replace(/[^a-zA-Z0-9_]/g, '_')}`,
    sourceFqn,
    sourceMode: incoming.sql ? 'sql' : 'table',
    selectSql: initialSql,
    qpsLimit: 50,
  });
  const [params] = useState<any[]>(() => {
    const extracted = paramsFromSql(initialSql);
    return extracted.length > 0 ? extracted : [];
  });
  const [returns] = useState<any[]>(() => (
    incoming.columns && incoming.columns.length > 0
      ? incoming.columns.map((column) => ({ name: column.name, type: column.type }))
      : [
          { name: 'order_id', type: 'BIGINT' },
          { name: 'phone', type: 'STRING', classification: 'L3', masked: true },
          { name: 'amount', type: 'DECIMAL' },
        ]
  ));

  const saveDraft = () => {
    setSaving(true);
    DataserviceAPI.createDraft({
      apiPath: draft.apiPath,
      viewName: draft.viewName,
      selectSql: draft.selectSql,
      sourceFqn: draft.sourceFqn,
      requestParams: JSON.stringify(params),
      responseSchema: JSON.stringify(returns),
      qpsLimit: draft.qpsLimit,
    })
      .then((api) => {
        message.success('API 草稿已保存');
        navigate(`/dataservice/apis/${api.id}`);
      })
      .catch((e) => message.error(e.message || 'API 草稿保存失败'))
      .finally(() => setSaving(false));
  };

  const steps = [
    { title: '选数据源', icon: <DatabaseOutlined /> },
    { title: '参数与响应', icon: <ApiOutlined /> },
    { title: '缓存与性能', icon: <SettingOutlined /> },
    { title: '鉴权与限流', icon: <LockOutlined /> },
    { title: '预览与发布', icon: <CheckOutlined /> },
  ];

  return (
    <div className="ol-page">
      <div className="ol-section" style={{ padding: '14px 20px' }}>
        <Space size={12}>
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/dataservice/apis')} />
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ol-ink)' }}>API 构建向导</div>
            <div style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>五步配置：选源 → 参数 → 缓存 → 鉴权 → 发布</div>
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

      <SectionCard title={<span style={{ fontSize: 14, fontWeight: 600 }}>{`第 ${step + 1} 步 · ${steps[step].title}`}</span>} style={{ minHeight: 320 }}>
        <div key={step} className="ol-anim-fade" style={{ maxWidth: 820 }}>
          {step === 0 && (
            <Form
              layout="vertical"
              requiredMark="optional"
              initialValues={draft}
              onValuesChange={(_, values) => setDraft((prev) => ({ ...prev, ...values }))}
            >
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item label="API 路径" name="apiPath" required>
                  <Input placeholder="/sql/orders" />
                </Form.Item>
                <Form.Item label="视图名" name="viewName" required>
                  <Input placeholder="v_orders" />
                </Form.Item>
              </div>
              <Form.Item label="来源（可从资产详情/SQL 工作台带参进入）" required>
                <Select
                  value={draft.sourceFqn}
                  onChange={(value) => setDraft((prev) => ({ ...prev, sourceFqn: value }))}
                  options={lakehouseAssets.map((a) => ({ label: `${a.fqn} (${a.description || ''})`, value: a.fqn }))}
                />
              </Form.Item>
              <Form.Item label="来源方式" name="sourceMode">
                <Select options={[{ label: '选表 / 模型', value: 'table' }, { label: '粘贴 SQL', value: 'sql' }]} />
              </Form.Item>
              <Form.Item label="SQL" name="selectSql" required>
                <Input.TextArea rows={4} style={{ fontFamily: 'monospace', fontSize: 12 }} />
              </Form.Item>
            </Form>
          )}

          {step === 1 && (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <SectionCard title="请求参数" icon={<ApiOutlined />} flatBody padded="none">
                <Table size="middle" rowKey="name" dataSource={params} pagination={false}
                  columns={[
                    { title: '参数名', dataIndex: 'name', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
                    { title: '类型', dataIndex: 'type', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
                    { title: '必填', dataIndex: 'required', width: 80, render: (r: boolean) => (
                      <Tag color={r ? 'error' : 'default'} style={{ margin: 0 }}>{r ? '必填' : '选填'}</Tag>
                    ) },
                    { title: '默认值', render: () => <Input size="small" style={{ width: 120 }} /> },
                    { title: '校验规则', render: () => <Input size="small" placeholder="正则/范围" style={{ width: 160 }} /> },
                  ]} />
              </SectionCard>
              <SectionCard title="返回字段" icon={<ApiOutlined />} flatBody padded="none">
                <Table size="middle" rowKey="name" dataSource={returns} pagination={false}
                  columns={[
                    { title: '字段', dataIndex: 'name', render: (v: string) => <Text strong style={{ fontSize: 13 }}>{v}</Text> },
                    { title: '类型', dataIndex: 'type', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
                    { title: '密级', dataIndex: 'classification', width: 120, render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
                    { title: '动态脱敏', dataIndex: 'masked', render: (m?: boolean) => <Switch size="small" defaultChecked={m} /> },
                  ]} />
              </SectionCard>
            </Space>
          )}

          {step === 2 && (
            <Form layout="vertical">
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item label="缓存 TTL"><Space><InputNumber defaultValue={60} /> 秒</Space></Form.Item>
                <Form.Item label="超时 (ms)"><InputNumber defaultValue={5000} /></Form.Item>
              </div>
              <Form.Item label="分页策略">
                <Select options={[{ label: 'limit/offset', value: 'offset' }, { label: '游标（推荐大结果集）', value: 'cursor' }]} defaultValue="cursor" />
              </Form.Item>
              <Form.Item label="排序字段"><Input placeholder="stat_date DESC" /></Form.Item>
            </Form>
          )}

          {step === 3 && (
            <Form layout="vertical">
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item label="QPS 限制"><InputNumber defaultValue={50} /></Form.Item>
                <Form.Item label="并发限制"><InputNumber defaultValue={20} /></Form.Item>
                <Form.Item label="熔断阈值"><Space><InputNumber defaultValue={50} />% 错误率</Space></Form.Item>
                <Form.Item label="时间戳防重放"><Switch defaultChecked /></Form.Item>
              </div>
              <Form.Item label="鉴权方式">
                <Select mode="multiple" options={['AppKey+Secret', 'OAuth2', 'JWT'].map((v) => ({ label: v, value: v }))} defaultValue={['AppKey+Secret']} />
              </Form.Item>
              <Form.Item label="IP 白名单">
                <Select mode="tags" placeholder="10.0.0.0/24" />
              </Form.Item>
              <div className="ol-section" style={{ padding: 14, background: 'var(--ol-brand-soft)', border: '1px solid var(--ol-brand-border)' }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-brand)', marginBottom: 8 }}>行/列级权限（越权防护）</div>
                <Space direction="vertical" size={4}>
                  <div><Tag color="blue" style={{ margin: 0 }}>行级</Tag> 注入 tenant_id = :ctx.tenant（防水平越权）</div>
                  <div><Tag color="blue" style={{ margin: 0 }}>列级</Tag> phone 仅 Sec 角色可见明文，其余动态脱敏</div>
                </Space>
              </div>
            </Form>
          )}

          {step === 4 && (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <SectionCard title="在线调试器" icon={<CodeOutlined />}>
                <Space>
                  <Text style={{ fontSize: 13 }}>order_id=</Text><Input defaultValue="1001" style={{ width: 120 }} />
                  <Text style={{ fontSize: 13 }}>dt=</Text><Input placeholder="2026-06-14" style={{ width: 140 }} />
                  <Button type="primary" onClick={() => message.success('200 OK')}>发送</Button>
                </Space>
                <pre style={{
                  marginTop: 12, padding: 14, background: 'var(--ol-ink)', color: 'var(--ol-card)',
                  borderRadius: 8, fontSize: 12, fontFamily: 'monospace', lineHeight: 1.6,
                }}>
{`响应 200: {"order_id": 1001, "phone": "138****8888", "amount": 99.0}
(按调用方角色动态脱敏)`}
                </pre>
              </SectionCard>
              <SectionCard title="OpenAPI 文档" icon={<FileTextOutlined />}>
                <Button type="primary" ghost>生成 OpenAPI YAML</Button>
              </SectionCard>
            </Space>
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
              <Button onClick={() => navigate('/dataservice/apis')}>取消</Button>
            ) : (
              <Button onClick={() => setStep(step - 1)}><ArrowLeftOutlined /> 上一步</Button>
            )}
            <Button loading={saving} onClick={saveDraft}>保存草稿</Button>
          </Space>
          <Space>
            {step < 4 && (
              <Button type="primary" onClick={() => setStep(step + 1)}>下一步 <ArrowRightOutlined /></Button>
            )}
            {step === 4 && (
              <Button type="primary" icon={<CheckOutlined />} loading={saving} onClick={saveDraft}>保存 API 草稿</Button>
            )}
          </Space>
        </div>
      </SectionCard>
    </div>
  );
}
