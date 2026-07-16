<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import {
  deviceDisplayName,
  deviceRowWarning,
  deviceStatusLabel,
  isLegacySharedDevice,
  isOutdatedAgent,
  locationLabel,
  networkLabel,
  relativeHeartbeat,
  slugifyAccountId,
} from '@/lib/deviceFleet'
import type { FleetDevice } from '@/lib/fleet'
import { accountDisplayName, formatDateTime, roleLabel } from '@/lib/labels'

type Device = FleetDevice

type Account = {
  account_id: string
  role: string
  status: string
  device_id?: string | null
  display_name?: string | null
  platform?: string
}

type FilterKey = 'all' | 'online' | 'offline' | 'unbound' | 'outdated' | 'a11y'

const devices = ref<Device[]>([])
const accounts = ref<Account[]>([])
const error = ref('')
const message = ref('')
const saving = ref<string | null>(null)
const filter = ref<FilterKey>('all')
const regionFilter = ref('')
const query = ref('')
const expandedId = ref('')
const bindAccountId = ref('')
const bindDeviceId = ref('')
const newAccountName = ref('')
const newAccountRole = ref('PUBLISHER_MATRIX')
const createForDeviceId = ref('')
let refreshTimer: number | null = null
let loadGeneration = 0

const accountsByDevice = computed(() => {
  const map = new Map<string, Account>()
  for (const account of accounts.value) {
    if (account.device_id) map.set(account.device_id, account)
  }
  return map
})

const unboundAccounts = computed(() => accounts.value.filter((a) => !a.device_id))

