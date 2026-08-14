import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      // 开发期将 /api 转发到网关 9080（网关不剥前缀，下游服务映射 /api/v1/**）
      '/api': {
        target: 'http://localhost:9080',
        changeOrigin: true,
      },
    },
  },
})
