/**
 * 组件注册表（WIDGET_REGISTRY）+ 通用渲染器（WidgetRenderer）。
 *
 * 设计原则（§7.2）：
 * 1. 每个组件类型对应一个 WidgetDef，含 icon / defaults / buildOption 函数
 * 2. buildOption(rows, node) 返回 ECharts option（高级用户可在 node.option 覆盖）
 * 3. superset 类型走 SupersetPanel（不走 ECharts）
 *
 * 优先级批次（对应 types.ts 注释）：
 *   P2 最小可用 15：line · bar · pie · scatter · metric · flipper · table · text ·
 *                  image · decoration · superset · rankList · radar · funnel · heatmap
 *   P3 常用补齐 5：map · gauge · progress · carousel · video
 *   P5 扩展 5：    wordCloud · liquidFill · treemap · sunburst · sankey
 */
import React, { useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
// ECharts 扩展（必须 import 才会注册到 echarts 模块）
import 'echarts-wordcloud';
import 'echarts-liquidfill';
import * as echarts from 'echarts';
import {
  LineChartOutlined, BarChartOutlined, PieChartOutlined, DotChartOutlined,
  RadarChartOutlined, FieldNumberOutlined, TableOutlined, FontSizeOutlined,
  PictureOutlined, BorderOutlined, FundOutlined, HeatMapOutlined,
  FallOutlined, TrophyOutlined,
  DashboardOutlined, ClockCircleOutlined, CarOutlined, VideoCameraOutlined,
  GlobalOutlined, CloudOutlined, ApartmentOutlined, ShareAltOutlined,
} from '@ant-design/icons';
import type { WidgetNode, DataBinding } from './types';
import type { AnalyticsQueryResult, AnalyticsDataBinding } from '../../../api';
import { AnalyticsAPI } from '../../../api';
import { SupersetPanel } from './SupersetPanel';
import { ensureChinaMap } from './chinaMap';
import { useLinkage } from './linkage';

export interface WidgetDef {
  type: WidgetNode['type'];
  label: string;
  icon: React.ReactNode;
  category: 'chart' | 'metric' | 'media' | 'decoration' | 'embed';
  defaults: Partial<WidgetNode>;
  buildOption?: (node: WidgetNode, rows: Record<string, unknown>[]) => unknown;
}

const COMMON_DEFAULTS: Partial<WidgetNode> = {
  layout: { x: 0, y: 0, w: 8, h: 6, z: 1 },
};

export const WIDGET_REGISTRY: Record<string, WidgetDef> = {
  // ============ P2 最小可用 15 ============
  line: {
    type: 'line', label: '折线图', icon: <LineChartOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: { trigger: 'axis' },
      legend: { textStyle: { color: '#eee' } },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: rows.map((r) => String(r[node.data!.dimensions[0]] ?? '')) },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#333' } } },
      series: (node.data!.measures || []).map((m) => ({
        type: 'line', smooth: true, name: m.field,
        data: rows.map((r) => Number(r[m.field] ?? 0)),
      })),
      ...(node.option ?? {}),
    }),
  },
  bar: {
    type: 'bar', label: '柱状图', icon: <BarChartOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: { trigger: 'axis' },
      legend: { textStyle: { color: '#eee' } },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: rows.map((r) => String(r[node.data!.dimensions[0]] ?? '')) },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#333' } } },
      series: (node.data!.measures || []).map((m) => ({
        type: 'bar', name: m.field,
        data: rows.map((r) => Number(r[m.field] ?? 0)),
      })),
      ...(node.option ?? {}),
    }),
  },
  pie: {
    type: 'pie', label: '饼图', icon: <PieChartOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: { trigger: 'item' },
      legend: { type: 'scroll', orient: 'vertical', right: 10, top: 'center', textStyle: { color: '#eee' } },
      series: [{
        type: 'pie', radius: ['35%', '70%'],
        data: rows.map((r) => ({
          name: String(r[node.data!.dimensions[0]] ?? ''),
          value: Number(r[node.data!.measures?.[0]?.field ?? ''] ?? 0),
        })),
      }],
      ...(node.option ?? {}),
    }),
  },
  scatter: {
    type: 'scatter', label: '散点图', icon: <DotChartOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: { trigger: 'item' },
      xAxis: { type: 'value', scale: true, splitLine: { lineStyle: { color: '#333' } } },
      yAxis: { type: 'value', scale: true, splitLine: { lineStyle: { color: '#333' } } },
      series: [{
        type: 'scatter', symbolSize: 8,
        data: rows.map((r) => [
          Number(r[node.data!.dimensions[0]] ?? 0),
          Number(r[node.data!.measures?.[0]?.field ?? ''] ?? 0),
        ]),
      }],
      ...(node.option ?? {}),
    }),
  },
  radar: {
    type: 'radar', label: '雷达图', icon: <RadarChartOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: {},
      radar: {
        indicator: rows.map((r) => ({
          name: String(r[node.data!.dimensions[0]] ?? ''),
          max: 100,
        })),
      },
      series: [{
        type: 'radar',
        data: (node.data!.measures || []).map((m) => ({
          name: m.field, value: rows.map((r) => Number(r[m.field] ?? 0)),
        })),
      }],
      ...(node.option ?? {}),
    }),
  },
  funnel: {
    type: 'funnel', label: '漏斗图', icon: <FallOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => ({
      tooltip: { trigger: 'item' },
      series: [{
        type: 'funnel', sort: 'descending', gap: 2,
        label: { color: '#eee' },
        data: rows.map((r) => ({
          name: String(r[node.data!.dimensions[0]] ?? ''),
          value: Number(r[node.data!.measures?.[0]?.field ?? ''] ?? 0),
        })),
      }],
      ...(node.option ?? {}),
    }),
  },
  heatmap: {
    type: 'heatmap', label: '热力图', icon: <HeatMapOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const xs = [...new Set(rows.map((r) => String(r[node.data!.dimensions[0]] ?? '')))];
      const ys = [...new Set(rows.map((r) => String(r[node.data!.dimensions[1]] ?? '')))];
      const data: any[] = [];
      rows.forEach((r) => {
        const xi = xs.indexOf(String(r[node.data!.dimensions[0]] ?? ''));
        const yi = ys.indexOf(String(r[node.data!.dimensions[1]] ?? ''));
        const val = Number(r[node.data!.measures?.[0]?.field ?? ''] ?? 0);
        data.push([xi, yi, val]);
      });
      return {
        tooltip: { position: 'top' },
        grid: { left: 60, right: 20, top: 20, bottom: 60 },
        xAxis: { type: 'category', data: xs, splitArea: { show: true } },
        yAxis: { type: 'category', data: ys, splitArea: { show: true } },
        visualMap: { min: 0, max: 100, calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
        series: [{ type: 'heatmap', data, label: { show: false } }],
        ...(node.option ?? {}),
      };
    },
  },
  metric: {
    type: 'metric', label: '指标卡', icon: <FieldNumberOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 4, h: 4, z: 1 },
                data: { dimensions: [], measures: [] } },
    // metric 不走 ECharts，由 MetricCard 组件渲染
    buildOption: () => ({}),
  },
  flipper: {
    type: 'flipper', label: '翻牌器', icon: <FundOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 4, h: 4, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: () => ({}),
  },
  rankList: {
    type: 'rankList', label: '排名榜', icon: <TrophyOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: () => ({}),
  },
  table: {
    type: 'table', label: '表格', icon: <TableOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 12, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: () => ({}),
  },
  text: {
    type: 'text', label: '文本', icon: <FontSizeOutlined />, category: 'media',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 6, h: 2, z: 1 }, title: '请输入文本' },
    buildOption: () => ({}),
  },
  image: {
    type: 'image', label: '图片', icon: <PictureOutlined />, category: 'media',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 4, h: 4, z: 1 }, title: '' },
    buildOption: () => ({}),
  },
  decoration: {
    type: 'decoration', label: '装饰边框', icon: <BorderOutlined />, category: 'decoration',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 2, z: 0 } },
    buildOption: () => ({}),
  },
  superset: {
    type: 'superset', label: 'Superset 面板', icon: <BarChartOutlined />, category: 'embed',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 12, h: 10, z: 1 } },
    buildOption: () => ({}),
  },

  // ============ P3 常用补齐 5 ============
  gauge: {
    type: 'gauge', label: '仪表盘', icon: <DashboardOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 4, h: 6, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const mea = node.data?.measures?.[0]?.field ?? '';
      const val = rows.length ? Number(rows[0][mea] ?? 0) : 0;
      return {
        series: [{
          type: 'gauge',
          progress: { show: true, width: 18 },
          axisLine: { lineStyle: { width: 18 } },
          detail: { valueAnimation: true, formatter: '{value}', color: '#eee', fontSize: 24 },
          data: [{ value: val, name: mea }],
        }],
        ...(node.option ?? {}),
      };
    },
  },
  progress: {
    type: 'progress', label: '进度条', icon: <ClockCircleOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 6, h: 3, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const mea = node.data?.measures?.[0]?.field ?? '';
      const val = rows.length ? Number(rows[0][mea] ?? 0) : 0;
      const pct = Math.min(100, Math.max(0, val));
      return {
        series: [{
          type: 'gauge',
          startAngle: 200, endAngle: -20,
          min: 0, max: 100,
          progress: { show: true, width: 26, itemStyle: { color: '#1f6feb' } },
          axisLine: { lineStyle: { width: 26, color: [[1, 'rgba(255,255,255,0.08)']] } },
          pointer: { show: false },
          axisTick: { show: false }, axisLabel: { show: false }, splitLine: { show: false },
          detail: { valueAnimation: true, formatter: '{value}%', color: '#eee', fontSize: 20, offsetCenter: [0, '40%'] },
          data: [{ value: pct }],
        }],
        ...(node.option ?? {}),
      };
    },
  },
  carousel: {
    type: 'carousel', label: '图片轮播', icon: <CarOutlined />, category: 'media',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 6, z: 1 },
                title: 'https://example.com/a.png\nhttps://example.com/b.png' },
    buildOption: () => ({}),  // carousel 由 WidgetRenderer 直接渲染（非 ECharts）
  },
  video: {
    type: 'video', label: '视频', icon: <VideoCameraOutlined />, category: 'media',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 6, z: 1 },
                title: 'https://example.com/intro.mp4' },
    buildOption: () => ({}),
  },
  map: {
    type: 'map', label: '中国地图', icon: <GlobalOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 12, h: 10, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const dim = node.data?.dimensions?.[0] ?? 'region';
      const mea = node.data?.measures?.[0]?.field ?? 'value';
      const data = rows.map((r) => ({
        name: String(r[dim] ?? ''),
        value: Number(r[mea] ?? 0),
      }));
      return {
        tooltip: { trigger: 'item', formatter: '{b}: {c}' },
        visualMap: {
          min: 0, max: data.length ? Math.max(...data.map((d) => d.value), 100) : 100,
          calculable: true, orient: 'horizontal', left: 'center', bottom: 10,
          textStyle: { color: '#eee' },
        },
        series: [{
          type: 'map', map: 'china',
          roam: true,
          emphasis: { label: { show: true } },
          data,
        }],
        ...(node.option ?? {}),
      };
    },
  },

  // ============ P5 扩展 5 ============
  wordCloud: {
    type: 'wordCloud', label: '词云', icon: <CloudOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const dim = node.data?.dimensions?.[0] ?? 'word';
      const mea = node.data?.measures?.[0]?.field ?? 'count';
      return {
        tooltip: { show: true },
        series: [{
          type: 'wordCloud',
          shape: 'circle',
          sizeRange: [12, 60],
          rotationRange: [-45, 45],
          textStyle: {
            fontFamily: 'sans-serif',
            fontWeight: 'bold',
            color: () => `rgb(${Math.random() * 160 | 0},${Math.random() * 160 | 0},${Math.random() * 160 | 0})`,
          },
          data: rows.map((r) => ({
            name: String(r[dim] ?? ''),
            value: Number(r[mea] ?? 0),
          })),
        }],
        ...(node.option ?? {}),
      };
    },
  },
  liquidFill: {
    type: 'liquidFill', label: '水球图', icon: <CloudOutlined />, category: 'metric',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 4, h: 6, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const mea = node.data?.measures?.[0]?.field ?? '';
      const val = rows.length ? Number(rows[0][mea] ?? 0) / 100 : 0.5;
      return {
        series: [{
          type: 'liquidFill',
          radius: '70%',
          data: [Math.min(1, Math.max(0, val))],
          label: { color: '#eee', insideColor: '#fff', fontSize: 24 },
          backgroundStyle: { color: 'transparent' },
          outline: { itemStyle: { borderColor: '#1f6feb', borderWidth: 2 }, borderDistance: 3 },
          color: ['#1f6feb', '#52c41a'],
        }],
        ...(node.option ?? {}),
      };
    },
  },
  treemap: {
    type: 'treemap', label: '矩形树图', icon: <ApartmentOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const dim = node.data?.dimensions?.[0] ?? 'category';
      const mea = node.data?.measures?.[0]?.field ?? 'value';
      return {
        tooltip: { formatter: '{b}: {c}' },
        series: [{
          type: 'treemap',
          roam: false,
          data: rows.map((r) => ({
            name: String(r[dim] ?? ''),
            value: Number(r[mea] ?? 0),
          })),
          label: { color: '#fff', fontSize: 12 },
          upperLabel: { show: true, height: 22, color: '#eee' },
          levels: [{
            itemStyle: { borderColor: '#0a1420', borderWidth: 2, gapWidth: 2 },
          }],
        }],
        ...(node.option ?? {}),
      };
    },
  },
  sunburst: {
    type: 'sunburst', label: '旭日图', icon: <ApartmentOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 8, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      const dim = node.data?.dimensions?.[0] ?? 'category';
      const mea = node.data?.measures?.[0]?.field ?? 'value';
      return {
        tooltip: { formatter: '{b}: {c}' },
        series: [{
          type: 'sunburst',
          radius: ['10%', '95%'],
          data: rows.map((r) => ({
            name: String(r[dim] ?? ''),
            value: Number(r[mea] ?? 0),
          })),
          label: { color: '#fff', minAngle: 5 },
        }],
        ...(node.option ?? {}),
      };
    },
  },
  sankey: {
    type: 'sankey', label: '桑基图', icon: <ShareAltOutlined />, category: 'chart',
    defaults: { ...COMMON_DEFAULTS, layout: { x: 0, y: 0, w: 12, h: 8, z: 1 },
                data: { dimensions: [], measures: [] } },
    buildOption: (node, rows) => {
      // 约定：第一维是源、第二维是目标、第一个 measure 是流量
      const sourceField = node.data?.dimensions?.[0] ?? 'source';
      const targetField = node.data?.dimensions?.[1] ?? 'target';
      const valueField = node.data?.measures?.[0]?.field ?? 'value';
      const nodes = new Set<string>();
      rows.forEach((r) => {
        nodes.add(String(r[sourceField] ?? ''));
        nodes.add(String(r[targetField] ?? ''));
      });
      return {
        tooltip: { trigger: 'item' },
        series: [{
          type: 'sankey',
          emphasis: { focus: 'adjacency' },
          lineStyle: { color: 'gradient', curveness: 0.5 },
          data: Array.from(nodes).map((name) => ({ name })),
          links: rows.map((r) => ({
            source: String(r[sourceField] ?? ''),
            target: String(r[targetField] ?? ''),
            value: Number(r[valueField] ?? 0),
          })),
        }],
        ...(node.option ?? {}),
      };
    },
  },
};

