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
  network_policy?: string
  inbox_sync_enabled?: boolean
}

type Row = {
  account: Account
  device: Device | null
}

type EditDraft = {
  account_id: string
  display_name: string
  role: string
  daily_publish_quota: number
  network_policy: string
  inbox_sync_enabled: boolean
}

const ROLE_OPTIONS = [
  { value: 'PUBLISHER_MAIN', label: '主号' },
  { value: 'PUBLISHER_MATRIX', label: '矩阵号' },
  { value: 'ENGAGER', label: '互动号' },
] as const

const rows = ref<Row[]>([])
const allDevices = ref<Device[]>([])
const allAccounts = ref<Account[]>([])
const devicesById = ref<Record<string, Device>>({})
const error = ref('')
const saving = ref<string | null>(null)
const message = ref('')
const editing = ref<EditDraft | null>(null)
let refreshTimer: number | null = null
let loadGeneration = 0

const unboundDeviceCount = computed(() => {
  const used = new Set(
    allAccounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return allDevices.value.filter((d) => !used.has(d.device_id)).length
})

const unboundOnlineDevices = computed(() => {
  const used = new Set(
    allAccounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return allDevices.value.filter((d) => !used.has(d.device_id) && d.online !== false)
})

const rebindAccountId = ref('')
const rebindDeviceId = ref('')

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

function openEdit(account: Account) {
  editing.value = {
    account_id: account.account_id,
    display_name: account.display_name?.trim() || '',
    role: account.role,
    daily_publish_quota: account.daily_publish_quota ?? 0,
    network_policy: account.network_policy || 'cellular_only',
    inbox_sync_enabled: Boolean(account.inbox_sync_enabled),
  }
  message.value = ''
  error.value = ''
}

function closeEdit() {
  editing.value = null
}

async function saveEdit() {
  const draft = editing.value
  if (!draft) return
  const name = draft.display_name.trim()
  if (!name) {
    error.value = '请填写账号显示名称'
    return
  }
  saving.value = `edit:${draft.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(draft.account_id)}`, {
      method: 'PATCH',
      body: JSON.stringify({
        display_name: name,
        role: draft.role,
        daily_publish_quota: Number(draft.daily_publish_quota) || 0,
        network_policy: draft.network_policy,
        inbox_sync_enabled: draft.inbox_sync_enabled,
      }),
    })
    message.value = '账号已更新'
    editing.value = null
    await load()
  } catch (err) {
    error.value = formatApiError(err, '更新账号失败')
  } finally {
    saving.value = null
  }
}

