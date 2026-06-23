/**
 * 流水线列表（对应原型 §4.4.1 升级版）。
 */
import { useEffect, useMemo, useState } from 'react';
import { Alert, App as AntApp, Button, Space, Table, Tag, Tooltip, Typography } from 'antd';
import {
  PlusOutlined, AppstoreOutlined, PlayCircleOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard } from '../../components';
import { OrchestrationAPI } from '../../api';
import type { Dag, JobRun } from '../../types';

const { Text } = Typography;

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN');
}

function LastRunSummary({ run }: { run?: JobRun }) {
  if (!run) {
    return <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>-</span>;
  }
  return (
    <Space direction="vertical" size={2}>
      <Space size={8}>
        <StatusBadge status={run.status} />
        <Text code style={{ fontSize: 11 }}>{run.dagsterRunId || run.id}</Text>
      </Space>
      <span style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{formatDate(run.startedAt)}</span>
    </Space>
  );
}

function TriggerState({ dag }: { dag: Dag }) {
  const triggerable = Boolean(dag.triggerable);
  const label = triggerable ? '可触发' : dag.enabled ? '待绑定' : '草稿';
  return (
    <span
      title={triggerable ? undefined : dag.triggerBlockedReason}
      style={{
        padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
        background: triggerable ? 'var(--ol-success-soft)' : 'var(--ol-fill-soft)',
        color: triggerable ? 'var(--ol-success)' : 'var(--ol-ink-2)',
      }}
    >
      {label}
    </span>
  );
}

export default function PipelineList() {
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [pipelines, setPipelines] = useState<Dag[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);

  const loadPipelines = () => {
    setLoading(true);
    setError(null);
    OrchestrationAPI.listDags()
      .then(setPipelines)
      .catch((e) => setError(e.message || '流水线列表加载失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadPipelines();
  }, []);

  const counts = useMemo(() => ({
    total: pipelines.length,
    enabled: pipelines.filter((p) => p.enabled).length,
    draft: pipelines.filter((p) => !p.enabled).length,
  }), [pipelines]);

  const triggerPipeline = (dag: Dag) => {
    if (!dag.triggerable) {
      message.warning(dag.triggerBlockedReason || '当前流水线不可触发');
      return;
    }
    setTriggeringId(dag.id);
    OrchestrationAPI.triggerDag(dag.id)
      .then(() => message.success(`流水线 ${dag.name} 已触发运行`))
      .catch((e) => message.error(e.message || '流水线触发失败'))
      .finally(() => {
        setTriggeringId(null);
        loadPipelines();
      });
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title="流水线"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="DAG 画布编辑、版本管理、环境隔离、依赖触发"
        meta={[
          { label: '总流水线', value: counts.total },
          { label: '已启用', value: counts.enabled },
          { label: '草稿', value: counts.draft },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>新建流水线</Button>}
      />

      <SectionCard title="流水线列表" icon={<AppstoreOutlined />} flatBody>
        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            action={<Button size="small" onClick={loadPipelines}>重试</Button>}
            style={{ margin: 12 }}
          />
        )}
        <Table
          size="middle"
          rowKey="id"
          dataSource={pipelines}
          loading={loading}
          pagination={false}
          columns={[
            { title: '名称', dataIndex: 'name', render: (n: string, r: Dag) => (
              <Space size={10}>
                <div style={{ width: 28, height: 28, borderRadius: 6, background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
                  <AppstoreOutlined />
                </div>
                <div>
                  <a className="ol-link" style={{ fontSize: 13, fontWeight: 500 }} onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>{n}</a>
                  <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>{r.dagsterJob}</div>
                </div>
              </Space>
            ) },
            { title: '运行态', width: 90, render: (_: unknown, r: Dag) => <TriggerState dag={r} /> },
            { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag style={{ margin: 0 }}>v{v}</Tag> },
            { title: '状态', dataIndex: 'enabled', width: 110, render: (enabled: boolean) => <StatusBadge status={enabled ? 'ENABLED' : 'DRAFT'} /> },
            { title: '最近运行', width: 260, render: (_: unknown, r: Dag) => <LastRunSummary run={r.lastRun} /> },
            { title: '操作', width: 180, render: (_: unknown, r: Dag) => (
              <Space>
                <Tooltip title={r.triggerable ? undefined : r.triggerBlockedReason}>
                  <span>
                    <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />}
                      disabled={!r.triggerable}
                      loading={triggeringId === r.id}
                      onClick={() => triggerPipeline(r)}
                    >
                      触发
                    </Button>
                  </span>
                </Tooltip>
                <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>打开画布</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
