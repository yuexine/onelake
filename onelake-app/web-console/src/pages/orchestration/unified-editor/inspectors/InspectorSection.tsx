import { Typography } from 'antd';
import type { CSSProperties, ReactNode } from 'react';

const { Text } = Typography;

export function InspectorSection({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section style={sectionStyle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, marginBottom: 14 }}>
        <Text strong style={{ fontSize: 15 }}>{title}</Text>
        {hint && <Text type="secondary" style={{ fontSize: 12, textAlign: 'right' }}>{hint}</Text>}
      </div>
      {children}
    </section>
  );
}

export const inspectorStackStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

export const inspectorGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
  gap: 14,
};

export const inspectorFormItemStyle: CSSProperties = { marginBottom: 0 };

const sectionStyle: CSSProperties = {
  border: '1px solid var(--ol-border, #dfe6f0)',
  borderRadius: 10,
  padding: 16,
  background: 'var(--ol-bg, #fff)',
};
