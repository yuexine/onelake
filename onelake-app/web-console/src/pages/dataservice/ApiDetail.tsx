/**
 * API 详情（对应原型 §8.8.3）。
 * Tab: 文档 / 版本 / 调试 / 订阅方 / 监控
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tabs, Tag, Space, Button, Typography, Input, Table, message } from 'antd';
import { useState } from 'react';
import { apis, apiVersions, subscriptions, apiCallTrend } from '../../mock';
import { DetailPageLayout, StatusBadge, ClassificationBadge, DangerConfirm } from '../../components';
import ReactECharts from 'echarts-for-react';

const { Text } = Typography;

export default function ApiDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const api = apis.find((a) => a.id === id) || apis[0];
  const [offlineOpen, setOfflineOpen] = useState(false);

  const tabs = [
    { key: 'doc', label: '文档', children: (
      <Card type="inner">
        <Space direction="vertical">
          <Text>路径：<Text code>/api{api.apiPath}</Text></Text>
          <Text>方法：GET</Text>
          <Text>描述：{api.description}</Text>
          <Text>SQL：<Text code>{api.selectSql}</Text></Text>
          <Button>下载 OpenAPI YAML</Button>
        </Space>
      </Card>
    ) },
    { key: 'versions', label: '版本', children: (
      <Table size="small" rowKey="id" dataSource={apiVersions.filter((v) => v.apiId === api.id)}
        columns={[
          { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
          { title: '发布时间', dataIndex: 'publishedAt' },
          { title: '弃用时间', dataIndex: 'deprecatedAt', render: (v?: string) => v || '-' },
          { title: '灰度', dataIndex: 'grayPercent', render: (v?: number) => v != null ? `${v}%` : '-' },
        ]} />
    ) },
    { key: 'debug', label: '调试', children: (
      <Card type="inner" title="在线调试">
        <Space><Text>order_id=</Text><Input defaultValue="1001" style={{ width: 120 }} /><Button type="primary" onClick={() => message.success('200 OK')}>发送</Button></Space>
        <pre style={{ marginTop: 12, background: '#f5f5f5', padding: 12 }}>200 OK:
&#123;"order_id":1001,"phone":"138****8888","amount":99.0&#125;

（按调用方角色动态脱敏 L5-3.1.4）</pre>
      </Card>
    ) },
    { key: 'subs', label: `订阅方 (${subscriptions.filter((s) => s.apiId === api.id).length})`, children: (
      <Table size="small" rowKey="id" dataSource={subscriptions.filter((s) => s.apiId === api.id)}
        columns={[
          { title: '订阅方', dataIndex: 'subscriberName' },
          { title: '原因', dataIndex: 'reason', ellipsis: true },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          { title: 'AppKey', dataIndex: 'appKeyId', render: (v?: string) => v ? <Text code>{v}</Text> : '-' },
          { title: '时间', dataIndex: 'createdAt' },
        ]} />
    ) },
    { key: 'monitor', label: '监控', children: (
      <>
        <Card size="small" title="调用趋势（24h）"><ReactECharts option={{
          xAxis: { type: 'category', data: apiCallTrend.map((t) => t.hour) },
          yAxis: [{ type: 'value', name: '调用次数' }, { type: 'value', name: '延迟ms' }],
          series: [
            { name: '调用次数', type: 'bar', data: apiCallTrend.map((t) => t.calls) },
            { name: '延迟', type: 'line', yAxisIndex: 1, data: apiCallTrend.map((t) => t.latency) },
          ],
        }} style={{ height: 240 }} /></Card>
      </>
    ) },
  ];

  return (
    <>
      <DetailPageLayout
        title={api.apiPath}
        subtitle={<Space><Tag color="blue">v{api.currentVersion}</Tag>{api.name}</Space>}
        status={<Space><StatusBadge status={api.status} /><ClassificationBadge level={api.classification} /></Space>}
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
