import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080';
const authProxyTarget = process.env.VITE_AUTH_PROXY_TARGET || 'http://localhost:8081';

// 开发代理：本地默认直连 Spring Boot 控制面；需要走网关时设置 VITE_API_PROXY_TARGET=http://localhost:9080。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiProxyTarget, changeOrigin: true },
      '/auth': { target: authProxyTarget, changeOrigin: true },
    },
  },
});
