/**
 * 连接详情（对应原型 §8.2.9 升级版）。
 *   多 Tab：概览 / 连通历史 / 使用中任务 / 密钥 / 变更历史
 *   右侧 sticky 元信息卡 + RTT 趋势 sparkline
 */
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Tag, Space, Button, Table, Timeline, Typography,
  message, Descriptions, Tooltip,
} from 'antd';
import {
  ThunderboltOutlined, EditOutlined, DeleteOutlined, PlusOutlined,
  DatabaseOutlined, SafetyCertificateOutlined, ClockCircleOutlined,
  ApiOutlined, HistoryOutlined, KeyOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useEffect, useState, type ReactNode } from 'react';
import {
  DetailPageLayout, DangerConfirm, StatusBadge, EntityTypeIcon,
  SectionCard, StatCard,
} from '../../components';
import { dataSources, syncTasks } from '../../mock';
import type { DataSource, SyncTask } from '../../types';
import { IntegrationAPI } from '../../api';

const { Text } = Typography;

function Sparkline({ data, color }: { data: number[]; color: string }) {
  const w = 200, h = 36;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const step = w / (data.length - 1);
  const pts = data.map((v, i) => `${i * step},${h - ((v - min) / range) * (h - 4) - 2}`).join(' ');
  const area = `0,${h} ${pts} ${w},${h}`;
  return (
    <svg width={w} height={h} style={{ display: 'block' }}>
      <polygon points={area} fill={color} opacity={0.08} />
      <polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  );
}

