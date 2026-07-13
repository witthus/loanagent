<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/lib/api'

type Alert = {
  alert_id: string
  kind: string
  message: string
  ref_id: string | null
  created_at: string
}

const rows = ref<Alert[]>([])
const error = ref('')

onMounted(async () => {
  try {
    rows.value = await api<Alert[]>('/api/v1/alerts')
  } catch {
    error.value = '加载告警失败'
  }
})
</script>

<template>
  <section>
    <h1>告警</h1>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-else-if="rows.length">
      <thead>
        <tr>
          <th>类型</th>
          <th>说明</th>
          <th>关联</th>
          <th>时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.alert_id">
          <td>{{ row.kind }}</td>
          <td>{{ row.message }}</td>
          <td>{{ row.ref_id || '—' }}</td>
          <td>{{ row.created_at }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无告警。</p>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.error { color: #b42318; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
