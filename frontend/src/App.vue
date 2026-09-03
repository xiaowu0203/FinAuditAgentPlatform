<script setup lang="ts">
// 根组件：全局 locale + 路由出口（登录页与主布局由路由决定）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
