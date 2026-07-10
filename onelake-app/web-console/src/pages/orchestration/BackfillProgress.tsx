import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Descriptions,
  Popconfirm,
  Progress,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  CalendarOutlined,
  HistoryOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { BackfillAPI } from '../../api';
import { BizError } from '../../api/http';
import { PageHeader, SectionCard, StateView, StatusBadge } from '../../components';
import type { Backfill, BackfillGrain, BackfillRun } from '../../types';

const { Text } = Typography;
const POLL_INTERVAL_MS = 3000;

interface UiError {
  message: string;
  noPermission: boolean;
}

const grainLabels: Record<BackfillGrain, string> = {
  DAY: '按天',
  HOUR: '按小时',
  MONTH: '按月',
};

function isTerminal(status?: string) {
  return ['SUCCEEDED', 'FAILED', 'PARTIAL', 'CANCELLED'].includes((status ?? '').toUpperCase());
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function formatBusinessDate(value?: string) {
  if (!value) return '-';
  return `${new Date(value).toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

function shortId(value: string) {
  return value.length > 12 ? `${value.slice(0, 8)}...` : value;
}

function toUiError(error: unknown, fallback: string): UiError {
  const code = error instanceof BizError ? error.code : undefined;
  return {
    message: error instanceof Error && error.message ? error.message : fallback,
    noPermission: code === 403 || code === 40300,
  };
}

export default function BackfillProgress() {
  const { pipelineId, backfillId } = useParams<{ pipelineId: string; backfillId: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [backfill, setBackfill] = useState<Backfill | null>(null);
  const [loading, setLoading] = useState(false);
  const [canceling, setCanceling] = useState(false);
  const [error, setError] = useState<UiError | null>(null);

  const loadBackfill = useCallback(async (silent = false) => {
    if (!backfillId) return;
    if (!silent) setLoading(true);
    try {
      const result = await BackfillAPI.get(backfillId);
      setBackfill(result);
      setError(null);
    } catch (requestError) {
      setError(toUiError(requestError, '回填进度加载失败'));
    } finally {
      if (!silent) setLoading(false);
    }
  }, [backfillId]);

  useEffect(() => {
    void loadBackfill();
  }, [loadBackfill]);

  useEffect(() => {
    if (!backfill || isTerminal(backfill.status)) return undefined;
    const timer = window.setInterval(() => void loadBackfill(true), POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [backfill?.status, loadBackfill]);

  const counts = useMemo(() => {
    const runs = backfill?.runs ?? [];
    return {
      queued: runs.filter((run) => run.status === 'QUEUED').length,
      running: runs.filter((run) => run.status === 'RUNNING').length,
      cancelled: runs.filter((run) => run.status === 'CANCELLED').length,
    };
  }, [backfill?.runs]);

  const completed = backfill ? Math.min(backfill.total, backfill.succeeded + backfill.failed) : 0;
  const percent = backfill?.total ? Math.round((completed / backfill.total) * 100) : 0;

  const handleCancel = async () => {
    if (!backfillId) return;
    setCanceling(true);
    try {
      const result = await BackfillAPI.cancel(backfillId);
      setBackfill(result);
      setError(null);
      message.success('回填已取消，后续子 Run 将停止派发');
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '取消回填失败');
    } finally {
      setCanceling(false);
    }
  };

  if (!pipelineId || !backfillId) {
    return (
      <div className="ol-page">
        <StateView state="error" title="回填地址无效" description="缺少流水线或回填批次标识" />
      </div>
    );
  }

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CalendarOutlined />}
        title="回填进度"
        subtitle={<span className="ol-chip">编排 · 回填</span>}
        description={backfill ? `批次 ${backfill.id}` : '查看批次派发与子 Run 执行进度'}
        breadcrumb={[
          { path: '/orchestration/pipelines', label: '流水线' },
          { path: `/orchestration/pipelines/${pipelineId}`, label: '流水线编辑器' },
          { label: '回填进度' },
        ]}
        meta={backfill ? [
          { label: '状态', value: <StatusBadge status={backfill.status} /> },
          { label: '粒度', value: grainLabels[backfill.grain] },
          { label: '最大并发', value: backfill.max_parallel },
          { label: '更新时间', value: formatDate(backfill.updated_at) },
        ] : []}
        actions={(
          <Space size={8} wrap>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/orchestration/pipelines/${pipelineId}`)}>
              返回流水线
            </Button>
            {backfill && (
              <Button
                icon={<HistoryOutlined />}
                onClick={() => navigate(`/orchestration/runs?backfill_id=${encodeURIComponent(backfill.id)}`)}
              >
                查看子 Run
              </Button>
            )}
            {backfill && !isTerminal(backfill.status) && (
              <Popconfirm
                title="取消回填"
                description="将停止派发并取消仍在运行的子 Run，确认继续？"
                okText="取消回填"
                cancelText="保留"
                okButtonProps={{ danger: true, loading: canceling }}
                onConfirm={handleCancel}
              >
                <Button danger icon={<StopOutlined />} loading={canceling}>取消回填</Button>
              </Popconfirm>
            )}
            <Button icon={<ReloadOutlined />} onClick={() => void loadBackfill()} loading={loading}>刷新</Button>
          </Space>
        )}
      />

      {loading && !backfill ? (
        <SectionCard title="批次进度" icon={<CalendarOutlined />}>
          <StateView state="loading" rows={5} />
        </SectionCard>
      ) : error && !backfill ? (
        <SectionCard title="批次进度" icon={<CalendarOutlined />}>
          <StateView
            state={error.noPermission ? 'no-permission' : 'error'}
            title={error.noPermission ? '无权查看回填进度' : '回填进度加载失败'}
            description={error.message}
            onRetry={() => void loadBackfill()}
          />
        </SectionCard>
      ) : backfill ? (
        <>
          {error && (
            <Alert
              type="warning"
              showIcon
              message="进度刷新失败，当前显示最近一次成功加载的数据"
              description={error.message}
              action={<Button size="small" onClick={() => void loadBackfill()}>重试</Button>}
              style={{ marginBottom: 12 }}
            />
          )}
          <SectionCard title="批次进度" icon={<CalendarOutlined />}>
            <Progress
              percent={percent}
              status={backfill.status === 'SUCCEEDED' ? 'success' : !isTerminal(backfill.status) ? 'active' : 'normal'}
              strokeColor="var(--ol-brand)"
              format={() => `${completed} / ${backfill.total}`}
            />
            <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 3 }} style={{ marginTop: 16 }}>
              <Descriptions.Item label="成功"><Text type="success">{backfill.succeeded}</Text></Descriptions.Item>
              <Descriptions.Item label="失败/取消"><Text type="danger">{backfill.failed}</Text></Descriptions.Item>
              <Descriptions.Item label="运行中">{counts.running}</Descriptions.Item>
              <Descriptions.Item label="待派发">{counts.queued}</Descriptions.Item>
              <Descriptions.Item label="已取消">{counts.cancelled}</Descriptions.Item>
              <Descriptions.Item label="最大并发">{backfill.max_parallel}</Descriptions.Item>
              <Descriptions.Item label="业务区间" span={2}>
                {formatBusinessDate(backfill.range.start)} 至 {formatBusinessDate(backfill.range.end)}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">{formatDate(backfill.created_at)}</Descriptions.Item>
            </Descriptions>
          </SectionCard>

          <SectionCard
            title="子 Run"
            subtitle={`按 ${grainLabels[backfill.grain]} 展开的 ${backfill.total} 个执行窗口`}
            icon={<HistoryOutlined />}
            flatBody
          >
            <Table<BackfillRun>
              rowKey="id"
              dataSource={backfill.runs}
              size="middle"
              pagination={backfill.runs.length > 20 ? { pageSize: 20, showSizeChanger: true } : false}
              scroll={{ x: 920 }}
              locale={{
                emptyText: (
                  <StateView
                    state="empty"
                    title="暂无子 Run"
                    description="批次已创建，但尚未生成执行窗口"
                  />
                ),
              }}
              columns={[
                {
                  title: '业务日期',
                  dataIndex: 'logical_date',
                  width: 180,
                  render: (value: string) => formatBusinessDate(value),
                },
                {
                  title: '数据区间',
                  width: 320,
                  render: (_: unknown, run) => (
                    <Text style={{ fontSize: 12 }}>
                      {formatBusinessDate(run.data_interval_start)} - {formatBusinessDate(run.data_interval_end)}
                    </Text>
                  ),
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 120,
                  render: (status: string) => <StatusBadge status={status} />,
                },
                {
                  title: 'Job Run',
                  dataIndex: 'job_run_id',
                  width: 170,
                  render: (jobRunId?: string) => jobRunId ? (
                    <Button
                      type="link"
                      size="small"
                      style={{ padding: 0 }}
                      onClick={() => navigate(`/orchestration/runs/${jobRunId}?backfill_id=${encodeURIComponent(backfill.id)}`)}
                    >
                      <Text code>{shortId(jobRunId)}</Text>
                    </Button>
                  ) : <Text type="secondary">待派发</Text>,
                },
                {
                  title: '错误信息',
                  dataIndex: 'error_msg',
                  ellipsis: true,
                  render: (value?: string) => value ? <Text type="danger">{value}</Text> : '-',
                },
              ]}
            />
          </SectionCard>
        </>
      ) : null}
    </div>
  );
}
