/**
 * 审批中心（对应原型 §8.10 / §8.10.4）。
 * 批量审批 + 详情抽屉（审批链 / 历史意见 / 通过 / 驳回 / 转交 / 加签）。
 */
import { Card, Table, Tag, Space, Button, Tabs, Drawer, Descriptions, Timeline, Input, Typography, message } from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { approvals } from '../../mock';
import { StatusBadge, ImpactAnalysis } from '../../components';

const { Text } = Typography;

export default function Approvals() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [drawerId, setDrawerId] = useState<string | undefined>(id);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);

  const current = approvals.find((a) => a.id === drawerId);

  return (
    <Card title="系统管理 / 审批中心"
      extra={<Space>
        <Button disabled={selectedRowKeys.length === 0} onClick={() => message.success(`已批量通过 ${selectedRowKeys.length} 项`)}>批量通过</Button>
        <Button disabled={selectedRowKeys.length === 0} danger onClick={() => message.success(`已批量驳回 ${selectedRowKeys.length} 项`)}>批量驳回</Button>
      </Space>}>
      <Tabs items={[
        { key: 'pending', label: '待审批', children: (
          <Table rowKey="id" rowSelection={{ selectedRowKeys, onChange: (k) => setSelectedRowKeys(k as string[]) }}
            dataSource={approvals.filter((a) => a.status === 'PENDING')} size="middle"
            columns={[
              { title: '类型', dataIndex: 'requestType', render: (t: string) => <Tag color="blue">{t}</Tag> },
              { title: '对象', dataIndex: 'targetRef', render: (v: string, r: any) => <a onClick={() => setDrawerId(r.id)}>{v}</a> },
              { title: '申请人', dataIndex: 'applicantName' },
              { title: '原因', dataIndex: 'reason', ellipsis: true },
              { title: '风险', dataIndex: 'riskLevel', render: (l: string) => <Tag color={l === 'HIGH' ? 'red' : l === 'MEDIUM' ? 'orange' : 'green'}>{l}</Tag> },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
              { title: '时间', dataIndex: 'createdAt' },
              { title: '操作', render: (_: unknown, r: any) => <Button size="small" type="primary" onClick={() => setDrawerId(r.id)}>审批</Button> },
            ]} />
        ) },
        { key: 'history', label: '审批历史', children: (
          <Table rowKey="id" dataSource={approvals.filter((a) => a.status !== 'PENDING')} size="middle"
            columns={[
              { title: '类型', dataIndex: 'requestType' },
              { title: '对象', dataIndex: 'targetRef' },
              { title: '申请人', dataIndex: 'applicantName' },
              { title: '审批人', dataIndex: 'approverName' },
              { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
              { title: '决定时间', dataIndex: 'decidedAt' },
            ]} />
        ) },
      ]} />

      <Drawer open={!!current} onClose={() => setDrawerId(undefined)} title={current ? `审批详情 · ${current.id}` : ''} width={680}
        extra={<Space>
          <Button>转交</Button><Button>加签</Button>
          <Button danger onClick={() => { setDrawerId(undefined); message.success('已驳回，结果回写来源页并通知申请人'); }}>驳回</Button>
          <Button type="primary" onClick={() => { setDrawerId(undefined); message.success('已通过，回写来源页状态 + 通知申请人'); }}>通过</Button>
        </Space>}>
        {current && (
          <>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="类型">{current.targetType}</Descriptions.Item>
              <Descriptions.Item label="申请人">{current.applicantName}</Descriptions.Item>
              <Descriptions.Item label="申请对象"><Text code>{current.targetRef}</Text></Descriptions.Item>
              <Descriptions.Item label="申请理由">{current.reason}</Descriptions.Item>
              <Descriptions.Item label="风险等级"><Tag color={current.riskLevel === 'HIGH' ? 'red' : current.riskLevel === 'MEDIUM' ? 'orange' : 'green'}>{current.riskLevel}</Tag></Descriptions.Item>
              <Descriptions.Item label="影响">
                {current.impactSummary?.assets ?? 0} 资产 / {current.impactSummary?.apis ?? 0} API / {current.impactSummary?.subscribers ?? 0} 订阅方
              </Descriptions.Item>
            </Descriptions>

            <Card size="small" title="审批链 / 历史意见" style={{ marginTop: 12 }}>
              <Timeline items={current.chain?.map((c) => ({
                color: c.status === 'APPROVED' ? 'green' : c.status === 'REJECTED' ? 'red' : 'gray',
                children: <Space><Tag>{c.role}</Tag>{c.user && <Text>{c.user}</Text>}<Tag color={c.status === 'APPROVED' ? 'success' : c.status === 'REJECTED' ? 'error' : 'processing'}>{c.status}</Tag>{c.at && <Text type="secondary">{c.at}</Text>}{c.comment && <Text type="secondary">"{c.comment}"</Text>}</Space>,
              })) || []} />
            </Card>

            {current.riskLevel === 'HIGH' && (
              <ImpactAnalysis impact={{
                assets: current.impactSummary?.assets ? [current.targetRef] : [],
                apis: current.impactSummary?.apis ? [current.targetRef] : [],
                subscribers: current.impactSummary?.subscribers,
                blocking: true,
                suggestion: '高风险变更，需资产负责人 + 安全合规双重审批',
              }} />
            )}

            <Card size="small" title="审批意见" style={{ marginTop: 12 }}>
              <Input.TextArea placeholder="填写审批意见（可选）" rows={3} />
            </Card>
          </>
        )}
      </Drawer>
    </Card>
  );
}
