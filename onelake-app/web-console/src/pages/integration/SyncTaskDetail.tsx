/**
 * 任务详情 + 运行历史（对应原型 §8.2.4 升级版）。
 *   Tab: 概览 / 配置 / 运行历史 / 血缘
 *   右侧 sticky 元信息卡 + RTT sparkline
 */
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Table, Tag, Space, Button, message, Typography,
} from 'antd';
import {
  PauseCircleOutlined, EditOutlined, ReloadOutlined, CloudSyncOutlined,
  FieldTimeOutlined, NodeIndexOutlined, ApartmentOutlined, BranchesOutlined,
} from '@ant-design/icons';
import { useEffect, useState } from 'react';
import {
  DetailPageLayout, StatusBadge, SectionCard, StatCard, ClassificationBadge,
} from '../../components';
import { syncTasks, syncRuns } from '../../mock';
import type { SyncRun, SyncTask } from '../../types';
import { IntegrationAPI } from '../../api';

const { Text } = Typography;

function Sparkline({ data, color }: { data: number[]; color: string }) {
  const w = 220, h = 40;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const step = w / (data.length - 1);
  const pts = data.map((v, i) => `${i * step},${h - ((v - min) / range) * (h - 4) - 2}`).join(' ');
  const area = `0,${h} ${pts} ${w},${h}`;
  return (
    <svg width={w} height={h} style={{ display: 'block' }}>
      <polygon points={area} fill={color} opacity={0.10} />
      <polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  );
}