const unboundDevices = computed(() => {
  const used = new Set(
    accounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  return devices.value.filter((d) => !used.has(d.device_id))
})

const pendingOnlineDevices = computed(() =>
  unboundDevices.value.filter((d) => d.online !== false),
)

const regionOptions = computed(() => {
  const set = new Set<string>()
  for (const device of devices.value) {
    const geo = device.geo_label?.trim()
    if (geo) set.add(geo)
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const summary = computed(() => {
  let online = 0
  let offline = 0
  let unbound = 0
  let outdated = 0
  let a11yDown = 0
  const used = new Set(
    accounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  for (const device of devices.value) {
    if (device.online === false) offline += 1
    else online += 1
    if (!used.has(device.device_id)) unbound += 1
    if (isOutdatedAgent(device) || isLegacySharedDevice(device)) outdated += 1
    if (device.a11y_bound === false) a11yDown += 1
  }
  return {
    total: devices.value.length,
    online,
    offline,
    unbound,
    outdated,
    a11yDown,
  }
})

const filteredDevices = computed(() => {
  const used = new Set(
    accounts.value.map((a) => a.device_id).filter(Boolean) as string[],
  )
  const q = query.value.trim().toLowerCase()
  return devices.value.filter((device) => {
    const bound = used.has(device.device_id)
    if (filter.value === 'online' && device.online === false) return false
    if (filter.value === 'offline' && device.online !== false) return false
    if (filter.value === 'unbound' && bound) return false
    if (filter.value === 'outdated' && !(isOutdatedAgent(device) || isLegacySharedDevice(device))) {
      return false
    }
    if (filter.value === 'a11y' && device.a11y_bound !== false) return false
    if (regionFilter.value && (device.geo_label || '').trim() !== regionFilter.value) return false
    if (!q) return true
    const account = accountsByDevice.value.get(device.device_id)
    const haystack = [
      device.device_id,
      device.display_name,
      device.model,
      device.manufacturer,
      device.geo_label,
      device.public_ip,
      device.agent_version,
      account?.display_name,
      account?.account_id,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(q)
  })
})

const canCreateBind = computed(() => {
  const device = unboundDevices.value.find((d) => d.device_id === createForDeviceId.value)
  return Boolean(device && device.online !== false)
})

function a11yLabel(device: Device): string {
  if (device.a11y_bound === true) return '已就绪'
  if (device.a11y_bound === false) return '未就绪'
  return '—'
}

function toggleExpand(deviceId: string) {
  expandedId.value = expandedId.value === deviceId ? '' : deviceId
}

async function load() {
  const generation = ++loadGeneration
  error.value = ''
  try {
    const [deviceRows, accountRows] = await Promise.all([
      api<Device[]>('/api/v1/devices'),
      api<Account[]>('/api/v1/accounts?platform=xhs'),
    ])
    if (generation !== loadGeneration) return
    devices.value = deviceRows
    accounts.value = accountRows
    if (!bindAccountId.value && unboundAccounts.value[0]) {
      bindAccountId.value = unboundAccounts.value[0].account_id
    }
    if (!bindDeviceId.value && unboundDevices.value[0]) {
      bindDeviceId.value = unboundDevices.value[0].device_id
    }
    if (!createForDeviceId.value && pendingOnlineDevices.value[0]) {
      createForDeviceId.value = pendingOnlineDevices.value[0].device_id
    } else if (
      createForDeviceId.value &&
      !unboundDevices.value.some((d) => d.device_id === createForDeviceId.value)
    ) {
      createForDeviceId.value = pendingOnlineDevices.value[0]?.device_id || ''
    }
  } catch {
    if (generation !== loadGeneration) return
    error.value = '加载设备失败'
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
  } catch (err) {
    error.value = formatApiError(err, '更新设备名称失败')
  } finally {
    saving.value = null
  }
}

async function deleteUnboundDevice(device: Device) {
  if (device.online !== false) {
    error.value = '设备仍在线，请先停止手机上的矩阵助手，等其离线后再删除'
    return
  }
  const name = deviceDisplayName(device)
  if (
    !window.confirm(
      `删除设备「${name}」（${device.device_id}）？\n将移除该设备及关联任务，不可恢复。`,
    )
  ) {
    return
  }
  saving.value = `delete:${device.device_id}`
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/devices/${encodeURIComponent(device.device_id)}`, {
      method: 'DELETE',
    })
    message.value = '设备已删除'
    if (bindDeviceId.value === device.device_id) bindDeviceId.value = ''
    if (createForDeviceId.value === device.device_id) createForDeviceId.value = ''
    await load()
  } catch (err) {
    error.value = formatApiError(err, '删除设备失败')
  } finally {
    saving.value = null
  }
}

async function bindAccountDevice() {
  if (!bindAccountId.value || !bindDeviceId.value) {
    error.value = '请选择账号和设备'
    return
  }
  const device = devices.value.find((d) => d.device_id === bindDeviceId.value)
  if (!device || device.online === false) {
    error.value = '请选择在线设备后再绑定'
    return
  }
  if (isLegacySharedDevice(device) || isOutdatedAgent(device)) {
    error.value = '请先安装 Agent 0.1.2 及以上，再绑定'
    return
  }
  if (device.a11y_bound === false) {
    if (!window.confirm('该设备无障碍尚未就绪，绑定后暂时无法执行任务。仍要继续绑定吗？')) {
      return
    }
  }
  saving.value = 'bind'
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/accounts/${encodeURIComponent(bindAccountId.value)}`, {
      method: 'PATCH',
      body: JSON.stringify({ device_id: bindDeviceId.value }),
    })
    message.value = '已绑定账号'
    bindAccountId.value = ''
    bindDeviceId.value = ''
    await load()
  } catch (err) {
    error.value = formatApiError(err, '绑定失败（设备可能已被其他账号占用）')
  } finally {
    saving.value = null
  }
}

async function createAccountAndBind() {
  const name = newAccountName.value.trim()
  const deviceId = createForDeviceId.value
  if (!name) {
    error.value = '请填写账号显示名称'
    return
  }
  if (!deviceId) {
    error.value = '请选择要绑定的新设备'
    return
  }
  const device = unboundDevices.value.find((d) => d.device_id === deviceId)
  if (!device || device.online === false) {
    error.value = '请选择在线设备。打开手机矩阵助手并等待约 30 秒后再绑定。'
    return
  }
  if (isLegacySharedDevice(device) || isOutdatedAgent(device)) {
    error.value = '请先在该手机安装 Agent 0.1.2 及以上，再绑定。'
    return
  }
  if (device.a11y_bound === false) {
    if (!window.confirm('该设备无障碍尚未就绪，绑定后暂时无法执行任务。仍要继续绑定吗？')) {
      return
    }
  }
  saving.value = 'create-bind'
  message.value = ''
  error.value = ''
  try {
    await api('/api/v1/accounts', {
      method: 'POST',
      body: JSON.stringify({
        account_id: slugifyAccountId(name),
        role: newAccountRole.value,
        device_id: deviceId,
        display_name: name,
        platform: 'xhs',
      }),
    })
    message.value = `已创建账号「${name}」并绑定设备`
    newAccountName.value = ''
    createForDeviceId.value = ''
    await load()
  } catch (err) {
    error.value = formatApiError(err, '创建并绑定失败')
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
    <h1>设备</h1>
    <p class="hint">
      机队总览：地区与公网 IP 由心跳自动上报；改名、绑定、装包均在本页完成。每 15 秒自动刷新。
    </p>

    <div class="summary">
      <button type="button" :class="{ active: filter === 'all' }" @click="filter = 'all'">
        全部 {{ summary.total }}
      </button>
      <button type="button" :class="{ active: filter === 'online' }" @click="filter = 'online'">
        在线 {{ summary.online }}
      </button>
      <button type="button" class="warn" :class="{ active: filter === 'offline' }" @click="filter = 'offline'">
        离线 {{ summary.offline }}
      </button>
      <button type="button" class="warn" :class="{ active: filter === 'unbound' }" @click="filter = 'unbound'">
        未绑定 {{ summary.unbound }}
      </button>
      <button type="button" class="warn" :class="{ active: filter === 'outdated' }" @click="filter = 'outdated'">
        版本落后 {{ summary.outdated }}
      </button>
      <button type="button" class="warn" :class="{ active: filter === 'a11y' }" @click="filter = 'a11y'">
        无障碍异常 {{ summary.a11yDown }}
      </button>
    </div>

    <div class="toolbar">
      <label>
        地区
        <select v-model="regionFilter">
          <option value="">全部地区</option>
          <option v-for="region in regionOptions" :key="region" :value="region">{{ region }}</option>
        </select>
      </label>
      <label class="search">
        搜索
        <input v-model="query" placeholder="名称 / ID / IP / 账号" />
      </label>
    </div>

    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <p class="hint-upgrade">
      Agent APK 下载、签名 manifest 发布与远程推送已移至
      <router-link to="/upgrades">远程升级</router-link>。
    </p>

    <div class="bind-panel pending">
      <h2>待绑定设备</h2>
      <p class="muted">
        离线且未绑定的设备可删除（永久废弃 / 旧机换新后的残留）。在线设备须先停 Agent 等离线。
        已绑定设备请先到「账号」解绑或换绑。
      </p>
      <p v-if="!unboundDevices.length" class="muted">
        暂无新设备。安装最新 Agent 并打开应用后，约 30 秒内会出现在这里。
      </p>
      <template v-else>
        <table class="mini">
          <thead>
            <tr>
              <th>设备</th>
              <th>地区</th>
              <th>在线</th>
              <th>无障碍</th>
              <th>心跳</th>
              <th>Agent</th>
              <th>提示</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in unboundDevices"
              :key="d.device_id"
              :class="{ highlight: d.online !== false }"
            >
              <td>
                <strong>{{ deviceDisplayName(d) }}</strong>
                <div class="muted">{{ d.device_id }}</div>
              </td>
              <td>
                <div>{{ locationLabel(d).primary }}</div>
                <div v-if="locationLabel(d).secondary" class="muted">{{ locationLabel(d).secondary }}</div>
              </td>
              <td>{{ d.online === false ? '离线' : '在线' }}</td>
              <td>{{ a11yLabel(d) }}</td>
              <td :title="formatDateTime(d.last_seen_at)">{{ relativeHeartbeat(d.last_seen_at) }}</td>
              <td>{{ d.agent_version || '—' }}</td>
              <td class="warn">{{ deviceRowWarning(d) || '—' }}</td>
              <td>
                <button
                  v-if="d.online === false"
                  type="button"
                  class="danger"
                  :disabled="saving === `delete:${d.device_id}`"
                  @click="deleteUnboundDevice(d)"
                >
                  {{ saving === `delete:${d.device_id}` ? '删除中…' : '删除' }}
                </button>
                <span v-else class="muted">在线不可删</span>
              </td>
            </tr>
          </tbody>
        </table>

        <h3>为新设备创建账号并绑定</h3>
        <div class="bind-row">
          <label>
            选择设备（仅在线）
            <select v-model="createForDeviceId">
              <option v-for="d in pendingOnlineDevices" :key="d.device_id" :value="d.device_id">
                {{ deviceDisplayName(d) }}
                {{ d.geo_label ? ` · ${d.geo_label}` : '' }}
                {{ d.a11y_bound === false ? '（无障碍未开）' : '' }}
              </option>
            </select>
          </label>
          <label>
            账号显示名
            <input v-model="newAccountName" placeholder="例如：黄冈-矩阵-01" />
          </label>
          <label>
            角色
            <select v-model="newAccountRole">
              <option value="PUBLISHER_MAIN">主号</option>
              <option value="PUBLISHER_MATRIX">矩阵号</option>
              <option value="ENGAGER">互动号</option>
            </select>
          </label>
          <button
            type="button"
            :disabled="saving === 'create-bind' || !canCreateBind"
            @click="createAccountAndBind"
          >
            {{ saving === 'create-bind' ? '创建中…' : '创建并绑定' }}
          </button>
        </div>
        <p v-if="!pendingOnlineDevices.length" class="warn">没有在线的待绑定设备，请先让手机完成心跳。</p>
      </template>
    </div>

    <div v-if="unboundAccounts.length && unboundDevices.length" class="bind-panel">
      <h2>绑定已有账号到设备</h2>
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
              {{ deviceDisplayName(d) }}
              {{ d.geo_label ? ` · ${d.geo_label}` : '' }}
              {{ d.online === false ? '（离线）' : '（在线）' }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="saving === 'bind'" @click="bindAccountDevice">
          {{ saving === 'bind' ? '绑定中…' : '绑定' }}
        </button>
      </div>
    </div>

    <table class="fleet">
      <thead>
        <tr>
          <th>设备</th>
          <th>地区</th>
          <th>状态</th>
          <th>网络</th>
          <th>心跳</th>
          <th>Agent</th>
          <th>绑定账号</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="device in filteredDevices" :key="device.device_id">
          <tr :class="{ dim: device.online === false }">
            <td>
              <button type="button" class="linkish" @click="toggleExpand(device.device_id)">
                <strong>{{ deviceDisplayName(device) }}</strong>
              </button>
              <div class="muted">{{ device.device_id }}</div>
            </td>
            <td>
              <div>{{ locationLabel(device).primary }}</div>
              <div v-if="locationLabel(device).secondary" class="muted">
                {{ locationLabel(device).secondary }}
              </div>
            </td>
            <td>
              <span :class="{ warn: deviceStatusLabel(device) !== '在线' }">
                {{ deviceStatusLabel(device) }}
              </span>
              <div v-if="deviceRowWarning(device)" class="warn muted">{{ deviceRowWarning(device) }}</div>
            </td>
            <td>{{ networkLabel(device) }}</td>
            <td :title="formatDateTime(device.last_seen_at)">
              {{ relativeHeartbeat(device.last_seen_at) }}
            </td>
            <td>
              <div>{{ device.agent_version || '—' }}</div>
              <div v-if="device.agent_upgrade?.status" class="muted">
                <router-link to="/upgrades">升级: {{ device.agent_upgrade.status }}</router-link>
                <span v-if="device.agent_upgrade.status === 'pending'">
                  （可到远程升级页「清除」僵尸 pending）
                </span>
              </div>
            </td>
            <td>
              <template v-if="accountsByDevice.get(device.device_id)">
                <div>{{ accountDisplayName(accountsByDevice.get(device.device_id)!) }}</div>
                <div class="muted">{{ roleLabel(accountsByDevice.get(device.device_id)!.role) }}</div>
              </template>
              <span v-else class="warn">未绑定</span>
            </td>
            <td class="actions">
              <button
                type="button"
                :disabled="saving === `device:${device.device_id}`"
                @click="renameDevice(device)"
              >
                改名
              </button>
              <button
                v-if="accountsByDevice.get(device.device_id)"
                type="button"
                :disabled="saving === `unbind:${accountsByDevice.get(device.device_id)!.account_id}`"
                @click="unbindAccount(accountsByDevice.get(device.device_id)!)"
              >
                解绑
              </button>
              <button type="button" @click="toggleExpand(device.device_id)">
                {{ expandedId === device.device_id ? '收起' : '详情' }}
              </button>
            </td>
          </tr>
          <tr v-if="expandedId === device.device_id" class="detail">
            <td colspan="8">
              <div class="detail-grid">
                <div>
                  <span class="label">机型</span>
                  {{ [device.manufacturer, device.model].filter(Boolean).join(' · ') || '—' }}
                </div>
                <div>
                  <span class="label">公网 IP</span>
                  {{ device.public_ip || '—' }}
                </div>
                <div>
                  <span class="label">归属地</span>
                  {{ device.geo_label || '—' }}
                </div>
                <div>
                  <span class="label">无障碍</span>
                  {{ a11yLabel(device) }}
                </div>
                <div>
                  <span class="label">最近心跳</span>
                  {{ formatDateTime(device.last_seen_at) }}
                </div>
                <div>
                  <span class="label">Agent 升级</span>
                  <template v-if="device.agent_upgrade">
                    {{ device.agent_upgrade.status || '—' }}
                    <span v-if="device.agent_upgrade.detail" class="muted">
                      · {{ device.agent_upgrade.detail }}
                    </span>
                  </template>
                  <span v-else>—</span>
                </div>
                <div>
                  <span class="label">账号状态</span>
                  {{
                    accountsByDevice.get(device.device_id)?.status
                      ? accountsByDevice.get(device.device_id)!.status
                      : '—'
                  }}
                </div>
              </div>
            </td>
          </tr>
        </template>
        <tr v-if="!filteredDevices.length">
          <td colspan="8" class="muted">没有符合筛选条件的设备。</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
h1 { margin: 0 0 8px; }
.hint { color: #5c6770; margin: 0 0 14px; }
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.summary button {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 999px;
  padding: 6px 12px;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
}
.summary button.active { background: #2d6a4f; color: #fff; border-color: #2d6a4f; }
.summary button.warn.active { background: #b54708; border-color: #b54708; }
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
}
.toolbar label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-weight: 600;
  font-size: 0.9rem;
}
.toolbar select,
.toolbar input {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid #d0d5dd;
  font: inherit;
  min-width: 180px;
}
.toolbar .search { flex: 1; min-width: 220px; }
.ok { color: #067647; }
.error { color: #b42318; }
.warn { color: #b54708; }
.muted { color: #5c6770; font-size: 0.85rem; margin-top: 4px; }
.hint-upgrade {
  margin: 0 0 14px;
  color: #5c6770;
}
.hint-upgrade a { color: #175cd3; font-weight: 600; }
.bind-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
}
.bind-panel.pending { border: 1px solid #fdba74; }
.bind-panel h2 { margin: 0 0 12px; font-size: 1rem; }
.bind-panel h3 { margin: 8px 0 12px; font-size: 0.95rem; }
.bind-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.bind-row label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-weight: 600;
  min-width: 160px;
}
.bind-row select,
.bind-row button,
.bind-row input {
  padding: 8px 12px;
  border-radius: 8px;
  font: inherit;
}
.bind-row input { border: 1px solid #d0d5dd; min-width: 180px; }
.bind-row button {
  background: #2d6a4f;
  color: #fff;
  border: 0;
  font-weight: 600;
  cursor: pointer;
}
.bind-row button:disabled { opacity: 0.6; cursor: default; }
table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}
th,
td {
  text-align: left;
  padding: 12px 14px;
  border-bottom: 1px solid #eee;
  vertical-align: top;
}
th { background: #f3f4f6; }
table.mini { margin-bottom: 14px; }
table.mini button.danger {
  border: 1px solid #fecdca;
  background: #fff;
  color: #b42318;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
}
table.mini button.danger:disabled {
  opacity: 0.6;
  cursor: default;
}
tr.highlight { background: #fff7ed; }
tr.dim { opacity: 0.78; }
tr.detail td { background: #f8fafc; }
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px 18px;
}
.detail-grid .label {
  display: block;
  color: #667085;
  font-size: 0.8rem;
  margin-bottom: 2px;
}
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.actions button {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}
.linkish {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}
</style>
