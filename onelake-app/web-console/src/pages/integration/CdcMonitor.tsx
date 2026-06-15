/**
 * CDC 实时监控（对应原型 §8.2.7 升级版）。
 *   - KPI 行（位点 / 延迟 / 背压 / 状态）
 *   - Tabs: 位点与延迟 / Schema 演进 / 运行日志
 */
import {
  Table, Tag, Space, Button, Typography, Descriptions, Timeline, message,
} from 'antd';
import {
  PauseCircleOutlined, ReloadOutlined, CloudSyncOutlined, FieldTimeOutlined,
  ApartmentOutlined, FileTextOutlined, ApiOutlined,
} from '@ant-design/icons';
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  PageHeader, StatusBadge, SectionCard, EntityTypeIcon, useAsyncAction, DangerConfirm,
} from '../../components';
import { schemaChangeRequests } from '../../mock';
import { IntegrationAPI } from '../../api';

const { Text } = Typography;

function Sparkline({ data, color }: { data: number[]; color: string }) {
  const w = 360, h = 60;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const step = w / (data.length - 1);
  const pts = data.map((v, i) => `${i * step},${h - ((v - min) / range) * (h - 4) - 2}`).join(' ');
  const area = `0,${h} ${pts} ${w},${h}`;
  return (
    <svg width={w} height={h} style={{ display: 'block' }}>
      <polygon points={area} fill={color} opacity={0.10} />
      <polyline points={pts} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" />
    </svg>
  );
}