export default function SyncTaskDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [sp] = useSearchParams();
  const initialTab = sp.get('tab') || 'overview';
  const [activeTab, setActiveTab] = useState(initialTab);
  const [task, setTask] = useState<SyncTask>(syncTasks.find((t) => t.id === id) || syncTasks[0]);
  const [runs, setRuns] = useState<SyncRun[]>(syncRuns.filter((r) => r.taskId === task.id));

  const loadTask = () => {
    if (!id) return;
    IntegrationAPI.getSyncTask(id)
      .then((next) => {
        setTask(next);
        return IntegrationAPI.listRuns(next.id);
      })
      .then((page) => setRuns(page.content || []))
      .catch((e) => message.error(e.message || '任务详情加载失败'));
  };

  useEffect(() => {
    loadTask();
  }, [id]);

  const handleTrigger = () => {
    IntegrationAPI.triggerSyncTask(task.id)
      .then(() => {
        message.success('已触发运行');
        loadTask();
      })
      .catch((e) => message.error(e.message || '触发运行失败'));
  };

  const handleDisable = () => {
    IntegrationAPI.disableSyncTask(task.id)
      .then((next) => {
        setTask(next);
        message.success('已暂停');
      })
      .catch((e) => message.error(e.message || '暂停失败'));
  };

  const successRate = runs.length > 0
    ? Math.round(runs.filter((r) => r.status === 'SUCCEEDED').length / runs.length * 100)
    : 100;

  const tabs = [
    {
      key: 'overview', label: '概览',
      children: (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <SectionCard title="任务概要" icon={<CloudSyncOutlined />}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {[
                { label: '源', value: <span className="ol-chip">{task.sourceName}</span> },
                { label: '模式', value: <span className="ol-chip">{task.mode}</span> },
                { label: '目标表', value: <Text code>{task.targetTable}</Text> },
                { label: '调度', value: task.scheduleCron ? <Text code>{task.scheduleCron}</Text> : <Text type="secondary">实时</Text> },
                { label: '限流', value: task.rateLimit ? <Text>{task.rateLimit} rows/s</Text> : <Text type="secondary">无</Text> },
              ].map((row) => (
                <div key={row.label} style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                  <Text style={{ color: 'var(--ol-ink-3)', width: 100, fontSize: 12, flexShrink: 0 }}>{row.label}</Text>
                  <div style={{ flex: 1, minWidth: 0 }}>{row.value}</div>
                </div>
              ))}
            </div>
          </SectionCard>

          {task.fieldMapping && task.fieldMapping.length > 0 && (
            <SectionCard title="字段映射" icon={<NodeIndexOutlined />} subtitle={`${task.fieldMapping.length} 列 · 含密级标记`}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 8 }}>
                {task.fieldMapping.map((f) => (
                  <div key={f.source} style={{ padding: '8px 12px', background: 'var(--ol-fill-soft)', borderRadius: 6, border: '1px solid var(--ol-line-soft)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                      <Text strong style={{ fontSize: 12 }}>{f.source}</Text>
                      {f.classification && <ClassificationBadge level={f.classification} size="small" />}
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--ol-ink-3)', marginTop: 2 }}>
                      {f.sourceType} → <Text code style={{ fontSize: 11 }}>{f.targetType}</Text>
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}
        </Space>
      ),
    },
    {
      key: 'config', label: '配置',
      children: (
        <SectionCard title="字段映射（完整配置）" icon={<NodeIndexOutlined />} flatBody>
          <Table size="middle" pagination={false} rowKey="source"
            dataSource={task.fieldMapping || []}
            columns={[
              { title: '源字段', dataIndex: 'source', render: (v: string, r: any) => (
                <Space size={6}><Text strong>{v}</Text>{r.classification && <ClassificationBadge level={r.classification} size="small" />}</Space>
              ) },
              { title: '源类型', dataIndex: 'sourceType', render: (t: string) => <Text code>{t}</Text> },
              { title: '目标字段', dataIndex: 'target' },
              { title: '目标类型', dataIndex: 'targetType', render: (t: string) => <Text code>{t}</Text> },
              { title: '兼容性', dataIndex: 'compatible', render: (c?: boolean) => c ? (
                <Tag color="success" style={{ margin: 0 }}>✓ 兼容</Tag>
              ) : (
                <Tag color="warning" style={{ margin: 0 }}>⚠ 类型转换建议</Tag>
              ) },
              { title: '脱敏', dataIndex: 'masked', render: (m?: boolean) => m ? <Tag color="processing" style={{ margin: 0 }}>已脱敏</Tag> : <Text type="secondary">-</Text> },
            ]}
          />
        </SectionCard>
      ),
    },
    {
      key: 'history', label: '运行历史', badge: runs.length,
      children: (
        <SectionCard title="运行实例" icon={<FieldTimeOutlined />} flatBody>
          <Table size="middle" rowKey="id" dataSource={runs} pagination={{ pageSize: 10 }}
            columns={[
              { title: 'Run ID', dataIndex: 'id', render: (v: string, r: any) => (
                <a className="ol-link" onClick={() => navigate(`/integration/sync-tasks/${task.id}/runs/${r.id}`)}>{v}</a>
              ) },
              { title: '开始时间', dataIndex: 'startedAt', render: (t: string) => (
                <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{new Date(t).toLocaleString('zh-CN')}</span>
              ) },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
              { title: '读取', dataIndex: 'rowsRead', align: 'right' as const, render: (v: number) => (
                <span className="mono tnum">{v.toLocaleString()}</span>
              ) },
              { title: '写入', dataIndex: 'rowsWritten', align: 'right' as const, render: (v: number) => (
                <span className="mono tnum">{v.toLocaleString()}</span>
              ) },
              { title: '耗时', dataIndex: 'durationMs', render: (d?: number) => d ? `${(d/1000).toFixed(1)}s` : '-' },
              { title: '吞吐', dataIndex: 'throughputRows', render: (t?: number) => t ? `${t.toLocaleString()}/s` : '-' },
              { title: '操作', render: (_: unknown, r: any) => (
                <Button size="small" type="link"
                  onClick={() => navigate(`/integration/sync-tasks/${task.id}/runs/${r.id}`)}>日志</Button>
              ) },
            ]}
          />
        </SectionCard>
      ),
    },
    {
      key: 'lineage', label: '血缘',
      children: (
        <SectionCard
          title="上下游血缘"
          icon={<ApartmentOutlined />}
          subtitle={`聚焦 ${task.targetTable}`}
          extra={<Button size="small" type="link" icon={<BranchesOutlined />} onClick={() => navigate(`/catalog/lineage?focus=${encodeURIComponent(task.targetTable)}&from=sync-task`)}>展开整页血缘 →</Button>}
        >
          <LineageMiniGraph sourceFqn={`${task.sourceName}.${task.targetTable.split('.')[1]}`} targetFqn={task.targetTable} />
        </SectionCard>
      ),
    },
  ];

  return (
    <DetailPageLayout
      icon={<CloudSyncOutlined />}
      title={task.name}
      activeTab={activeTab}
      onTabChange={setActiveTab}
      subtitle={<Space size={8}><span className="ol-chip">{task.mode}</span><Text type="secondary" style={{ fontSize: 13 }}>目标 {task.targetTable}</Text></Space>}
      status={<StatusBadge status={task.status} />}
      breadcrumb={[{ path: '/integration/sync-tasks', label: '采集任务' }, { label: task.name }]}
      tabs={tabs}
      actions={[
        <Button key="run" type="primary" icon={<ReloadOutlined />} onClick={handleTrigger}>触发运行</Button>,
        <Button key="pause" icon={<PauseCircleOutlined />} onClick={handleDisable}>暂停</Button>,
        <Button key="edit" icon={<EditOutlined />}>编辑</Button>,
      ]}
      meta={[
        { label: '源', value: task.sourceName },
        { label: '模式', value: task.mode },
        { label: '目标表', value: task.targetTable },
        { label: '调度', value: task.scheduleCron || '实时' },
        { label: '限流', value: `${task.rateLimit || '-'} rows/s` },
        { label: '创建时间', value: task.createdAt.slice(0, 10) },
      ]}
      rightExtra={
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, alignItems: 'stretch' }}>
          <StatCard label="成功率" value={successRate} suffix="%" intent={successRate >= 95 ? 'success' : 'warning'} style={{ padding: 12, minHeight: 78 }} />
          <StatCard label="运行次数" value={runs.length} suffix="次" intent="brand" style={{ padding: 12, minHeight: 78 }} />
        </div>
      }
    />
  );
}

