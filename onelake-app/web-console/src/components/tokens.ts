/**
 * 企业级设计令牌（v2 · 升级版）
 * 对应原型 §2.2 设计令牌；此版本补齐语义色、动效曲线、阴影分层、密度、字体刻度。
 *
 * 全站通过 `tokens` 引用 — 不得在业务页面直接写死颜色/间距/阴影。
 */

import { theme } from 'antd';
import type { ThemeConfig } from 'antd';

/* ------------------------------------------------------------------ */
/* 1. 颜色系统：以「深蓝 + 中性蓝灰」为主调的企业级配色                 */
/* ------------------------------------------------------------------ */

export const color = {
  // 品牌
  brand:       '#0F4FD8',
  brandHover:  '#1E62F0',
  brandActive: '#0A3FB0',
  brandSoft:   '#E8F0FF',
  brandBorder: '#B7D2FF',
  brandGradient: 'linear-gradient(135deg, #0F4FD8 0%, #4A85FF 100%)',

  // 中性（基于 slate）
  ink:     '#0F172A', // 主文本
  ink2:    '#334155', // 次文本
  ink3:    '#64748B', // 辅助文本
  ink4:    '#94A3B8', // 占位/禁用
  line:    '#E2E8F0', // 默认描边
  lineSoft:'#EEF2F7', // 弱描边
  fill:    '#F5F7FA', // 页面背景
  fillSoft:'#FAFBFC', // 卡片次级背景
  cardBg:  '#FFFFFF', // 卡片背景

  // 状态（更克制的色调，减少视觉刺激）
  success:    '#16A34A',
  successSoft:'#DCFCE7',
  warning:    '#F59E0B',
  warningSoft:'#FEF3C7',
  error:      '#DC2626',
  errorSoft:  '#FEE2E2',
  info:       '#0EA5E9',
  infoSoft:   '#E0F2FE',

  // 强调（用于特殊高亮，谨慎使用）
  violet: '#7C3AED',
} as const;

/* ------------------------------------------------------------------ */
/* 2. 密级色（§2.2 跨模块强约定 — 不可改色相）                          */
/* ------------------------------------------------------------------ */

export interface ClassificationMeta {
  label: string;
  name: string;
  color: string;
  bg: string;
  border: string;
  dot: string;   // 用于节点描边/小圆点
}

export const classifications: Record<'L1' | 'L2' | 'L3' | 'L4', ClassificationMeta> = {
  L1: { label: 'L1', name: '公开', color: '#475569', bg: '#F1F5F9', border: '#CBD5E1', dot: '#64748B' },
  L2: { label: 'L2', name: '内部', color: '#0F4FD8', bg: '#E8F0FF', border: '#B7D2FF', dot: '#0F4FD8' },
  L3: { label: 'L3', name: '敏感', color: '#C2410C', bg: '#FFF7ED', border: '#FDBA74', dot: '#F97316' },
  L4: { label: 'L4', name: '机密', color: '#B91C1C', bg: '#FEF2F2', border: '#FCA5A5', dot: '#DC2626' },
};

/* ------------------------------------------------------------------ */
/* 3. 状态语义映射                                                     */
/* ------------------------------------------------------------------ */

export const statusColors = {
  success: color.success,
  warning: color.warning,
  error:   color.error,
  info:    color.info,
  neutral: color.ink4,
  running: color.info,
  pending: color.ink4,
  failed:  color.error,
  offline: '#475569',
} as const;

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

/* ------------------------------------------------------------------ */
/* 4. 间距 / 圆角 / 字号 / 行高                                        */
/* ------------------------------------------------------------------ */

export const space = { xxs: 2, xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32, xxxl: 48 };

export const radius = {
  none: 0,
  sm: 4,
  md: 6,
  lg: 8,
  xl: 12,
  pill: 999,
};