async function deleteAccount(account: Account) {
  const name = accountDisplayName(account)
  const bound = account.device_id
    ? '\n将解除与设备的绑定，并删除该账号下的任务/私信/笔记等历史数据。'
    : '\n将删除该账号下的任务/私信/笔记等历史数据。'
  if (!window.confirm(`删除账号「${name}」？${bound}\n此操作不可恢复。`)) return
  saving.value = `delete:${account.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}`, {
      method: 'DELETE',
    })
    if (editing.value?.account_id === account.account_id) editing.value = null
    message.value = '账号已删除'
    await load()
  } catch (err) {
    error.value = formatApiError(err, '删除账号失败')
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

function startRebind(account: Account) {
  rebindAccountId.value = account.account_id
  rebindDeviceId.value = unboundOnlineDevices.value[0]?.device_id || ''
  message.value = ''
  error.value = ''
}

async function confirmRebind() {
  if (!rebindAccountId.value || !rebindDeviceId.value) {
    error.value = '请选择要换绑的在线未绑定设备'
    return
  }
  const account = allAccounts.value.find((a) => a.account_id === rebindAccountId.value)
  const label = account ? accountDisplayName(account) : rebindAccountId.value
  if (
    !window.confirm(
      `将「${label}」换绑到 ${rebindDeviceId.value}？\n旧设备会变为未绑定，离线后可在设备页删除。`,
    )
  ) {
    return
  }
  saving.value = `rebind:${rebindAccountId.value}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(rebindAccountId.value)}/rebind`, {
      method: 'POST',
      body: JSON.stringify({ device_id: rebindDeviceId.value }),
    })
    message.value = '已换绑到新设备'
    rebindAccountId.value = ''
    rebindDeviceId.value = ''
    await load()
  } catch (err) {
    error.value = formatApiError(err, '换绑失败')
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
      管理账号名称、角色、暂停、解绑与删除。装包、待绑定设备、改设备名请到
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

    <div v-if="rebindAccountId" class="edit-panel">
      <h2>换绑设备</h2>
      <p class="muted">
        账号 <code>{{ rebindAccountId }}</code> → 选一台在线且未绑定的手机。旧设备解绑后若离线，可在设备页删除。
      </p>
      <div class="edit-grid">
        <label>
          新设备
          <select v-model="rebindDeviceId">
            <option disabled value="">请选择</option>
            <option v-for="d in unboundOnlineDevices" :key="d.device_id" :value="d.device_id">
              {{ deviceDisplayName(d) }} · {{ d.device_id }}
              {{ d.agent_version ? ` · ${d.agent_version}` : '' }}
            </option>
          </select>
        </label>
      </div>
      <div class="actions">
        <button
          type="button"
          class="primary"
          :disabled="saving === `rebind:${rebindAccountId}` || !rebindDeviceId"
          @click="confirmRebind"
        >
          {{ saving === `rebind:${rebindAccountId}` ? '换绑中…' : '确认换绑' }}
        </button>
        <button type="button" :disabled="!!saving" @click="rebindAccountId = ''">取消</button>
      </div>
    </div>

    <div v-if="editing" class="edit-panel">
      <h2>编辑账号</h2>
      <p class="muted">ID：{{ editing.account_id }}</p>
      <div class="edit-grid">
        <label>
          显示名称
          <input v-model="editing.display_name" type="text" maxlength="256" />
        </label>
        <label>
          角色
          <select v-model="editing.role">
            <option v-for="opt in ROLE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <label>
          每日发布配额
          <input v-model.number="editing.daily_publish_quota" type="number" min="0" step="1" />
        </label>
        <label>
          网络策略
          <select v-model="editing.network_policy">
            <option value="cellular_only">仅蜂窝</option>
            <option value="wifi_allowed">允许 Wi‑Fi</option>
          </select>
        </label>
        <label class="checkbox">
          <input v-model="editing.inbox_sync_enabled" type="checkbox" />
          启用私信同步
        </label>
      </div>
      <div class="actions">
        <button
          type="button"
          class="primary"
          :disabled="saving === `edit:${editing.account_id}`"
          @click="saveEdit"
        >
          {{ saving === `edit:${editing.account_id}` ? '保存中…' : '保存' }}
        </button>
        <button type="button" :disabled="!!saving" @click="closeEdit">取消</button>
      </div>
    </div>

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
              :disabled="!!saving"
              @click="openEdit(row.account)"
            >
              编辑
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
            <button
              type="button"
              :disabled="!!saving || !unboundOnlineDevices.length"
              :title="unboundOnlineDevices.length ? '' : '没有在线的未绑定设备可换绑'"
              @click="startRebind(row.account)"
            >
              换绑
            </button>
            <button
              type="button"
              class="danger"
              :disabled="saving === `delete:${row.account.account_id}`"
              @click="deleteAccount(row.account)"
            >
              删除
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
.actions button.danger {
  color: #b42318;
  border-color: #fecdca;
}
.actions button:disabled { opacity: 0.6; cursor: default; }
.edit-panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
}
.edit-panel h2 {
  margin: 0 0 8px;
  font-size: 1.05rem;
}
.edit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px 16px;
  margin: 12px 0 14px;
}
.edit-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-weight: 600;
  font-size: 0.9rem;
}
.edit-grid input[type='text'],
.edit-grid input[type='number'],
.edit-grid select {
  border: 1px solid #d0d5dd;
  border-radius: 8px;
  padding: 8px 10px;
  font: inherit;
}
.edit-grid label.checkbox {
  flex-direction: row;
  align-items: center;
  font-weight: 500;
  margin-top: 22px;
}
</style>