/**
 * 简易血缘小图：上游源 → 当前表 → 假设下游（dws/ads）
 * 真实数据需通过 catalog.lineage API 加载（后续接入）。
 */
function LineageMiniGraph({ sourceFqn, targetFqn }: { sourceFqn: string; targetFqn: string }) {
  const downstream = targetFqn.replace(/^ods\./, 'dws.') + ' (推断)';
  const nodes = [
    { fqn: sourceFqn, layer: '源', intent: 'neutral' as const },
    { fqn: targetFqn, layer: '当前', intent: 'brand' as const, highlight: true },
    { fqn: downstream, layer: '下游', intent: 'success' as const },
  ];
  return (
    <div style={{ padding: '12px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap', justifyContent: 'center' }}>
        {nodes.map((n, i) => (
          <div key={n.fqn} style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <div
              style={{
                padding: '10px 16px',
                borderRadius: 8,
                background: n.highlight ? 'var(--ol-brand-soft)' : 'var(--ol-fill-soft)',
                border: `1px solid ${n.highlight ? 'var(--ol-brand-border)' : 'var(--ol-line-soft)'}`,
                minWidth: 180,
              }}
            >
              <div style={{ fontSize: 11, color: 'var(--ol-ink-3)', marginBottom: 4 }}>{n.layer}</div>
              <Text code style={{ fontSize: 12, color: n.highlight ? 'var(--ol-brand)' : 'var(--ol-ink)' }}>{n.fqn}</Text>
            </div>
            {i < nodes.length - 1 && (
              <div style={{ fontSize: 22, color: 'var(--ol-brand)', lineHeight: 1 }}>→</div>
            )}
          </div>
        ))}
      </div>
      <div style={{ marginTop: 16, padding: 12, background: 'var(--ol-fill-soft)', borderRadius: 6, fontSize: 12, color: 'var(--ol-ink-3)' }}>
        💡 仅展示一级上下游；点击右上角"展开整页血缘"查看完整链路、字段级映射与影响分析
      </div>
    </div>
  );
}
