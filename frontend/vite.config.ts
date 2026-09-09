import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // .env 中的 VITE_GATEWAY_ORIGIN 决定开发代理目标（原硬编码 localhost:9080 已出配置）
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      // Element Plus 按需引入：组件与样式随模板/调用点自动装配，消除全量 1.2MB chunk
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver()],
        dts: 'src/auto-imports.d.ts',
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: 'src/components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: true,
      port: 5173,
      proxy: {
        // 开发期将 /api 转发到网关（网关不剥前缀，下游服务映射 /api/v1/**）
        '/api': {
          target: env.VITE_GATEWAY_ORIGIN || 'http://localhost:9080',
          changeOrigin: true,
        },
      },
    },
  }
})
