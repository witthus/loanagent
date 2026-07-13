<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { zhCN } from '@/i18n/zh-CN'

const route = useRoute()
const title = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : '功能'))
</script>

<template>
  <section>
    <h1>{{ title }}</h1>
    <p>{{ zhCN.comingSoon }}</p>
  </section>
</template>

<style scoped>
h1 {
  margin: 0 0 12px;
}
p {
  color: #5c6770;
}
</style>
