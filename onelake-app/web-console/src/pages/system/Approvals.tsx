/**
 * 审批中心（对应原型 §8.10 升级版）。
 *   批量审批 + 详情抽屉（审批链 / 历史意见 / 通过 / 驳回 / 转交 / 加签）
 */
import { Table, Tag, Space, Button, Tabs, Drawer, Timeline, Input, Typography, message } from 'antd';
import { AuditOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useState } from 'react';
import { approvals } from '../../mock';
import { StatusBadge, ImpactAnalysis, PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

const RISK_COLOR: Record<string, { bg: string; fg: string }> = {
  HIGH:   { bg: 'var(--ol-error-soft)',   fg: 'var(--ol-error)' },
  MEDIUM: { bg: 'var(--ol-warning-soft)', fg: '#B45309' },
  LOW:    { bg: 'var(--ol-success-soft)', fg: 'var(--ol-success)' },
};

export default function Approvals() {
  const { id } = useParams();
  const [drawerId, setDrawerId] = useState<string | undefined>(id);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);

  const current = approvals.find((a) => a.id === drawerId);

  const counts = {
    pending: approvals.filter((a) => a.status === 'PENDING').length,
    approved: approvals.filter((a) => a.status === 'APPROVED').length,
    rejected: approvals.filter((a) => a.status === 'REJECTED').length,
    high: approvals.filter((a) => a.riskLevel === 'HIGH' && a.status === 'PENDING').length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AuditOutlined />}
        title="审批中心"
        subtitle={<span className="ol-chip">系统 · L10-3</span>}
        description="访问 / 订阅 / 发布 / 下线 / Schema 变更 / 豁免 / 升额 七类审批"
        actions={
          <>
            <Button disabled={selectedRowKeys.length === 0} icon={<CheckOutlined />}
              onClick={() => message.success(`已批量通过 ${selectedRowKeys.length} 项`)}>
              批量通过
            </Button>
            <Button disabled={selectedRowKeys.length === 0} danger icon={<CloseOutlined />}
              onClick={() => message.success(`已批量驳回 ${selectedRowKeys.length} 项`)}>
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
                rowSelection={{ selectedRowKeys, onChange: (k) => setSelectedRowKeys(k as string[]) }}
                dataSource={approvals.filter((a) => a.status === 'PENDING')}
                size="middle"
                pagination={false}
                columns={[
                  { title: '类型', dataIndex: 'requestType', width: 120, render: (t: string) => (
                    <span className="ol-chip" style={{ background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)', border: 'none' }}>{t}</span>
                  ) },
                  { title: '对象', dataIndex: 'targetRef', render: (v: string, r: any) => (
                    <a className="ol-link" onClick={() => setDrawerId(r.id)}><Text code style={{ fontSize: 12 }}>{v}</Text></a>
                  ) },
                  { title: '申请人', dataIndex: 'applicantName', width: 100 },
                  { title: '原因', dataIndex: 'reason', ellipsis: true, render: (r?: string) => (
                    <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{r || '-'}</span>
                  ) },
                  { title: '风险', dataIndex: 'riskLevel', width: 90, render: (l: string) => {
                    const c = RISK_COLOR[l] || RISK_COLOR.LOW;
                    return (
                      <span style={{
                        padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                        background: c.bg, color: c.fg,
                      }}>{l}</span>
                    );
                  } },
                  { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <StatusBadge status={s} /> },
                  { title: '时间', dataIndex: 'createdAt', render: (t: string) => <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> },
                  { title: '操作', width: 90, render: (_: unknown, r: any) => (
                    <Button size="small" type="primary" onClick={() => setDrawerId(r.id)}>审批</Button>
                  ) },
                ]}
              />
            ) },
            { key: 'history', label: '审批历史', children: (
              <Table rowKey="id" dataSource={approvals.filter((a) => a.status !== 'PENDING')} size="middle"
                columns={[
                  { title: '类型', dataIndex: 'requestType', render: (t: string) => (
                    <span className="ol-chip">{t}</span>
                  ) },
                  { title: '对象', dataIndex: 'targetRef', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
                  { title: '申请人', dataIndex: 'applicantName' },
                  { title: '审批人', dataIndex: 'approverName', render: (a?: string) => a || '-' },
                  { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
                  { title: '决定时间', dataIndex: 'decidedAt', render: (t?: string) => t ? <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t}</span> : '-' },
                ]} />
            ) },
          ]} />
        </div>
      </SectionCard>

      <Drawer
        open={!!current}
        onClose={() => setDrawerId(undefined)}
        title={current ? `审批详情 · ${current.id}` : ''}
        width={680}
        extra={
          <Space>
            <Button>转交</Button>
            <Button>加签</Button>
            <Button danger onClick={() => { setDrawerId(undefined); message.success('已驳回，结果回写来源页并通知申请人'); }}>驳回</Button>
            <Button type="primary" onClick={() => { setDrawerId(undefined); message.success('已通过，回写来源页状态 + 通知申请人'); }}>通过</Button>
          </Space>
        }
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
                  <div style={{ marginTop: 4, fontSize: 13 }}>{current.reason}</div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>风险等级 / 影响</Text>
                  <div style={{ marginTop: 4 }}>
                    <Space split={<span className="ol-divider-v" />} wrap>
                      <span style={{
                        padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                        background: (RISK_COLOR[current.riskLevel || 'LOW'] || RISK_COLOR.LOW).bg,
                        color: (RISK_COLOR[current.riskLevel || 'LOW'] || RISK_COLOR.LOW).fg,
                      }}>{current.riskLevel}</span>
                      <Text style={{ fontSize: 12 }}>
                        {current.impactSummary?.assets ?? 0} 资产 · {current.impactSummary?.apis ?? 0} API · {current.impactSummary?.subscribers ?? 0} 订阅方
                      </Text>
                    </Space>
                  </div>
                </div>
              </Space>
            </div>

            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 10 }}>审批链 / 历史意见</div>
              <Timeline items={current.chain?.map((c) => ({
                color: c.status === 'APPROVED' ? 'green' : c.status === 'REJECTED' ? 'red' : 'gray',
                children: (
                  <Space size={6} wrap>
                    <Tag style={{ margin: 0 }}>{c.role}</Tag>
                    {c.user && <Text style={{ fontSize: 12 }}>{c.user}</Text>}
                    <Tag color={c.status === 'APPROVED' ? 'success' : c.status === 'REJECTED' ? 'error' : 'processing'} style={{ margin: 0 }}>{c.status}</Tag>
                    {c.at && <Text type="secondary" style={{ fontSize: 11 }}>{c.at}</Text>}
                    {c.comment && <Text type="secondary" style={{ fontSize: 12 }}>「{c.comment}」</Text>}
                  </Space>
                ),
              })) || []} />
            </div>

            {current.riskLevel === 'HIGH' && (
              <ImpactAnalysis impact={{
                assets: current.impactSummary?.assets ? [current.targetRef] : [],
                apis: current.impactSummary?.apis ? [current.targetRef] : [],
                subscribers: current.impactSummary?.subscribers,
                blocking: true,
                suggestion: '高风险变更，需资产负责人 + 安全合规双重审批',
              }} />
            )}

            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 6 }}>审批意见</div>
              <Input.TextArea placeholder="填写审批意见（可选）" rows={3} />
            </div>
          </Space>
        )}
      </Drawer>
    </div>
  );
}
