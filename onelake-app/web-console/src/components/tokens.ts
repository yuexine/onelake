/**
 * 设计令牌（对应原型设计文档 §2.2）。
 *
 * 密级色 L1-L4 是跨模块强约定：采集层字段标记、目录资产徽章、血缘节点描边、
 * 脱敏策略、API 返回字段，凡涉及敏感分级处必须用同一套密级色。
 */
import { theme } from 'antd';

export const tokens = {
  // 间距系统（基础 4px）
  spacing: { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 },
  // 圆角
  radius: { sm: 4, md: 8 },
  // 栅格
  grid: { cols: 12, maxWidth: 1440, min: 1280 },
  // 断点
  breakpoints: { compact: 1280, standard: 1599, wide: 1600 },
};

/** 状态色（§2.2） */
export const statusColors: Record<string, string> = {
  success: '#52c41a',
  warning: '#faad14',
  error:   '#ff4d4f',
  info:    '#1677ff',
  neutral: '#8c8c8c',
  running: '#1677ff',
  pending: '#8c8c8c',
  failed:  '#ff4d4f',
  offline: '#595959',
};

/** 密级色板（§2.2 跨模块强约定） */
export interface ClassificationMeta {
  label: string;        // L1/L2/L3/L4
  name: string;         // 公开/内部/敏感/机密
  color: string;        // 文字色
  bg: string;           // 背景色
  border: string;
}

export const classifications: Record<string, ClassificationMeta> = {
  L1: { label: 'L1', name: '公开', color: '#595959', bg: '#fafafa', border: '#d9d9d9' },
  L2: { label: 'L2', name: '内部', color: '#1677ff', bg: '#e6f4ff', border: '#91caff' },
  L3: { label: 'L3', name: '敏感', color: '#fa8c16', bg: '#fff7e6', border: '#ffd591' },
  L4: { label: 'L4', name: '机密', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
};

/** 任务状态徽章颜色映射（§2.3 StatusBadge） */
export const taskStatusColorMap: Record<string, string> = {
  DRAFT: 'default',
  ENABLED: 'processing',
  PAUSED: 'warning',
  QUEUED: 'default',
  RUNNING: 'processing',
  SUCCEEDED: 'success',
  SUCCESS: 'success',
  FAILED: 'error',
  PENDING: 'default',
  APPROVED: 'success',
  REJECTED: 'error',
  ACTIVE: 'success',
  EXPIRED: 'warning',
  REVOKED: 'error',
  PUBLISHED: 'success',
  DEPRECATED: 'warning',
  OFFLINE: 'default',
  OPEN: 'error',
  ACK: 'warning',
  CLOSED: 'default',
};

export const { defaultAlgorithm } = theme;
