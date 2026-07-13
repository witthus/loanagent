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

type Account = {
  account_id: string
  role: string
  device_id?: string | null
  display_name?: string | null
}

type DeviceRow = Device & {
  account_label?: string | null
  account_role?: string | null
}

const rows = ref<DeviceRow[]>([])
const error = ref('')

function statusText(row: Device): string {
  if (!row.online) return '手机好像不在线'
  if (row.a11y_bound === false) return humanizeError('A11Y_DOWN').title
  return '就绪'
}

onMounted(async () => {
  try {
    const [devices, accounts] = await Promise.all([
      api<Device[]>('/api/v1/devices'),
      api<Account[]>('/api/v1/accounts'),
    ])
    const byDevice = new Map<string, Account>()
    for (const account of accounts) {
      if (account.device_id) byDevice.set(account.device_id, account)
    }
    rows.value = devices.map((device) => {
      const account = byDevice.get(device.device_id)
      return {
        ...device,
        account_role: account?.role ?? null,
        account_label: account ? account.display_name || account.account_id : null,
      }
    })
  } catch {
    error.value = '加载设备失败'
  }
})
</script>

<template>
  <section>
    <h1>设备</h1>
    <p class="hint">一台设备当前绑定一个账号（一对一）。</p>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-else>
      <thead>
        <tr>
          <th>设备</th>
          <th>绑定账号</th>
          <th>角色</th>
          <th>状态</th>
          <th>无障碍</th>
          <th>最近心跳</th>
          <th>Agent</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.device_id">
          <td>{{ row.device_id }}</td>
          <td>{{ row.account_label || '未绑定' }}</td>
          <td>{{ row.account_role || '—' }}</td>
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
.hint { color: #5c6770; margin: 0 0 12px; }
</style>
