import { BizError } from '../../../api/http';
import type { PipelineParam } from '../../../types';

export interface ParamUiError {
  message: string;
  noPermission: boolean;
}

export function toParamUiError(error: unknown): ParamUiError {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  const rawMessage = error instanceof Error ? error.message.trim() : '';
  if (status === 401 || code === 40100) {
    return { message: '登录状态已失效，请重新登录。', noPermission: true };
  }
  if (status === 403 || code === 40300) {
    return { message: '当前账号没有参数管理权限。', noPermission: true };
  }
  return {
    message: rawMessage || '参数服务请求失败，请稍后重试。',
    noPermission,
  };
}

export function validateParamDrafts(params: PipelineParam[]) {
  const reservedKeys = new Set([
    'run_id', 'logical_date', 'bizdate', 'data_interval_start', 'data_interval_end',
    'timezone', 'run_mode', 'backfill_id', 'trigger_type', 'cyctime',
  ]);
  const identities = new Set<string>();
  for (const param of params) {
    const key = param.paramKey.trim();
    if (!/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(key)) {
      throw new Error(`参数 key “${key || '(空)'}” 非法：需以字母或下划线开头，仅允许字母、数字、下划线、点和连字符。`);
    }
    if (reservedKeys.has(key) || key.startsWith('bizdate') || key.startsWith('cyctime') || key.startsWith('upstream.')) {
      throw new Error(`参数 key “${key}” 属于运行时保留名称，不能自定义。`);
    }
    const identity = `${param.scope}\u0000${param.taskKey ?? ''}\u0000${key}`;
    if (identities.has(identity)) {
      throw new Error(`同一作用域内参数 key 重复：${key}`);
    }
    identities.add(identity);
    const value = param.paramValue?.trim() ?? '';
    if (param.valueType === 'NUMBER' && (value === '' || Number.isNaN(Number(value)))) {
      throw new Error(`NUMBER 参数 “${key}” 需要合法数字。`);
    }
    if (param.valueType === 'BOOL' && value !== 'true' && value !== 'false') {
      throw new Error(`BOOL 参数 “${key}” 仅支持 true 或 false。`);
    }
    if (param.valueType === 'EXPR' && value === '') {
      throw new Error(`EXPR 参数 “${key}” 不能为空。`);
    }
  }
}
