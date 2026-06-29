/**
 * 全局筛选器条（大屏顶部 / 底部，编辑钻取联动用）。
 *
 * 用户在条上选 region='华东' → setVar('region', '华东')
 * → 所有 widget.data.filters[].fromVar === 'region' 的组件自动重渲染
 */
import { useEffect, useState } from 'react';
import { Input, Select, Button, Space, Tag, Empty } from 'antd';
import { FilterOutlined, ReloadOutlined } from '@ant-design/icons';
import { useLinkage } from './linkage';
import type { GlobalVar } from './types';

const { Search } = Input;

export interface GlobalFilterBarProps {
  vars: GlobalVar[];
}

export function GlobalFilterBar({ vars }: GlobalFilterBarProps) {
  const { varValues, setVar, resetVar } = useLinkage();

  if (!vars || vars.length === 0) {
    return (
      <div style={{ padding: '8px 16px', color: '#888', fontSize: 12, background: 'rgba(255,255,255,0.04)' }}>
        <FilterOutlined /> 全局筛选器：无（在 Inspector "全局变量" 处可新增，被组件 events 或 filters.fromVar 引用）
      </div>
    );
  }

  return (
    <div style={{
      padding: '8px 16px', background: 'rgba(255,255,255,0.04)',
      borderTop: '1px solid #1f2d3d', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12,
    }}>
      <span style={{ color: '#aaa', fontSize: 12 }}><FilterOutlined /> 全局筛选器：</span>
      {vars.map((v) => {
        const value = varValues[v.key];
        const isSet = value !== undefined && value !== '' && value !== null;
        return (
          <Space key={v.key} size="small">
            <span style={{ color: '#ccc', fontSize: 12 }}>{v.label}:</span>
            <Search
              placeholder={String(v.default ?? '输入或选择')}
              value={value === undefined ? '' : String(value)}
              onSearch={(val) => setVar(v.key, val || undefined as any)}
              onPressEnter={(e) => setVar(v.key, (e.target as HTMLInputElement).value || undefined as any)}
              style={{ width: 160 }}
              enterButton={<Button size="small" type="primary" ghost>应用</Button>}
              allowClear
            />
            {isSet && (
              <Tag color="blue" closable onClose={() => resetVar(v.key)}>
                {String(value)}
              </Tag>
            )}
          </Space>
        );
      })}
      <Button
        size="small"
        icon={<ReloadOutlined />}
        onClick={() => vars.forEach((v) => resetVar(v.key))}
        type="text"
        style={{ color: '#999' }}
      >
        清空
      </Button>
    </div>
  );
}
