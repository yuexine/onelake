import { useCallback, useEffect } from 'react';
import { NotificationAPI } from '../api';
import { useAppStore } from '../stores/app';

export function useNotifications() {
  const setNotifications = useAppStore((s) => s.setNotifications);

  const refresh = useCallback(async () => {
    const notifications = await NotificationAPI.list({ limit: 50 });
    setNotifications(notifications);
  }, [setNotifications]);

  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;

    const delay = () => document.hidden ? 60_000 : 15_000;

    const schedule = () => {
      if (stopped) return;
      timer = window.setTimeout(async () => {
        await refresh().catch(() => undefined);
        schedule();
      }, delay());
    };

    const resetSchedule = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      schedule();
    };

    refresh().catch(() => undefined);
    schedule();
    document.addEventListener('visibilitychange', resetSchedule);

    return () => {
      stopped = true;
      if (timer !== undefined) window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', resetSchedule);
    };
  }, [refresh]);

  return { refresh };
}
