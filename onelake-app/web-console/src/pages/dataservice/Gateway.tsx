/**
 * 网关路由（对应原型 §8.8.6 升级版）。
 */
import { Table, Tag, Space, Button, Form, Select, Input, Modal, message, Typography } from 'antd';
import { ApiOutlined, CloudOutlined, PlusOutlined, SwapOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { PageHeader, SectionCard, useAsyncAction, DangerConfirm } from '../../components';

const { Text } = Typography;

const routes = [
  { path: '/v2/order/detail', backend: 'daas-order', version: 'v2', gray: '100%', status: 'online' },
  { path: '/v1/order/detail', backend: 'daas-order', version: 'v1', gray: '-', status: 'deprecated' },
  { path: '/v1/user/profile', backend: 'daas-user', version: 'v1', gray: '-', status: 'deprecated' },
];

export default function Gateway() {
  const [open, setOpen] = useState(false);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const { run, isLoading } = useAsyncAction();

  return (
    <div className="ol-page">
      <PageHeader
        icon={<ApiOutlined />}
        title="API 网关 · 路由管理"
        subtitle={<span className="ol-chip">数据服务 · L5-2</span>}
        description="基于 APISIX 的路由策略、灰度发布、协议转换、插件编排"
        meta={[
          { label: '统一域名', value: <Text code style={{ fontSize: 12 }}>api.dataplat.io</Text> },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建路由</Button>}
      />

      <SectionCard title="路由列表" icon={<ApiOutlined />} flatBody>
        <Table
          size="middle"
          rowKey="path"
          dataSource={routes}
          pagination={false}
          columns={[
            { title: '路径', dataIndex: 'path', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '后端服务', dataIndex: 'backend', render: (v: string) => <span className="ol-chip">{v}</span> },
            { title: '版本', dataIndex: 'version', width: 80, render: (v: string) => <Tag color="blue" style={{ margin: 0 }}>{v}</Tag> },
            { title: '灰度', dataIndex: 'gray', width: 80, render: (g: string) => g === '-' ? <span className="ol-quiet">-</span> : <Tag color="processing" style={{ margin: 0 }}>{g}</Tag> },
            { title: '状态', dataIndex: 'status', width: 140, render: (s: string) => (
              <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
                background: s === 'online' ? 'var(--ol-success-soft)' : 'var(--ol-warning-soft)',
                color: s === 'online' ? 'var(--ol-success)' : '#B45309',
              }}>
                <span className={`ol-status-dot ${s === 'online' ? 'is-success' : 'is-warning'}`} />
                {s === 'online' ? '在线' : '410 倒计时'}
              </span>
            ) },
            { title: '操作', width: 180, render: () => (
              <Space>
                <Button size="small" type="link">编辑</Button>
                <Button size="small" type="link" danger
                  onClick={() => setRollbackOpen(true)}
                >
                  一键回退
                </Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="灰度发布" icon={<CloudOutlined />}>
        <Space direction="vertical" size={10}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Text style={{ width: 100, color: 'var(--ol-ink-3)', fontSize: 12 }}>新版本 v3</Text>
            <Select defaultValue="10%" options={['10%', '30%', '50%', '100%'].map((v) => ({ label: v, value: v }))} style={{ width: 100 }} />
            <Text style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>按比例</Text>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Text style={{ width: 100, color: 'var(--ol-ink-3)', fontSize: 12 }}>消费方白名单</Text>
            <Input placeholder="AppKey ak_xxx, ak_yyy" style={{ flex: 1, maxWidth: 400 }} />
          </div>
          <Button type="primary"
            loading={isLoading('publish-gray')}
            onClick={() => run('publish-gray', async () => {
              await new Promise((r) => setTimeout(r, 600));
            }, {
              successMsg: '已发布灰度 10%，异常可一键回退 v2',
              errorMsg: '灰度发布失败，请检查网关状态',
              duration: 3,
            })}
          >
            发布灰度
          </Button>
        </Space>
      </SectionCard>

      <SectionCard title="协议转换" icon={<SwapOutlined />}>
        <Space>
          <span className="ol-chip">对外 REST</span>
          <SwapOutlined style={{ color: 'var(--ol-brand)' }} />
          <span className="ol-chip">对内 gRPC</span>
          <Tag color="success" style={{ margin: 0 }}>已启用</Tag>
        </Space>
      </SectionCard>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        title="新建路由"
        onOk={() => { setOpen(false); message.success('路由已创建'); }}
      >
        <Form layout="vertical">
          <Form.Item label="路径"><Input placeholder="/v1/order/detail" /></Form.Item>
          <Form.Item label="后端服务"><Input placeholder="postgrest:3000" /></Form.Item>
          <Form.Item label="插件">
            <Select
              mode="multiple"
              options={['key-auth', 'limit-req', 'proxy-rewrite'].map((v) => ({ label: v, value: v }))}
              defaultValue={['key-auth', 'limit-req']}
            />
          </Form.Item>
        </Form>
      </Modal>

      <DangerConfirm
        open={rollbackOpen}
        title="一键回退到 v2 稳定版本"
        description="当前生产环境流量将立即切换到 v2 稳定版本，v3 灰度中的请求会失败。"
        impacts={[
          { label: '当前版本', value: 'v3 灰度' },
          { label: '回退目标', value: 'v2 稳定' },
          { label: '预计耗时', value: '< 1 min' },
        ]}
        impactLevel="HIGH"
        confirmName="一键回退"
        okText="确认回退"
        onCancel={() => setRollbackOpen(false)}
        onConfirm={() => run('rollback', async () => {
          await new Promise((r) => setTimeout(r, 600));
          setRollbackOpen(false);
        }, { successMsg: '已一键回退到 v2 稳定版本', duration: 3 })}
      />
    </div>
  );
}
