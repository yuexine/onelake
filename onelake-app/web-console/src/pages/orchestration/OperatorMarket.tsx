/**
 * 算子市场（对应原型 §8.4.6 升级版）。
 */
import { Row, Col, Tag, Space, Button, Input, Typography, Modal, message } from 'antd';
import { SearchOutlined, AppstoreOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { PageHeader, SectionCard, IntentBadge, type Intent } from '../../components';

const { Text } = Typography;

const OPERATORS = [
  { id: 'op-1', category: '治理', name: '清洗去重',     in: '表', out: '表', version: 'v2', scope: '内置' },
  { id: 'op-2', category: '脱敏', name: '掩码脱敏',     in: '字段', out: '表', version: 'v1', scope: '内置' },
  { id: 'op-3', category: '加密', name: '字段加密',     in: '字段', out: '表', version: 'v3', scope: '内置' },
  { id: 'op-4', category: '治理', name: 'MDM 主数据',   in: '多源', out: '金标表', version: 'v1', scope: '租户私有' },
  { id: 'op-5', category: '脱敏', name: '保格加密 FPE', in: '字段', out: '字段', version: 'v1', scope: '自定义' },
];

const CATEGORY_INTENT: Record<string, Intent> = {
  '治理': 'brand',
  '脱敏': 'warning',
  '加密': 'success',
};

export default function OperatorMarket() {
  const [selected, setSelected] = useState<typeof OPERATORS[0] | null>(null);
  const [filter, setFilter] = useState('全部');
  const filtered = filter === '全部' ? OPERATORS : OPERATORS.filter((o) => o.scope === filter);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<AppstoreOutlined />}
        title="算子市场"
        subtitle={<span className="ol-chip">编排 · L4</span>}
        description="内置 / 自定义 / 租户私有三类算子，可在 DAG 画布中拖入使用"
        actions={
          <Input.Search placeholder="搜索算子" prefix={<SearchOutlined />} style={{ width: 240 }} />
        }
      />

      <SectionCard padded="sm">
        <Space>
          {['全部', '内置', '自定义', '租户私有'].map((c) => (
            <button
              key={c}
              onClick={() => setFilter(c)}
              style={{
                padding: '6px 14px', borderRadius: 6, border: '1px solid transparent',
                background: filter === c ? 'var(--ol-brand)' : 'transparent',
                color: filter === c ? '#fff' : 'var(--ol-ink-2)',
                cursor: 'pointer', fontSize: 13, fontWeight: 500,
                transition: 'all var(--ol-dur-fast) var(--ol-ease)',
              }}
            >{c}</button>
          ))}
        </Space>
      </SectionCard>

      <Row gutter={[16, 16]}>
        {filtered.map((op) => {
          const intent = CATEGORY_INTENT[op.category] || 'brand';
          return (
            <Col key={op.id} xs={24} sm={12} md={8} lg={6}>
              <div
                onClick={() => setSelected(op)}
                style={{
                  cursor: 'pointer',
                  background: 'var(--ol-card)',
                  border: '1px solid var(--ol-line-soft)',
                  borderRadius: 10,
                  padding: 18,
                  boxShadow: 'var(--ol-shadow-e1)',
                  transition: 'all var(--ol-dur-base) var(--ol-ease)',
                  height: '100%',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'translateY(-2px)';
                  e.currentTarget.style.boxShadow = 'var(--ol-shadow-e2)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.boxShadow = 'var(--ol-shadow-e1)';
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
                  <IntentBadge intent={intent}>{op.category}</IntentBadge>
                  <Tag style={{ margin: 0, fontSize: 11 }}>{op.version}</Tag>
                </div>
                <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 10 }}>{op.name}</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--ol-ink-3)' }}>
                  <span>{op.in}</span>
                  <ArrowRightOutlined style={{ fontSize: 10 }} />
                  <span>{op.out}</span>
                </div>
                <div style={{ marginTop: 12 }}>
                  <span className="ol-chip" style={{ fontSize: 11 }}>{op.scope}</span>
                </div>
              </div>
            </Col>
          );
        })}
      </Row>

      <Modal
        open={!!selected}
        onCancel={() => setSelected(null)}
        title={selected?.name}
        footer={[
          <Button key="i" onClick={() => { setSelected(null); message.success('已安装/更新'); }}>安装</Button>,
          <Button key="u" type="primary" onClick={() => message.success('已使用')}>使用</Button>,
        ]}
      >
        {selected && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>声明输入</Text>
              <div style={{ marginTop: 4 }}><span className="ol-chip">{selected.in}</span></div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>声明输出</Text>
              <div style={{ marginTop: 4 }}><span className="ol-chip">{selected.out}</span></div>
            </div>
            <div>
              <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>使用示例</Text>
              <div style={{ marginTop: 4 }}>
                <Text code style={{ fontSize: 12 }}>FROM ods.orders | clean(mask(phone)) | WRITE dws</Text>
              </div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
