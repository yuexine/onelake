/**
 * 详情页骨架（§2.3 增强版）。
 *   - 头部（图标+标题+状态+主操作+元信息）
 *   - Tab 区
 *   - 右侧元信息卡（可选）
 */
import { Tabs, Space, Typography, Breadcrumb } from 'antd';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useState } from 'react';

const { Text } = Typography;

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
  icon?: ReactNode;
  breadcrumb?: { path?: string; label: string }[];
  tabs: TabItem[];
  activeTab?: string;
  onTabChange?: (key: string) => void;
  actions?: ReactNode[];
  meta?: { label: string; value: ReactNode }[];
  rightExtra?: ReactNode;            // 右侧元信息卡上方补充
}

export function DetailPageLayout({
  title, subtitle, status, icon, breadcrumb, tabs, activeTab, onTabChange,
  actions = [], meta = [], rightExtra,
}: Props) {
  const [innerActiveTab, setInnerActiveTab] = useState(tabs[0]?.key);
  const currentActiveTab = activeTab || innerActiveTab || tabs[0]?.key;

  const handleTabChange = (key: string) => {
    if (!activeTab) {
      setInnerActiveTab(key);
    }
    onTabChange?.(key);
  };

  return (
    <div className="ol-anim-fade">
      {breadcrumb && breadcrumb.length > 0 && (
        <div style={{ marginBottom: 10 }}>
          <Breadcrumb
            items={breadcrumb.map((b, i) => ({
              key: i,
              title: b.path
                ? <Link to={b.path} style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>{b.label}</Link>
                : <span style={{ color: 'var(--ol-ink)', fontSize: 12 }}>{b.label}</span>,
            }))}
          />
        </div>
      )}

      <div
        style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
          gap: 16, marginBottom: 16, flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', minWidth: 0 }}>
          {icon && (
            <div
              style={{
                width: 44, height: 44, borderRadius: 10,
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)',
                fontSize: 20, flexShrink: 0,
              }}
            >
              {icon}
            </div>
          )}
          <div style={{ minWidth: 0 }}>
            <Space align="center" size={8}>
              <h1 style={{ margin: 0, fontSize: 20, fontWeight: 600, color: 'var(--ol-ink)' }}>{title}</h1>
              {status}
            </Space>
            {subtitle && <div style={{ color: 'var(--ol-ink-3)', marginTop: 4, fontSize: 13 }}>{subtitle}</div>}
          </div>
        </div>
        <Space wrap>{actions}</Space>
      </div>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Tabs
            activeKey={currentActiveTab}
            onChange={handleTabChange}
            destroyOnHidden={false}
            items={tabs.map((t) => ({
              key: t.key,
              label: t.badge != null ? (
                <Space size={6}>
                  <span>{t.label}</span>
                  <span
                    style={{
                      display: 'inline-block', minWidth: 18, padding: '0 6px', height: 18,
                      borderRadius: 9, background: 'var(--ol-fill-soft)', color: 'var(--ol-ink-3)',
                      fontSize: 11, textAlign: 'center', lineHeight: '18px', fontWeight: 500,
                    }}
                  >
                    {t.badge}
                  </span>
                </Space>
              ) : t.label,
              children: t.children,
            }))}
          />
        </div>
        {meta.length > 0 && (
          <div style={{ width: 280, flexShrink: 0, position: 'sticky', top: 8 }}>
            <div className="ol-section" style={{ padding: 16 }}>
              <div style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginBottom: 8, fontWeight: 600 }}>
                基本信息
              </div>
              {meta.map((m, i) => (
                <div
                  key={m.label}
                  style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '7px 0',
                    borderBottom: i < meta.length - 1 ? '1px dashed var(--ol-line-soft)' : 'none',
                  }}
                >
                  <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>{m.label}</Text>
                  <Text style={{ color: 'var(--ol-ink)', fontSize: 13, fontWeight: 500, maxWidth: 170, textAlign: 'right' }} className="ol-truncate">
                    {m.value}
                  </Text>
                </div>
              ))}
              {rightExtra && <div style={{ marginTop: 12 }}>{rightExtra}</div>}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
