/**
 * 数据集详情页（P1：浏览 + 试查询）。
 *
 * - 展示元信息
 * - 试查询（POST /datasets/{id}/query 默认 SELECT *）
 * - 展示字段 schema 与密级 badge
 */
import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Table, Button, Space, Tag, Input, message,
  type TableColumnsType,
} from 'antd';
import { ArrowLeftOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { AnalyticsAPI, type AnalyticsDataset, type AnalyticsQueryResult } from '../../../api';
import { ClassificationBadge } from '../../../components/ClassificationBadge';

const SOURCE_LABEL: Record<string, string> = {
  ASSET: 'Iceberg 资产', SQL: 'Trino SQL', API: '数据服务 API', NOTEBOOK: 'Notebook 产出',
};

export default function DatasetDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [dataset, setDataset] = useState<AnalyticsDataset | null>(null);
  const [result, setResult] = useState<AnalyticsQueryResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [limit, setLimit] = useState(100);

  useEffect(() => {
    if (!id) return;
    AnalyticsAPI.getDataset(id).then(setDataset).catch((e) => {
      message.error('加载数据集失败：' + (e as Error).message);
    });
  }, [id]);

  const runQuery = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const r = await AnalyticsAPI.queryDataset(id, { dimensions: [], measures: [], limit });
      setResult(r);
      message.success(`返回 ${r.rows?.length ?? 0} 行，耗时 ${r.durationMs}ms ${r.cacheHit ? '(缓存命中)' : ''}`);
    } catch (e) {
      message.error('查询失败：' + (e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  if (!dataset) return <div style={{ padding: 24 }}>加载中...</div>;

  const columns: TableColumnsType<Record<string, unknown>> = result?.fields?.map((f) => ({
    title: f.name,
    dataIndex: f.name,
    key: f.name,
    ellipsis: true,
  })) ?? [];

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/analytics/datasets')}>返回</Button>
        <h2 style={{ margin: 0 }}>{dataset.name}</h2>
        <ClassificationBadge level={dataset.classification as any} />
      </Space>

      <Card title="基本信息" style={{ marginBottom: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="名称">{dataset.name}</Descriptions.Item>
          <Descriptions.Item label="来源">
            <Tag color="blue">{SOURCE_LABEL[dataset.sourceType] ?? dataset.sourceType}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="资产 FQN" span={2}>{dataset.assetFqn ?? '-'}</Descriptions.Item>
          {dataset.selectSql && (
            <Descriptions.Item label="Trino SQL" span={2}>
              <pre style={{ background: '#f5f5f5', padding: 12, maxHeight: 200, overflow: 'auto', margin: 0 }}>
                {dataset.selectSql}
              </pre>
            </Descriptions.Item>
          )}
          <Descriptions.Item label="缓存 TTL">{dataset.cacheTtlSec} 秒</Descriptions.Item>
          <Descriptions.Item label="行级过滤">{dataset.rowFilter || '-'}</Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {dataset.updatedAt ? new Date(dataset.updatedAt).toLocaleString() : '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card
        title="字段 schema"
        extra={
          <Space>
            <span>行数限制：</span>
            <Input type="number" value={limit} onChange={(e) => setLimit(Number(e.target.value))} style={{ width: 100 }} />
            <Button type="primary" icon={<PlayCircleOutlined />} loading={loading} onClick={runQuery}>
              试查询
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={dataset.fieldSchema ?? []}
          columns={[
            { title: '字段', dataIndex: 'name' },
            { title: '类型', dataIndex: 'type' },
            { title: '密级', dataIndex: 'classification',
              render: (v: string) => <ClassificationBadge level={v as any} /> },
          ]}
        />
      </Card>

      {result && (
        <Card title={`查询结果（${result.rows?.length ?? 0} 行，耗时 ${result.durationMs}ms${result.cacheHit ? ' · 缓存命中' : ''}）`} style={{ marginTop: 16 }}>
          <Table
            size="small"
            rowKey={(_, i) => String(i)}
            columns={columns}
            dataSource={result.rows}
            pagination={{ pageSize: 10 }}
            scroll={{ x: 'max-content' }}
          />
        </Card>
      )}
    </div>
  );
}
