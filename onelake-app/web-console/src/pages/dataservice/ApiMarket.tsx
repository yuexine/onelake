/**
 * API 市场（对应原型 §8.8.1 升级版）。
 */
import { Table, Tag, Space, Button, Input, Select, message, Typography } from 'antd';
import { PlusOutlined, CloudOutlined, ApiOutlined, TeamOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { apis } from '../../mock';
import { ClassificationBadge, StatusBadge, PageHeader, SectionCard, FilterBar } from '../../components';

const { Text } = Typography;

export default function ApiMarket() {
  const navigate = useNavigate();

  const counts = {
    total: apis.length,
    published: apis.filter((a) => a.status === 'PUBLISHED').length,
    deprecated: apis.filter((a) => a.status === 'DEPRECATED').length,
    subscribers: apis.reduce((sum, a) => sum + (a.subscriberCount || 0), 0),
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<CloudOutlined />}
        title="API 市场"
        subtitle={<span className="ol-chip">数据服务 · L5</span>}
        description="数据 API 集市，支持订阅、限流、密级联动、版本管理"
        actions={
          <>
            <Select defaultValue="all" options={[
              { label: '我发布的', value: 'mine' },
              { label: '我订阅的', value: 'subscribed' },
              { label: '全部', value: 'all' },
            ]} style={{ width: 130 }} />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/dataservice/apis/new')}>构建 API</Button>
          </>
        }
      />

      <FilterBar
        search={{ placeholder: 'API 名 / 路径', width: 260 }}
        filters={
          <>
            <Select placeholder="分类" allowClear style={{ width: 120 }}
              options={['交易', '用户', '风控'].map((v) => ({ label: v, value: v }))} />
            <Select placeholder="状态" allowClear style={{ width: 140 }}
              options={['PUBLISHED', 'DRAFT', 'DEPRECATED', 'OFFLINE'].map((v) => ({ label: v, value: v }))} />
            <Select placeholder="密级" allowClear style={{ width: 120 }}
              options={['L1', 'L2', 'L3', 'L4'].map((v) => ({ label: v, value: v }))} />
          </>
        }
        summary={<span className="ol-quiet" style={{ fontSize: 12 }}>共 {apis.length} 条</span>}
      />

      <SectionCard title="API 列表" icon={<ApiOutlined />} flatBody>
        <Table
          rowKey="id"
          dataSource={apis}
          size="middle"
          pagination={{ pageSize: 20 }}
          columns={[
            { title: '路径', dataIndex: 'apiPath', render: (v: string, r: any) => (
              <Space size={8}>
                <a className="ol-link" style={{ fontSize: 13, fontWeight: 500 }} onClick={() => navigate(`/dataservice/apis/${r.id}`)}>{v}</a>
                <Tag color="blue" style={{ margin: 0 }}>v{r.currentVersion}</Tag>
              </Space>
            ) },
            { title: '名称', dataIndex: 'name' },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
            { title: 'QPS 限制', dataIndex: 'qpsLimit', align: 'right' as const, render: (v: number) => <span className="mono tnum">{v}</span> },
            { title: '实际 QPS', dataIndex: 'qps', align: 'right' as const, render: (v?: number) => v ? <span className="mono tnum">{v}</span> : '-' },
            { title: '成功率', dataIndex: 'successRate', width: 100, render: (v?: number) => v ? (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: v > 99 ? 'var(--ol-success-soft)' : 'var(--ol-warning-soft)',
                color: v > 99 ? 'var(--ol-success)' : '#B45309',
              }}>{v}%</span>
            ) : '-' },
            { title: '密级', dataIndex: 'classification', width: 110, render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
            { title: '订阅方', dataIndex: 'subscriberCount', align: 'right' as const },
            { title: '操作', width: 100, render: (_: unknown, r: any) => (
              <Button size="small" type="primary" ghost disabled={r.status !== 'PUBLISHED'}
                onClick={() => message.success('已提交订阅申请')}>订阅</Button>
            ) },
          ]}
        />
      </SectionCard>
    </div>
  );
}
