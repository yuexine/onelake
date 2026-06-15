/**
 * API 详情（对应原型 §8.8.3）。
 * Tab: 文档 / 版本 / 调试 / 订阅方 / 监控
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Tabs, Tag, Space, Button, Typography, Input, Table, message } from 'antd';
import { CloudOutlined, ApiOutlined, FileTextOutlined, CodeOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { apis, apiVersions, subscriptions, apiCallTrend } from '../../mock';
import { DetailPageLayout, StatusBadge, ClassificationBadge, DangerConfirm, SectionCard, StatCard, useAsyncAction } from '../../components';
import ReactECharts from 'echarts-for-react';

const { Text } = Typography;

export default function ApiDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const api = apis.find((a) => a.id === id) || apis[0];
  const [offlineOpen, setOfflineOpen] = useState(false);
  const { run, isLoading } = useAsyncAction();

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
        <Space>
          <Text style={{ fontSize: 13 }}>order_id =</Text>
          <Input defaultValue="1001" style={{ width: 140 }} />
          <Button
            type="primary"
            loading={isLoading('debug-send')}
            onClick={() => run('debug-send', async () => {
              await new Promise((r) => setTimeout(r, 600));
              return { order_id: 1001, phone: '138****8888', amount: 99.0 };
            }, {
              successMsg: '200 OK · 响应正常',
              errorMsg: '调试失败，请检查 API 是否可用',
              duration: 2.5,
            })}
          >
            发送
          </Button>
        </Space>
        <pre style={{
          marginTop: 12, padding: 14, background: 'var(--ol-ink)', color: 'var(--ol-card)',
          borderRadius: 8, fontSize: 12, fontFamily: 'monospace', lineHeight: 1.6,
        }}>{
          '200 OK:\n{"order_id": 1001, "phone": "138****8888", "amount": 99.0}\n\n(按调用方角色动态脱敏 L5-3.1.4)'
        }</pre>
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
