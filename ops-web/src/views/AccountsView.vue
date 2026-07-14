<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
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
let refreshTimer: ReturnType<typeof setInterval> | null = null

const unboundAccounts = computed(() =>
  allAccounts.value.filter((a) => !a.device_id),
)
const unboundDevices = computed(() => {
  const used = new Set(
    allAccounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return allDevices.value.filter((d) => !used.has(d.device_id))
})

function isDeviceReady(device: Device | null): boolean {
  return Boolean(device?.online && device.a11y_bound === true)
}

function needsBindRecovery(row: Row): boolean {
  if (!row.device) return false
  // Bound or unbound device stuck offline / a11y-down during setup.
  return !isDeviceReady(row.device)
}

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
  return device.display_name?.trim() || device.device_id
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
    if (!bindDeviceId.value || !unboundDevices.value.some((d) => d.device_id === bindDeviceId.value)) {
      bindDeviceId.value = unboundDevices.value[0]?.device_id || ''
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
  const device = unboundDevices.value.find((d) => d.device_id === bindDeviceId.value)
  if (device && !isDeviceReady(device)) {
    const proceed = window.confirm(
      '该设备当前不在线或无障碍未开。仍可先绑定，但任务会失败；掉线时可在下方终止任务、解绑或删除设备。是否继续？',
    )
    if (!proceed) return
  }
  saving.value = 'bind'
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(bindAccountId.value)}`, {
      method: 'PATCH',
      body: JSON.stringify({ device_id: bindDeviceId.value }),
    })
    message.value = '账号与设备已绑定。若设备未就绪，可在下方暂停、终止任务或删除后重绑。'
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
    message.value = '已解除绑定，可重新选择设备绑定'
    await load()
  } catch {
    error.value = '解除绑定失败'
  } finally {
    saving.value = null
  }
}

async function pauseAccount(account: Account) {
  if (!window.confirm(`暂停「${accountDisplayName(account)}」？暂停后不会再下发新任务。`)) return
  saving.value = `pause:${account.account_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(account.account_id)}/pause`, {
      method: 'POST',
    })
    message.value = '账号已暂停'
    await load()
  } catch {
    error.value = '暂停账号失败'
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
  } catch {
    error.value = '恢复账号失败'
  } finally {
    saving.value = null
  }
}

async function cancelDeviceTasks(device: Device) {
  if (!window.confirm(`终止设备「${deviceLabel(device)}」上所有进行中的任务？`)) return
  saving.value = `cancel:${device.device_id}`
  message.value = ''
  error.value = ''
  try {
    const result = await api<{ cancelled_count: number }>(
      `/api/v1/devices/${encodeURIComponent(device.device_id)}/cancel-tasks`,
      { method: 'POST' },
    )
    message.value = `已终止 ${result.cancelled_count} 个进行中的任务`
    await load()
  } catch {
    error.value = '终止任务失败'
  } finally {
    saving.value = null
  }
}

async function deleteDevice(device: Device, account: Account | null) {
  const label = deviceLabel(device)
  const boundHint = account
    ? `将同时解除与「${accountDisplayName(account)}」的绑定，并终止进行中任务。`
    : '将终止该设备进行中的任务。'
  if (!window.confirm(`删除设备「${label}」？\n${boundHint}\n删除后可重新安装 Agent 并绑定。`)) {
    return
  }
  saving.value = `delete:${device.device_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/devices/${encodeURIComponent(device.device_id)}`, {
      method: 'DELETE',
    })
    message.value = '设备已删除，可重新上线后绑定'
    await load()
  } catch {
    error.value = '删除设备失败'
  } finally {
    saving.value = null
  }
}

onMounted(() => {
  void load()
  refreshTimer = setInterval(() => {
    if (rows.value.some((row) => needsBindRecovery(row))) void load()
  }, 8000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<template>
  <section>
    <h1>账号与设备</h1>
    <p class="hint">
      一台手机绑定一个账号。绑定后若掉线，可暂停账号、终止任务、解绑或删除设备后重新绑定。
    </p>
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
              {{ isDeviceReady(d) ? '（就绪）' : d.online ? '（无障碍未开）' : '（离线）' }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="saving === 'bind'" @click="bindAccountDevice">
          {{ saving === 'bind' ? '提交中…' : '绑定' }}
        </button>
        <button
          v-if="saving === 'bind'"
          type="button"
          class="ghost"
          @click="saving = null"
        >
          取消等待
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
        <template v-for="(row, index) in rows" :key="row.account?.account_id || row.device?.device_id || index">
          <tr>
            <td>
              <div>{{ accountLabel(row.account) }}</div>
              <div v-if="row.account?.status === 'paused'" class="badge">已暂停</div>
            </td>
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
              <button
                v-if="row.device"
                type="button"
                class="danger"
                :disabled="saving === `delete:${row.device.device_id}`"
                @click="deleteDevice(row.device, row.account)"
              >
                删除设备
              </button>
            </td>
          </tr>
          <tr v-if="row.device && needsBindRecovery({ account: row.account, device: row.device })" class="recovery">
            <td colspan="7">
              <div class="recovery-box">
                <strong>设备未就绪</strong>
                <span>
                  {{
                    row.device.online
                      ? '无障碍未开，绑定/任务可能卡住。'
                      : '手机已掉线。可暂停账号、终止进行中任务，或删除设备后重新绑定。'
                  }}
                </span>
                <div class="actions">
                  <button
                    v-if="row.account && row.account.status === 'active'"
                    type="button"
                    :disabled="saving === `pause:${row.account.account_id}`"
                    @click="pauseAccount(row.account)"
                  >
                    暂停账号
                  </button>
                  <button
                    v-if="row.account && row.account.status === 'paused'"
                    type="button"
                    :disabled="saving === `resume:${row.account.account_id}`"
                    @click="resumeAccount(row.account)"
                  >
                    恢复账号
                  </button>
                  <button
                    type="button"
                    :disabled="saving === `cancel:${row.device.device_id}`"
                    @click="cancelDeviceTasks(row.device)"
                  >
                    终止进行中任务
                  </button>
                  <button
                    v-if="row.account?.device_id"
                    type="button"
                    :disabled="saving === `unbind:${row.account.account_id}`"
                    @click="unbindAccount(row.account)"
                  >
                    解绑重来
                  </button>
                  <button
                    type="button"
                    class="danger"
                    :disabled="saving === `delete:${row.device.device_id}`"
                    @click="deleteDevice(row.device, row.account)"
                  >
                    删除设备并重绑
                  </button>
                </div>
              </div>
            </td>
          </tr>
        </template>
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
.badge {
  display: inline-block;
  margin-top: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff7ed;
  color: #b54708;
  font-size: 0.8rem;
  font-weight: 600;
}
.bind-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
  max-width: 820px;
}
.bind-panel h2 { margin: 0 0 12px; font-size: 1rem; }
.bind-row { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
.bind-row label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; min-width: 180px; }
.bind-row select, .bind-row button, .actions button { padding: 8px 12px; border-radius: 8px; font: inherit; }
.bind-row button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; }
.bind-row button.ghost,
.actions button {
  border: 1px solid #d0d5dd;
  background: #fff;
  color: #1f2329;
  font-weight: 600;
  cursor: pointer;
}
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.actions button:disabled { opacity: 0.6; cursor: default; }
.actions button.danger { color: #b42318; border-color: #fecdca; }
.recovery td { background: #fff7ed; border-bottom: 1px solid #fdba74; }
.recovery-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.recovery-box strong { color: #b54708; }
.recovery-box span { color: #5c6770; }
.recovery-box .actions { margin-top: 2px; }
</style>
