import type { CSSProperties } from 'react';
import { useId } from 'react';

type OneLakeMarkProps = {
  size?: number;
  className?: string;
  style?: CSSProperties;
  title?: string;
};

export function OneLakeMark({ size = 32, className, style, title = 'OneLake' }: OneLakeMarkProps) {
  const uid = useId().replace(/:/g, '');
  const lakeGradient = `ol-lake-${uid}`;
  const shineGradient = `ol-shine-${uid}`;
  const streamGradient = `ol-stream-${uid}`;

  return (
    <svg
      className={className}
      style={{ width: size, height: size, display: 'block', ...style }}
      viewBox="0 0 64 64"
      role="img"
      aria-label={title}
    >
      <defs>
        <linearGradient id={lakeGradient} x1="12" y1="8" x2="54" y2="58" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#10C7B6" />
          <stop offset="0.46" stopColor="#1267E8" />
          <stop offset="1" stopColor="#082A63" />
        </linearGradient>
        <linearGradient id={shineGradient} x1="18" y1="15" x2="42" y2="45" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#FFFFFF" stopOpacity="0.92" />
          <stop offset="1" stopColor="#D7F7FF" stopOpacity="0.62" />
        </linearGradient>
        <linearGradient id={streamGradient} x1="18" y1="25" x2="51" y2="48" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#E8FFF7" />
          <stop offset="0.52" stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#BFE0FF" />
        </linearGradient>
      </defs>

      <path
        d="M32 4.4C43.8 11.7 54.2 24.1 54.2 36c0 13-9.6 22-22.2 23.6C19.4 58 9.8 49 9.8 36 9.8 24.1 20.2 11.7 32 4.4Z"
        fill={`url(#${lakeGradient})`}
      />
      <path
        d="M17.5 37.8c7.1-11.2 17.5-14.5 31.2-8.7-4.7 2-8.4 5.5-11.7 10.6-4.8 7.2-11.3 10.2-19.4 8.5 5.4-1.5 9.4-4.1 12-7.7 2.7-3.7 6.4-5.6 11.1-5.7-8.3-3.7-16-2.7-23.2 3Z"
        fill={`url(#${streamGradient})`}
        opacity="0.94"
      />
      <path
        d="M18.5 27.2c4.2-4.9 9.2-8.2 15-9.9"
        fill="none"
        stroke={`url(#${shineGradient})`}
        strokeWidth="3.4"
        strokeLinecap="round"
        opacity="0.64"
      />
      <path
        d="M21.7 43.8c6.8 1.1 13-1.3 18.7-7.3"
        fill="none"
        stroke="rgba(255,255,255,.42)"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <circle cx="41.7" cy="29.4" r="3" fill="#FFFFFF" opacity="0.96" />
      <circle cx="31.9" cy="39.2" r="3.4" fill="#0B2C61" opacity="0.9" />
    </svg>
  );
}

type OneLakeLogoProps = OneLakeMarkProps & {
  collapsed?: boolean;
};

export function OneLakeLogo({ collapsed = false, size = 34, className, style }: OneLakeLogoProps) {
  return (
    <div
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 10,
        minWidth: 0,
        ...style,
      }}
    >
      <OneLakeMark size={size} />
      {!collapsed && (
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ol-ink)', lineHeight: 1.2 }} className="ol-truncate">
            OneLake数据中台
          </div>
        </div>
      )}
    </div>
  );
}