export default function DatasourceDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [ds, setDs] = useState<DataSource>(dataSources.find((d) => d.id === id) || dataSources[0]);
  const [usingTasks, setUsingTasks] = useState<SyncTask[]>(syncTasks.filter((t) => t.sourceId === ds.id));
  const [confirmOpen, setConfirmOpen] = useState(false);

  useEffect(() => {
    if (!id) return;
    IntegrationAPI.getDatasource(id)
      .then((next) => {
        setDs(next);
        return IntegrationAPI.listSyncTasksBySource(next.id);
      })
      .then(setUsingTasks)
      .catch((e) => message.error(e.message || '连接详情加载失败'));
  }, [id]);

  const handleTest = () => {
    IntegrationAPI.testDatasource(ds.id)
      .then((result) => {
        setDs((current) => ({
          ...current,
          health: result.ok ? 'OK' : 'FAIL',
          rttMs: result.rttMillis,
          lastCheckAt: new Date().toISOString(),
        }));
        if (result.ok) message.success(`已测连 (RTT ${result.rttMillis ?? '-'}ms)`);
        else message.error(result.message || '连接失败');
      })
      .catch((e) => message.error(e.message || '测连失败'));
  };

  const handleDelete = () => {
    IntegrationAPI.deleteDatasource(ds.id)
      .then(() => {
        setConfirmOpen(false);
        message.success('已删除');
        navigate('/integration/datasources');
      })
      .catch((e) => message.error(e.message || '删除失败'));
  };

  const tabs = [
    {
      key: 'overview', label: '概览',
      children: (
        <SectionCard title="连接信息" icon={<DatabaseOutlined />}>
          <Descriptions column={2} size="middle" labelStyle={{ width: 120, color: 'var(--ol-ink-3)', fontSize: 12 }} contentStyle={{ fontSize: 13 }}>
            <Descriptions.Item label="类型"><Space size={8}><EntityTypeIcon kind={ds.type} size={22} /><Text strong>{ds.type}</Text></Space></Descriptions.Item>
            <Descriptions.Item label="Host"><Text code style={{ fontSize: 12 }}>{ds.host}:{ds.port}</Text></Descriptions.Item>
            <Descriptions.Item label="库名">{ds.dbName || '-'}</Descriptions.Item>
            <Descriptions.Item label="账号">{ds.username}</Descriptions.Item>
            <Descriptions.Item label="网络">{ds.networkMode}</Descriptions.Item>
            <Descriptions.Item label="环境">
              <span className="ol-chip" style={{
                background: ds.envLevel === 'PROD' ? 'var(--ol-error-soft)' : 'var(--ol-warning-soft)',
                color: ds.envLevel === 'PROD' ? 'var(--ol-error)' : '#B45309',
                border: 'none',
              }}>{ds.envLevel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="租户 / 项目">交易事业部 / 订单域</Descriptions.Item>
            <Descriptions.Item label="最近检查">{ds.lastCheckAt ? new Date(ds.lastCheckAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
          </Descriptions>
        </SectionCard>
      ),
    },
    {
      key: 'connectivity', label: '连通历史',
      children: (
        <SectionCard title="近 24 小时探活" icon={<SafetyCertificateOutlined />} subtitle="平均 RTT 23ms · 1 次失败（鉴权）"
          extra={<Tag color={ds.health === 'OK' ? 'success' : 'error'}>{ds.health}</Tag>}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', gap: 4 }}>
              {Array.from({ length: 24 }).map((_, i) => {
                const ok = i !== 8;
                return (
                  <Tooltip key={i} title={`${i}:00 ~ ${i + 1}:00  ·  ${ok ? '正常' : '失败'}`}>
                    <div
                      style={{
                        height: 32,
                        borderRadius: 4,
                        background: ok ? 'var(--ol-success-soft)' : 'var(--ol-error-soft)',
                        border: `1px solid ${ok ? '#BBF7D0' : '#FCA5A5'}`,
                        position: 'relative',
                        cursor: 'pointer',
                        transition: 'transform var(--ol-dur-fast) var(--ol-ease)',
                      }}
                      onMouseEnter={(e) => (e.currentTarget.style.transform = 'scaleY(1.15)')}
                      onMouseLeave={(e) => (e.currentTarget.style.transform = 'scaleY(1)')}
                    >
                      <div
                        style={{
                          position: 'absolute', inset: 0, margin: 'auto',
                          width: 4, height: 4, borderRadius: '50%',
                          background: ok ? 'var(--ol-success)' : 'var(--ol-error)',
                        }}
                      />
                    </div>
                  </Tooltip>
                );
              })}
            </div>
            <div>
              <div style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginBottom: 6 }}>RTT 趋势 (ms)</div>
              <Sparkline data={[18, 22, 19, 25, 21, 24, 23, 5012, 22, 20, 19, 18, 24, 26, 23, 21, 22, 25, 23, 22, 19, 24, 23, 22]} color={ds.health === 'OK' ? 'var(--ol-success)' : 'var(--ol-error)'} />
            </div>
          </Space>
        </SectionCard>
      ),
    },
    {
      key: 'using', label: `使用中任务`, badge: usingTasks.length,
      children: (
        <SectionCard title={`使用该连接的采集任务（${usingTasks.length}）`} icon={<ApiOutlined />} flatBody>
          <Table size="middle" rowKey="id" dataSource={usingTasks} pagination={false}
            columns={[
              { title: '任务名', dataIndex: 'name', render: (n: string, r: any) => (
                <a className="ol-link" onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>{n}</a>
              ) },
              { title: '模式', dataIndex: 'mode', render: (m: string) => <span className="ol-chip">{m}</span> },
              { title: '目标', dataIndex: 'targetTable', render: (t: string) => <Text code style={{ fontSize: 12 }}>{t}</Text> },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
            ]}
          />
        </SectionCard>
      ),
    },
    {
      key: 'secret', label: '密钥',
      children: (
        <SectionCard title="密钥引用 (KMS)" icon={<KeyOutlined />}
          extra={<Button size="small" type="primary" ghost icon={<ReloadOutlined />} onClick={() => message.success('已触发轮换（热更新，不中断任务）')}>立即轮换</Button>}
        >
          <Descriptions column={1} size="middle" labelStyle={{ width: 140, color: 'var(--ol-ink-3)', fontSize: 12 }} contentStyle={{ fontSize: 13 }}>
            <Descriptions.Item label="引用 ref"><Text code>ds/order_db/pwd</Text></Descriptions.Item>
            <Descriptions.Item label="KMS Key"><Text code>cmk-order-v3</Text></Descriptions.Item>
            <Descriptions.Item label="上次轮换">2026-06-01</Descriptions.Item>
            <Descriptions.Item label="下次计划">2026-09-01</Descriptions.Item>
          </Descriptions>
        </SectionCard>
      ),
    },
    {
      key: 'changes', label: '变更历史',
      children: (
        <SectionCard title="变更历史" icon={<HistoryOutlined />}>
          <Timeline
            items={[
              { color: 'blue',   children: <Space direction="vertical" size={0}><Text strong>密钥轮换</Text><Text type="secondary" style={{ fontSize: 12 }}>2026-06-01 12:00 · 张三</Text></Space> },
              { color: 'gray',   children: <Space direction="vertical" size={0}><Text>连接池 maxPoolSize 10 → 20</Text><Text type="secondary" style={{ fontSize: 12 }}>2026-05-15 18:00 · 张三</Text></Space> },
              { color: 'gray',   children: <Space direction="vertical" size={0}><Text>创建连接</Text><Text type="secondary" style={{ fontSize: 12 }}>2026-03-01 10:00 · 系统</Text></Space> },
            ]}
          />
        </SectionCard>
      ),
    },
  ];

  return (
    <>
      <DetailPageLayout
        icon={<EntityTypeIcon kind={ds.type} size={42} rounded={10} />}
        title={ds.name}
        subtitle={<Space size={8}><span className="ol-chip">{ds.type}</span><Text type="secondary" style={{ fontSize: 13 }}>{ds.host}:{ds.port} · {ds.dbName}</Text></Space>}
        status={<StatusBadge status={ds.health === 'OK' ? 'SUCCEEDED' : 'FAILED'} label={ds.health === 'OK' ? `连通 · ${ds.rttMs}ms` : '异常'} pulsing={false} />}
        breadcrumb={[{ path: '/integration/datasources', label: '连接管理' }, { label: ds.name }]}
        tabs={tabs}
        actions={[
          <Button key="test" icon={<ThunderboltOutlined />} onClick={handleTest}>测连</Button>,
          <Button key="edit" icon={<EditOutlined />}>编辑</Button>,
          <Button key="new-task" type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/integration/sync-tasks/new?sourceId=${ds.id}`)}>基于此连接建采集</Button>,
          <Button key="del" danger icon={<DeleteOutlined />} onClick={() => setConfirmOpen(true)}>删除</Button>,
        ]}
        meta={[
          { label: '负责人', value: ds.username },
          { label: '环境', value: ds.envLevel },
          { label: '网络模式', value: ds.networkMode },
          { label: '使用中任务', value: usingTasks.length },
          { label: '创建时间', value: ds.createdAt.slice(0, 10) },
        ]}
        rightExtra={
          <div className="ol-inline-stats" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, alignItems: 'stretch' }}>
            <StatCard label="RTT" value={ds.rttMs ?? '-'} suffix="ms" intent={ds.health === 'OK' ? 'success' : 'error'} style={{ padding: 12, minHeight: 78 }} />
            <StatCard label="使用任务" value={usingTasks.length} suffix="个" intent="brand" style={{ padding: 12, minHeight: 78 }} />
          </div>
        }
      />

      <DangerConfirm
        open={confirmOpen}
        title={`删除连接 ${ds.name}`}
        description="该操作不可恢复。请确认该连接没有正在运行的采集任务。"
        confirmName={ds.name}
        impacts={[
          { label: '关联任务', value: usingTasks.length },
          { label: '活跃运行', value: 0 },
        ]}
        impactLevel={usingTasks.length > 0 ? 'HIGH' : 'LOW'}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={handleDelete}
      />
    </>
  );
}
