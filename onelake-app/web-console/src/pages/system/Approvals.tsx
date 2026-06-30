/**
 * 审批中心（对应原型 §8.10 升级版）。
 *   真实待审列表 + 详情抽屉 + 通过 / 驳回后生成授权闭环。
 */
import { useEffect, useMemo, useState } from 'react';
import { Table, Tag, Space, Button, Tabs, Drawer, Timeline, Input, Typography, App as AntdApp, Select, Modal, Form, Checkbox, InputNumber, Popconfirm } from 'antd';
import { AuditOutlined, CheckOutlined, CloseOutlined, ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { CatalogAPI, SecurityAPI } from '../../api';
import type { AccessGrant, ApprovalRequest } from '../../types';
import { StatusBadge, PageHeader, SectionCard, StateView, IntentBadge, riskColor } from '../../components';

const { Text } = Typography;

type ApprovalPayload = {
  reason?: string;
  source?: string;
  sourcePath?: string;
  sql?: string;
  durationDays?: number;
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH';
  impactSummary?: { assets?: number; apis?: number; subscribers?: number };
  approvalChain?: ApprovalRequest['chain'];
  changeType?: 'ADD_COLUMN' | 'DROP_COLUMN' | 'RENAME_COLUMN' | 'CHANGE_TYPE';
  columnName?: string;
  dataType?: string;
  afterName?: string;
  afterType?: string;
  nullable?: boolean;
  executionMode?: string;
  executionStatus?: 'SUCCEEDED' | 'FAILED' | string;
  executionSql?: string;
  executionError?: string;
  beforeColumns?: Array<{ name: string; type?: string; description?: string; classification?: string }>;
  permissions?: {
    query?: boolean;
    download?: boolean;
    api?: boolean;
  };
};

type ApprovalRow = ApprovalRequest & {
  applicantName: string;
  reason?: string;
  payload?: ApprovalPayload;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  impactSummary: { assets: number; apis: number; subscribers: number };
};

function parsePayload(payload: unknown): ApprovalPayload {
  if (!payload) return {};
  if (typeof payload === 'object') return payload as ApprovalPayload;
  if (typeof payload !== 'string') return {};
  try {
    const parsed = JSON.parse(payload);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function normalizeApproval(item: ApprovalRequest): ApprovalRow {
  const payload = parsePayload((item as { payload?: unknown }).payload);
  const permissions = payload.permissions || {};
  const riskLevel = payload.riskLevel || (permissions.api ? 'HIGH' : permissions.download ? 'MEDIUM' : 'LOW');
  return {
    ...item,
    applicantName: item.applicantName || item.applicantId.slice(0, 8),
    reason: item.reason || payload.reason,
    payload,
    chain: item.chain || payload.approvalChain,
    riskLevel,
    impactSummary: {
      assets: payload.impactSummary?.assets ?? 1,
      apis: payload.impactSummary?.apis ?? (permissions.api ? 1 : 0),
      subscribers: payload.impactSummary?.subscribers ?? 0,
    },
  };
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '-';
}

function permissionText(payload?: ApprovalPayload) {
  const permissions = payload?.permissions || {};
  const items = ['query'];
  if (permissions.download) items.push('download');
  if (permissions.api) items.push('api');
  return items.join(' / ');
}

function schemaChangeText(payload?: ApprovalPayload) {
  if (!payload?.changeType) return '-';
  const labels: Record<string, string> = {
    ADD_COLUMN: '新增字段',
    DROP_COLUMN: '删除字段',
    RENAME_COLUMN: '重命名字段',
    CHANGE_TYPE: '修改类型',
  };
  const base = labels[payload.changeType] || payload.changeType;
  if (payload.changeType === 'ADD_COLUMN') return `${base} ${payload.columnName || '-'} ${payload.dataType || ''}`.trim();
  if (payload.changeType === 'RENAME_COLUMN') return `${base} ${payload.columnName || '-'} -> ${payload.afterName || '-'}`;
  if (payload.changeType === 'CHANGE_TYPE') return `${base} ${payload.columnName || '-'} -> ${payload.afterType || '-'}`;
  return `${base} ${payload.columnName || '-'}`;
}

function requestSummary(row: ApprovalRow) {
  if (row.requestType === 'SCHEMA_CHANGE') {
    return schemaChangeText(row.payload);
  }
  return permissionText(row.payload);
}

function grantPermissions(grant: AccessGrant) {
  if (!grant.permissions) return {};
  if (typeof grant.permissions === 'object') return grant.permissions;
  try {
    const parsed = JSON.parse(grant.permissions);
    return parsed && typeof parsed === 'object' ? parsed as AccessGrant['permissions'] : {};
  } catch {
    return {};
  }
}

type ManualGrantForm = {
  subjectId: string;
  assetFqn: string;
  durationDays?: number;
  download?: boolean;
  api?: boolean;
};

export default function Approvals() {
  const { id } = useParams();
  const { message } = AntdApp.useApp();
  const [grantForm] = Form.useForm<ManualGrantForm>();
  const [drawerId, setDrawerId] = useState<string | undefined>(id);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [pending, setPending] = useState<ApprovalRow[]>([]);
  const [processed, setProcessed] = useState<ApprovalRow[]>([]);
  const [grants, setGrants] = useState<AccessGrant[]>([]);
  const [loading, setLoading] = useState(false);
  const [processedLoading, setProcessedLoading] = useState(false);
  const [grantsLoading, setGrantsLoading] = useState(false);
  const [processedStatus, setProcessedStatus] = useState<'ALL' | 'APPROVED' | 'REJECTED' | 'CANCELED'>('ALL');
  const [grantStatus, setGrantStatus] = useState<'ALL' | AccessGrant['status']>('ACTIVE');
  const [processedPage, setProcessedPage] = useState(0);
  const [processedSize, setProcessedSize] = useState(20);
  const [processedTotal, setProcessedTotal] = useState(0);
  const [actionLoading, setActionLoading] = useState<string>();
  const [comment, setComment] = useState('');
  const [nextApproverId, setNextApproverId] = useState('');
  const [grantModalOpen, setGrantModalOpen] = useState(false);

  const allRows = useMemo(() => [...pending, ...processed], [pending, processed]);
  const current = allRows.find((a) => a.id === drawerId);

  const counts = {
    pending: pending.length,
    processed: processedTotal,
    grants: grants.length,
    high: pending.filter((a) => a.riskLevel === 'HIGH').length,
  };

  const loadPending = () => {
    setLoading(true);
    SecurityAPI.pendingApprovals()
      .then((items) => {
        const rows = items.map(normalizeApproval);
        setPending(rows);
        setSelectedRowKeys((keys) => keys.filter((key) => rows.some((row) => row.id === key)));
      })
      .catch((e) => {
        setPending([]);
        message.error(e.message || '待审批加载失败');
      })
      .finally(() => setLoading(false));
  };

  const loadProcessed = (page = processedPage, size = processedSize, status = processedStatus) => {
    setProcessedLoading(true);
    SecurityAPI.processedApprovals({
      status: status === 'ALL' ? undefined : status,
      page,
      size,
    })
      .then((data) => {
        setProcessed(data.content.map(normalizeApproval));
        setProcessedPage(data.number);
        setProcessedSize(data.size);
        setProcessedTotal(data.totalElements);
      })
      .catch((e) => {
        setProcessed([]);
        setProcessedTotal(0);
        message.error(e.message || '已处理审批加载失败');
      })
      .finally(() => setProcessedLoading(false));
  };

  const loadGrants = (status = grantStatus) => {
    setGrantsLoading(true);
    SecurityAPI.listGrants({ status: status === 'ALL' ? undefined : status })
      .then(setGrants)
      .catch((e) => {
        setGrants([]);
        message.error(e.message || '授权列表加载失败');
      })
      .finally(() => setGrantsLoading(false));
  };

  const refreshApprovals = () => {
    loadPending();
    loadProcessed();
    loadGrants();
  };

  useEffect(() => {
    loadPending();
    loadGrants();
  }, []);

  useEffect(() => {
    loadProcessed(0, processedSize, processedStatus);
  }, [processedStatus]);

  useEffect(() => {
    setComment('');
    setNextApproverId('');
  }, [drawerId]);

  const removePendingRows = (ids: string[]) => {
    setPending((rows) => rows.filter((row) => !ids.includes(row.id)));
    setSelectedRowKeys((keys) => keys.filter((key) => !ids.includes(key)));
  };

  const executeSchemaChange = (row: ApprovalRow) => {
    setActionLoading(`execute-${row.id}`);
    return CatalogAPI.executeSchemaChange(row.id)
      .then((result) => {
        loadProcessed(0, processedSize, processedStatus);
        message.success(result.message || 'Schema 变更已执行');
        return result;
      })
      .catch((e) => {
        message.error(e.message || 'Schema 变更执行失败');
        throw e;
      })
      .finally(() => setActionLoading(undefined));
  };

  const approveOne = (row: ApprovalRow) => {
    setActionLoading(`approve-${row.id}`);
    SecurityAPI.approveApproval(row.id, comment || undefined)
      .then(async (grant) => {
        if (grant?.assetFqn) {
          removePendingRows([row.id]);
          loadProcessed(0, processedSize, processedStatus);
          loadGrants();
          message.success(`已通过，授权已生效：${grant.assetFqn}`);
        } else if (row.requestType === 'SCHEMA_CHANGE') {
          await CatalogAPI.executeSchemaChange(row.id);
          removePendingRows([row.id]);
          loadProcessed(0, processedSize, processedStatus);
          message.success('Schema 变更已通过并执行');
        } else {
          loadPending();
          message.success('一审已通过，等待二审复核');
        }
        setDrawerId(undefined);
      })
      .catch((e) => message.error(e.message || '审批通过失败'))
      .finally(() => setActionLoading(undefined));
  };

  const rejectOne = (row: ApprovalRow) => {
    setActionLoading(`reject-${row.id}`);
    SecurityAPI.rejectApproval(row.id, comment || undefined)
      .then(() => {
        removePendingRows([row.id]);
        loadProcessed(0, processedSize, processedStatus);
        setDrawerId(undefined);
        message.success('已驳回申请');
      })
      .catch((e) => message.error(e.message || '审批驳回失败'))
      .finally(() => setActionLoading(undefined));
  };

  const approveSelected = () => {
    const ids = [...selectedRowKeys];
    setActionLoading('batch-approve');
    Promise.all(ids.map((approvalId) => SecurityAPI.approveApproval(approvalId, 'batch-approve')))
      .then(() => {
        removePendingRows(ids);
        loadProcessed(0, processedSize, processedStatus);
        message.success(`已批量通过 ${ids.length} 项审批`);
      })
      .catch((e) => message.error(e.message || '批量通过失败'))
      .finally(() => setActionLoading(undefined));
  };

  const rejectSelected = () => {
    const ids = [...selectedRowKeys];
    setActionLoading('batch-reject');
    Promise.all(ids.map((approvalId) => SecurityAPI.rejectApproval(approvalId, 'batch-reject')))
      .then(() => {
        removePendingRows(ids);
        loadProcessed(0, processedSize, processedStatus);
        message.success(`已批量驳回 ${ids.length} 项审批`);
      })
      .catch((e) => message.error(e.message || '批量驳回失败'))
      .finally(() => setActionLoading(undefined));
  };

  const transferCurrent = (row: ApprovalRow) => {
    setActionLoading(`transfer-${row.id}`);
    SecurityAPI.transferApproval(row.id, nextApproverId || undefined, comment || undefined)
      .then(() => {
        loadPending();
        message.success('已记录转交');
      })
      .catch((e) => message.error(e.message || '转交失败'))
      .finally(() => setActionLoading(undefined));
  };

  const addSignCurrent = (row: ApprovalRow) => {
    setActionLoading(`add-sign-${row.id}`);
    SecurityAPI.addSignApproval(row.id, 'SECURITY_REVIEW', comment || undefined)
      .then(() => {
        loadPending();
        message.success('已加签安全复核');
      })
      .catch((e) => message.error(e.message || '加签失败'))
      .finally(() => setActionLoading(undefined));
  };

  const createManualGrant = () => {
    grantForm.validateFields()
      .then((values) => {
        setActionLoading('create-grant');
        return SecurityAPI.createGrant(values.subjectId, values.assetFqn, {
          permissions: {
            query: true,
            download: Boolean(values.download),
            api: Boolean(values.api),
          },
          durationDays: values.durationDays ?? 30,
        });
      })
      .then(() => {
        setGrantModalOpen(false);
        grantForm.resetFields();
        loadGrants();
        message.success('授权已创建');
      })
      .catch((e) => {
        if (e?.errorFields) return;
        message.error(e.message || '创建授权失败');
      })
      .finally(() => setActionLoading(undefined));
  };

  const revokeGrant = (grant: AccessGrant) => {
    setActionLoading(`revoke-${grant.id}`);
    SecurityAPI.revokeGrant(grant.id, 'manual-revoke')
      .then(() => {
        loadGrants();
        message.success('授权已撤销');
      })
      .catch((e) => message.error(e.message || '撤销授权失败'))
      .finally(() => setActionLoading(undefined));
  };

  const extendGrant = (grant: AccessGrant) => {
    setActionLoading(`extend-${grant.id}`);
    SecurityAPI.extendGrant(grant.id, 30)
      .then(() => {
        loadGrants();
        message.success('授权已延期 30 天');
      })
      .catch((e) => message.error(e.message || '延期失败'))
      .finally(() => setActionLoading(undefined));
  };

  const columns = [
    { title: '类型', dataIndex: 'requestType', width: 120, render: (t: string) => (
      <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>{t}</span>
    ) },
    { title: '对象', dataIndex: 'targetRef', render: (v: string, r: ApprovalRow) => (
      <a className="ol-link" onClick={() => setDrawerId(r.id)}><Text code style={{ fontSize: 12 }}>{v}</Text></a>
    ) },
    { title: '申请人', dataIndex: 'applicantName', width: 120 },
    { title: '内容', width: 180, render: (_: unknown, row: ApprovalRow) => (
      <Text style={{ fontSize: 12 }}>{requestSummary(row)}</Text>
    ) },
    { title: '原因', dataIndex: 'reason', ellipsis: true, render: (r?: string) => (
      <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{r || '-'}</span>
    ) },
    { title: '风险', dataIndex: 'riskLevel', width: 90, render: (l: ApprovalRow['riskLevel']) => (
      <IntentBadge intent={riskColor[l] || 'success'}>{l}</IntentBadge>
    ) },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <StatusBadge status={s} /> },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{formatTime(t)}</span> },
    { title: '操作', width: 90, render: (_: unknown, r: ApprovalRow) => (
      <Button size="small" type="primary" onClick={() => setDrawerId(r.id)}>审批</Button>
    ) },
  ];

  const grantColumns = [
    { title: '资产', dataIndex: 'assetFqn', render: (value: string) => <Text code style={{ fontSize: 12 }}>{value}</Text> },
    { title: '用户', dataIndex: 'subjectId', width: 230, render: (value: string) => <Text code style={{ fontSize: 12 }}>{value}</Text> },
    { title: '权限', width: 190, render: (_: unknown, row: AccessGrant) => {
      const permissions = grantPermissions(row) as { query?: boolean; download?: boolean; api?: boolean };
      return (
        <Space size={6} wrap>
          {permissions.query && <Tag color="success" style={{ margin: 0 }}>query</Tag>}
          {permissions.download && <Tag color="processing" style={{ margin: 0 }}>download</Tag>}
          {permissions.api && <Tag color="purple" style={{ margin: 0 }}>api</Tag>}
        </Space>
      );
    } },
    { title: '状态', dataIndex: 'status', width: 100, render: (status: AccessGrant['status']) => (
      <Tag color={status === 'ACTIVE' ? 'success' : status === 'EXPIRED' ? 'warning' : 'default'} style={{ margin: 0 }}>
        {status}
      </Tag>
    ) },
    { title: '授权时间', dataIndex: 'grantedAt', width: 170, render: (value: string) => <span style={{ fontSize: 12 }}>{formatTime(value)}</span> },
    { title: '到期时间', dataIndex: 'expiresAt', width: 170, render: (value?: string) => <span style={{ fontSize: 12 }}>{value ? formatTime(value) : '长期'}</span> },
    { title: '操作', width: 160, render: (_: unknown, row: AccessGrant) => (
      <Space size={6}>
        <Button size="small" onClick={() => extendGrant(row)} loading={actionLoading === `extend-${row.id}`}>
          延期
        </Button>
        {row.status !== 'REVOKED' && (
          <Popconfirm
            title="撤销这条授权？"
            okText="撤销"
            cancelText="取消"
            onConfirm={() => revokeGrant(row)}
          >
            <Button size="small" danger loading={actionLoading === `revoke-${row.id}`}>撤销</Button>
          </Popconfirm>
        )}
      </Space>
    ) },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AuditOutlined />}
        title="审批中心"
        subtitle={<span className="ol-chip">系统 · L10-3</span>}
        description="访问 / 订阅 / 发布 / 下线 / Schema 变更 / 豁免 / 升额 七类审批"
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={refreshApprovals} loading={loading || processedLoading}>刷新</Button>
            <Button icon={<PlusOutlined />} onClick={() => setGrantModalOpen(true)}>手工授权</Button>
            <Button
              disabled={selectedRowKeys.length === 0}
              loading={actionLoading === 'batch-approve'}
              icon={<CheckOutlined />}
              onClick={approveSelected}
            >
              批量通过
            </Button>
            <Button
              disabled={selectedRowKeys.length === 0}
              danger
              loading={actionLoading === 'batch-reject'}
              icon={<CloseOutlined />}
              onClick={rejectSelected}
            >
              批量驳回
            </Button>
          </>
        }
      />

      <SectionCard padded="none" bodyStyle={{ padding: 0 }}>
        <div style={{ padding: '0 16px' }}>
          <Tabs items={[
            { key: 'pending', label: `待审批 (${counts.pending})`, children: (
              <Table
                rowKey="id"
                rowSelection={{ selectedRowKeys, onChange: (keys) => setSelectedRowKeys(keys as string[]) }}
                dataSource={pending}
                loading={loading}
                locale={{
                  emptyText: <StateView state="empty" title="暂无待审批" description="所有申请都已处理完毕。" />,
                }}
                size="middle"
                pagination={false}
                columns={columns}
              />
            ) },
            { key: 'grants', label: `授权管理 (${counts.grants})`, children: (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                  <Select
                    size="small"
                    value={grantStatus}
                    onChange={(value) => {
                      const next = value as typeof grantStatus;
                      setGrantStatus(next);
                      loadGrants(next);
                    }}
                    style={{ width: 140 }}
                    options={[
                      { label: '生效授权', value: 'ACTIVE' },
                      { label: '全部状态', value: 'ALL' },
                      { label: '已过期', value: 'EXPIRED' },
                      { label: '已撤销', value: 'REVOKED' },
                    ]}
                  />
                  <Button size="small" icon={<ReloadOutlined />} loading={grantsLoading} onClick={() => loadGrants()}>
                    刷新
                  </Button>
                </div>
                <Table
                  rowKey="id"
                  dataSource={grants}
                  loading={grantsLoading}
                  size="middle"
                  pagination={{ pageSize: 10 }}
                  locale={{
                    emptyText: <StateView state="empty" title="暂无授权记录" description="审批通过或手工授权后会在这里展示。" />,
                  }}
                  columns={grantColumns}
                />
              </Space>
            ) },
            { key: 'history', label: `已处理 (${counts.processed})`, children: (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <Select
                    size="small"
                    value={processedStatus}
                    onChange={(value) => setProcessedStatus(value as typeof processedStatus)}
                    style={{ width: 140 }}
                    options={[
                      { label: '全部状态', value: 'ALL' },
                      { label: '已通过', value: 'APPROVED' },
                      { label: '已驳回', value: 'REJECTED' },
                      { label: '已取消', value: 'CANCELED' },
                    ]}
                  />
                </div>
                <Table
                  rowKey="id"
                  dataSource={processed}
                  loading={processedLoading}
                  size="middle"
                  pagination={{
                    current: processedPage + 1,
                    pageSize: processedSize,
                    total: processedTotal,
                    showSizeChanger: true,
                    onChange: (page, size) => loadProcessed(page - 1, size, processedStatus),
                  }}
                  locale={{
                    emptyText: <StateView state="empty" title="暂无已处理审批" description="处理通过或驳回后的审批会在这里沉淀为历史记录。" />,
                  }}
                  columns={[
                    { title: '类型', dataIndex: 'requestType', render: (t: string) => <span className="ol-chip">{t}</span> },
                    { title: '对象', dataIndex: 'targetRef', render: (v: string, r: ApprovalRow) => (
                      <a className="ol-link" onClick={() => setDrawerId(r.id)}><Text code style={{ fontSize: 12 }}>{v}</Text></a>
                    ) },
                    { title: '申请人', dataIndex: 'applicantName' },
                    { title: '审批人', dataIndex: 'approverName', render: (a?: string) => a || '-' },
                    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
                    { title: '决定时间', dataIndex: 'decidedAt', render: (t?: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{formatTime(t)}</span> },
                  ]}
                />
              </Space>
            ) },
          ]} />
        </div>
      </SectionCard>

      <Drawer
        open={!!current}
        onClose={() => setDrawerId(undefined)}
        title={current ? `审批详情 · ${current.id}` : ''}
        width={680}
        extra={current?.status === 'PENDING' ? (
          <Space>
            <Button
              loading={actionLoading === `add-sign-${current.id}`}
              onClick={() => addSignCurrent(current)}
            >
              加签
            </Button>
            <Button
              disabled={!nextApproverId.trim()}
              loading={actionLoading === `transfer-${current.id}`}
              onClick={() => transferCurrent(current)}
            >
              转交
            </Button>
            <Button
              danger
              loading={actionLoading === `reject-${current.id}`}
              onClick={() => rejectOne(current)}
            >
              驳回
            </Button>
            <Button
              type="primary"
              loading={actionLoading === `approve-${current.id}`}
              onClick={() => approveOne(current)}
            >
              {current.requestType === 'ACCESS' ? '通过并授权' : current.requestType === 'SCHEMA_CHANGE' ? '通过并执行' : '通过'}
            </Button>
          </Space>
        ) : current?.requestType === 'SCHEMA_CHANGE' && current.status === 'APPROVED' && current.payload?.executionStatus !== 'SUCCEEDED' ? (
          <Button
            type="primary"
            loading={actionLoading === `execute-${current.id}`}
            onClick={() => executeSchemaChange(current)}
          >
            执行变更
          </Button>
        ) : undefined}
      >
        {current && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div className="ol-section" style={{ padding: 14 }}>
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>类型 / 申请人</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space split={<span className="ol-divider-v" />}>
                      <span className="ol-chip">{current.targetType || current.requestType}</span>
                      <Text>{current.applicantName}</Text>
                    </Space>
                  </div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>申请对象</Text>
                  <div style={{ marginTop: 4 }}><Text code style={{ fontSize: 12 }}>{current.targetRef}</Text></div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>申请理由</Text>
                  <div style={{ marginTop: 4, fontSize: 13 }}>{current.reason || '-'}</div>
                </div>
                {current.requestType === 'SCHEMA_CHANGE' ? (
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>Schema 变更内容</Text>
                    <div style={{ marginTop: 4 }}>
                      <Space split={<span className="ol-divider-v" />} wrap>
                        <Text style={{ fontSize: 12 }}>{schemaChangeText(current.payload)}</Text>
                        <Text style={{ fontSize: 12 }}>
                          {current.payload?.nullable == null ? '空值策略未指定' : current.payload.nullable ? '允许为空' : '不允许为空'}
                        </Text>
                        <Text style={{ fontSize: 12 }}>{current.payload?.executionMode || 'APPROVAL_ONLY'}</Text>
                      </Space>
                    </div>
                    {current.payload?.beforeColumns?.length ? (
                      <div style={{ marginTop: 8 }}>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          当前字段快照 {current.payload.beforeColumns.length} 列，审批通过后仍需由 Schema 执行器应用 DDL。
                        </Text>
                      </div>
                    ) : null}
                    {(current.payload?.executionStatus || current.payload?.executionSql || current.payload?.executionError) && (
                      <div style={{ marginTop: 10 }}>
                        <Space direction="vertical" size={6} style={{ width: '100%' }}>
                          <Space size={6} wrap>
                            <Tag color={current.payload.executionStatus === 'SUCCEEDED' ? 'success' : current.payload.executionStatus === 'FAILED' ? 'error' : 'processing'} style={{ margin: 0 }}>
                              {current.payload.executionStatus || 'PENDING_EXECUTION'}
                            </Tag>
                            {current.payload.executionError && <Text type="danger" style={{ fontSize: 12 }}>{current.payload.executionError}</Text>}
                          </Space>
                          {current.payload.executionSql && (
                            <pre style={{ margin: 0, padding: 10, background: 'var(--ol-fill-soft)', border: '1px solid var(--ol-line-soft)', borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                              {current.payload.executionSql}
                            </pre>
                          )}
                        </Space>
                      </div>
                    )}
                  </div>
                ) : (
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>权限范围 / 有效期</Text>
                    <div style={{ marginTop: 4 }}>
                      <Space split={<span className="ol-divider-v" />} wrap>
                        <Text style={{ fontSize: 12 }}>{permissionText(current.payload)}</Text>
                        <Text style={{ fontSize: 12 }}>{current.payload?.durationDays ? `${current.payload.durationDays} 天` : '长期'}</Text>
                      </Space>
                    </div>
                  </div>
                )}
                {current.payload?.sql && (
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>来源 SQL</Text>
                    <pre style={{ margin: '4px 0 0', padding: 10, background: 'var(--ol-fill-soft)', border: '1px solid var(--ol-line-soft)', borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                      {current.payload.sql}
                    </pre>
                  </div>
                )}
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>风险等级 / 影响</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space split={<span className="ol-divider-v" />} wrap>
                      <IntentBadge intent={riskColor[current.riskLevel || 'LOW']}>{current.riskLevel}</IntentBadge>
                      <Text style={{ fontSize: 12 }}>
                        {current.impactSummary.assets} 资产 · {current.impactSummary.apis} API · {current.impactSummary.subscribers} 订阅方
                      </Text>
                    </Space>
                  </div>
                </div>
              </Space>
            </div>

            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 10 }}>审批链 / 历史意见</div>
              <Timeline
                items={(current.chain?.length ? current.chain : [{ role: 'SEC / ADMIN', status: current.status === 'APPROVED' ? 'APPROVED' : current.status === 'REJECTED' ? 'REJECTED' : 'PENDING', at: current.decidedAt, comment: current.comment }]).map((step) => ({
                  color: step.status === 'APPROVED' ? 'green' : step.status === 'REJECTED' ? 'red' : 'blue',
                  children: (
                    <Space direction="vertical" size={4}>
                      <Space size={6} wrap>
                        <Tag style={{ margin: 0 }}>{step.role}</Tag>
                        <Tag color={step.status === 'APPROVED' ? 'success' : step.status === 'REJECTED' ? 'error' : 'processing'} style={{ margin: 0 }}>{step.status}</Tag>
                        <Text type="secondary" style={{ fontSize: 11 }}>{step.at ? formatTime(step.at) : '等待处理'}</Text>
                      </Space>
                      {step.comment && <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{step.comment}</Text>}
                    </Space>
                  ),
                }))}
              />
            </div>

            {current.status === 'PENDING' && (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 6 }}>转交对象</div>
                  <Input
                    placeholder="填写接收人用户 ID，留空则不能转交"
                    value={nextApproverId}
                    onChange={(event) => setNextApproverId(event.target.value)}
                  />
                </div>
                <div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 6 }}>审批意见</div>
                  <Input.TextArea
                    placeholder="填写审批意见（可选）"
                    rows={3}
                    value={comment}
                    onChange={(event) => setComment(event.target.value)}
                  />
                </div>
              </Space>
            )}
          </Space>
        )}
      </Drawer>

      <Modal
        open={grantModalOpen}
        title="手工授予资产权限"
        okText="创建授权"
        cancelText="取消"
        confirmLoading={actionLoading === 'create-grant'}
        onOk={createManualGrant}
        onCancel={() => setGrantModalOpen(false)}
        destroyOnHidden
      >
        <Form
          form={grantForm}
          layout="vertical"
          preserve={false}
          initialValues={{ durationDays: 30, download: false, api: false }}
        >
          <Form.Item name="subjectId" label="授权用户 ID" rules={[{ required: true, message: '请填写授权用户 ID' }]}>
            <Input placeholder="用户 UUID" />
          </Form.Item>
          <Form.Item name="assetFqn" label="资产 FQN" rules={[{ required: true, message: '请填写资产 FQN' }]}>
            <Input placeholder="例如 ods.ods_customers_100k" />
          </Form.Item>
          <Form.Item name="durationDays" label="有效期">
            <InputNumber min={0} max={366} style={{ width: '100%' }} addonAfter="天，0 表示长期" />
          </Form.Item>
          <Form.Item label="权限范围">
            <Space direction="vertical" size={6}>
              <Checkbox checked disabled>查询 query</Checkbox>
              <Form.Item name="download" valuePropName="checked" noStyle>
                <Checkbox>下载 download</Checkbox>
              </Form.Item>
              <Form.Item name="api" valuePropName="checked" noStyle>
                <Checkbox>API 调用 api</Checkbox>
              </Form.Item>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
