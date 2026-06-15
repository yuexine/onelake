import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发代理（对应《技术初始化文档》§5.4）
// /api  → APISIX 网关 :9080；/auth → Keycloak :8081
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9080', changeOrigin: true },
      '/auth': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
});
