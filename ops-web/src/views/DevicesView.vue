<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'

type Device = {
  device_id: string
  online?: boolean
  a11y_bound?: boolean | null
  wifi_connected?: boolean | null
  last_seen_at?: string | null
  agent_version?: string | null
}

const rows = ref<Device[]>([])
const error = ref('')

function statusText(row: Device): string {
  if (!row.online) return humanizeError(null).title.replace('出了点问题', '手机好像不在线')
  if (row.a11y_bound === false) return humanizeError('A11Y_DOWN').title
  return '就绪'
}

onMounted(async () => {
  try {
    rows.value = await api<Device[]>('/api/v1/devices')
  } catch {
    error.value = '加载设备失败'
  }
})
</script>

<template>
  <section>
    <h1>设备</h1>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-else>
      <thead>
        <tr>
          <th>设备</th>
          <th>状态</th>
          <th>无障碍</th>
          <th>最近心跳</th>
          <th>Agent</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.device_id">
          <td>{{ row.device_id }}</td>
          <td>{{ statusText(row) }}</td>
          <td>{{ row.a11y_bound === true ? '已就绪' : row.a11y_bound === false ? '未就绪' : '未知' }}</td>
          <td>{{ row.last_seen_at || '—' }}</td>
          <td>{{ row.agent_version || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.error { color: #b42318; }
</style>