/**
 * hook：根据 binding 拉数据集数据（带轮询刷新）。
 */
function useDatasetQuery(binding: DataBinding | undefined) {
  const [data, setData] = useState<AnalyticsQueryResult | null>(null);
  const [loading, setLoading] = useState(false);
  const { varValues } = useLinkage();

  // 把 binding.filters 中带 fromVar 的项替换为 varValues 中的实际值
  const resolvedFilters = useMemo(() => {
    if (!binding?.filters) return undefined;
    return binding.filters.map((f) => {
      if (f.fromVar && varValues[f.fromVar] !== undefined) {
        return { ...f, value: varValues[f.fromVar] };
      }
      return f;
    }).filter((f) => f.value !== undefined && f.value !== null && f.value !== '');
  }, [JSON.stringify(binding?.filters), JSON.stringify(varValues)]);

  useEffect(() => {
    if (!binding?.datasetId) return;
    let cancelled = false;
    const fetchData = async () => {
      setLoading(true);
      try {
        const apiBinding: AnalyticsDataBinding = {
          datasetId: binding.datasetId,
          dimensions: binding.dimensions,
          measures: binding.measures,
          filters: resolvedFilters,
          limit: binding.refreshSec ? undefined : 10000,
        };
        const r = await AnalyticsAPI.queryDataset(binding.datasetId!, apiBinding);
        if (!cancelled) setData(r);
      } catch (e) {
        console.error('dataset query failed', e);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchData();
    if (binding.refreshSec && binding.refreshSec >= 5) {
      const timer = setInterval(fetchData, binding.refreshSec * 1000);
      return () => { cancelled = true; clearInterval(timer); };
    }
    return () => { cancelled = true; };
  }, [binding?.datasetId, binding?.refreshSec, JSON.stringify(resolvedFilters)]);

  return { data: data?.rows ?? [], fields: data?.fields ?? [], loading };
}

export function WidgetRenderer({ node }: { node: WidgetNode }) {
  const { data: rows, loading } = useDatasetQuery(node.data);
  const { setVar } = useLinkage();

  // map 组件依赖 china 地图 geojson，懒加载
  const [mapReady, setMapReady] = useState(false);
  useEffect(() => {
    if (node.type !== 'map') return;
    let cancelled = false;
    ensureChinaMap().then(() => { if (!cancelled) setMapReady(true); });
    return () => { cancelled = true; };
  }, [node.type]);

  // 钻取联动：点击 ECharts 时触发 widget.events[type=filter]
  const handleChartClick = (params: { name?: string; value?: unknown; dimensionNames?: string[] }) => {
    if (!node.events || !node.events.length) return;
    const filterEvents = node.events.filter((e) => e.type === 'filter' && e.targetVar);
    if (!filterEvents.length) return;
    // 取点击的维度值（params.name 通常等于 X 轴 / 类别）
    const clickedValue = params.name ?? params.value;
    filterEvents.forEach((e) => {
      if (e.targetVar) setVar(e.targetVar, clickedValue);
    });
  };

  // Superset 嵌入：不走 ECharts
  if (node.type === 'superset') {
    return node.supersetUuid
      ? <SupersetPanel uuid={node.supersetUuid} />
      : <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>未配置 Superset Dashboard UUID</div>;
  }

  // 指标卡 / 翻牌器：纯文本展示
  if (node.type === 'metric' || node.type === 'flipper') {
    const val = rows[0]?.[node.data?.measures?.[0]?.field ?? ''] as number | undefined;
    return (
      <div style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        height: '100%', color: '#fff',
      }}>
        {node.title && <div style={{ fontSize: 14, opacity: 0.7, marginBottom: 8 }}>{node.title}</div>}
        <div style={{ fontSize: 36, fontWeight: 700 }}>
          {loading ? '...' : (val !== undefined ? Number(val).toLocaleString() : '-')}
        </div>
      </div>
    );
  }

  // 排名榜：取前 10
  if (node.type === 'rankList') {
    const dim = node.data?.dimensions?.[0] ?? 'name';
    const mea = node.data?.measures?.[0]?.field ?? 'value';
    const top10 = [...rows].sort((a, b) => Number(b[mea] ?? 0) - Number(a[mea] ?? 0)).slice(0, 10);
    return (
      <div style={{ padding: 12, height: '100%', overflow: 'auto' }}>
        {top10.map((r, i) => (
          <div key={i} style={{ display: 'flex', padding: '4px 0', color: '#fff' }}>
            <span style={{ width: 28, fontWeight: 700, color: i < 3 ? '#faad14' : '#eee' }}>#{i + 1}</span>
            <span style={{ flex: 1 }}>{String(r[dim] ?? '')}</span>
            <span>{Number(r[mea] ?? 0).toLocaleString()}</span>
          </div>
        ))}
        {!top10.length && <div style={{ color: '#999', textAlign: 'center', paddingTop: 24 }}>{loading ? '加载中...' : '无数据'}</div>}
      </div>
    );
  }

  // 表格
  if (node.type === 'table') {
    return (
      <div style={{ padding: 8, height: '100%', overflow: 'auto', color: '#fff' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr>{(node.data?.dimensions ?? []).concat((node.data?.measures ?? []).map((m) => m.field)).map((f) => (
              <th key={f} style={{ textAlign: 'left', padding: 6, borderBottom: '1px solid #444' }}>{f}</th>
            ))}</tr>
          </thead>
          <tbody>
            {rows.slice(0, 50).map((r, i) => (
              <tr key={i}>
                {(node.data?.dimensions ?? []).concat((node.data?.measures ?? []).map((m) => m.field)).map((f) => (
                  <td key={f} style={{ padding: 6, borderBottom: '1px solid #333' }}>{String(r[f] ?? '')}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <div style={{ color: '#999', textAlign: 'center', paddingTop: 24 }}>{loading ? '加载中...' : '无数据'}</div>}
      </div>
    );
  }

  // 文本 / 图片 / 装饰：直接渲染 DOM
  if (node.type === 'text') {
    return <div style={{ padding: 12, color: '#fff', fontSize: 18, fontWeight: 600 }}>{node.title}</div>;
  }
  if (node.type === 'image') {
    return <img src={node.title} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />;
  }
  if (node.type === 'decoration') {
    return (
      <div style={{ width: '100%', height: '100%', background: 'linear-gradient(90deg, transparent, #1f6feb, transparent)' }} />
    );
  }

  // 视频组件（HTML5 video，title 字段存放 URL）
  if (node.type === 'video') {
    return (
      <video
        src={node.title}
        controls
        autoPlay
        loop
        muted
        style={{ width: '100%', height: '100%', objectFit: 'contain', background: '#000' }}
      />
    );
  }

  // 图片轮播（title 字段按换行分隔多个 URL）
  if (node.type === 'carousel') {
    return <Carousel urls={(node.title ?? '').split(/\s+/).filter(Boolean)} />;
  }

  // 标准图表：ECharts
  const def = WIDGET_REGISTRY[node.type];
  if (!def?.buildOption) return <div style={{ padding: 24, color: '#999' }}>未知组件 {node.type}</div>;
  // map 类型等地图数据加载完成
  if (node.type === 'map' && !mapReady) {
    return <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>加载地图...</div>;
  }
  return (
    <ReactECharts
      option={def.buildOption(node, rows) as any}
      style={{ height: '100%', width: '100%' }}
      notMerge
      lazyUpdate
      theme="dark"
      onEvents={{ click: handleChartClick }}
    />
  );
}

/**
 * 图片轮播组件（P5-A）：title 字段按空白分隔多个 URL。
 */
function Carousel({ urls }: { urls: string[] }) {
  const [idx, setIdx] = useState(0);
  useEffect(() => {
    if (urls.length <= 1) return;
    const t = setInterval(() => setIdx((i) => (i + 1) % urls.length), 4000);
    return () => clearInterval(t);
  }, [urls.length]);
  if (!urls.length) {
    return <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>无图片</div>;
  }
  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden' }}>
      <img
        src={urls[idx]}
        alt={`slide-${idx}`}
        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
      />
      {urls.length > 1 && (
        <div style={{ position: 'absolute', bottom: 8, right: 12, padding: '2px 8px', background: 'rgba(0,0,0,0.5)', borderRadius: 10, color: '#fff', fontSize: 11 }}>
          {idx + 1} / {urls.length}
        </div>
      )}
    </div>
  );
}
