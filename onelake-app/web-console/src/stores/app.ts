/**
 * 全局 store：当前租户、用户、运行任务条、通知、搜索弹窗。
 */
import { create } from 'zustand';
import { currentUser, tenants } from '../mock';
import type { Notification, RunningTask, Tenant } from '../types';

interface AppState {
  user: typeof currentUser;
  setUser: (user: typeof currentUser) => void;
  tenant: Tenant;
  tenants: Tenant[];
  switchTenant: (id: string) => void;
  tasks: RunningTask[];
  taskLoadError?: string;
  setTasks: (tasks: RunningTask[]) => void;
  setTaskLoadError: (message?: string) => void;
  removeTask: (id: string) => void;
  notifications: Notification[];
  setNotifications: (notifications: Notification[]) => void;
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

  tasks: [],
  taskLoadError: undefined,
  setTasks: (tasks) => set({ tasks }),
  setTaskLoadError: (message) => set({ taskLoadError: message }),
  removeTask: (id) => set((s) => ({ tasks: s.tasks.filter((t) => t.id !== id) })),

  notifications: [],
  setNotifications: (notifications) => set({ notifications }),
  markAllRead: () => set((s) => ({ notifications: s.notifications.map((n) => ({ ...n, isRead: true })) })),
  markRead: (id) => set((s) => ({
    notifications: s.notifications.map((n) => (n.id === id ? { ...n, isRead: true } : n)),
  })),

  searchOpen: false,
  setSearchOpen: (open) => set({ searchOpen: open }),
  notifyOpen: false,
  setNotifyOpen: (open) => set({ notifyOpen: open }),
}));
