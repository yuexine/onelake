/**
 * useAsyncAction — 统一异步操作的 loading / 错误反馈
 *
 * 用法：
 *   const { run, loadingKey } = useAsyncAction();
 *   <Button loading={loadingKey === 'test'} onClick={() => run('test', async () => { ... }, { successMsg: '已测连' })} />
 *
 * - 同一时刻只允许一个 loadingKey（避免并发竞争）
 * - 失败默认 message.error('操作失败，请重试')，可通过 opts.errorMsg 覆盖
 * - 成功可选 successMsg；duration 默认 1.5s，重要操作请显式 duration: 5
 * - 抛出的异常会被吞掉；如需链式请用返回值
 */
import { useCallback, useRef, useState } from 'react';
import { message } from 'antd';

interface RunOptions {
  successMsg?: string;
  errorMsg?: string | ((e: unknown) => string);
  duration?: number;       // 秒；默认重要操作 3s，普通 1.5s
  rethrow?: boolean;       // true 时失败仍 throw，便于上层额外处理
}

export function useAsyncAction() {
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const reqIdRef = useRef(0);

  const run = useCallback(async <T,>(
    key: string,
    fn: () => Promise<T>,
    opts: RunOptions = {},
  ): Promise<T | undefined> => {
    setLoadingKey(key);
    const myId = ++reqIdRef.current;
    try {
      const result = await fn();
      if (opts.successMsg) {
        message.success({ content: opts.successMsg, duration: opts.duration ?? 1.5 });
      }
      return result;
    } catch (e) {
      const msg = typeof opts.errorMsg === 'function'
        ? opts.errorMsg(e)
        : (opts.errorMsg || '操作失败，请稍后重试');
      message.error({ content: msg, duration: opts.duration ?? 3 });
      if (opts.rethrow) throw e;
      return undefined;
    } finally {
      if (myId === reqIdRef.current) setLoadingKey(null);
    }
  }, []);

  const isLoading = useCallback((key: string) => loadingKey === key, [loadingKey]);

  return { loadingKey, isLoading, run };
}
