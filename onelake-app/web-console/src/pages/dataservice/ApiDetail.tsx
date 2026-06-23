/**
 * API 详情（对应原型 §8.8.3）。
 * Tab: 文档 / 版本 / 调试 / 订阅方 / 监控
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Alert, Tabs, Tag, Space, Button, Typography, Input, Table, message } from 'antd';
import { CloudOutlined, ApiOutlined, FileTextOutlined, CodeOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { apis, apiVersions, subscriptions, apiCallTrend } from '../../mock';
import { DataserviceAPI } from '../../api';
import { DetailPageLayout, StatusBadge, ClassificationBadge, DangerConfirm, SectionCard, StatCard, useAsyncAction } from '../../components';
import type { ApiDefinition, ApiReturnField } from '../../types';
import ReactECharts from 'echarts-for-react';

const { Text } = Typography;

interface ApiDebugResult {
  columns: { name: string; type: string }[];
  rows: Record<string, unknown>[];
  durationMs: number;
  rowCount: number;
  truncated: boolean;
  maskedColumns?: string[];
  securityNotices?: string[];
}

function hasProtectedDebugResult(result?: ApiDebugResult) {
  if (!result) return false;
  return Boolean(result.securityNotices?.length || result.maskedColumns?.length);
}

function errorMessage(e: unknown) {
  return e instanceof Error && e.message ? e.message : '调试失败，请检查 API 是否可用';
}

function parseApiReturns(raw?: string): ApiReturnField[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export default function ApiDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [api, setApi] = useState<ApiDefinition>(apis.find((a) => a.id === id) || apis[0]);
  const [offlineOpen, setOfflineOpen] = useState(false);
  const [debugParams, setDebugParams] = useState('{\n  "order_id": 1001\n}');
  const [debugResult, setDebugResult] = useState<ApiDebugResult>();
  const [debugError, setDebugError] = useState<string>();
  const { run, isLoading } = useAsyncAction();
  const responseFields = parseApiReturns(api.responseSchema);

  useEffect(() => {
    if (!id) return;
    DataserviceAPI.getApi(id)
      .then(setApi)
      .catch(() => setApi(apis.find((a) => a.id === id) || apis[0]));
  }, [id]);

  const tabs = [
    { key: 'doc', label: '文档', children: (
      <SectionCard title="API 文档" icon={<FileTextOutlined />}>
        <Space direction="vertical" size={10}>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>路径</Text>
            <div style={{ marginTop: 4 }}><Text code style={{ fontSize: 12 }}>{`/api${api.apiPath}`}</Text></div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>方法</Text>
            <div style={{ marginTop: 4 }}><Tag color="processing" style={{ margin: 0 }}>GET</Tag></div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>描述</Text>
            <div style={{ marginTop: 4, fontSize: 13 }}>{api.description}</div>
          </div>
          <div>
            <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>SQL</Text>
            <div style={{ marginTop: 4 }}>
              <pre style={{ padding: 12, background: 'var(--ol-fill-soft)', borderRadius: 6, fontSize: 12, fontFamily: 'monospace', margin: 0 }}>{api.selectSql}</pre>
            </div>
          </div>
          {responseFields.length > 0 && (
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>响应字段与术语</Text>
              <Table
                size="small"
                rowKey="name"
                pagination={false}
                style={{ marginTop: 6 }}
                dataSource={responseFields}
                columns={[
                  { title: '字段', dataIndex: 'name', render: (value: string) => <Text code style={{ fontSize: 12 }}>{value}</Text> },
                  { title: '类型', dataIndex: 'type', width: 110 },
                  { title: '术语', width: 180, render: (_: unknown, row: ApiReturnField) => row.termCode ? <Tag color="blue">{row.termCode} · {row.termName}</Tag> : '-' },
                  { title: '口径/定义', ellipsis: true, render: (_: unknown, row: ApiReturnField) => row.caliberSql || row.termDefinition || '-' },
                  { title: '密级', width: 90, render: (_: unknown, row: ApiReturnField) => row.suggestLevel || row.classification ? <Tag color={row.masked ? 'error' : 'default'}>{row.suggestLevel || row.classification}</Tag> : '-' },
                  { title: '脱敏', dataIndex: 'masked', width: 80, render: (value?: boolean) => value ? <Tag color="error">动态脱敏</Tag> : '-' },
                ]}
              />
            </div>
          )}
          <Button>下载 OpenAPI YAML</Button>
        </Space>
      </SectionCard>
    ) },
    { key: 'versions', label: '版本', children: (
      <SectionCard title="版本历史" icon={<ApiOutlined />} flatBody>
        <Table size="middle" rowKey="id" dataSource={apiVersions.filter((v) => v.apiId === api.id)}
          columns={[
            { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue" style={{ margin: 0 }}>v{v}</Tag> },
            { title: '发布时间', dataIndex: 'publishedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '弃用时间', dataIndex: 'deprecatedAt', render: (v?: string) => v || '-' },
            { title: '灰度', dataIndex: 'grayPercent', render: (v?: number) => v != null ? <Tag color="processing" style={{ margin: 0 }}>{v}%</Tag> : '-' },
          ]} />
      </SectionCard>
    ) },
    { key: 'debug', label: '调试', children: (
      <SectionCard title="在线调试" icon={<CodeOutlined />} flatBody>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Text style={{ fontSize: 13, color: 'var(--ol-ink-2)' }}>请求参数 JSON</Text>
          <Input.TextArea
            value={debugParams}
            onChange={(e) => setDebugParams(e.target.value)}
            rows={4}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
          <Button
            type="primary"
            loading={isLoading('debug-send')}
            onClick={() => run('debug-send', async () => {
              setDebugError(undefined);
              let params: Record<string, unknown>;
              try {
                params = debugParams.trim() ? JSON.parse(debugParams) : {};
              } catch {
                const error = '请求参数不是合法 JSON';
                setDebugError(error);
                throw new Error(error);
              }
              try {
                const data = await DataserviceAPI.debugApi(api.id, params);
                setDebugResult(data);
                return data;
              } catch (e) {
                setDebugError(errorMessage(e));
                throw e;
              }
            }, {
              successMsg: '200 OK · 响应正常',
              errorMsg: errorMessage,
              duration: 2.5,
            })}
          >
            发送
          </Button>
          {debugError && (
            <Alert
              type="error"
              showIcon
              style={{ borderRadius: 6 }}
              message={<span style={{ fontSize: 13 }}>{debugError}</span>}
            />
          )}
          {hasProtectedDebugResult(debugResult) && (
            <Alert
              type="info"
              showIcon
              style={{ borderRadius: 6 }}
              message={<span style={{ fontSize: 13 }}>{debugResult?.securityNotices?.join('；') || '调试响应已按 Catalog 密级与 Security 脱敏策略处理。'}</span>}
            />
          )}
        </Space>
        <pre style={{
          marginTop: 12, padding: 14, background: 'var(--ol-ink)', color: 'var(--ol-card)',
          borderRadius: 8, fontSize: 12, fontFamily: 'monospace', lineHeight: 1.6,
        }}>{debugResult ? JSON.stringify(debugResult, null, 2) : '等待发送调试请求'}</pre>
      </SectionCard>
    ) },
    { key: 'subs', label: `订阅方`, badge: subscriptions.filter((s) => s.apiId === api.id).length, children: (
      <SectionCard title="订阅方" icon={<ApiOutlined />} flatBody>
        <Table size="middle" rowKey="id" dataSource={subscriptions.filter((s) => s.apiId === api.id)}
          columns={[
            { title: '订阅方', dataIndex: 'subscriberName' },
            { title: '原因', dataIndex: 'reason', ellipsis: true, render: (r: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{r}</span> },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: 'AppKey', dataIndex: 'appKeyId', render: (v?: string) => v ? <Text code style={{ fontSize: 12 }}>{v}</Text> : '-' },
            { title: '时间', dataIndex: 'createdAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
          ]} />
      </SectionCard>
    ) },
    { key: 'monitor', label: '监控', children: (
      <SectionCard title="调用趋势（24h）" icon={<ApiOutlined />}>
        <ReactECharts option={{
          tooltip: { trigger: 'axis' },
          legend: { data: ['调用次数', '延迟(ms)'], top: 0, textStyle: { color: '#64748B', fontSize: 12 } },
          grid: { left: 40, right: 50, top: 40, bottom: 30 },
          xAxis: { type: 'category', data: apiCallTrend.map((t) => t.hour), axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
          yAxis: [
            { type: 'value', name: '调用次数', splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
            { type: 'value', name: '延迟ms', splitLine: { show: false }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
          ],
          series: [
            { name: '调用次数', type: 'bar', data: apiCallTrend.map((t) => t.calls), itemStyle: { color: '#0F4FD8' } },
            { name: '延迟(ms)', type: 'line', yAxisIndex: 1, smooth: true, data: apiCallTrend.map((t) => t.latency), itemStyle: { color: '#F59E0B' }, symbol: 'none' },
          ],
        }} style={{ height: 280 }} />
      </SectionCard>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        icon={<ApiOutlined />}
        title={api.apiPath}
        subtitle={<Space size={8}><Tag color="blue">v{api.currentVersion}</Tag><Text type="secondary" style={{ fontSize: 13 }}>{api.name}</Text></Space>}
        status={<Space size={10}><StatusBadge status={api.status} /><ClassificationBadge level={api.classification} /></Space>}
        breadcrumb={[{ path: '/dataservice/apis', label: 'API 市场' }, { label: api.apiPath }]}
        tabs={tabs}
        actions={[
          <Button key="new-ver" onClick={() => navigate('/dataservice/apis/new')}>新建版本</Button>,
          api.status === 'DEPRECATED' && <Tag color="warning">下线倒计时 23 天</Tag>,
          <Button key="offline" danger onClick={() => setOfflineOpen(true)}>下线</Button>,
        ]}
        meta={[
          { label: 'QPS 限制', value: api.qpsLimit },
          { label: '实际 QPS', value: api.qps ?? '-' },
          { label: '成功率', value: api.successRate ? `${api.successRate}%` : '-' },
          { label: '订阅方', value: api.subscriberCount ?? 0 },
          { label: '来源', value: api.sourceFqn },
          { label: '创建', value: api.createdAt.slice(0, 10) },
        ]}
        rightExtra={
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, alignItems: 'stretch' }}>
            <StatCard label="成功率" value={api.successRate ?? '-'} suffix="%" intent={api.successRate && api.successRate > 99 ? 'success' : 'warning'} style={{ padding: 12, minHeight: 78 }} />
            <StatCard label="订阅方" value={api.subscriberCount ?? 0} suffix="个" intent="brand" style={{ padding: 12, minHeight: 78 }} />
          </div>
        }
      />

      <DangerConfirm
        open={offlineOpen}
        title={`下线 ${api.apiPath}`}
        description="下线前需确认活跃订阅方已通知；下线后将进入宽限期，到期返 410"
        impacts={[
          { label: '活跃订阅方', value: api.subscriberCount ?? 0 },
          { label: '近 7 天调用', value: 12500 },
        ]}
        impactLevel="HIGH"
        confirmName={api.apiPath}
        onCancel={() => setOfflineOpen(false)}
        onConfirm={() => { setOfflineOpen(false); message.success('已进入下线流程，订阅方已通知'); navigate('/dataservice/apis'); }}
      />
    </>
  );
}
