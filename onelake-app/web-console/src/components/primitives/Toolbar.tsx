/**
 * 表格工具栏 Toolbar
 *   - 左：批量操作（带选中数量徽标 + 操作按钮组）
 *   - 右：密度 / 列设置 / 刷新 / 新建等
 *   - 无选中时不显示批量操作条
 */
import type { ReactNode } from 'react';
import { Button, Space, Tag } from 'antd';

interface Props {
  selectedCount?: number;
  bulkActions?: ReactNode[];
  right?: ReactNode;
  /** 列表底部统计文案，如「共 N 条 · 已选 M」 */
  summary?: ReactNode;
}

export function Toolbar({ selectedCount = 0, bulkActions = [], right, summary }: Props) {
  const showBulk = selectedCount > 0 && bulkActions.length > 0;
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 8,
        padding: '8px 0',
        flexWrap: 'wrap',
        transition: 'background var(--ol-dur-base) var(--ol-ease)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {showBulk ? (
          <div
            className="ol-anim-fade"
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              padding: '4px 10px',
              background: 'var(--ol-brand-soft)',
              border: '1px solid var(--ol-brand-border)',
              borderRadius: 6,
            }}
          >
            <Tag color="blue" style={{ margin: 0, background: 'var(--ol-brand)', color: '#fff', border: 'none' }}>
              已选 {selectedCount}
            </Tag>
            <Space size={4}>{bulkActions.map((b, i) => <span key={i}>{b}</span>)}</Space>
          </div>
        ) : (
          summary
        )}
      </div>
      {right && <Space size={8}>{right}</Space>}
    </div>
  );
}
