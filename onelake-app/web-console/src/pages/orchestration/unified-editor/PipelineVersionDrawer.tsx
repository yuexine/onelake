import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Button,
  Descriptions,
  Drawer,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  ArrowRightOutlined,
  ReloadOutlined,
  SwapOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { PipelineVersionAPI } from '../../../api';
import { BizError } from '../../../api/http';
import { StateView } from '../../../components';
import { color, space } from '../../../components/tokens';
import type {
  PipelineVersionCollectionDiff,
  PipelineVersionDetail,
  PipelineVersionDiff,
  PipelineVersionItemDiff,
  PipelineVersionSummary,
} from '../../../types';

const { Text } = Typography;

interface Props {
  dagId: string;
  open: boolean;
  publishedVersionId?: string;
  onClose: () => void;
  onRolledBack?: () => void | Promise<void>;
}

interface UiError {
  message: string;
  noPermission: boolean;
}

type DiffKind = 'added' | 'removed' | 'changed';
type DiffDomain = '任务' | '连线' | '参数';

interface DiffRow {
  id: string;
  domain: DiffDomain;
  kind: DiffKind;
  objectKey: string;
  field: string;
  before?: unknown;
  after?: unknown;
}

function toUiError(error: unknown, fallback: string): UiError {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const rawMessage = error instanceof Error ? error.message.trim() : '';
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  const technicalMessage = /^(request failed with status code \d+|network error)$/i.test(rawMessage)
    || /(?:timeout|econnrefused|failed to fetch|socket hang up)/i.test(rawMessage);
  if (status === 401 || code === 40100) {
    return { message: '登录状态已失效，请重新登录。', noPermission: true };
  }
  if (status === 403 || code === 40300) {
    return { message: '当前账号没有查看或回滚流水线版本的权限。', noPermission: true };
  }
  if (status === 404 || code === 40400) {
    return { message: '未找到该流水线或版本记录，请刷新编辑器后重试。', noPermission: false };
  }
  if ((status !== undefined && status >= 500) || (code !== undefined && code >= 50000)) {
    return { message: '版本服务暂时不可用，请稍后重试。', noPermission: false };
  }
  if (technicalMessage) {
    return { message: '无法连接版本服务，请检查网络后重试。', noPermission: false };
  }
  return {
    message: rawMessage || fallback,
    noPermission,
  };
}

function json(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

function valueText(value: unknown) {
  if (value === undefined || value === null) return '-';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function diffRows(domain: DiffDomain, diff: PipelineVersionCollectionDiff): DiffRow[] {
  const wholeObject = (kind: 'added' | 'removed', item: PipelineVersionItemDiff): DiffRow => ({
    id: `${domain}-${kind}-${item.key}`,
    domain,
    kind,
    objectKey: item.key,
    field: '完整对象',
    before: item.before,
    after: item.after,
  });
  return [
    ...diff.added.map((item) => wholeObject('added', item)),
    ...diff.removed.map((item) => wholeObject('removed', item)),
    ...diff.changed.flatMap((item) => item.fields.map((field) => ({
      id: `${domain}-changed-${item.key}-${field.field}`,
      domain,
      kind: 'changed' as const,
      objectKey: item.key,
      field: field.field,
      before: field.before,
      after: field.after,
    }))),
  ];
}

function diffCount(diff: PipelineVersionCollectionDiff) {
  return diff.added.length + diff.removed.length
    + diff.changed.reduce((count, item) => count + item.fields.length, 0);
}

function DiffTable({ domain, diff }: { domain: DiffDomain; diff: PipelineVersionCollectionDiff }) {
  const rows = useMemo(() => diffRows(domain, diff), [diff, domain]);
  if (rows.length === 0) {
    return <StateView state="empty" title={`无${domain}差异`} description="两个版本在这一类对象上完全一致。" />;
  }
  return (
    <Table<DiffRow>
      size="small"
      rowKey="id"
      pagination={false}
      dataSource={rows}
      scroll={{ x: 760, y: 440 }}
      columns={[
        {
          title: '类型', dataIndex: 'kind', width: 76,
          render: (kind: DiffKind) => kind === 'added'
            ? <Tag color="success">新增</Tag>
            : kind === 'removed' ? <Tag color="error">删除</Tag> : <Tag color="warning">修改</Tag>,
        },
        {
          title: '对象', dataIndex: 'objectKey', width: 180,
          render: (value: string) => <Text code>{value}</Text>,
        },
        {
          title: '字段', dataIndex: 'field', width: 130,
          render: (value: string) => <Text strong>{value}</Text>,
        },
        {
          title: '原值', dataIndex: 'before', width: 260,
          onCell: (record) => ({
            style: record.kind === 'removed' || record.kind === 'changed'
              ? { background: color.errorSoft, verticalAlign: 'top' }
              : { verticalAlign: 'top' },
          }),
          render: (value: unknown) => (
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 11 }}>
              {valueText(value)}
            </pre>
          ),
        },
        {
          title: '新值', dataIndex: 'after', width: 260,
          onCell: (record) => ({
            style: record.kind === 'added' || record.kind === 'changed'
              ? { background: color.successSoft, verticalAlign: 'top' }
              : { verticalAlign: 'top' },
          }),
          render: (value: unknown) => (
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 11 }}>
              {valueText(value)}
            </pre>
          ),
        },
      ]}
    />
  );
}

