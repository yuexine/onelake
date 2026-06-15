/**
 * 统一筛选条 FilterBar
 *   - 左侧：关键字搜索 + 多维筛选下拉
 *   - 右侧：自定义 slot（密度切换 / 我的视图 / 批量操作）
 *   - 折叠时仅保留首项
 */
import { useState, type ReactNode } from 'react';
import { Input, Button, Space } from 'antd';
import { SearchOutlined, ReloadOutlined, DownOutlined, UpOutlined } from '@ant-design/icons';

interface Props {
  search?: {
    placeholder?: string;
    value?: string;
    onChange?: (v: string) => void;
    width?: number;
  };
  /** 紧凑下拉集合：{ label, node } */
  filters?: ReactNode;
  /** 右侧自定义区 */
  extra?: ReactNode;
  onReset?: () => void;
  collapsible?: boolean;
  defaultCollapsed?: boolean;
  /** 提示条 (展示筛选命中数量等) */
  summary?: ReactNode;
}

export function FilterBar({
  search, filters, extra, onReset,
  collapsible = true, defaultCollapsed = false, summary,
}: Props) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: '12px 16px',
        background: 'var(--ol-card)',
        border: '1px solid var(--ol-line-soft)',
        borderRadius: 10,
      }}
    >
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
          {search && (
            <Input
              prefix={<SearchOutlined style={{ color: 'var(--ol-ink-4)' }} />}
              placeholder={search.placeholder || '搜索…'}
              allowClear
              size="middle"
              value={search.value}
              onChange={(e) => search.onChange?.(e.target.value)}
              style={{ width: search.width ?? 260, borderRadius: 6 }}
            />
          )}
          {!collapsed && filters}
          {collapsible && (
            <Button
              type="text"
              size="small"
              icon={collapsed ? <DownOutlined /> : <UpOutlined />}
              onClick={() => setCollapsed((c) => !c)}
              style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}
            >
              {collapsed ? '更多筛选' : '收起'}
            </Button>
          )}
        </div>
        <Space size={8}>
          {summary}
          {onReset && (
            <Button icon={<ReloadOutlined />} onClick={onReset} size="middle">
              重置
            </Button>
          )}
          {extra}
        </Space>
      </div>
    </div>
  );
}
