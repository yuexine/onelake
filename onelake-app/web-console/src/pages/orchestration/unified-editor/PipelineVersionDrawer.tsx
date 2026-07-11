import { useCallback, useEffect, useState } from 'react';
import { Descriptions, Drawer, Space, Table, Tabs, Tag, Typography } from 'antd';
import { PipelineAPI } from '../../../api';
import { StateView } from '../../../components';
import type { PipelineVersionDetail, PipelineVersionSummary } from '../../../types';

const { Text } = Typography;

function json(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

export function PipelineVersionDrawer({
  dagId,
  open,
  publishedVersionId,
  onClose,
}: {
  dagId: string;
  open: boolean;
  publishedVersionId?: string;
  onClose: () => void;
}) {
  const [versions, setVersions] = useState<PipelineVersionSummary[]>([]);
  const [selected, setSelected] = useState<number>();
  const [detail, setDetail] = useState<PipelineVersionDetail>();
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string>();

  const loadDetail = useCallback(async (version: number) => {
    setSelected(version);
    setDetailLoading(true);
    setError(undefined);
    try {
      setDetail(await PipelineAPI.getVersion(dagId, version));
    } catch (err) {
      setDetail(undefined);
      setError((err as Error).message);
    } finally {
      setDetailLoading(false);
    }
  }, [dagId]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const items = await PipelineAPI.listVersions(dagId);
      setVersions(items);
      const current = items.find((item) => item.id === publishedVersionId) ?? items[0];
      if (current) await loadDetail(current.version);
      else {
        setSelected(undefined);
        setDetail(undefined);
      }
    } catch (err) {
      setVersions([]);
      setDetail(undefined);
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }, [dagId, loadDetail, publishedVersionId]);

  useEffect(() => {
    if (open) load();
  }, [load, open]);

  const snapshot = detail?.snapshot;
  const current = detail?.id === publishedVersionId;

  return (
    <Drawer title="发布版本" width={920} open={open} onClose={onClose} destroyOnClose>
      {loading && versions.length === 0 ? (
        <StateView state="loading" rows={4} />
      ) : error && versions.length === 0 ? (
        <StateView state="error" title="版本加载失败" description={error} onRetry={load} />
      ) : versions.length === 0 ? (
        <StateView state="empty" title="暂无已发布版本" description="完成首次发布后，可在这里查看不可变快照。" />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '250px minmax(0, 1fr)', gap: 16 }}>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={versions}
            rowClassName={(record) => record.version === selected ? 'ant-table-row-selected' : ''}
            onRow={(record) => ({ onClick: () => loadDetail(record.version), style: { cursor: 'pointer' } })}
            columns={[
              {
                title: '版本',
                render: (_value, record) => (
                  <Space direction="vertical" size={2}>
                    <Space size={6}>
                      <Text strong>版本 {record.version}</Text>
                      {record.id === publishedVersionId && <Tag color="green">当前</Tag>}
                    </Space>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {new Date(record.createdAt).toLocaleString('zh-CN')}
                    </Text>
                    <Text code style={{ fontSize: 10 }}>{record.checksum.slice(0, 12)}</Text>
                  </Space>
                ),
              },
            ]}
          />
          <div style={{ minWidth: 0 }}>
            {detailLoading ? (
              <StateView state="loading" rows={5} />
            ) : error ? (
              <StateView state="error" title="版本详情加载失败" description={error} onRetry={() => selected && loadDetail(selected)} />
            ) : detail && snapshot ? (
              <>
                <Descriptions size="small" bordered column={2} style={{ marginBottom: 16 }}>
                  <Descriptions.Item label="版本">
                    <Space><Text strong>版本 {detail.version}</Text>{current && <Tag color="green">当前生产版本</Tag>}</Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="发布人">{detail.publishedByName || '-'}</Descriptions.Item>
                  <Descriptions.Item label="Checksum"><Text code copyable>{detail.checksum}</Text></Descriptions.Item>
                  <Descriptions.Item label="发布时间">{new Date(detail.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
                </Descriptions>
                <Tabs
                  items={[
                    {
                      key: 'tasks', label: `节点 ${snapshot.tasks.length}`,
                      children: <Table size="small" pagination={false} rowKey={(row) => String(row.id ?? row.taskKey)} dataSource={snapshot.tasks} columns={[
                        { title: '节点 Key', dataIndex: 'taskKey', width: 150, render: (value) => <Text code>{String(value)}</Text> },
                        { title: '名称', dataIndex: 'name', width: 130, render: (value) => String(value ?? '-') },
                        { title: '类型', dataIndex: 'taskType', width: 110, render: (value) => <Tag>{String(value)}</Tag> },
                        { title: '目标表', dataIndex: 'targetFqn', render: (value) => <Text code>{String(value ?? '-')}</Text> },
                        { title: '配置', dataIndex: 'config', render: (value) => <pre style={{ margin: 0, maxWidth: 360, whiteSpace: 'pre-wrap' }}>{json(value)}</pre> },
                      ]} />,
                    },
                    {
                      key: 'edges', label: `边 ${snapshot.edges.length}`,
                      children: <Table size="small" pagination={false} rowKey={(row) => String(row.id ?? `${row.sourceKey}-${row.targetKey}`)} dataSource={snapshot.edges} columns={[
                        { title: '上游', dataIndex: 'sourceKey', render: (value) => <Text code>{String(value)}</Text> },
                        { title: '下游', dataIndex: 'targetKey', render: (value) => <Text code>{String(value)}</Text> },
                        { title: '层级', dataIndex: 'edgeLayer', render: (value) => <Tag>{String(value)}</Tag> },
                        { title: '端口', render: (_value, row) => `${String(row.sourcePort ?? '-')} → ${String(row.targetPort ?? '-')}` },
                      ]} />,
                    },
                    {
                      key: 'params', label: `参数 ${snapshot.pipeline_params.length}`,
                      children: <Table size="small" pagination={false} rowKey={(row) => String(row.id ?? `${row.scope}-${row.taskKey}-${row.paramKey}`)} dataSource={snapshot.pipeline_params} columns={[
                        { title: '作用域', dataIndex: 'scope', render: (value) => <Tag>{String(value)}</Tag> },
                        { title: '节点', dataIndex: 'taskKey', render: (value) => String(value ?? '-') },
                        { title: '参数', dataIndex: 'paramKey', render: (value) => <Text code>{String(value)}</Text> },
                        { title: '值', dataIndex: 'paramValue', render: (value) => String(value ?? '') },
                        { title: '类型', dataIndex: 'valueType', render: (value) => String(value ?? '-') },
                      ]} />,
                    },
                    { key: 'schedule', label: '调度配置', children: <pre style={{ whiteSpace: 'pre-wrap' }}>{json(snapshot.schedule)}</pre> },
                  ]}
                />
              </>
            ) : null}
          </div>
        </div>
      )}
    </Drawer>
  );
}
