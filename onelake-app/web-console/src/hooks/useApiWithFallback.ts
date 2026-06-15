/**
 * useApiWithFallback — 尝试调用真实 API，失败时回退到 mock 数据。
 *
 * 用法：
 *   const { data, loading } = useApiWithFallback(
 *     () => IntegrationAPI.listCdcTasks(),
 *     mockCdcTasks,
 *   );
 */
import { useState, useEffect, useCallback } from 'react';

export function useApiWithFallback<T>(
  fetcher: () => Promise<T[]>,
  fallback: T[],
  deps: unknown[] = [],
): { data: T[]; loading: boolean; refresh: () => void } {
  const [data, setData] = useState<T[]>(fallback);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    fetcher()
      .then((d) => {
        if (d && Array.isArray(d) && d.length > 0) setData(d);
      })
      .catch(() => { /* keep fallback */ })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => { load(); }, [load]);

  return { data, loading, refresh: load };
}

/** 单对象版本（如 healthSummary 返回的 map）。 */
export function useApiObject<T>(
  fetcher: () => Promise<T>,
  fallback: T,
  deps: unknown[] = [],
): { data: T; loading: boolean } {
  const [data, setData] = useState<T>(fallback);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetcher()
      .then((d) => { if (d) setData(d); })
      .catch(() => {})
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, loading };
}
