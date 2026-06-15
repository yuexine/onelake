import axios from 'axios';

/**
 * 全局 axios 客户端：附带 JWT、错误处理、统一 ApiResponse 解包。
 * dev 环境通过 vite proxy 走 APISIX :9080；生产由 nginx 转发。
 */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('onelake.access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  const traceId = Math.random().toString(36).slice(2, 12);
  config.headers['X-Trace-Id'] = traceId;
  return config;
});

http.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data;
      return Promise.reject(new Error(body.message || `code=${body.code}`));
    }
    return body;
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('onelake.access_token');
      window.location.href = '/auth/login';
    }
    return Promise.reject(error);
  },
);
