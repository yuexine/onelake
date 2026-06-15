/**
 * IntentBadge — 统一的意图徽章
 *
 * 替代全站散落的 inline <span style={{ background, color, padding }}> 和 <Tag color="processing">。
 * 颜色与 tokens.ts 的 intentMeta 对齐，保证语义一致。
 *
 * 用法：
 *   <IntentBadge intent="success">已发布</IntentBadge>
 *   <IntentBadge intent={layerColor[a.layer]}>DWD</IntentBadge>
 *   <IntentBadge intent="warning" solid>破坏性</IntentBadge>
 */
import type { ReactNode } from 'react';
import { intentMeta, type Intent } from '../tokens';

interface Props {
  intent?: Intent;
  children: ReactNode;
  size?: 'sm' | 'md';
  solid?: boolean;          // 实心（背景填色，文字反色）
  dot?: boolean;            // 前置小圆点
  pulse?: boolean;          // 圆点脉冲动画（运行中/processing）
  uppercase?: boolean;
  style?: React.CSSProperties;
}

export function IntentBadge({
  intent = 'neutral', children, size = 'sm', solid = false,
  dot = false, pulse = false, uppercase = false, style,
}: Props) {
  const c = intentMeta[intent];

  if (solid) {
    return (
      <span
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 4,
          padding: size === 'sm' ? '1px 8px' : '2px 10px',
          borderRadius: 4,
          fontSize: size === 'sm' ? 11 : 12,
          fontWeight: 600,
          lineHeight: size === 'sm' ? '16px' : '18px',
          background: c.fg,
          color: '#fff',
          whiteSpace: 'nowrap',
          textTransform: uppercase ? 'uppercase' : 'none',
          letterSpacing: uppercase ? '0.04em' : 'normal',
          ...style,
        }}
      >
        {dot && (
          <span
            style={{
              width: 6, height: 6, borderRadius: '50%', background: '#fff',
              display: 'inline-block', opacity: pulse ? 0.8 : 1,
              animation: pulse ? 'ol-pulse-soft 1.6s var(--ol-ease) infinite' : undefined,
            }}
          />
        )}
        {children}
      </span>
    );
  }

  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 4,
        padding: size === 'sm' ? '1px 8px' : '2px 10px',
        borderRadius: 4,
        fontSize: size === 'sm' ? 11 : 12,
        fontWeight: 600,
        lineHeight: size === 'sm' ? '16px' : '18px',
        background: c.bg,
        color: c.fg,
        border: `1px solid ${c.border}`,
        whiteSpace: 'nowrap',
        textTransform: uppercase ? 'uppercase' : 'none',
        letterSpacing: uppercase ? '0.04em' : 'normal',
        ...style,
      }}
    >
      {dot && (
        <span
          className={pulse ? '' : ''}
          style={{
            width: 6, height: 6, borderRadius: '50%', background: c.fg,
            display: 'inline-block',
            boxShadow: pulse ? `0 0 0 0 ${c.fg}` : undefined,
            animation: pulse ? 'ol-pulse-glow 1.6s var(--ol-ease) infinite' : undefined,
          }}
        />
      )}
      {children}
    </span>
  );
}
