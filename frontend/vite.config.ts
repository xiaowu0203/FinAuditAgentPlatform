import { fileURLToPath, URL } from 'node:url'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Element Plus 按需引入 → 自动推导 dev 依赖预构建清单（P3.8 / R0-11）
 * 只影响 dev 的依赖预构建，**不改变打包产物**。
 *
 * 【要解决的问题】
 * 组件由 unplugin-vue-components **按需**引入，其样式是「虚拟模块」，Vite 启动期的依赖扫描器
 * 扫不到；而路由又全是动态 import()，这些依赖只能在**首次访问某页时**才被发现。dev 控制台实测：
 *   [vite] ✨ new dependencies optimized: element-plus/es/components/timeline/style/css, ...
 *   [vite] ✨ optimized dependencies changed. reloading      ← 浏览器整页刷新
 * 表现为「头几次点菜单整页刷新抖动」。
 *
 * 注意：`optimizeDeps.include` 里写 `element-plus/es/components/*<slash>style/css` 这类**通配无效**
 * （实测 Vite 6.4.3 不按 element-plus 的 exports 展开），必须给出具体 specifier；而手写清单会漏
 * （本文件前一版就漏了 `button`，导致该页仍抖）。故改为**自动推导**：
 *
 *   1. 扫描 src 下 .vue 模板里用到的 `<el-xxx>` 标签 → 组件目录名
 *   2. 扫描 `import { ElMessage } from 'element-plus'` 函数式组件（无标签，扫不到）
 *   3. 映射为 `element-plus/es/components/<dir>/style/css`，以**文件系统存在性校验**兜底
 *
 * 新增任何 element-plus 组件**自动生效、零维护**；漏配只会在该页首次访问时多抖一下。
 * 另：`base`（基础样式）与 `loading`（v-loading 指令，无标签）不在模板里，需显式补充。
 */

/** 不会被模板/导入扫描发现、但必须预构建的样式（Element Plus 基础样式与指令样式） */
const NON_TAG_STYLES = [
  'element-plus/es/components/base/style/css',
  'element-plus/es/components/loading/style/css',
]

/** 与 element-plus 无关的根依赖（组件扫描覆盖不到全部，这里显式列出） */
const EXTRA_INCLUDE = [
  'vue',
  'vue-router',
  'pinia',
  'axios',
  'marked',
  'dompurify',
  '@element-plus/icons-vue',
  'element-plus',
  'element-plus/es',
  'element-plus/es/locale/lang/zh-cn',
]

/** camelCase -> kebab-case（ElInputNumber -> el-input-number） */
function toKebab(compName: string): string {
  return compName
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
    .toLowerCase()
}

function walkFiles(dir: string, filter: (f: string) => boolean, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'dist') continue
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      walkFiles(full, filter, out)
    } else if (filter(entry)) {
      out.push(full)
    }
  }
  return out
}

/** 收集源码里用到的 element-plus 组件目录名（标签 + 函数式导入） */
function collectUsedComponents(srcDir: string): Set<string> {
  const used = new Set<string>()
  for (const file of walkFiles(srcDir, (f) => f.endsWith('.vue'))) {
    const code = readFileSync(file, 'utf8')
    // 1) 模板标签：<el-button ...> / <el-table-column>
    for (const m of code.matchAll(/<\s*(el-[a-z0-9-]+)/g)) {
      used.add(m[1].replace(/^el-/, ''))
    }
    // 2) 函数式组件：import { ElMessage, ElMessageBox } from 'element-plus'
    for (const m of code.matchAll(/import\s*\{([^}]+)\}\s*from\s*['"]element-plus['"]/g)) {
      for (const raw of m[1].split(',')) {
        const name = raw.trim().split(/\s+as\s+/)[0].trim()
        if (/^El[A-Z]/.test(name)) used.add(toKebab(name).replace(/^el-/, ''))
      }
    }
  }
  return used
}

/** 推导 element-plus 样式 specifier（带目录存在性校验，避免生成无效条目） */
function buildStyleInclude(srcDir: string, elementPlusDir: string): string[] {
  const componentsDir = join(elementPlusDir, 'es', 'components')
  if (!existsSync(componentsDir)) {
    return [...NON_TAG_STYLES]
  }
  const available = new Set(readdirSync(componentsDir))
  const out = new Set<string>(NON_TAG_STYLES)

  for (const name of collectUsedComponents(srcDir)) {
    if (!name) continue
    // 目录名候选：完整 kebab（table-column）优先；回退末段（兼容 el- 前缀写法）
    const words = name.split('-').filter(Boolean)
    const candidates = words.length > 1 ? [name, words[words.length - 1]] : [name]
    for (const cand of candidates) {
      if (!available.has(cand)) continue
      if (existsSync(join(componentsDir, cand, 'style', 'css.mjs'))) {
        out.add(`element-plus/es/components/${cand}/style/css`)
      }
      break
    }
  }
  return [...out].sort()
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // .env 中的 VITE_GATEWAY_ORIGIN 决定开发代理目标（原硬编码 localhost:9080 已出配置）
  const env = loadEnv(mode, process.cwd(), '')
  const srcDir = fileURLToPath(new URL('./src', import.meta.url))
  const elementPlusDir = fileURLToPath(new URL('./node_modules/element-plus', import.meta.url))

  const styleInclude = buildStyleInclude(srcDir, elementPlusDir)
  const optimizeInclude = [...new Set([...EXTRA_INCLUDE, ...styleInclude])]

  return {
    plugins: [
      {
        // 打印推导结果，便于排查「新加的组件没被预构建」
        name: 'finaudit:log-optimize-deps',
        configResolved(config) {
          config.logger.info(
            `\n  \x1b[36m[finaudit]\x1b[0m dev 预构建依赖自动推导：共 \x1b[32m${optimizeInclude.length}\x1b[0m 个，` +
              `其中 element-plus 组件样式 \x1b[32m${styleInclude.length}\x1b[0m 个\n`,
          )
        },
      },
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
    /**
     * 注入 Vue 编译期特性开关，消除控制台告警并改善 tree-shaking：
     * `Feature flags __VUE_OPTIONS_API__, __VUE_PROD_DEVTOOLS__, __VUE_PROD_HYDRATION_MISMATCH_DETAILS__
     * are not explicitly defined.`
     * 本项目全部使用组合式 API，故 OPTIONS_API 关掉可获得更小的产物。
     */
    define: {
      __VUE_OPTIONS_API__: 'false',
      __VUE_PROD_DEVTOOLS__: 'false',
      __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
    },
    /** 根因与推导规则见文件顶部注释（R0-11） */
    optimizeDeps: {
      include: optimizeInclude,
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
