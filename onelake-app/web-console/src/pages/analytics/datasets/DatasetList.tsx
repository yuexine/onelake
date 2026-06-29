/**
 * 数据集列表（P1 主页面）。
 *
 * 功能：
 * - 列出当前租户的所有数据集（AnalyticsAPI.listDatasets）
 * - 顶部关键词搜索 + 新建按钮
 * - 行操作：编辑 / 删除 / 浏览（执行查询）
 *
 * 关键：状态由本地 React state 管理（useEffect + useState），后续可迁移到 React Query。
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Input, Space, Table, Tag, Modal, message,
  type TableColumnsType,
} from 'antd';
import { PlusOutlined, SearchOutlined, EditOutlined, DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { AnalyticsAPI, type AnalyticsDataset } from '../../../api';
import { DatasetEditor } from './DatasetEditor';

const SOURCE_TYPE_LABEL: Record<string, { label: string; color: string }> = {
  ASSET: { label: 'Iceberg 资产', color: 'blue' },
  SQL: { label: 'Trino SQL', color: 'geekblue' },
  API: { label: '数据服务 API', color: 'purple' },
  NOTEBOOK: { label: 'Notebook 产出', color: 'orange' },
};

const CLASSIFICATION_COLOR: Record<string, string> = {
  L1: 'default',
  L2: 'blue',
  L3: 'orange',
  L4: 'red',
};

export default function DatasetList() {
  const navigate = useNavigate();
  const [data, setData] = useState<AnalyticsDataset[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [editing, setEditing] = useState<AnalyticsDataset | null>(null);
  const [creating, setCreating] = useState(false);

  const reload = async () => {
    setLoading(true);
    try {
      const list = await AnalyticsAPI.listDatasets(keyword);
      setData(list ?? []);
    } catch (e) {
      message.error('加载失败：' + (e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const handleDelete = (id: string, name: string) => {
    Modal.confirm({
      title: '删除数据集',
      content: `确认删除「${name}」？该操作不可恢复。`,
      okType: 'danger',
      onOk: async () => {
        await AnalyticsAPI.deleteDataset(id);
        message.success('已删除');
        reload();
      },
    });
  };

  const columns: TableColumnsType<AnalyticsDataset> = [
    { title: '名称', dataIndex: 'name', key: 'name',
      render: (v, r) => <a onClick={() => navigate(`/analytics/datasets/${r.id}`)}>{v}</a> },
    { title: '来源', dataIndex: 'sourceType', key: 'sourceType',
      render: (v: string) => {
        const info = SOURCE_TYPE_LABEL[v] ?? { label: v, color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      } },
    { title: '资产 / SQL 摘要', key: 'fqn',
      ellipsis: true,
      render: (_, r) => r.assetFqn || (r.selectSql ? r.selectSql.slice(0, 64) + '…' : '-') },
    { title: '密级', dataIndex: 'classification', key: 'classification', align: 'center',
      render: (v: string) => <Tag color={CLASSIFICATION_COLOR[v] ?? 'default'}>{v}</Tag> },
    { title: '缓存TTL', dataIndex: 'cacheTtlSec', key: 'cacheTtlSec', align: 'right',
      render: (v: number) => `${v}s` },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt',
      render: (v?: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '操作', key: 'action', width: 180,
      render: (_, r) => (
        <Space size="small">
          <Button size="small" icon={<EyeOutlined />} onClick={() => navigate(`/analytics/datasets/${r.id}`)}>浏览</Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(r)}>编辑</Button>
          <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id, r.name)} />
        </Space>
      ) },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="数据集管理"
        extra={
          <Space>
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索名称"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={reload}
              style={{ width: 220 }}
              allowClear
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              新建数据集
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data}
          pagination={{ pageSize: 20, showSizeChanger: true }}
        />
      </Card>

      <DatasetEditor
        open={creating || editing !== null}
        dataset={editing}
        onClose={() => { setCreating(false); setEditing(null); }}
        onSuccess={() => { setCreating(false); setEditing(null); reload(); }}
      />
    </div>
  );
}
