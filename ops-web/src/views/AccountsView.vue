<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { accountDisplayName, roleLabel } from '@/lib/labels'
import { humanizeError } from '@/lib/humanize'
import { platformLabel, type Platform } from '@/platform'

type Device = {
  device_id: string
  display_name?: string | null
  online?: boolean
  a11y_bound?: boolean | null
  wifi_connected?: boolean | null
  last_seen_at?: string | null
  agent_version?: string | null
}

type Account = {
  account_id: string
  role: string
  status: string
  device_id?: string | null
  display_name?: string | null
  platform: Platform
}

type Row = {
  account: Account | null
  device: Device | null
}

const rows = ref<Row[]>([])
const allDevices = ref<Device[]>([])
const allAccounts = ref<Account[]>([])
const error = ref('')
const saving = ref<string | null>(null)
const message = ref('')
const bindAccountId = ref('')
const bindDeviceId = ref('')

const unboundAccounts = computed(() =>
  allAccounts.value.filter((a) => !a.device_id),
)
const unboundDevices = computed(() => {
  const used = new Set(
    allAccounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return allDevices.value.filter((d) => !used.has(d.device_id))
})

function deviceStatus(device: Device | null): string {
  if (!device) return '未绑定设备'
  if (!device.online) return '手机好像不在线'
  if (device.a11y_bound === false) return humanizeError('A11Y_DOWN').title
  return '就绪'
}

function accountLabel(account: Account | null): string {
  if (!account) return '未绑定账号'
  return accountDisplayName(account)
}

function deviceLabel(device: Device | null): string {
  if (!device) return '—'
  return device.display_name?.trim() || '未命名设备'
}

async function load() {
  error.value = ''
  try {
    const [devices, accounts] = await Promise.all([
      api<Device[]>('/api/v1/devices'),
      api<Account[]>('/api/v1/accounts?platform=xhs'),
    ])
    allDevices.value = devices
    allAccounts.value = accounts
    const byDevice = new Map<string, Account>()
    for (const account of accounts) {
      if (account.device_id) byDevice.set(account.device_id, account)
    }
    const usedAccounts = new Set<string>()
    const merged: Row[] = devices.map((device) => {
      const account = byDevice.get(device.device_id) ?? null
      if (account) usedAccounts.add(account.account_id)
      return { account, device }
    })
    for (const account of accounts) {
      if (!usedAccounts.has(account.account_id)) {
        merged.push({ account, device: null })
      }
    }
    rows.value = merged
    if (!bindAccountId.value && unboundAccounts.value[0]) {
      bindAccountId.value = unboundAccounts.value[0].account_id
    }
    if (!bindDeviceId.value && unboundDevices.value[0]) {
      bindDeviceId.value = unboundDevices.value[0].device_id
    }
  } catch {
    error.value = '加载账号与设备失败'
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
  } catch {
    error.value = '更新账号名称失败'
  } finally {
    saving.value = null
  }
}

async function renameDevice(device: Device) {
  const next = window.prompt('设备显示名称', device.display_name || '')
  if (next == null) return
  const trimmed = next.trim()
  if (!trimmed || trimmed === (device.display_name || '')) return
  saving.value = `device:${device.device_id}`
  message.value = ''
  try {
    await api(`/api/v1/devices/${encodeURIComponent(device.device_id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ display_name: trimmed }),
    })
    message.value = '设备名称已更新'
    await load()
  } catch {
    error.value = '更新设备名称失败'
  } finally {
    saving.value = null
  }
}

async function bindAccountDevice() {
  if (!bindAccountId.value || !bindDeviceId.value) {
    error.value = '请选择账号和设备'
    return
  }
  saving.value = 'bind'
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(bindAccountId.value)}`, {
      method: 'PATCH',
      body: JSON.stringify({ device_id: bindDeviceId.value }),
    })
    message.value = '账号与设备已绑定'
    bindAccountId.value = ''
    bindDeviceId.value = ''
    await load()
  } catch {
    error.value = '绑定失败（设备可能已被其他账号占用）'
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
  } catch {
    error.value = '解除绑定失败'
  } finally {
    saving.value = null
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h1>账号与设备</h1>
    <p class="hint">一台手机绑定一个账号。可在这里改显示名称，或绑定/解绑设备。</p>
    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="unboundAccounts.length && unboundDevices.length" class="bind-panel">
      <h2>绑定账号与设备</h2>
      <div class="bind-row">
        <label>
          未绑定账号
          <select v-model="bindAccountId">
            <option v-for="a in unboundAccounts" :key="a.account_id" :value="a.account_id">
              {{ accountDisplayName(a) }}
            </option>
          </select>
        </label>
        <label>
          未绑定设备
          <select v-model="bindDeviceId">
            <option v-for="d in unboundDevices" :key="d.device_id" :value="d.device_id">
              {{ d.display_name?.trim() || d.device_id }}
              {{ d.online ? '（在线）' : '（离线）' }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="saving === 'bind'" @click="bindAccountDevice">
          {{ saving === 'bind' ? '绑定中…' : '绑定' }}
        </button>
      </div>
    </div>

    <table>
      <thead>
        <tr>
          <th>账号</th>
          <th>角色</th>
          <th>平台</th>
          <th>设备</th>
          <th>状态</th>
          <th>无障碍</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="row.account?.account_id || row.device?.device_id || index">
          <td>{{ accountLabel(row.account) }}</td>
          <td>{{ row.account ? roleLabel(row.account.role) : '—' }}</td>
          <td>{{ row.account ? platformLabel(row.account.platform || 'xhs') : '—' }}</td>
          <td>
            <div>{{ deviceLabel(row.device) }}</div>
          </td>
          <td>{{ deviceStatus(row.device) }}</td>
          <td>
            {{
              row.device?.a11y_bound === true
                ? '已就绪'
                : row.device?.a11y_bound === false
                  ? '未就绪'
                  : '—'
            }}
          </td>
          <td class="actions">
            <button
              v-if="row.account"
              type="button"
              :disabled="saving === `account:${row.account.account_id}`"
              @click="renameAccount(row.account)"
            >
              改账号名
            </button>
            <button
              v-if="row.device"
              type="button"
              :disabled="saving === `device:${row.device.device_id}`"
              @click="renameDevice(row.device)"
            >
              改设备名
            </button>
            <button
              v-if="row.account?.device_id"
              type="button"
              :disabled="saving === `unbind:${row.account.account_id}`"
              @click="unbindAccount(row.account)"
            >
              解绑
            </button>
          </td>
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
.hint { color: #5c6770; margin: 0 0 12px; }
.bind-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
  max-width: 720px;
}
.bind-panel h2 { margin: 0 0 12px; font-size: 1rem; }
.bind-row { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
.bind-row label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; min-width: 180px; }
.bind-row select, .bind-row button { padding: 8px 12px; border-radius: 8px; font: inherit; }
.bind-row button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.actions button {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}
.actions button:disabled { opacity: 0.6; cursor: default; }
</style>
