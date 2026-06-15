/**
 * 采集任务模板库（对应原型 §8.2.8 升级版）。
 *   - 卡片网格（统一 hover 效果）
 *   - 模板使用弹窗：参数化生成
 */
import {
  Row, Col, Button, Typography, Modal, Form, Select, Checkbox, Space, message, Input,
} from 'antd';
import {
  DatabaseOutlined, HourglassOutlined, CloudSyncOutlined, FileTextOutlined,
  PlusOutlined, ArrowRightOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  PageHeader, SectionCard,
} from '../../components';
import { collectTemplates } from '../../mock';

const { Text, Title } = Typography;

const ICON_MAP: Record<string, React.ReactNode> = {
  '🗃': <DatabaseOutlined />,
  '⏱': <HourglassOutlined />,
  '🔄': <CloudSyncOutlined />,
  '📁': <FileTextOutlined />,
};

const COLOR_MAP: Record<number, { bg: string; fg: string }> = {
  0: { bg: 'var(--ol-brand-soft)',   fg: 'var(--ol-brand)' },
  1: { bg: 'var(--ol-info-soft)',    fg: '#0369A1' },
  2: { bg: 'var(--ol-success-soft)', fg: 'var(--ol-success)' },
  3: { bg: 'var(--ol-warning-soft)', fg: '#B45309' },
};

export default function CollectTemplates() {
  const navigate = useNavigate();
  const [selected, setSelected] = useState<typeof collectTemplates[0] | null>(null);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<DatabaseOutlined />}
        title="任务模板"
        subtitle={<span className="ol-chip">数据集成 · L1-4</span>}
        description="一键参数化生成多个采集任务，支持批量调度"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => message.warning({ content: '自定义模板编辑器待接入：可通过 DSL 或导入 YAML 定义', duration: 4 })}>自定义模板</Button>}
      />

      <Row gutter={[16, 16]}>
        {collectTemplates.map((t, idx) => {
          const c = COLOR_MAP[idx % 4];
          return (
            <Col key={t.id} xs={24} sm={12} md={8} lg={6}>
              <div
                onClick={() => setSelected(t)}
                style={{
                  cursor: 'pointer',
                  background: 'var(--ol-card)',
                  border: '1px solid var(--ol-line-soft)',
                  borderRadius: 10,
                  padding: 20,
                  boxShadow: 'var(--ol-shadow-e1)',
                  transition: 'all var(--ol-dur-base) var(--ol-ease)',
                  height: '100%',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'translateY(-2px)';
                  e.currentTarget.style.boxShadow = 'var(--ol-shadow-e2)';
                  e.currentTarget.style.borderColor = 'var(--ol-line)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.boxShadow = 'var(--ol-shadow-e1)';
                  e.currentTarget.style.borderColor = 'var(--ol-line-soft)';
                }}
              >
                <div style={{
                  width: 44, height: 44, borderRadius: 10,
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  background: c.bg, color: c.fg, fontSize: 22,
                }}>
                  {ICON_MAP[t.icon] || <DatabaseOutlined />}
                </div>
                <Title level={5} style={{ margin: '14px 0 4px', fontSize: 15 }}>{t.name}</Title>
                <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.55, display: 'block', minHeight: 36 }}>
                  {t.desc}
                </Text>
                <div style={{ marginTop: 12, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {t.fields.map((f) => (
                    <span key={f} className="ol-chip" style={{ fontSize: 11 }}>{f}</span>
                  ))}
                </div>
                <Button
                  type="primary" block
                  style={{ marginTop: 14 }}
                  icon={<ArrowRightOutlined />}
                >
                  使用模板
                </Button>
              </div>
            </Col>
          );
        })}

        {/* 新建自定义模板卡 */}
        <Col xs={24} sm={12} md={8} lg={6}>
          <div
            onClick={() => message.warning({ content: '自定义模板编辑器待接入：可通过 DSL 或导入 YAML 定义', duration: 4 })}
            style={{
              cursor: 'pointer',
              background: 'transparent',
              border: '2px dashed var(--ol-line)',
              borderRadius: 10,
              padding: 20,
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
              minHeight: 220,
              transition: 'all var(--ol-dur-base) var(--ol-ease)',
              color: 'var(--ol-ink-3)',
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.borderColor = 'var(--ol-brand)';
              e.currentTarget.style.color = 'var(--ol-brand)';
              e.currentTarget.style.background = 'var(--ol-brand-soft)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.borderColor = 'var(--ol-line)';
              e.currentTarget.style.color = 'var(--ol-ink-3)';
              e.currentTarget.style.background = 'transparent';
            }}
          >
            <PlusOutlined style={{ fontSize: 28, marginBottom: 10 }} />
            <Text style={{ color: 'inherit', fontSize: 13, fontWeight: 500 }}>新建自定义模板</Text>
            <Text style={{ color: 'var(--ol-ink-4)', fontSize: 11, marginTop: 4 }}>通过 DSL 定义调度与映射</Text>
          </div>
        </Col>
      </Row>

      <Modal
        open={!!selected}
        title={selected ? `使用模板「${selected.name}」` : ''}
        onCancel={() => setSelected(null)}
        okText="一键生成"
        onOk={() => {
          setSelected(null);
          message.success('已批量生成 N 个采集任务');
          navigate('/integration/sync-tasks');
        }}
      >
        <Form layout="vertical">
          <Form.Item label="源连接"><Select options={[{ label: '订单库', value: 'ds-001' }]} defaultValue="ds-001" /></Form.Item>
          <Form.Item label="目标层"><Select options={['ODS', 'DWD'].map((v) => ({ label: v, value: v }))} defaultValue="ODS" /></Form.Item>
          <Form.Item label="表范围">
            <Checkbox.Group
              options={['orders', 'order_items', 'users', 'payments']}
              defaultValue={['orders', 'order_items']}
              style={{ display: 'grid', gridTemplateColumns: '1fr 1fr' }}
            />
          </Form.Item>
          <div style={{
            padding: 12, borderRadius: 6, background: 'var(--ol-brand-soft)', marginTop: 8,
            fontSize: 12, color: 'var(--ol-brand)',
          }}>
            将一键生成 N 个采集任务（可批量编辑调度）
          </div>
        </Form>
      </Modal>
    </div>
  );
}