export default function CdcMonitor() {
  const navigate = useNavigate();
  const { run, isLoading } = useAsyncAction();
  const [rebuildOpen, setRebuildOpen] = useState(false);
  const [cdcTask, setCdcTask] = useState<any>(null);
  const [cdcStatus, setCdcStatus] = useState<any>(null);

  useEffect(() => {
    IntegrationAPI.listCdcTasks()
      .then((tasks: any[]) => {
        if (tasks && tasks.length > 0) {
          setCdcTask(tasks[0]);
          return IntegrationAPI.getCdcStatus(tasks[0].id);
        }
      })
      .then((status: any) => { if (status) setCdcStatus(status); })
      .catch(() => {});
  }, []);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CloudSyncOutlined />}
        title="CDC 实时监控"
        subtitle={<Space size={8}><EntityTypeIcon kind="MYSQL" size={20} /><span className="ol-chip">{cdcTask?.sourceName || 'mysql_orders_cdc'}</span></Space>}
        description="Binlog 订阅 · 初始快照 + 增量衔接 · 位点原子持久化 · 两阶段 Exactly-Once"
        meta={[
          { label: '当前位点', value: <Text code style={{ fontSize: 12 }}>{cdcStatus?.checkpoint || cdcTask?.checkpoint || 'binlog.000128 : 4456'}</Text> },
          { label: '状态', value: <StatusBadge status={cdcTask?.status || 'RUNNING'} label={cdcTask?.status === 'PAUSED' ? '已暂停' : '运行中'} /> },
        ]}
        actions={
          <>
            <Button
              icon={<PauseCircleOutlined />}
              loading={isLoading('pause')}
              onClick={() => run('pause', async () => {
                await new Promise((r) => setTimeout(r, 500));
              }, { successMsg: 'CDC 管道已暂停，增量数据将缓冲', duration: 2.5 })}
            >
              暂停
            </Button>
            <Button
              type="primary" ghost icon={<ReloadOutlined />}
              onClick={() => setRebuildOpen(true)}
            >
              重建快照
            </Button>
          </>
        }
      />

      <SectionCard
        title="延迟曲线（近 1 小时）"
        icon={<FieldTimeOutlined />}
        subtitle={`平均 ${cdcStatus?.lagMs ? (cdcStatus.lagMs / 1000).toFixed(1) : '1.2'}s · P99 5.6s · 背压${cdcStatus?.backpressure ? '压力较高' : '正常'}`}
      >
        <Sparkline data={[3, 2, 1, 2, 1, 1, 1, 2, 3, 4, 5, 6, 4, 3, 2, 1, 1, 1, 1, 2, 3, 5, 6, 5, 4, 3, 2, 1, 1, 1]} color="var(--ol-info)" />
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 11, color: 'var(--ol-ink-3)' }}>
          <span>60 min ago</span>
          <span>30 min ago</span>
          <span>now</span>
        </div>
      </SectionCard>

      <SectionCard
        title="Schema 演进审批队列"
        icon={<ApartmentOutlined />}
        subtitle="未审批期间按旧 schema 缓冲写入，不影响其他表"
        flatBody
      >
        <Table
          size="middle"
          rowKey="id"
          dataSource={schemaChangeRequests}
          pagination={false}
          columns={[
            { title: '时间', dataIndex: 'createdAt', render: (t: string) => (
              <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{new Date(t).toLocaleString('zh-CN')}</span>
            ) },
            { title: 'CDC 任务', dataIndex: 'sourceName', render: (s: string) => <span className="ol-chip">{s}</span> },
            { title: '表', dataIndex: 'table' },
            { title: '变更', dataIndex: 'change', render: (c: string) => <Text code style={{ fontSize: 12 }}>{c}</Text> },
            { title: '类型', dataIndex: 'type', render: (t: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: t === '破坏性' ? 'var(--ol-error-soft)' : 'var(--ol-success-soft)',
                color: t === '破坏性' ? 'var(--ol-error)' : 'var(--ol-success)',
              }}>{t}</span>
            ) },
            { title: '缓冲策略', dataIndex: 'bufferStrategy' },
            { title: '操作', render: (_: unknown, r: any) => r.status === 'PENDING' ? (
              <Button type="link" onClick={() => navigate(`/integration/schema-change/${r.id}`)}>审批</Button>
            ) : (
              <Tag color="success" style={{ margin: 0 }}>{r.status === 'AUTO_APPLIED' ? '已自动应用' : r.status}</Tag>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard
        title="运行日志"
        icon={<FileTextOutlined />}
        subtitle="最近 50 条"
      >
        <Timeline
          items={[
            { color: 'blue',   children: <Space direction="vertical" size={0}><Text>BinlogReader connect to <Text code>mysql-bin.000128:4456</Text></Text><Text type="secondary" style={{ fontSize: 11 }}>10:21:35 · INFO</Text></Space> },
            { color: 'blue',   children: <Space direction="vertical" size={0}><Text>Snapshot phase DONE, switch to incremental</Text><Text type="secondary" style={{ fontSize: 11 }}>10:21:36 · INFO</Text></Space> },
            { color: 'orange', children: <Space direction="vertical" size={0}><Text>DDL change detected: <Text code>ALTER TABLE users DROP COLUMN age</Text></Text><Text type="secondary" style={{ fontSize: 11 }}>09:50:21 · WARN</Text></Space> },
            { color: 'red',    children: <Space direction="vertical" size={0}><Text>Pipeline paused for users（破坏性变更），其他表继续</Text><Text type="secondary" style={{ fontSize: 11 }}>09:50:22 · ERROR</Text></Space> },
          ]}
        />
      </SectionCard>

      <DangerConfirm
        open={rebuildOpen}
        title="重建 CDC 快照"
        description="重建会重新读取源端初始全量，过程可能持续 5-10 分钟，期间订阅方会先消费历史增量再切换到新快照。"
        impacts={[
          { label: '待重建表', value: 1 },
          { label: '预计耗时', value: '5-10 min' },
          { label: '下游依赖', value: 3 },
        ]}
        impactLevel="MEDIUM"
        confirmName="重建快照"
        okText="确认重建"
        okType="primary"
        onCancel={() => setRebuildOpen(false)}
        onConfirm={() => run('rebuild-snapshot', async () => {
          await new Promise((r) => setTimeout(r, 800));
          setRebuildOpen(false);
        }, {
          successMsg: '已触发重建快照，初始全量将重新抽取',
          errorMsg: '触发重建失败，请检查位点状态',
          duration: 3,
        })}
      />
    </div>
  );
}
