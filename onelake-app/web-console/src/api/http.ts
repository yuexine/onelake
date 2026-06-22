import axios, { type AxiosResponse } from 'axios';
import { clearAuth, getValidAccessToken, startLogin } from '../auth/oidc';

/**
 * 业务错误：携带后端 ApiResponse.code，便于前端按 code 渲染提示。
 */
export class BizError extends Error {
  code?: number;
  constructor(message: string, code?: number) {
    super(message);
    this.name = 'BizError';
    this.code = code;
  }
}

/**
 * 全局 axios 客户端：附带 JWT、错误处理、统一 ApiResponse 解包。
 * dev 环境通过 vite proxy 走 APISIX :9080；生产由 nginx 转发。
 */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
});

http.interceptors.request.use(async (config) => {
  const token = await getValidAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  const traceId = Math.random().toString(36).slice(2, 12);
  config.headers['X-Trace-Id'] = traceId;
  return config;
});

http.interceptors.response.use(
  (response) => {
    // Blob 响应：保留完整 AxiosResponse 让调用方能读取 headers（如 X-Onelake-Export-Truncated）
    if (response.config?.responseType === 'blob') {
      return response;
    }
    const body = response.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data;
      return Promise.reject(new BizError(body.message || `code=${body.code}`, body.code));
    }
    return body;
  },
  async (error) => {
    if (error.response?.status === 401) {
      clearAuth();
      void startLogin(`${window.location.pathname}${window.location.search}${window.location.hash}`);
    }

    const body = error.response?.data;
    const httpStatus = error.response?.status;

    // Blob 错误体（responseType: 'blob' 请求失败时 data 是 Blob）
    // 需异步读取文本再尝试解析 JSON
    if (typeof Blob !== 'undefined' && body instanceof Blob) {
      try {
        const text = await body.text();
        try {
          const parsed = JSON.parse(text);
          if (parsed && typeof parsed === 'object') {
            const msg = typeof parsed.message === 'string' && parsed.message.trim() ? parsed.message : `导出失败 (HTTP ${httpStatus ?? '?'})`;
            return Promise.reject(new BizError(msg, typeof parsed.code === 'number' ? parsed.code : undefined));
          }
        } catch {
          if (text.trim()) {
            return Promise.reject(new BizError(text.slice(0, 500)));
          }
        }
      } catch {
        // 读取失败则继续走通用错误
      }
      return Promise.reject(new BizError(`请求失败 (HTTP ${httpStatus ?? '?'})`));
    }

    if (body && typeof body === 'object' && 'code' in body) {
      const msg = typeof body.message === 'string' && body.message.trim() ? body.message : `code=${body.code}`;
      return Promise.reject(new BizError(msg, body.code));
    }

    if (body && typeof body === 'object' && 'message' in body) {
      const message = (body as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        return Promise.reject(new BizError(message));
      }
    }

    return Promise.reject(error);
  },
);

export type { AxiosResponse };
