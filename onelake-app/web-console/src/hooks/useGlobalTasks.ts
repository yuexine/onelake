import { useCallback, useEffect } from 'react';
import { TaskAPI } from '../api';
import { useAppStore } from '../stores/app';

interface Options {
  expanded: boolean;
}

export function useGlobalTasks({ expanded }: Options) {
  const setTasks = useAppStore((s) => s.setTasks);
  const setTaskLoadError = useAppStore((s) => s.setTaskLoadError);

  const refresh = useCallback(async () => {
    try {
      const tasks = await TaskAPI.listRunning({ includeRecent: true, limit: 24 });
      setTasks(tasks);
      setTaskLoadError(undefined);
    } catch (e) {
      setTaskLoadError(e instanceof Error ? e.message : '全局任务刷新失败');
    }
  }, [setTaskLoadError, setTasks]);

  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;

    const delay = () => {
      if (document.hidden) return 30_000;
      return expanded ? 2_000 : 5_000;
    };

    const schedule = () => {
      if (stopped) return;
      timer = window.setTimeout(async () => {
        await refresh();
        schedule();
      }, delay());
    };

    const resetSchedule = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      schedule();
    };

    refresh();
    schedule();
    document.addEventListener('visibilitychange', resetSchedule);

    return () => {
      stopped = true;
      if (timer !== undefined) window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', resetSchedule);
    };
  }, [expanded, refresh]);

  return { refresh };
}
