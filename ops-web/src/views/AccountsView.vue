<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import {
  accountHealth,
  accountStatusLabel,
  healthLabel,
  type FleetDevice,
} from '@/lib/fleet'
import { deviceDisplayName, networkLabel } from '@/lib/deviceFleet'
import { accountDisplayName, formatDateTime, roleLabel } from '@/lib/labels'
import { platformLabel, type Platform } from '@/platform'

type Device = FleetDevice & {
  device_id: string
}

type Account = {
  account_id: string
  role: string
  status: string
  device_id?: string | null
  display_name?: string | null
  platform: Platform
  daily_publish_quota?: number
}

type Row = {
  account: Account
  device: Device | null
}

const rows = ref<Row[]>([])
const allDevices = ref<Device[]>([])
const allAccounts = ref<Account[]>([])
const devicesById = ref<Record<string, Device>>({})
const error = ref('')
const saving = ref<string | null>(null)
const message = ref('')
let refreshTimer: number | null = null
let loadGeneration = 0

const unboundDeviceCount = computed(() => {
  const used = new Set(
    allAccounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return allDevices.value.filter((d) => !used.has(d.device_id)).length
})

const summary = computed(() => {
  let ready = 0
  let offline = 0
  let paused = 0
  for (const account of allAccounts.value) {
    const health = accountHealth(account, devicesById.value)
    if (health === 'ready') ready += 1
    else if (health === 'paused') paused += 1
    else if (health === 'offline' || health === 'a11y_down' || health === 'unbound') offline += 1
  }
  return {
    total: allAccounts.value.length,
    ready,
    offline,
    paused,
    pending: unboundDeviceCount.value,
  }
})

function rowHealth(account: Account): string {
  return healthLabel(accountHealth(account, devicesById.value))
}

async function load() {
  const generation = ++loadGeneration
  error.value = ''
  try {
    const [devices, accounts] = await Promise.all([
      api<Device[]>('/api/v1/devices'),
      api<Account[]>('/api/v1/accounts?platform=xhs'),
    ])
    if (generation !== loadGeneration) return
    allDevices.value = devices
    allAccounts.value = accounts
    const map: Record<string, Device> = {}
    for (const device of devices) map[device.device_id] = device
    devicesById.value = map
    rows.value = accounts.map((account) => ({
      account,
      device: account.device_id ? map[account.device_id] || null : null,
    }))
  } catch {
    if (generation !== loadGeneration) return
    error.value = '加载账号失败'
  }
}

async function renameAccount(account: Account) {
  const next = window.prompt('账号显示名称', account.display_name || '')
  if (next == null) return
  const trimmed = next.trim()
  if (!trimmed || trimmed === (account.display_name || '')) return
  saving.value = `account:${account.account_id}`
  message.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ display_name: trimmed }),
    })
    message.value = '账号名称已更新'
    await load()
  } catch (err) {
    error.value = formatApiError(err, '更新账号名称失败')
  } finally {
    saving.value = null
  }
}

async function unbindAccount(account: Account) {
  if (!window.confirm(`解除「${accountDisplayName(account)}」与设备的绑定？`)) return
  saving.value = `unbind:${account.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ device_id: null }),
    })
    message.value = '已解除绑定'
    await load()
  } catch (err) {
    error.value = formatApiError(err, '解除绑定失败')
  } finally {
    saving.value = null
  }
}

async function pauseAccount(account: Account) {
  if (!window.confirm(`暂停「${accountDisplayName(account)}」？暂停后不会再下发任务。`)) return
  saving.value = `pause:${account.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}/pause`, {
      method: 'POST',
    })
    message.value = '账号已暂停'
    await load()
  } catch (err) {
    error.value = formatApiError(err, '暂停失败')
  } finally {
    saving.value = null
  }
}

async function resumeAccount(account: Account) {
  saving.value = `resume:${account.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}/resume`, {
      method: 'POST',
    })
    message.value = '账号已恢复'
    await load()
  } catch (err) {
    error.value = formatApiError(err, '恢复失败')
  } finally {
    saving.value = null
  }
}

onMounted(() => {
  load()
  refreshTimer = window.setInterval(() => {
    load()
  }, 15_000)
})

onUnmounted(() => {
  if (refreshTimer != null) window.clearInterval(refreshTimer)
})
</script>

<template>
  <section>
    <h1>账号</h1>
    <p class="hint">
      管理账号角色、暂停与解绑。装包、待绑定设备、改设备名请到
      <router-link to="/devices">设备</router-link>
      页。页面每 15 秒自动刷新。
    </p>
    <div class="summary">
      <span>账号 {{ summary.total }}</span>
      <span class="ok">可用 {{ summary.ready }}</span>
      <span class="warn">异常 {{ summary.offline }}</span>
      <span>暂停 {{ summary.paused }}</span>
      <router-link
        v-if="summary.pending"
        class="warn link"
        to="/devices"
      >
        待绑定设备 {{ summary.pending }} →
      </router-link>
    </div>
    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <table>
      <thead>
        <tr>
          <th>账号</th>
          <th>角色</th>
          <th>账号状态</th>
          <th>设备</th>
          <th>运行状态</th>
          <th>网络</th>
          <th>最近心跳</th>
          <th>Agent</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.account.account_id">
          <td>
            <div>{{ accountDisplayName(row.account) }}</div>
            <div class="muted">{{ platformLabel(row.account.platform || 'xhs') }}</div>
          </td>
          <td>{{ roleLabel(row.account.role) }}</td>
          <td>{{ accountStatusLabel(row.account.status) }}</td>
          <td>
            <template v-if="row.device">
              <div>{{ deviceDisplayName(row.device) }}</div>
              <div class="muted">{{ row.device.geo_label || row.device.device_id }}</div>
            </template>
            <router-link v-else class="warn link" to="/devices">去绑定设备</router-link>
          </td>
          <td>{{ rowHealth(row.account) }}</td>
          <td>{{ networkLabel(row.device) }}</td>
          <td>{{ formatDateTime(row.device?.last_seen_at) }}</td>
          <td>{{ row.device?.agent_version || '—' }}</td>
          <td class="actions">
            <button
              type="button"
              :disabled="saving === `account:${row.account.account_id}`"
              @click="renameAccount(row.account)"
            >
              改账号名
            </button>
            <button
              v-if="row.account.status === 'active'"
              type="button"
              :disabled="saving === `pause:${row.account.account_id}`"
              @click="pauseAccount(row.account)"
            >
              暂停
            </button>
            <button
              v-if="row.account.status === 'paused'"
              type="button"
              class="primary"
              :disabled="saving === `resume:${row.account.account_id}`"
              @click="resumeAccount(row.account)"
            >
              恢复
            </button>
            <button
              v-if="row.account.device_id"
              type="button"
              :disabled="saving === `unbind:${row.account.account_id}`"
              @click="unbindAccount(row.account)"
            >
              解绑
            </button>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="9" class="muted">暂无账号。请先在「设备」页创建并绑定。</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; vertical-align: top; }
th { background: #f3f4f6; }
.error { color: #b42318; }
.ok { color: #067647; }
.warn { color: #b54708; }
.muted { color: #5c6770; font-size: 0.85rem; margin-top: 4px; }
.hint { color: #5c6770; margin: 0 0 12px; }
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 14px;
  font-weight: 600;
  align-items: center;
}
.link { text-decoration: none; }
.link:hover { text-decoration: underline; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.actions button {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}
.actions button.primary {
  background: #2d6a4f;
  color: #fff;
  border-color: #2d6a4f;
}
.actions button:disabled { opacity: 0.6; cursor: default; }
</style>