export const fontSize = {
  xs:  11,
  sm:  12,
  body:13,
  md:  14,
  lg:  16,
  xl:  18,
  h3:  20,
  h2:  24,
  h1:  30,
};

export const lineHeight = { tight: 1.25, base: 1.5, relaxed: 1.65 };

export const fontWeight = { regular: 400, medium: 500, semibold: 600, bold: 700 };

/* ------------------------------------------------------------------ */
/* 5. 阴影分层（e1 卡片 / e2 hover / e3 弹层）                         */
/* ------------------------------------------------------------------ */

export const shadow = {
  none: 'none',
  e1: '0 1px 2px rgba(15, 23, 42, 0.04), 0 1px 1px rgba(15, 23, 42, 0.02)',
  e2: '0 4px 12px rgba(15, 23, 42, 0.06), 0 2px 4px rgba(15, 23, 42, 0.04)',
  e3: '0 12px 32px rgba(15, 23, 42, 0.10), 0 4px 8px rgba(15, 23, 42, 0.06)',
  e4: '0 24px 56px rgba(15, 23, 42, 0.14), 0 8px 16px rgba(15, 23, 42, 0.08)',
  focus: '0 0 0 3px rgba(15, 79, 216, 0.16)',
  focusError: '0 0 0 3px rgba(220, 38, 38, 0.16)',
};

/* ------------------------------------------------------------------ */
/* 6. 动效曲线与时长                                                   */
/* ------------------------------------------------------------------ */

export const motion = {
  durationFast:   '120ms',
  durationBase:   '200ms',
  durationSlow:   '320ms',
  easeInOut: 'cubic-bezier(0.4, 0, 0.2, 1)',
  easeOut:   'cubic-bezier(0.16, 1, 0.3, 1)',
  easeIn:    'cubic-bezier(0.4, 0, 1, 1)',
  spring:    'cubic-bezier(0.34, 1.56, 0.64, 1)',
};

/* ------------------------------------------------------------------ */
/* 7. 控件高度（统一 density）                                          */
/* ------------------------------------------------------------------ */

export const controlHeight = { xs: 20, sm: 26, md: 32, lg: 38 };

/* ------------------------------------------------------------------ */
/* 8. z-index 规约                                                     */
/* ------------------------------------------------------------------ */

export const z = {
  base:     0,
  sticky:   10,
  dropdown: 1000,
  drawer:   1010,
  modal:    1020,
  toast:    1030,
  tooltip:  1060,
};

/* ------------------------------------------------------------------ */
/* 9. 兼容旧 API（不破坏已有引用）                                      */
/* ------------------------------------------------------------------ */

export const tokens = {
  spacing: space,
  radius: { sm: radius.sm, md: radius.md, lg: radius.lg },
  grid:   { cols: 12, maxWidth: 1440, min: 1280 },
  breakpoints: { compact: 1280, standard: 1599, wide: 1600 },
};

/* ------------------------------------------------------------------ */
/* 10. antd 主题配置（注入到 ConfigProvider）                           */
/* ------------------------------------------------------------------ */

