/**
 * 故障复盘 / 事件时间线（对应原型 §8.9.3 升级版）。
 */
import { Tag, Timeline, Typography, Space, Button } from 'antd';
import { AlertOutlined, BugOutlined } from '@ant-design/icons';
import { incidents } from '../../mock';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Incidents() {
  return (
    <div className="ol-page">
      <PageHeader
        icon={<AlertOutlined />}
        title="故障复盘"
        subtitle={<span className="ol-chip">运营 · L9-2</span>}
        description="P0/P1 事件复盘：时间线 + 影响时长 + RCA 根因 + 改进项跟踪"
      />

      {incidents.map((inc) => (
        <SectionCard
          key={inc.id}
          title={
            <Space size={8}>
              <Tag color="error" style={{ margin: 0 }}>P0</Tag>
              <Text strong>{inc.id}</Text>
              <Text type="secondary" style={{ fontSize: 13 }}>{inc.alert}</Text>
            </Space>
          }
          icon={<AlertOutlined />}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 10 }}>事件时间线</div>
              <Timeline items={inc.timeline.map((t) => ({
                children: <Space size={8}><Text strong style={{ fontSize: 12 }}>{t.at}</Text><Text style={{ fontSize: 13 }}>{t.event}</Text></Space>,
              }))} />
            </div>

            <div className="ol-section" style={{ padding: 14, background: 'var(--ol-fill-soft)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>影响时长</Text>
                  <div style={{ marginTop: 4, fontSize: 14, fontWeight: 600, color: 'var(--ol-error)' }}>{inc.impactDuration}</div>
                </div>
                <div>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>下游影响</Text>
                  <div style={{ marginTop: 4, fontSize: 13 }}>{inc.downstreamImpact}</div>
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>RCA 根因</Text>
                  <div style={{ marginTop: 4, fontSize: 13, lineHeight: 1.6 }}>{inc.rca}</div>
                </div>
              </div>
            </div>

            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ol-ink)', marginBottom: 10 }}>改进项</div>
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                {inc.improvements.map((im, i) => (
                  <div key={i} style={{
                    display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
                    padding: '8px 12px', background: 'var(--ol-fill-soft)',
                    borderRadius: 6, border: '1px solid var(--ol-line-soft)',
                  }}>
                    <Tag color="processing" style={{ margin: 0 }}>待办</Tag>
                    <Text style={{ fontSize: 13, flex: 1, minWidth: 0 }}>{im.action}</Text>
                    <span className="ol-chip">{im.owner}</span>
                    <span className="ol-chip">due {im.due}</span>
                    <Button size="small" type="link">建任务</Button>
                  </div>
                ))}
              </Space>
            </div>
          </Space>
        </SectionCard>
      ))}
    </div>
  );
}
