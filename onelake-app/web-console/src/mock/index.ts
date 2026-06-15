/**
 * Mock 数据统一出口。
 * 实际接入后端时把这里的 import 改成 axios 请求即可，业务代码无需改动。
 */
export * from './l1-integration';
export * from './l2-lakehouse';
export * from './l3-catalog';
export * from './l3-quality';
export * from './l5-daas';
export * from './common';

// 我的全局用户/角色
export const currentUser = {
  id: 'u-1',
  name: '张三',
  username: 'zhangsan',
  roles: ['DE', 'ADMIN'],
  tenant: { id: 't-1', name: '交易事业部' },
};

// 全局任务条
export const runningTasks = [
  { id: 'rt-1', category: 'COLLECT' as const, name: '采集任务 user_cdc', progress: 62, status: 'running' as const, detail: '62%' },
  { id: 'rt-2', category: 'COMPACTION' as const, name: 'dwd_order_df 小文件合并', progress: 80, status: 'running' as const, detail: '80%' },
  { id: 'rt-3', category: 'QUALITY' as const, name: 'dwd_order_df 质量稽核', progress: 35, status: 'running' as const, detail: '35%' },
];
