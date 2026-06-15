/**
 * 统一页面头部
 *   模块徽标 + 标题 + 描述 + 右侧操作区
 *   附带可折叠的「我的视图/筛选」摘要条
 */
import type { ReactNode } from 'react';
import { Breadcrumb, Space, Tooltip } from 'antd';
import { Link } from 'react-router-dom';
import { InfoCircleOutlined } from '@ant-design/icons';

interface Crumb {
  path?: string;
  label: string;
}

interface Props {
  title: ReactNode;
  subtitle?: ReactNode;
  description?: ReactNode;
  hint?: ReactNode;              // 标题右侧 ⓘ 提示
  breadcrumb?: Crumb[];
  icon?: ReactNode;
  actions?: ReactNode;
  meta?: { label: string; value: ReactNode }[];
  banner?: ReactNode;            // 头部下方的横幅（如全局告警、引导）
  style?: React.CSSProperties;
}

export function PageHeader({
  title, subtitle, description, hint, breadcrumb, icon,
  actions, meta, banner, style,
}: Props) {
  return (
    <div className="ol-anim-fade" style={style}>
      {breadcrumb && breadcrumb.length > 0 && (
        <div style={{ marginBottom: 8, fontSize: 12 }}>
          <Breadcrumb
            items={breadcrumb.map((b, i) => ({
              key: i,
              title: b.path ? <Link to={b.path} style={{ color: 'var(--ol-ink-3)' }}>{b.label}</Link> : <span style={{ color: 'var(--ol-ink)' }}>{b.label}</span>,
            }))}
          />
        </div>
      )}

      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 16,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', minWidth: 0 }}>
          {icon && (
            <div
              style={{
                width: 40, height: 40, borderRadius: 10,
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                background: 'var(--ol-brand-soft)', color: 'var(--ol-brand)',
                fontSize: 18, flexShrink: 0,
              }}
            >
              {icon}
            </div>
          )}
          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <h1
                style={{
                  margin: 0, fontSize: 20, fontWeight: 600, color: 'var(--ol-ink)',
                  lineHeight: 1.3,
                }}
                className="ol-truncate"
              >
                {title}
              </h1>
              {subtitle}
              {hint && (
                <Tooltip title={hint} placement="right">
                  <InfoCircleOutlined style={{ color: 'var(--ol-ink-4)', fontSize: 13 }} />
                </Tooltip>
              )}
            </div>
            {description && (
              <div style={{ marginTop: 4, fontSize: 13, color: 'var(--ol-ink-3)', lineHeight: 1.55 }}>
                {description}
              </div>
            )}
            {meta && meta.length > 0 && (
              <div style={{ marginTop: 10, display: 'flex', flexWrap: 'wrap', gap: '4px 18px' }}>
                {meta.map((m) => (
                  <div key={m.label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{m.label}</span>
                    <span style={{ fontSize: 12, color: 'var(--ol-ink)', fontWeight: 500 }}>{m.value}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {actions && (
          <Space size={8} wrap style={{ flexShrink: 0 }}>
            {actions}
          </Space>
        )}
      </div>

      {banner && <div style={{ marginTop: 12 }}>{banner}</div>}
    </div>
  );
}