export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: color.brand,
    colorInfo:    color.info,
    colorSuccess: color.success,
    colorWarning: color.warning,
    colorError:   color.error,

    colorText:           color.ink,
    colorTextSecondary:  color.ink2,
    colorTextTertiary:   color.ink3,
    colorTextQuaternary: color.ink4,
    colorTextDisabled:   color.ink4,

    colorBorder:          color.line,
    colorBorderSecondary: color.lineSoft,
    colorBgLayout:        color.fill,
    colorBgContainer:     color.cardBg,
    colorBgElevated:      color.cardBg,

    borderRadius:    radius.md,
    borderRadiusLG:  radius.lg,
    borderRadiusSM:  radius.sm,
    borderRadiusXS:  radius.sm,

    fontFamily: `-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif`,
    fontFamilyCode: `'SFMono-Regular', 'JetBrains Mono', 'Menlo', 'Consolas', 'Liberation Mono', monospace`,
    fontSize: fontSize.md,

    controlHeight:   controlHeight.md,
    controlHeightSM: controlHeight.sm,
    controlHeightLG: controlHeight.lg,

    boxShadow: `
      0 1px 2px rgba(15, 23, 42, 0.04),
      0 1px 1px rgba(15, 23, 42, 0.02)
    `,
    boxShadowSecondary: `
      0 12px 32px rgba(15, 23, 42, 0.10),
      0 4px 8px rgba(15, 23, 42, 0.06)
    `,

    wireframe: false,
    motionDurationFast:  motion.durationFast,
    motionDurationMid:   motion.durationBase,
    motionDurationSlow:  motion.durationSlow,
  },
  components: {
    Layout: {
      headerBg:   color.cardBg,
      headerHeight: 56,
      headerPadding: '0 20px',
      siderBg:    color.cardBg,
      bodyBg:     color.fill,
    },
    Menu: {
      itemBg: 'transparent',
      itemHeight: 36,
      subMenuItemBg: 'transparent',
      itemSelectedBg:   color.brandSoft,
      itemSelectedColor: color.brand,
      itemHoverBg:       color.fill,
      itemHoverColor:    color.ink,
      itemColor:         color.ink2,
      activeBarHeight:   0,
      activeBarBorderWidth: 0,
      iconSize: 16,
    },
    Card: {
      headerBg: 'transparent',
      headerFontSize: fontSize.md,
      headerHeight: 48,
      paddingLG: space.xl,
      boxShadowTertiary: 'none',
    },
    Table: {
      headerBg: color.fillSoft,
      headerColor: color.ink3,
      headerSplitColor: color.lineSoft,
      cellPaddingBlock: space.md,
      cellPaddingInline: space.lg,
      cellPaddingBlockSM: space.sm,
      cellPaddingInlineSM: space.md,
      rowHoverBg: color.fillSoft,
      rowSelectedBg: color.brandSoft,
      rowSelectedHoverBg: '#DCE8FF',
      borderColor: color.lineSoft,
      footerBg: color.fillSoft,
    },
    Button: {
      controlHeight: controlHeight.md,
      controlHeightSM: controlHeight.sm,
      controlHeightLG: controlHeight.lg,
      fontWeight: fontWeight.medium,
      primaryShadow: 'none',
      defaultShadow: 'none',
      dangerShadow: 'none',
    },
    Input: {
      controlHeight: controlHeight.md,
      activeShadow: '0 0 0 3px rgba(15, 79, 216, 0.12)',
    },
    Select: {
      controlHeight: controlHeight.md,
      optionSelectedBg: color.brandSoft,
      optionSelectedColor: color.brand,
      optionActiveBg: color.fill,
    },
    Tabs: {
      horizontalItemPadding: '10px 4px',
      horizontalItemGutter: 24,
      horizontalMargin: '0 0 16px 0',
      inkBarColor: color.brand,
      itemActiveColor: color.brand,
      itemSelectedColor: color.brand,
      itemColor: color.ink2,
      titleFontSize: fontSize.md,
    },
    Tag: {
      defaultBg: color.fillSoft,
      defaultColor: color.ink2,
    },
    Badge: { fontSizeSM: 11 },
    Statistic: {
      contentFontSize: fontSize.h2,
    },
    Modal: { borderRadiusLG: radius.lg },
    Drawer: { borderRadiusLG: 0 },
    Form: {
      itemMarginBottom: space.lg,
      verticalLabelPadding: '0 0 6px',
      labelColor: color.ink2,
    },
    Tooltip: { borderRadius: radius.md },
    Steps: {
      colorPrimary: color.brand,
      titleLineHeight: 32,
    },
    Pagination: { itemSize: 30 },
    Segmented: {
      itemSelectedBg: color.cardBg,
      itemHoverBg: 'transparent',
      trackBg: color.fill,
    },
  },
};

export const { defaultAlgorithm } = theme;
