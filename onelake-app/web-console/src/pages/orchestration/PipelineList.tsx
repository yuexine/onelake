/**
 * 流水线列表（对应原型 §4.4.1 升级版）。
 */
import { Table, Tag, Space, Button, message, Typography } from 'antd';
import {
  PlusOutlined, AppstoreOutlined, PlayCircleOutlined,
  EditOutlined, CloudSyncOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { StatusBadge, PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

const pipelines = [
  { id: 'p-1', name: 'order_pipeline', env: 'prod', version: 3, status: 'ENABLED', lastRun: '2026-06-14 02:00', enabled: true },
  { id: 'p-2', name: 'user_dws', env: 'prod', version: 2, status: 'ENABLED', lastRun: '2026-06-14 02:00', enabled: true },
  { id: 'p-3', name: 'risk_score', env: 'dev', version: 1, status: 'DRAFT', lastRun: '-', enabled: false },
];

export default function PipelineList() {
  const navigate = useNavigate();

  const counts = {
    total: pipelines.length,
    enabled: pipelines.filter((p) => p.status === 'ENABLED').length,
    draft: pipelines.filter((p) => p.status === 'DRAFT').length,
    prod: pipelines.filter((p) => p.env === 'prod').length,
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
          { label: '生产环境', value: counts.prod },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>新建流水线</Button>}
      />

      <SectionCard title="流水线列表" icon={<AppstoreOutlined />} flatBody>
        <Table
          size="middle"
          rowKey="id"
          dataSource={pipelines}
          pagination={false}
          columns={[
            { title: '名称', dataIndex: 'name', render: (n: string, r: any) => (
              <Space size={10}>
                <div style={{ width: 28, height: 28, borderRadius: 6, background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
                  <AppstoreOutlined />
                </div>
                <div>
                  <a className="ol-link" style={{ fontSize: 13, fontWeight: 500 }} onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>{n}</a>
                  <div style={{ fontSize: 11, color: 'var(--ol-ink-3)' }}>最近运行 {r.lastRun}</div>
                </div>
              </Space>
            ) },
            { title: '环境', dataIndex: 'env', width: 90, render: (e: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: e === 'prod' ? 'var(--ol-error-soft)' : 'var(--ol-info-soft)',
                color: e === 'prod' ? 'var(--ol-error)' : '#0369A1',
              }}>{e}</span>
            ) },
            { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag style={{ margin: 0 }}>v{v}</Tag> },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: '最近运行', dataIndex: 'lastRun', render: (l: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{l}</span> },
            { title: '操作', width: 180, render: (_: unknown, r: any) => (
              <Space>
                <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />} onClick={() => message.success('已触发')}>触发</Button>
                <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>打开画布</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
