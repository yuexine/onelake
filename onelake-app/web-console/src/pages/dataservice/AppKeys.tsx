/**
 * AppKey / 凭据管理（对应原型 §8.8.7 升级版）。
 */
import { Table, Tag, Space, Button, Modal, Form, Input, message, Tooltip, Typography } from 'antd';
import { PlusOutlined, CopyOutlined, ReloadOutlined, KeyOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { appKeys } from '../../mock';
import { StatusBadge, PageHeader, SectionCard, useAsyncAction, StateView } from '../../components';

const { Text } = Typography;

export default function AppKeys() {
  const [newOpen, setNewOpen] = useState(false);
  const { run, isLoading } = useAsyncAction();

  return (
    <div className="ol-page">
      <PageHeader
        icon={<KeyOutlined />}
        title="我的凭据"
        subtitle={<span className="ol-chip">数据服务 · L5-3</span>}
        description="管理 AppKey + Secret、IP 白名单、配额；Secret 仅展示一次需妥善保存"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setNewOpen(true)}>新建凭据</Button>}
      />

      <SectionCard title="凭据列表" icon={<KeyOutlined />} subtitle="点击行展开查看 AppKey/Secret/IP 白名单/调用统计" flatBody>
        <Table
          rowKey="id"
          dataSource={appKeys}
          size="middle"
          pagination={false}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无凭据"
                description="创建 AppKey 以调用已订阅的 API"
                cta={<Button type="primary" icon={<PlusOutlined />} onClick={() => setNewOpen(true)}>+ 新建凭据</Button>}
              />
            ),
          }}
          expandable={{
            expandedRowRender: (r: any) => (
              <div className="ol-section" style={{ padding: 16, background: 'var(--ol-fill-soft)' }}>
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>AppKey</Text>
                    <div style={{ marginTop: 4 }}>
                      <Space>
                        <Text code style={{ fontSize: 12 }}>{r.appKey}</Text>
                        <Tooltip title="复制">
                          <Button type="text" size="small" icon={<CopyOutlined />} />
                        </Tooltip>
                      </Space>
                    </div>
                  </div>
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>Secret</Text>
                    <div style={{ marginTop: 4 }}>
                      <Space>
                        <Text code style={{ fontSize: 12 }}>••••••••••••</Text>
                        <Button size="small" type="link" icon={<ReloadOutlined />}
                          loading={isLoading(`reset-${r.id}`)}
                          onClick={() => run(`reset-${r.id}`, async () => {
                            await new Promise((resolve) => setTimeout(resolve, 500));
                          }, {
                            successMsg: `已重置 ${r.appKey} 的 Secret（仅展示一次，请妥善保存）`,
                            errorMsg: 'Secret 重置失败，请重试',
                            duration: 4,
                          })}
                        >重置</Button>
                      </Space>
                    </div>
                  </div>
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>IP 白名单</Text>
                    <div style={{ marginTop: 4 }}>
                      {(r.ipWhitelist || []).map((ip: string) => <Tag key={ip} color="processing" style={{ margin: 2 }}>{ip}</Tag>)}
                    </div>
                  </div>
                  <div>
                    <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>最近调用统计</Text>
                    <div style={{ marginTop: 4, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <Tag color="success" style={{ padding: '2px 10px' }}>2xx: {r.recentCalls?.status2xx.toLocaleString()}</Tag>
                      <Tag color="warning" style={{ padding: '2px 10px' }}>429: {r.recentCalls?.status429}</Tag>
                      <Tag color="error" style={{ padding: '2px 10px' }}>401: {r.recentCalls?.status401}</Tag>
                    </div>
                  </div>
                  <Space>
                    <Button type="link" onClick={() => message.info('查看调用统计')}>调用统计</Button>
                    <Button type="link" onClick={() => message.info('提交升额申请')}>申请升额</Button>
                    <Button type="link" danger>禁用</Button>
                  </Space>
                </Space>
              </div>
            ),
          }}
          columns={[
            { title: 'AppKey', dataIndex: 'appKey', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '所有者', dataIndex: 'ownerName' },
            { title: '配额/日', dataIndex: 'quotaDaily', align: 'right' as const, render: (v?: number) => v ? <span className="mono tnum">{v.toLocaleString()}</span> : '-' },
            { title: '过期时间', dataIndex: 'expiresAt', render: (v?: string) => v ? <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{v}</span> : '-' },
            { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <StatusBadge status={s} /> },
          ]}
        />
      </SectionCard>

      <Modal
        open={newOpen}
        onCancel={() => setNewOpen(false)}
        title="新建 AppKey"
        okButtonProps={{ loading: isLoading('create-appkey') }}
        onOk={() => run('create-appkey', async () => {
          await new Promise((r) => setTimeout(r, 600));
          setNewOpen(false);
        }, {
          successMsg: 'AppKey 已创建（Secret 仅展示一次，请妥善保存）',
          errorMsg: 'AppKey 创建失败，请重试',
          duration: 5,
        })}
      >
        <Form layout="vertical">
          <Form.Item label="所有者"><Input defaultValue="报表组" /></Form.Item>
          <Form.Item label="IP 白名单"><Input.TextArea placeholder="10.0.0.0/24" /></Form.Item>
          <Form.Item label="每日配额"><Input defaultValue={100000} /></Form.Item>
          <Form.Item label="过期时间"><Input placeholder="2026-12-31" /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
