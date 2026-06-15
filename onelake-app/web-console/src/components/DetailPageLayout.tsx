/**
 * 详情页骨架（§2.3）。
 * 头部（标题+状态+主操作）+ Tab 区 + 右侧元信息卡。
 */
import { Card, Tabs, Space, Tag, Typography, Breadcrumb } from 'antd';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';

const { Title } = Typography;

interface TabItem {
  key: string;
  label: string;
  children: ReactNode;
  badge?: number;
}

interface Props {
  title: string;
  subtitle?: ReactNode;
  status?: ReactNode;
  breadcrumb?: { path?: string; label: string }[];
  tabs: TabItem[];
  activeTab?: string;
  onTabChange?: (key: string) => void;
  actions?: ReactNode[];
  meta?: { label: string; value: ReactNode }[];
}

export function DetailPageLayout({
  title, subtitle, status, breadcrumb, tabs, activeTab, onTabChange,
  actions = [], meta = [],
}: Props) {
  return (
    <div>
      {breadcrumb && breadcrumb.length > 0 && (
        <Breadcrumb style={{ marginBottom: 12 }}>
          {breadcrumb.map((b, i) => (
            <Breadcrumb.Item key={i}>
              {b.path ? <Link to={b.path}>{b.label}</Link> : b.label}
            </Breadcrumb.Item>
          ))}
        </Breadcrumb>
      )}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
        <div>
          <Space align="center">
            <Title level={4} style={{ margin: 0 }}>{title}</Title>
            {status}
          </Space>
          {subtitle && <div style={{ color: '#8c8c8c', marginTop: 4 }}>{subtitle}</div>}
        </div>
        <Space>{actions}</Space>
      </div>

      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Card>
            <Tabs
              activeKey={activeTab || tabs[0]?.key}
              onChange={onTabChange}
              items={tabs.map((t) => ({
                key: t.key,
                label: t.badge != null ? `${t.label} (${t.badge})` : t.label,
                children: t.children,
              }))}
            />
          </Card>
        </div>
        {meta.length > 0 && (
          <Card style={{ width: 280, flexShrink: 0 }} size="small">
            {meta.map((m) => (
              <div key={m.label} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', borderBottom: '1px dashed #f0f0f0' }}>
                <span style={{ color: '#8c8c8c' }}>{m.label}</span>
                <span>{m.value}</span>
              </div>
            ))}
          </Card>
        )}
      </div>
    </div>
  );
}

export { Tag };
