/**
 * 运行实例（对应原型 §4.4.1 升级版）。
 */
import { useEffect, useMemo, useState } from 'react';
import { Alert, App as AntApp, Table, Tag, Space, Button, Typography } from 'antd';
import { ReloadOutlined, HistoryOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard, StateView } from '../../components';
import { OrchestrationAPI } from '../../api';
import type { JobRun } from '../../types';

const { Text } = Typography;

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function formatDuration(run: JobRun) {
  if (!run.startedAt || !run.finishedAt) return '-';
  const durationMs = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
  if (!Number.isFinite(durationMs) || durationMs < 0) return '-';
  if (durationMs < 60_000) return `${Math.max(1, Math.round(durationMs / 1000))}s`;
  return `${(durationMs / 60_000).toFixed(1)}m`;
}

export default function RunInstances() {
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [runs, setRuns] = useState<JobRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const loadRuns = (nextPage = page, nextSize = pageSize) => {
    setLoading(true);
    setError(null);
    OrchestrationAPI.listRuns(nextPage, nextSize)
      .then((result) => {
        setRuns(result.content || []);
        setPage(result.number ?? nextPage);
        setPageSize(result.size ?? nextSize);
        setTotal(result.totalElements ?? 0);
      })
      .catch((e) => {
        const msg = e.message || '运行实例加载失败';
        setError(msg);
        message.error(msg);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadRuns(0, pageSize);
  }, []);

  const counts = useMemo(() => ({
    total: runs.length,
    success: runs.filter((r) => r.status === 'SUCCESS' || r.status === 'SUCCEEDED').length,
    failed: runs.filter((r) => r.status === 'FAILED').length,
    cron: runs.filter((r) => r.triggerType === 'CRON').length,
  }), [runs]);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<HistoryOutlined />}
        title="运行实例"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="查看所有流水线运行历史，含触发方式、耗时、责任人"
        meta={[
          { label: '当前页', value: counts.total },
          { label: '成功', value: counts.success },
          { label: '失败', value: counts.failed },
          { label: '调度触发', value: counts.cron },
        ]}
        actions={<Button icon={<ReloadOutlined />} onClick={() => loadRuns()} loading={loading}>刷新</Button>}
      />

      <SectionCard title="运行历史" icon={<HistoryOutlined />} flatBody>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            action={<Button size="small" onClick={() => loadRuns()}>重试</Button>}
            style={{ margin: 12 }}
          />
        )}
        <Table
          rowKey="id"
          dataSource={runs}
          loading={loading}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无运行记录"
                description="触发流水线后，运行历史将自动出现"
              />
            ),
          }}
          size="middle"
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (value) => `共 ${value} 条`,
            onChange: (nextPage, nextPageSize) => loadRuns(nextPage - 1, nextPageSize),
          }}
          columns={[
            { title: 'Run ID', render: (_: unknown, r: JobRun) => <Text code style={{ fontSize: 12 }}>{r.dagsterRunId || r.id}</Text> },
            { title: '流水线', render: (_: unknown, r: JobRun) => (
              <div>
                <a className="ol-link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>{r.dagName || r.dagId}</a>
                {r.dagsterJob && <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{r.dagsterJob}</div>}
              </div>
            ) },
            { title: '触发方式', dataIndex: 'triggerType', width: 110, render: (t: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: t === 'CRON' ? 'var(--ol-brand-soft)' : 'var(--ol-fill-soft)',
                color: t === 'CRON' ? 'var(--ol-brand)' : 'var(--ol-ink-2)',
              }}>{t}</span>
            ) },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: '开始', dataIndex: 'startedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatDate(t)}</span> },
            { title: '结束', dataIndex: 'finishedAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-2)' }}>{formatDate(t)}</span> },
            { title: '耗时', width: 90, render: (_: unknown, r: JobRun) => <Tag style={{ margin: 0 }}>{formatDuration(r)}</Tag> },
            { title: '触发人', dataIndex: 'triggeredBy', width: 140, render: (b?: string) => (
              <span style={{ fontSize: 12, color: b ? 'var(--ol-ink)' : 'var(--ol-ink-3)' }}>{b || 'system'}</span>
            ) },
            { title: '操作', width: 120, render: (_: unknown, r: JobRun) => (
              <Space>
                <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.dagId}`)}>打开流水线</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
