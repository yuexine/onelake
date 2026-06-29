/**
 * 组件库 Palette（左侧拖拽源）。
 *
 * 按 WIDGET_REGISTRY 渲染卡片列表；点击卡片直接添加到画布（移动端友好）。
 * DnD 接入留 P3：用 @dnd-kit/core 实现 drag-and-drop（当前用 click-to-add 简化）。
 */
import { Card, Empty } from 'antd';
import { WIDGET_REGISTRY } from './registry';
import type { WidgetType } from './types';

export interface PaletteProps {
  onAdd: (type: WidgetType) => void;
}

export function Palette({ onAdd }: PaletteProps) {
  const defs = Object.values(WIDGET_REGISTRY);
  // 按 category 分组
  const groups = defs.reduce<Record<string, typeof defs>>((acc, d) => {
    (acc[d.category] ??= []).push(d);
    return acc;
  }, {});

  const groupLabel: Record<string, string> = {
    chart: '图表', metric: '指标 / 列表', media: '媒体', decoration: '装饰', embed: '嵌入',
  };

  return (
    <div style={{ padding: 12, overflowY: 'auto', height: '100%' }}>
      {Object.entries(groups).map(([cat, items]) => (
        <Card
          key={cat}
          size="small"
          title={groupLabel[cat] ?? cat}
          style={{ marginBottom: 12 }}
          bodyStyle={{ padding: 8 }}
        >
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 6 }}>
            {items.map((d) => (
              <button
                key={d.type}
                onClick={() => onAdd(d.type as WidgetType)}
                style={{
                  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                  gap: 4, padding: '10px 4px', background: 'rgba(255,255,255,0.05)',
                  border: '1px solid #2a3a4a', borderRadius: 4, color: '#eee', cursor: 'pointer',
                  fontSize: 12, transition: 'all .15s',
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(31,111,235,0.2)')}
                onMouseLeave={(e) => (e.currentTarget.style.background = 'rgba(255,255,255,0.05)')}
              >
                <span style={{ fontSize: 18 }}>{d.icon}</span>
                <span>{d.label}</span>
              </button>
            ))}
          </div>
        </Card>
      ))}
      {!defs.length && <Empty description="组件库为空" />}
    </div>
  );
}