export function PipelineVersionDrawer({
  dagId,
  open,
  publishedVersionId,
  onClose,
  onRolledBack,
}: Props) {
  const { message, modal } = AntApp.useApp();
  const [versions, setVersions] = useState<PipelineVersionSummary[]>([]);
  const [selected, setSelected] = useState<number>();
  const [detail, setDetail] = useState<PipelineVersionDetail>();
  const [activeTab, setActiveTab] = useState<'snapshot' | 'diff'>('snapshot');
  const [compareFrom, setCompareFrom] = useState<number>();
  const [compareTo, setCompareTo] = useState<number>();
  const [diff, setDiff] = useState<PipelineVersionDiff>();
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [diffLoading, setDiffLoading] = useState(false);
  const [rollbackLoading, setRollbackLoading] = useState(false);
  const [listError, setListError] = useState<UiError>();
  const [detailError, setDetailError] = useState<UiError>();
  const [diffError, setDiffError] = useState<UiError>();

  const loadDetail = useCallback(async (version: number) => {
    setSelected(version);
    setDetailLoading(true);
    setDetailError(undefined);
    setDetail(undefined);
    try {
      setDetail(await PipelineVersionAPI.get(dagId, version));
    } catch (error) {
      setDetailError(toUiError(error, '版本快照加载失败，请稍后重试。'));
    } finally {
      setDetailLoading(false);
    }
  }, [dagId]);

  const load = useCallback(async () => {
    setLoading(true);
    setListError(undefined);
    setDetailError(undefined);
    setDiffError(undefined);
    setVersions([]);
    setDetail(undefined);
    setDiff(undefined);
    try {
      const items = await PipelineVersionAPI.list(dagId);
      setVersions(items);
      const current = items.find((item) => item.id === publishedVersionId) ?? items[0];
      setCompareTo(items[0]?.version);
      setCompareFrom(items[1]?.version);
      if (current) await loadDetail(current.version);
      else setSelected(undefined);
    } catch (error) {
      setListError(toUiError(error, '版本历史加载失败，请稍后重试。'));
      setSelected(undefined);
    } finally {
      setLoading(false);
    }
  }, [dagId, loadDetail, publishedVersionId]);

  const loadDiff = useCallback(async () => {
    if (compareFrom === undefined || compareTo === undefined || compareFrom === compareTo) {
      setDiff(undefined);
      return;
    }
    setDiffLoading(true);
    setDiffError(undefined);
    setDiff(undefined);
    try {
      setDiff(await PipelineVersionAPI.diff(dagId, compareFrom, compareTo));
    } catch (error) {
      setDiffError(toUiError(error, '版本差异加载失败，请稍后重试。'));
    } finally {
      setDiffLoading(false);
    }
  }, [compareFrom, compareTo, dagId]);

  useEffect(() => {
    if (open) {
      setActiveTab('snapshot');
      void load();
    }
  }, [load, open]);

  useEffect(() => {
    if (open && activeTab === 'diff' && versions.length >= 2) void loadDiff();
  }, [activeTab, loadDiff, open, versions.length]);

  const snapshot = detail?.snapshot;
  const current = detail?.id === publishedVersionId;
  const versionOptions = versions.map((item) => ({
    label: `v${item.version} · ${new Date(item.createdAt).toLocaleString('zh-CN')}`,
    value: item.version,
  }));

  const confirmRollback = () => {
    if (!detail) return;
    modal.confirm({
      title: `回滚到版本 v${detail.version}？`,
      content: '回滚只会用该快照覆盖当前 DEV 草稿，不会修改当前生产版本或历史记录。回滚后必须重新发布才会在生产生效。',
      okText: `确认回滚到 v${detail.version}`,
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setRollbackLoading(true);
        try {
          await PipelineVersionAPI.rollback(dagId, detail.version);
          message.success(`已回滚到 v${detail.version} 的 DEV 草稿；请重新发布后再投入生产。`);
          await onRolledBack?.();
        } catch (error) {
          const uiError = toUiError(error, '版本回滚失败，请稍后重试。');
          message.error(uiError.message);
          throw error;
        } finally {
          setRollbackLoading(false);
        }
      },
    });
  };

  const snapshotView = detailLoading ? (
    <StateView state="loading" rows={5} />
  ) : detailError?.noPermission ? (
    <StateView state="no-permission" title="无版本查看权限" description={detailError.message} />
  ) : detailError ? (
    <StateView state="error" title="版本快照加载失败" description={detailError.message} onRetry={() => selected !== undefined && loadDetail(selected)} />
  ) : detail && snapshot ? (
    <>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: space.md, marginBottom: space.lg }}>
        <Descriptions size="small" bordered column={2} style={{ flex: 1 }}>
          <Descriptions.Item label="版本">
            <Space><Text strong>v{detail.version}</Text>{current && <Tag color="green">当前生产版本</Tag>}</Space>
          </Descriptions.Item>
          <Descriptions.Item label="发布人">{detail.publishedByName || '-'}</Descriptions.Item>
          <Descriptions.Item label="Checksum"><Text code copyable>{detail.checksum}</Text></Descriptions.Item>
          <Descriptions.Item label="发布时间">{new Date(detail.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
        </Descriptions>
        <Tooltip title={current ? '当前生产版本无需回滚；请选择更早的历史版本。' : undefined}>
          <Button
            danger
            icon={<UndoOutlined />}
            loading={rollbackLoading}
            disabled={current}
            onClick={confirmRollback}
          >
            回滚到此版本
          </Button>
        </Tooltip>
      </div>
      <Tabs
        size="small"
        items={[
          { key: 'dag', label: '流水线', children: <pre style={{ whiteSpace: 'pre-wrap' }}>{json(snapshot.dag)}</pre> },
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
  ) : null;

  const diffView = versions.length < 2 ? (
    <StateView state="empty" title="暂无可对比版本" description="至少发布两个版本后才能进行结构化对比。" />
  ) : (
    <>
      <Space wrap style={{ marginBottom: space.lg }}>
        <Text type="secondary">基准版本</Text>
        <Select
          style={{ width: 230 }}
          value={compareFrom}
          options={versionOptions.map((option) => ({ ...option, disabled: option.value === compareTo }))}
          onChange={setCompareFrom}
        />
        <ArrowRightOutlined style={{ color: color.ink4 }} />
        <Text type="secondary">目标版本</Text>
        <Select
          style={{ width: 230 }}
          value={compareTo}
          options={versionOptions.map((option) => ({ ...option, disabled: option.value === compareFrom }))}
          onChange={setCompareTo}
        />
        <Button
          icon={<SwapOutlined />}
          disabled={compareFrom === undefined || compareTo === undefined}
          onClick={() => {
            setCompareFrom(compareTo);
            setCompareTo(compareFrom);
          }}
        >
          交换
        </Button>
        <Button icon={<ReloadOutlined />} onClick={() => void loadDiff()}>重新对比</Button>
      </Space>
      {diffLoading ? (
        <StateView state="loading" rows={5} />
      ) : diffError?.noPermission ? (
        <StateView state="no-permission" title="无版本对比权限" description={diffError.message} />
      ) : diffError ? (
        <StateView state="error" title="版本对比失败" description={diffError.message} onRetry={loadDiff} />
      ) : diff ? (
        <Tabs
          size="small"
          items={[
            { key: 'tasks', label: `任务差异 ${diffCount(diff.tasks)}`, children: <DiffTable domain="任务" diff={diff.tasks} /> },
            { key: 'edges', label: `连线差异 ${diffCount(diff.edges)}`, children: <DiffTable domain="连线" diff={diff.edges} /> },
            { key: 'params', label: `参数差异 ${diffCount(diff.params)}`, children: <DiffTable domain="参数" diff={diff.params} /> },
          ]}
        />
      ) : null}
    </>
  );

  return (
    <Drawer title="版本历史" width={1120} open={open} onClose={onClose} destroyOnClose>
      {loading && versions.length === 0 ? (
        <StateView state="loading" rows={4} />
      ) : listError?.noPermission ? (
        <StateView state="no-permission" title="无版本历史权限" description={listError.message} />
      ) : listError ? (
        <StateView state="error" title="版本历史加载失败" description={listError.message} onRetry={load} />
      ) : versions.length === 0 ? (
        <StateView state="empty" title="暂无已发布版本" description="完成首次发布后，可在这里查看不可变生产快照。" />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '260px minmax(0, 1fr)', gap: space.lg }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: space.sm }}>
              <Text strong>版本列表</Text>
              <Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => void load()} aria-label="刷新版本历史" />
            </div>
            <Table
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={versions}
              rowClassName={(record) => record.version === selected ? 'ant-table-row-selected' : ''}
              onRow={(record) => ({ onClick: () => void loadDetail(record.version), style: { cursor: 'pointer' } })}
              columns={[
                {
                  title: '版本',
                  render: (_value, record) => (
                    <Space direction="vertical" size={2}>
                      <Space size={6}>
                        <Text strong>v{record.version}</Text>
                        {record.id === publishedVersionId && <Tag color="green">当前</Tag>}
                      </Space>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {new Date(record.createdAt).toLocaleString('zh-CN')}
                      </Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>{record.publishedByName || '未知发布人'}</Text>
                      <Text code style={{ fontSize: 10 }}>{record.checksum.slice(0, 12)}</Text>
                    </Space>
                  ),
                },
              ]}
            />
          </div>
          <div style={{ minWidth: 0 }}>
            <Tabs
              activeKey={activeTab}
              onChange={(key) => setActiveTab(key as 'snapshot' | 'diff')}
              items={[
                { key: 'snapshot', label: '查看快照', children: snapshotView },
                { key: 'diff', label: '两版本对比', children: diffView },
              ]}
            />
          </div>
        </div>
      )}
    </Drawer>
  );
}
