/**
 * 统一区块容器 SectionCard
 *   - 标题 + 副标题 + 右侧 extra
 *   - 可选 padding 紧凑
 *   - 默认白底，含 subtle border
 */
import type { ReactNode, CSSProperties } from 'react';

interface Props {
  title?: ReactNode;
  subtitle?: ReactNode;
  extra?: ReactNode;
  icon?: ReactNode;
  children?: ReactNode;
  padded?: boolean | 'sm' | 'md' | 'lg' | 'none';
  bodyStyle?: CSSProperties;
  headerStyle?: CSSProperties;
  style?: CSSProperties;
  bordered?: boolean;
  flatBody?: boolean;          // 不带默认 padding
}

const PAD: Record<string, number> = { sm: 12, md: 16, lg: 24 };

export function SectionCard({
  title, subtitle, extra, icon, children,
  padded = 'md', bodyStyle, headerStyle, style, bordered = true, flatBody,
}: Props) {
  const pad = padded === true ? PAD.md : padded === false ? 0 : PAD[padded] ?? PAD.md;
  const showHeader = title || extra || subtitle;
  return (
    <section
      className="ol-anim-fade"
      style={{
        background: 'var(--ol-card)',
        border: bordered ? '1px solid var(--ol-line-soft)' : 'none',
        borderRadius: 10,
        boxShadow: 'var(--ol-shadow-e1)',
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        ...style,
      }}
    >
      {showHeader && (
        <header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            padding: '12px 16px',
            borderBottom: '1px solid var(--ol-line-soft)',
            ...headerStyle,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            {icon && <span style={{ color: 'var(--ol-brand)', fontSize: 14, flexShrink: 0 }}>{icon}</span>}
            <div style={{ minWidth: 0 }}>
              {typeof title === 'string' ? (
                <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ol-ink)', lineHeight: 1.3 }}>{title}</div>
              ) : title}
              {subtitle && (
                <div style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginTop: 2 }}>{subtitle}</div>
              )}
            </div>
          </div>
          {extra}
        </header>
      )}
      <div style={{ flex: 1, ...(flatBody ? { ...bodyStyle } : { padding: pad, ...bodyStyle }) }}>{children}</div>
    </section>
  );
}
