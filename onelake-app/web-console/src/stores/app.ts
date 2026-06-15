/**
 * 全局 store：当前租户、用户、运行任务条、通知、搜索弹窗。
 */
import { create } from 'zustand';
import { currentUser, runningTasks, notifications, tenants } from '../mock';
import type { Notification, Tenant } from '../types';
import type { RunningTask } from '../components';

interface AppState {
  user: typeof currentUser;
  setUser: (user: typeof currentUser) => void;
  tenant: Tenant;
  tenants: Tenant[];
  switchTenant: (id: string) => void;
  tasks: RunningTask[];
  removeTask: (id: string) => void;
  notifications: Notification[];
  markAllRead: () => void;
  markRead: (id: string) => void;
  searchOpen: boolean;
  setSearchOpen: (open: boolean) => void;
  notifyOpen: boolean;
  setNotifyOpen: (open: boolean) => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  user: currentUser,
  setUser: (user) => set({ user }),
  tenant: tenants[0],
  tenants,
  switchTenant: (id) => {
    const t = get().tenants.find((x) => x.id === id);
    if (t) set({ tenant: t });
  },

  tasks: runningTasks,
  removeTask: (id) => set((s) => ({ tasks: s.tasks.filter((t) => t.id !== id) })),

  notifications,
  markAllRead: () => set((s) => ({ notifications: s.notifications.map((n) => ({ ...n, isRead: true })) })),
  markRead: (id) => set((s) => ({
    notifications: s.notifications.map((n) => (n.id === id ? { ...n, isRead: true } : n)),
  })),

  searchOpen: false,
  setSearchOpen: (open) => set({ searchOpen: open }),
  notifyOpen: false,
  setNotifyOpen: (open) => set({ notifyOpen: open }),
}));
