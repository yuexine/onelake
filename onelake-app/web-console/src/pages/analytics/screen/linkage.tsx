/**
 * 大屏联动上下文（钻取联动的运行时核心）。
 *
 * 设计：
 * - varValues: Record<key, value> —— 当前各 globalVar 的实际值
 * - setVar(key, value): 触发 filter 类事件时调用，所有订阅该 var 的组件自动重渲染
 * - resetVar(key): 清空（回到 undefined）
 *
 * 触发链路：
 *   用户点击组件 A → onChartClick 回调查 A.events[type=filter] → setVar(targetVar, value)
 *   → 所有 widget.data.filters[].fromVar === targetVar 的组件读到新值 → 重新 queryDataset
 *
 * 全局筛选器条同理：用户在条上选 region='华东' → setVar('region', '华东')
 */
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { GlobalVar } from './types';

export interface VarValues {
  [key: string]: unknown;
}

interface LinkageContextValue {
  varValues: VarValues;
  setVar: (key: string, value: unknown) => void;
  resetVar: (key: string) => void;
  /** 监听某个 var 的值（无值时返回 undefined） */
  getVar: (key: string) => unknown;
}

const LinkageContext = createContext<LinkageContextValue | null>(null);

export function LinkageProvider({ initialVars, children }: {
  initialVars?: GlobalVar[];
  children: ReactNode;
}) {
  const [varValues, setVarValues] = useState<VarValues>(() => {
    const init: VarValues = {};
    initialVars?.forEach((v) => {
      if (v.default !== undefined) init[v.key] = v.default;
    });
    return init;
  });

  const setVar = useCallback((key: string, value: unknown) => {
    setVarValues((prev) => ({ ...prev, [key]: value }));
  }, []);

  const resetVar = useCallback((key: string) => {
    setVarValues((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }, []);

  const getVar = useCallback((key: string) => varValues[key], [varValues]);

  const value = useMemo<LinkageContextValue>(() => ({
    varValues, setVar, resetVar, getVar,
  }), [varValues, setVar, resetVar, getVar]);

  return <LinkageContext.Provider value={value}>{children}</LinkageContext.Provider>;
}

export function useLinkage(): LinkageContextValue {
  const ctx = useContext(LinkageContext);
  if (!ctx) {
    throw new Error('useLinkage must be used inside <LinkageProvider>');
  }
  return ctx;
}
