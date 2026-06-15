/**
 * 数据源/资源类型图标（替换原型中的 emoji，提升专业感）
 * 统一以 18px 字号 + 类型色，与 StatusBadge/ClassificationBadge 风格一致。
 */
import type { CSSProperties } from 'react';

type Kind =
  | 'MYSQL' | 'POSTGRES' | 'ORACLE' | 'HIVE' | 'KAFKA'
  | 'S3' | 'FTP' | 'SFTP' | 'FILE' | 'API' | 'TOPIC' | 'TABLE' | 'VIEW';

const META: Record<Kind, { label: string; bg: string; fg: string; glyph: string }> = {
  MYSQL:     { label: 'MySQL',     bg: '#F7E3E3', fg: '#C53030', glyph: 'SQL' },
  POSTGRES:  { label: 'PostgreSQL',bg: '#E2E8F0', fg: '#1E40AF', glyph: 'PG'  },
  ORACLE:    { label: 'Oracle',    bg: '#FEF3C7', fg: '#92400E', glyph: 'ORA' },
  HIVE:      { label: 'Hive',      bg: '#FFE4B5', fg: '#B45309', glyph: 'HV'  },
  KAFKA:     { label: 'Kafka',     bg: '#E0E7FF', fg: '#4338CA', glyph: 'K'   },
  S3:        { label: 'S3',        bg: '#E0F2FE', fg: '#0369A1', glyph: 'S3'  },
  FTP:       { label: 'FTP',       bg: '#F1F5F9', fg: '#475569', glyph: 'FTP' },
  SFTP:      { label: 'SFTP',      bg: '#F1F5F9', fg: '#475569', glyph: 'SFTP'},
  FILE:      { label: 'File',      bg: '#F1F5F9', fg: '#475569', glyph: 'CSV' },
  API:       { label: 'API',       bg: '#DCFCE7', fg: '#15803D', glyph: 'API' },
  TOPIC:     { label: 'Topic',     bg: '#E0E7FF', fg: '#4338CA', glyph: 'T'   },
  TABLE:     { label: 'Table',     bg: '#E8F0FF', fg: '#0F4FD8', glyph: 'TBL' },
  VIEW:      { label: 'View',      bg: '#F0F9FF', fg: '#0369A1', glyph: 'VW'  },
};

interface Props {
  kind: Kind | string;
  size?: number;
  rounded?: number;
  withTooltip?: boolean;
  style?: CSSProperties;
}

export function EntityTypeIcon({ kind, size = 28, rounded = 6, style }: Props) {
  const meta = META[(kind as Kind) ?? 'TABLE'] ?? META.TABLE;
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: rounded,
        background: meta.bg,
        color: meta.fg,
        fontSize: Math.max(9, size * 0.30),
        fontWeight: 700,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        letterSpacing: '-0.02em',
        flexShrink: 0,
        ...style,
      }}
      title={meta.label}
    >
      {meta.glyph}
    </div>
  );
}

export const ENTITY_META = META;
