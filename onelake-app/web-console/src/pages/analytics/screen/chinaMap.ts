/**
 * ECharts 中国地图注册器。
 * map 类型组件依赖 china.json 几何数据，懒加载以避免首屏体积。
 *
 * 加载时机：首次渲染 map 组件时调用 ensureChinaMap()，已加载则直接返回。
 */
import * as echarts from 'echarts';

let loaded = false;
let loading: Promise<void> | null = null;

const CHINA_GEO_URL = 'https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json';

export function ensureChinaMap(): Promise<void> {
  if (loaded) return Promise.resolve();
  if (loading) return loading;
  loading = (async () => {
    try {
      const resp = await fetch(CHINA_GEO_URL);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const geo = await resp.json();
      echarts.registerMap('china', geo);
      loaded = true;
    } catch (e) {
      console.warn('[ensureChinaMap] failed to load china geojson from aliyun, fallback to plain scatter map', e);
      // 降级：注册一个空 map 让 ECharts 不报错（用户可改用 region 柱状图替代）
      echarts.registerMap('china', { type: 'FeatureCollection', features: [] });
      loaded = true;
    }
  })();
  return loading;
}
