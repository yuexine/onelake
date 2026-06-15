/**
 * PII 识别（对应原型 §8.7.2 升级版）。
 */
import { Table, Tag, Space, Button, message, Modal, Typography } from 'antd';
import { ScanOutlined, ReloadOutlined, CheckCircleOutlined, LockOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { piiScan } from '../../mock';
import { SecurityAPI } from '../../api';
import { useApiWithFallback } from '../../hooks/useApiWithFallback';
import { ClassificationBadge, PageHeader, SectionCard, StateView, useAsyncAction } from '../../components';

const { Text } = Typography;

export default function PiiScan() {
  const { run, isLoading } = useAsyncAction();
  const [selected, setSelected] = useState<string[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const { data: piiData } = useApiWithFallback(
    () => SecurityAPI.listPiiScan() as Promise<any[]>,
    piiScan,
  );

  const counts = {
    total: piiData.length,
    high: piiData.filter((p) => p.confidence > 0.9).length,
    pending: piiData.filter((p) => p.status !== 'confirmed').length,
    confirmed: piiData.filter((p) => p.status === 'confirmed').length,
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<ScanOutlined />}
        title="PII 识别"
        subtitle={<span className="ol-chip">安全 · L3-5</span>}
        description="自动扫描敏感字段，含置信度、建议密级、批量确认 — 一处设定，全站随动"
        meta={[
          { label: '识别字段', value: counts.total },
          { label: '待确认', value: counts.pending },
        ]}
        actions={
          <>
            <Button icon={<ReloadOutlined />}>重新扫描</Button>
            <Button type="primary" disabled={selected.length === 0} onClick={() => setConfirmOpen(true)}>批量确认密级</Button>
          </>
        }
      />

      <SectionCard title="识别结果" icon={<ScanOutlined />} flatBody>
        <Table
          rowKey="fqn"
          dataSource={piiData}
          locale={{
            emptyText: (
              <StateView
                state="empty"
                title="暂无 PII 字段"
                description="全库扫描未发现需要确认的敏感字段"
              />
            ),
          }}
          size="middle"
          pagination={false}
          rowSelection={{ selectedRowKeys: selected, onChange: (k) => setSelected(k as string[]) }}
          columns={[
            { title: '资产 · 字段', dataIndex: 'fqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '识别类型', dataIndex: 'type', render: (t: string) => <Tag color="blue" style={{ margin: 0 }}>{t}</Tag> },
            { title: '置信度', dataIndex: 'confidence', width: 130, render: (c: number) => {
              const pct = (c * 100).toFixed(0);
              const intent = c > 0.9 ? 'var(--ol-success)' : c > 0.8 ? '#B45309' : 'var(--ol-ink-3)';
              return (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ width: 50, height: 4, background: 'var(--ol-fill-soft)', borderRadius: 2, overflow: 'hidden' }}>
                    <div style={{ width: `${pct}%`, height: '100%', background: intent }} />
                  </div>
                  <span className="mono tnum" style={{ fontSize: 12, color: intent, fontWeight: 600 }}>{pct}%</span>
                </div>
              );
            } },
            { title: '建议密级', dataIndex: 'suggestLevel', width: 110, render: (l: string) => <ClassificationBadge level={l as any} /> },
            { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: s === 'confirmed' ? 'var(--ol-success-soft)' : 'var(--ol-info-soft)',
                color: s === 'confirmed' ? 'var(--ol-success)' : '#0369A1',
              }}>{s === 'confirmed' ? '已确认' : '待确认'}</span>
            ) },
            { title: '操作', width: 140, render: () => (
              <Space>
                <Button size="small" type="link">确认</Button>
                <Button size="small" type="link">忽略</Button>
              </Space>
            ) },
          ]}
        />
      </SectionCard>

      <Modal
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        title="批量确认密级"
        okButtonProps={{ loading: isLoading('batch-confirm') }}
        onOk={() => run('batch-confirm', async () => {
          await new Promise((r) => setTimeout(r, 800));
          setConfirmOpen(false);
          setSelected([]);
        }, {
          successMsg: `已确认 ${selected.length} 个字段密级 · 全站随动生效（采集脱敏 + 目录徽章 + API 返回脱敏）`,
          errorMsg: '批量确认失败，请重试',
          duration: 4,
        })}
      >
        <Text>将批量确认 <Text strong>{selected.length}</Text> 个字段的密级。</Text>
        <div style={{
          marginTop: 12, padding: 12, borderRadius: 6,
          background: 'var(--ol-warning-soft)', border: '1px solid #FDE68A',
        }}>
          <Text style={{ color: '#B45309', fontSize: 12 }}>
            ⚠ 一处设定，全站随动：目录徽章、血缘节点描边、采集脱敏开关、DAG 脱敏算子默认值、API 返回脱敏将自动套用同一策略。
          </Text>
        </div>
      </Modal>
    </div>
  );
}
