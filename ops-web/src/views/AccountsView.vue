<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { platformLabel, type Platform } from '@/platform'

type Account = {
  account_id: string
  role: string
  status: string
  device_id?: string | null
  display_name?: string | null
  platform: Platform
}

const ROLE_LABEL: Record<string, string> = {
  PUBLISHER_MAIN: '主号',
  PUBLISHER_MATRIX: '矩阵号',
  ENGAGER: '互动号',
}

const rows = ref<Account[]>([])
const error = ref('')

onMounted(async () => {
  try {
    rows.value = await api<Account[]>('/api/v1/accounts?platform=xhs')
  } catch {
    error.value = '加载账号失败'
  }
})
</script>

<template>
  <section>
    <h1>账号</h1>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-else>
      <thead>
        <tr>
          <th>名称</th>
          <th>角色</th>
          <th>平台</th>
          <th>状态</th>
          <th>绑定设备</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.account_id">
          <td>{{ row.display_name || row.account_id }}</td>
          <td>{{ ROLE_LABEL[row.role] || row.role }}</td>
          <td>{{ platformLabel(row.platform || 'xhs') }}</td>
          <td>{{ row.status }}</td>
          <td>{{ row.device_id || '未绑定' }}</td>
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
