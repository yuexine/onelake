/**
 * 采集任务列表（对应原型 §4.2.1 / §8.2.4 升级版）。
 */
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Table, Space, Button, Input, Select, Tooltip, message,
  Dropdown, Tag, Typography,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, PlayCircleOutlined, EditOutlined,
  EllipsisOutlined, PauseCircleOutlined, DeleteOutlined, CopyOutlined,
  CloudSyncOutlined, DatabaseOutlined, FileTextOutlined, HourglassOutlined,
} from '@ant-design/icons';
import { syncTasks } from '../../mock';
import {
  PageHeader, FilterBar, SectionCard, Toolbar, StatusBadge, StateView,
  IntentBadge, useAsyncAction, modeColor,
} from '../../components';

const { Text } = Typography;

const MODE_META: Record<string, { icon: React.ReactNode; label: string }> = {
  FULL:        { icon: <DatabaseOutlined />,   label: '全量' },
  INCREMENTAL: { icon: <HourglassOutlined />,  label: '增量' },
  CDC:         { icon: <CloudSyncOutlined />,  label: 'CDC' },
  FILE:        { icon: <FileTextOutlined />,   label: '文件' },
};

export default function SyncTaskList() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [mode, setMode] = useState<string>();
  const [status, setStatus] = useState<string>();
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);

  const rows = useMemo(() => syncTasks.filter((t) =>
    (!mode || t.mode === mode) &&
    (!status || t.status === status) &&
    (!keyword || t.name.toLowerCase().includes(keyword.toLowerCase()) || t.targetTable.includes(keyword)),
  ), [mode, status, keyword]);

  const counts = useMemo(() => ({
    total: syncTasks.length,
    enabled: syncTasks.filter((t) => t.status === 'ENABLED').length,
    draft: syncTasks.filter((t) => t.status === 'DRAFT').length,
    cdc: syncTasks.filter((t) => t.mode === 'CDC').length,
  }), []);

  const { run, isLoading } = useAsyncAction();

  const handleTrigger = (id: string, name: string) => {
    run(`trigger-${id}`, async () => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return { runId: 'mock-' + Date.now() };
    }, {
      successMsg: `已触发 ${name} · runId 已生成`,
      errorMsg: `触发失败，请检查任务状态`,
      duration: 2.5,
    });
  };

  const columns = [
    {
      title: '任务名', dataIndex: 'name',
      render: (n: string, r: any) => (
        <Space size={10}>
          {(() => {
            const m = MODE_META[r.mode] ?? MODE_META.FULL;
            const intent = modeColor[r.mode] || 'neutral';
            return (
              <div style={{
                width: 30, height: 30, borderRadius: 7, display: 'inline-flex',
                alignItems: 'center', justifyContent: 'center',
                background: `var(--ol-${intent === 'neutral' ? 'fill' : intent === 'brand' ? 'brand' : intent === 'info' ? 'info' : intent === 'warning' ? 'warning' : 'fill'}-soft)`,
                color: intent === 'neutral' ? 'var(--ol-ink-3)' : intent === 'brand' ? 'var(--ol-brand)' : intent === 'info' ? '#0369A1' : '#B45309',
                fontSize: 14,
              }}>{m.icon}</div>
            );
          })()}
          <div style={{ minWidth: 0 }}>
            <a className="ol-link ol-truncate" style={{ display: 'inline-block', maxWidth: 220, fontSize: 13, fontWeight: 500 }}
              onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>{n}</a>
            <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }} className="ol-truncate">
              <Text code style={{ fontSize: 11 }}>{r.targetTable}</Text>
            </div>
          </div>
        </Space>
      ),
    },
    {
      title: '源', dataIndex: 'sourceName',
      render: (s: string) => <span className="ol-chip">{s}</span>,
    },
    {
      title: '模式', dataIndex: 'mode', width: 100,
      render: (m: string) => {
        const meta = MODE_META[m] ?? MODE_META.FULL;
        return (
          <IntentBadge intent={modeColor[m] || 'neutral'} size="md">
            {meta.icon} {meta.label}
          </IntentBadge>
        );
      },
    },
    {
      title: '调度', dataIndex: 'scheduleCron', width: 140,
      render: (c: string) => c ? <Text code style={{ fontSize: 11 }}>{c}</Text> : <span className="ol-quiet">实时</span>,
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (s: string) => <StatusBadge status={s} />,
    },
    {
      title: '限流', dataIndex: 'rateLimit', width: 100,
      render: (r?: number) => r ? <span className="mono ol-quiet" style={{ fontSize: 12 }}>{r} rows/s</span> : <span className="ol-quiet">-</span>,
    },
    {
      title: '操作', key: 'actions', width: 130, fixed: 'right' as const,
      render: (_: unknown, r: any) => (
        <Space size={2}>
          <Tooltip title="触发运行">
            <Button
              size="small" type="text"
              icon={<PlayCircleOutlined style={{ color: 'var(--ol-success)' }} />}
              loading={isLoading(`trigger-${r.id}`)}
              onClick={() => handleTrigger(r.id, r.name)}
            />
          </Tooltip>
          <Tooltip title="暂停">
            <Button size="small" type="text" icon={<PauseCircleOutlined />} onClick={() => message.success('已暂停')} />
          </Tooltip>
          <Button size="small" type="link" onClick={() => navigate(`/integration/sync-tasks/${r.id}`)}>详情</Button>
          <Dropdown trigger={['click']} menu={{
            items: [
              { key: 'edit', icon: <EditOutlined />, label: '编辑' },
              { key: 'copy', icon: <CopyOutlined />, label: '复制' },
              { type: 'divider' as const },
              { key: 'del', icon: <DeleteOutlined />, label: '删除', danger: true },
            ],
            onClick: ({ key }) => message.success(`${key} · ${r.name}`),
          }}>
            <Button size="small" type="text" icon={<EllipsisOutlined />} />
          </Dropdown>
        </Space>
      ),
    },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CloudSyncOutlined />}
        title="采集任务"
        subtitle={<span className="ol-chip">数据集成 · L1</span>}
        description="管理源 → ODS 的批 / 增 / CDC / 文件四类采集任务，统一调度与限流"
        meta={[
          { label: '总任务', value: counts.total },
          { label: '已启用', value: counts.enabled },
          { label: '草稿', value: counts.draft },
          { label: 'CDC 实时', value: counts.cdc },
        ]}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => message.success('已刷新')}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/integration/sync-tasks/new')}>新建采集任务</Button>
          </>
        }
      />

      <FilterBar
        search={{ placeholder: '搜索任务名 / 目标表', value: keyword, onChange: setKeyword, width: 280 }}
        filters={
          <>
            <Select placeholder="采集模式" allowClear style={{ width: 140 }} value={mode} onChange={setMode}
              options={['FULL', 'INCREMENTAL', 'CDC', 'FILE'].map((m) => ({ label: MODE_META[m].label + ' · ' + m, value: m }))} />
            <Select placeholder="状态" allowClear style={{ width: 120 }} value={status} onChange={setStatus}
              options={['DRAFT', 'ENABLED', 'PAUSED'].map((s) => ({ label: s, value: s }))} />
          </>
        }
        summary={<span className="ol-quiet" style={{ fontSize: 12 }}>共 {rows.length} 条</span>}
        onReset={() => { setKeyword(''); setMode(undefined); setStatus(undefined); }}
      />

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div style={{ padding: '8px 16px 0' }}>
          <Toolbar
            selectedCount={selectedKeys.length}
            bulkActions={[
              <Button size="small" icon={<PlayCircleOutlined />} onClick={() => message.success(`已批量触发 ${selectedKeys.length}`)}>批量触发</Button>,
              <Button size="small" icon={<PauseCircleOutlined />} onClick={() => message.success(`已批量暂停 ${selectedKeys.length}`)}>批量暂停</Button>,
              <Button size="small" icon={<DeleteOutlined />} danger onClick={() => message.warning('批量删除需确认')}>批量删除</Button>,
            ]}
            right={<span className="ol-quiet" style={{ fontSize: 12 }}>显示 {rows.length} / {syncTasks.length}</span>}
          />
        </div>
        <Table
          rowKey="id" columns={columns} dataSource={rows}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无采集任务"
                description="从模板生成或新建任务，将源数据接入 ODS 层"
                cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/integration/sync-tasks/new')}>新建采集任务</Button>}
              />
            ),
          }}
          pagination={{ pageSize: 20, showTotal: (t) => <span className="ol-quiet" style={{ fontSize: 12 }}>共 {t} 条</span> }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          size="middle" scroll={{ x: 1000 }}
        />
      </SectionCard>
    </div>
  );
}
