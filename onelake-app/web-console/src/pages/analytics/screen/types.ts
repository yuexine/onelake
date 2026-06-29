/**
 * ScreenSpec 类型 + 组件类型常量（对应设计方案 §7.1）。
 *
 * 组件实现优先级（WIDGET_REGISTRY 按批次实现 buildOption）：
 *   P2 最小可用 15：line · bar · pie · scatter · metric · flipper · table · text ·
 *                  image · decoration · superset · rankList · radar · funnel · heatmap
 *   P3 常用补齐 5： map · gauge · progress · carousel · video
 *   P5 扩展 5：     wordCloud · liquidFill · treemap · sunburst · sankey
 *
 * 长尾扩展（后续按需）：themeRiver · graph · map3D · scatter3D · bar3D
 */

export const WIDGET_TYPES = [
  // P2 最小可用 15
  'line', 'bar', 'pie', 'scatter', 'metric', 'flipper', 'table', 'text',
  'image', 'decoration', 'superset', 'rankList', 'radar', 'funnel', 'heatmap',
  // P3 常用补齐 5
  'map', 'gauge', 'progress', 'carousel', 'video',
  // P5 扩展 5
  'wordCloud', 'liquidFill', 'treemap', 'sunburst', 'sankey',
] as const;

export type WidgetType = (typeof WIDGET_TYPES)[number] | string;

export interface DataBinding {
  datasetId?: string;
  dimensions: string[];
  measures: { field: string; agg: 'sum' | 'avg' | 'max' | 'min' | 'count' }[];
  filters?: { field: string; op: string; value: unknown; fromVar?: string }[];
  refreshSec?: number;
}

export interface WidgetNode {
  id: string;
  type: WidgetType;
  layout: { x: number; y: number; w: number; h: number; z: number };
  title?: string;
  style?: Record<string, unknown>;
  option?: Record<string, unknown>;
  data?: DataBinding;
  supersetUuid?: string;
  /**
   * 事件配置（钻取联动）。
   * - type='filter'：点击本组件某维度时，把值写入 globalVar
   * - type='jump'：点击本组件跳转到 target（dashboardId 或 URL）
   */
  events?: WidgetEvent[];
}

/**
 * 组件事件（钻取联动配置）。
 */
export interface WidgetEvent {
  type: 'filter' | 'jump';
  /** type='filter'：触发字段（通常等于组件绑定维度） */
  field?: string;
  /** type='filter'：写入哪个 globalVar key */
  targetVar?: string;
  /** type='jump'：跳转目标（dashboardId 或外部 URL） */
  target?: string;
}

export interface ScreenCanvas {
  width: number;
  height: number;
  theme: 'light' | 'dark';
  background: string;
}

export interface ScreenSpec {
  canvas: ScreenCanvas;
  widgets: WidgetNode[];
  /**
   * 全局变量（被筛选器条 / 组件事件写入；可被任意 widget 的 data.filters[].fromVar 引用）。
   * 渲染时 ScreenDesigner 维护 varValues: Record<key, value>。
   */
  globalVars?: GlobalVar[];
}

export interface GlobalVar {
  key: string;
  label: string;
  /** 默认值（首次渲染时初始化 varValues） */
  default?: unknown;
  /** 可选：来源组件 ID（用于 UI 提示） */
  source?: 'manual' | 'widget';
}

export const DEFAULT_CANVAS: ScreenCanvas = {
  width: 1920,
  height: 1080,
  theme: 'dark',
  background: '#0a1a2f',
};

export const DEFAULT_LAYOUT = { x: 0, y: 0, w: 8, h: 6, z: 1 };
