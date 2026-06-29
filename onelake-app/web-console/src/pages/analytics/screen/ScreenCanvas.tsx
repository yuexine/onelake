/**
 * 大屏画布（react-grid-layout 拖拽编排）。
 *
 * 关键：
 * - cols=48, rowHeight=20, 自由布局（compactType=null）允许重叠/分层
 * - 拖拽入画布 → 由 ScreenDesigner 派发 append widget；onLayoutChange 同步 layout 字段
 */
import { useMemo } from 'react';
import GridLayout, { type Layout } from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import type { ScreenSpec, WidgetNode } from './types';
import { WidgetRenderer } from './registry';

export interface ScreenCanvasProps {
  spec: ScreenSpec;
  selectedId?: string;
  onChange: (spec: ScreenSpec) => void;
  onSelect: (id: string | null) => void;
}

export function ScreenCanvas({ spec, selectedId, onChange, onSelect }: ScreenCanvasProps) {
  const layout: Layout[] = useMemo(
    () => spec.widgets.map((w) => ({
      i: w.id, x: w.layout.x, y: w.layout.y, w: w.layout.w, h: w.layout.h,
    })),
    [spec.widgets],
  );

  const handleLayoutChange = (newLayout: Layout[]) => {
    const map = new Map(newLayout.map((l) => [l.i, l]));
    onChange({
      ...spec,
      widgets: spec.widgets.map((w) => {
        const l = map.get(w.id);
        if (!l) return w;
        return { ...w, layout: { ...w.layout, x: l.x, y: l.y, w: l.w, h: l.h } };
      }),
    });
  };

  return (
    <div
      style={{
        width: spec.canvas.width,
        height: spec.canvas.height,
        background: spec.canvas.background,
        position: 'relative',
        margin: '0 auto',
        boxShadow: '0 6px 24px rgba(0,0,0,0.3)',
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onSelect(null);
      }}
    >
      <GridLayout
        layout={layout}
        cols={48}
        rowHeight={20}
        width={spec.canvas.width}
        margin={[4, 4]}
        compactType={null}
        preventCollision={false}
        isDraggable
        isResizable
        onLayoutChange={handleLayoutChange}
      >
        {spec.widgets.map((w) => (
          <div
            key={w.id}
            onClick={(e) => { e.stopPropagation(); onSelect(w.id); }}
            style={{
              border: selectedId === w.id ? '2px solid #1f6feb' : '1px solid rgba(255,255,255,0.08)',
              background: 'rgba(255,255,255,0.04)',
              overflow: 'hidden',
              zIndex: w.layout.z as any,
              cursor: 'move',
            }}
          >
            <WidgetRenderer node={w} />
          </div>
        ))}
      </GridLayout>
    </div>
  );
}
