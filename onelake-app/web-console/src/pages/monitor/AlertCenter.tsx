/**
 * 告警中心（对应原型 §8.9.1 / §8.9.2 升级版）。
 */
import { Table, Tag, Space, Button, Tabs, Drawer, Timeline, Typography, message } from 'antd';
import { AlertOutlined, BellOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { opsAlerts } from '../../mock';
import { PageHeader, SectionCard, StateView, IntentBadge } from '../../components';

const { Text } = Typography;

export default function AlertCenter() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [drawerId, setDrawerId] = useState<string | undefined>(id);
  const [activeTab, setActiveTab] = useState('all');

  const alerts = opsAlerts;
  const current = alerts.find((a) => a.id === drawerId);

  const counts = {
    total: alerts.length,
    p0: alerts.filter((a) => a.level === 'P0').length,
    p1: alerts.filter((a) => a.level === 'P1').length,
    p2: alerts.filter((a) => a.level === 'P2').length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AlertOutlined />}
        title="告警中心"
        subtitle={<span className="ol-chip">运营 · L9-1</span>}
        description="P0/P1/P2 三级告警，支持静默规则、转工单、闭环跟踪"
        actions={<Button icon={<BellOutlined />}>静默规则</Button>}
      />

      <SectionCard
        title="告警列表"
        icon={<AlertOutlined />}
        flatBody
        extra={
          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            items={[
              { key: 'all', label: `全部 (${counts.total})` },
              { key: 'P0', label: `P0 (${counts.p0})` },
              { key: 'P1', label: `P1 (${counts.p1})` },
              { key: 'P2', label: `P2 (${counts.p2})` },
            ]}
          />
        }
      >
        <Table
          rowKey="id"
          dataSource={activeTab === 'all' ? alerts : alerts.filter((a) => a.level === activeTab)}
          locale={{
            emptyText: <StateView state="empty" title={activeTab === 'all' ? '暂无告警' : `${activeTab} 告警已清零`} description="系统运行正常，无需关注" />,
          }}
          size="middle"
          pagination={false}
          columns={[
            { title: '级别', dataIndex: 'level', width: 80, render: (l: string) => (
              <IntentBadge intent={l === 'P0' ? 'error' : l === 'P1' ? 'warning' : 'neutral'} solid={l === 'P0'}>{l}</IntentBadge>
            ) },
            { title: '来源', dataIndex: 'source', render: (s: string) => <span className="ol-chip">{s}</span> },
            { title: '告警', dataIndex: 'title' },
            { title: '时间', dataIndex: 'createdAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
            { title: '认领', dataIndex: 'assignee', render: (a?: string) => a ? <Tag color="processing" style={{ margin: 0 }}>{a}</Tag> : <Button size="small" type="link">认领</Button> },
            { title: '操作', width: 80, render: (_: unknown, r: any) => (
              <Button size="small" type="link" onClick={() => setDrawerId(r.id)}>处理</Button>
            ) },
          ]}
        />
      </SectionCard>

      <Drawer
        open={!!current}
        onClose={() => setDrawerId(undefined)}
        title={current ? `告警详情 · ${current.title}` : ''}
        width={680}
        extra={
          <Space>
            <Button onClick={() => message.success('已静默 2h')}>静默 2h</Button>
            <Button>转工单</Button>
            <Button type="primary" onClick={() => message.success('已关闭')}>关闭</Button>
          </Space>
        }
      >
        {current && (
          <>
            <div className="ol-section" style={{ padding: 14, marginBottom: 12 }}>
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>级别</Text>
                  <div style={{ marginTop: 4 }}>
                    <IntentBadge size="md" solid={current.level === 'P0'} intent={current.level === 'P0' ? 'error' : 'warning'}>{current.level}</IntentBadge>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>来源 / 规则</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space split={<span className="ol-divider-v" />}>
                      <span>{current.source}</span>
                      <Text code style={{ fontSize: 12 }}>{current.rule}</Text>
                    </Space>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>关联</Text>
                  <div style={{ marginTop: 4 }}><Text code style={{ fontSize: 12 }}>{current.relatedRunId || current.relatedApi}</Text></div>
                </div>
              </Space>
            </div>

            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 8 }}>处理时间线</div>
            <Timeline
              items={[
                { color: 'red',    children: <Space direction="vertical" size={0}><Text>{current.createdAt} · 触发</Text></Space> },
                { color: 'orange', children: <Text>通知值班（电话 + 钉钉）</Text> },
                { color: 'blue',   children: <Text>已认领（张三）</Text> },
                { color: 'blue',   children: <Text>重试中…</Text> },
              ]}
            />

            <Space style={{ marginTop: 16 }}>
              <Button onClick={() => navigate('/integration/sync-tasks')}>直达 run</Button>
              <Button onClick={() => navigate('/catalog/lineage')}>查看血缘影响</Button>
            </Space>
          </>
        )}
      </Drawer>
    </div>
  );
}
