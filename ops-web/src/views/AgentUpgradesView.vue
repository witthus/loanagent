<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { deviceDisplayName } from '@/lib/deviceFleet'
import type { FleetDevice } from '@/lib/fleet'
import { formatDateTime } from '@/lib/labels'

type AgentRelease = {
  available: boolean
  filename: string
  download_path: string
  version_name?: string | null
  version_code?: number | null
  sha256?: string | null
  built_at?: string | null
  byte_size?: number | null
  missing_reason?: string | null
  guide_available?: boolean
  guide_download_path?: string | null
}

type RingManifest = {
  ring: string
  available: boolean
  download_path: string
  manifest_version?: string | null
  agent_version?: string | null
  missing_reason?: string | null
}

const RINGS = ['canary', 'staged', 'stable'] as const

const devices = ref<FleetDevice[]>([])
const agentRelease = ref<AgentRelease | null>(null)
const rings = ref<RingManifest[]>([])
const error = ref('')
const message = ref('')
const saving = ref<string | null>(null)
const selected = ref<Record<string, boolean>>({})
const pushRing = ref<(typeof RINGS)[number]>('canary')
const publishRing = ref<(typeof RINGS)[number]>('canary')
const manifestJson = ref('')
const query = ref('')
let refreshTimer: number | null = null

const agentDownloadUrl = computed(() => {
  if (!agentRelease.value?.available) return ''
  return agentRelease.value.download_path || '/downloads/agent-latest.apk'
})

const guideDownloadUrl = computed(() => {
  if (!agentRelease.value?.guide_available) return ''
  return agentRelease.value.guide_download_path || '/downloads/device-bind-guide.pdf'
})

const agentSizeLabel = computed(() => {
  const bytes = agentRelease.value?.byte_size
  if (bytes == null || bytes < 0) return '—'
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
})

const absoluteAgentUrl = computed(() => {
  if (!agentDownloadUrl.value) return ''
  if (agentDownloadUrl.value.startsWith('http')) return agentDownloadUrl.value
  return `${window.location.origin}${agentDownloadUrl.value}`
})

const filteredDevices = computed(() => {
  const q = query.value.trim().toLowerCase()
  return devices.value.filter((d) => {
    if (!q) return true
    const upgrade = d.agent_upgrade
    const hay = [
      d.device_id,
      d.display_name,
      d.agent_version,
      upgrade?.status,
      upgrade?.ring,
      upgrade?.detail,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return hay.includes(q)
  })
})

const selectedIds = computed(() =>
  Object.entries(selected.value)
    .filter(([, on]) => on)
    .map(([id]) => id),
)

function shortSha(sha: string | null | undefined): string {
  if (!sha) return '—'
  return sha.length > 12 ? `${sha.slice(0, 12)}…` : sha
}

function ringRow(ring: string): RingManifest | undefined {
  return rings.value.find((r) => r.ring === ring)
}

function upgradeLabel(device: FleetDevice): string {
  const u = device.agent_upgrade
  if (!u?.status) return '—'
  const parts = [u.status]
  if (u.ring) parts.push(u.ring)
  return parts.join(' · ')
}

async function load() {
  error.value = ''
  try {
    const [deviceList, release, manifestList] = await Promise.all([
      api<FleetDevice[]>('/api/v1/devices'),
      api<AgentRelease>('/api/v1/agent/latest').catch(() => null),
      api<{ rings: RingManifest[] }>('/api/v1/update-manifests').catch(() => ({ rings: [] })),
    ])
    devices.value = deviceList
    agentRelease.value = release
    rings.value = manifestList.rings || []
  } catch (err) {
    error.value = formatApiError(err, '加载升级页失败')
  }
}

function absoluteUrl(path: string): string {
  if (!path) return ''
  if (path.startsWith('http')) return path
  return `${window.location.origin}${path}`
}

async function copyText(text: string, okMsg: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.value = okMsg
    error.value = ''
  } catch {
    error.value = '复制失败，请手动选中链接'
  }
}

async function copyRingLink(ring: string) {
  const row = ringRow(ring)
  if (!row?.download_path) return
  await copyText(absoluteUrl(row.download_path), `已复制 ${ring} manifest 链接`)
}

async function copyAgentLink() {
  await copyText(absoluteAgentUrl.value, '已复制 APK 下载链接')
}

async function publishManifest() {
  message.value = ''
  error.value = ''
  let payload: unknown
  try {
    payload = JSON.parse(manifestJson.value)
  } catch {
    error.value = '签名 manifest JSON 无法解析'
    return
  }
  if (!payload || typeof payload !== 'object') {
    error.value = 'manifest 必须是 JSON 对象'
    return
  }
  saving.value = 'publish'
  try {
    await api(`/api/v1/update-manifests/${publishRing.value}`, {
      method: 'POST',
      body: JSON.stringify({ ring: publishRing.value, manifest: payload }),
    })
    message.value = `已发布 ${publishRing.value} 签名 manifest`
    manifestJson.value = ''
    await load()
  } catch (err) {
    error.value = formatApiError(err, '发布签名 manifest 失败')
  } finally {
    saving.value = null
  }
}

async function pushSelected() {
  if (!selectedIds.value.length) {
    error.value = '请先勾选要推送的设备（建议用 DPC enrolled device_id）'
    return
  }
  saving.value = 'push'
  message.value = ''
  error.value = ''
  const failures: string[] = []
  for (const deviceId of selectedIds.value) {
    try {
      await api(`/api/v1/devices/${encodeURIComponent(deviceId)}/upgrade`, {
        method: 'POST',
        body: JSON.stringify({ ring: pushRing.value }),
      })
    } catch (err) {
      failures.push(`${deviceId}: ${formatApiError(err, '失败')}`)
    }
  }
  if (failures.length) {
    error.value = `部分推送失败：${failures.join('；')}`
  } else {
    message.value = `已向 ${selectedIds.value.length} 台推送 ${pushRing.value}；DPC 约 15 分钟轮询，或手机点「Check remote Agent upgrade」`
    selected.value = {}
  }
  await load()
  saving.value = null
}

async function pushOne(device: FleetDevice) {
  selected.value = { [device.device_id]: true }
  await pushSelected()
}

onMounted(async () => {
  await load()
  refreshTimer = window.setInterval(() => {
    void load()
  }, 30_000)
})

onUnmounted(() => {
  if (refreshTimer != null) window.clearInterval(refreshTimer)
})
</script>

<template>
  <section>
    <h1>远程升级</h1>
    <p class="hint">
      侧载下载用最新 APK；Device Owner 机走签名 update-manifest + DPC 轮询。推送请优先选
      Device Controller 上的 enrolled device_id。
    </p>

    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="panel">
      <h2>侧载：最新 Agent APK</h2>
      <template v-if="agentRelease?.available">
        <p class="meta">
          版本 {{ agentRelease.version_name || '—' }}
          · code {{ agentRelease.version_code ?? '—' }}
          · {{ agentSizeLabel }}
          · 构建 {{ formatDateTime(agentRelease.built_at) }}
          · SHA {{ shortSha(agentRelease.sha256) }}
        </p>
        <div class="row">
          <a class="download" :href="agentDownloadUrl" download="matrix-assistant-agent.apk">
            下载最新 Agent APK
          </a>
          <button type="button" class="ghost" @click="copyAgentLink">
            复制下载链接
          </button>
        </div>
        <p class="muted mono">{{ absoluteAgentUrl }}</p>
        <p class="muted">API：<code>GET /api/v1/agent/latest</code> · 公开路径
          <code>/downloads/agent-latest.apk</code></p>
      </template>
      <p v-else class="warn">
        {{ agentRelease?.missing_reason || '服务器尚未发布侧载 Agent APK。' }}
      </p>
      <p v-if="guideDownloadUrl" class="guide-row">
        <a class="guide-link" :href="guideDownloadUrl" download="矩阵助手-新设备绑定安装指引.pdf">
          下载新设备绑定安装指引（PDF）
        </a>
      </p>
    </div>

    <div class="panel">
      <h2>Device Owner：签名 update-manifest</h2>
      <p class="muted">
        与侧载 <code>latest.json</code> 分离。发布前需离线 ECDSA-P256 签名；服务器需配置
        <code>HTTPS_PUBLIC_BASE_URL</code> 才能按 ring 拼出 https 下载地址。
      </p>
      <table class="rings">
        <thead>
          <tr>
            <th>Ring</th>
            <th>状态</th>
            <th>Agent</th>
            <th>Manifest</th>
            <th>公开链接</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ring in RINGS" :key="ring">
            <td><strong>{{ ring }}</strong></td>
            <td>
              <span v-if="ringRow(ring)?.available" class="ok-inline">已发布</span>
              <span v-else class="warn">未发布</span>
            </td>
            <td>{{ ringRow(ring)?.agent_version || '—' }}</td>
            <td>{{ ringRow(ring)?.manifest_version || '—' }}</td>
            <td>
              <template v-if="ringRow(ring)?.available">
                <a :href="ringRow(ring)!.download_path" target="_blank" rel="noopener">
                  {{ ringRow(ring)!.download_path }}
                </a>
                <button
                  type="button"
                  class="ghost tiny"
                  @click="copyRingLink(ring)"
                >
                  复制
                </button>
              </template>
              <span v-else class="muted">{{ ringRow(ring)?.missing_reason || '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <h3>发布已签名 JSON</h3>
      <div class="row">
        <label>
          Ring
          <select v-model="publishRing">
            <option v-for="ring in RINGS" :key="ring" :value="ring">{{ ring }}</option>
          </select>
        </label>
        <button
          type="button"
          :disabled="saving === 'publish' || !manifestJson.trim()"
          @click="publishManifest"
        >
          {{ saving === 'publish' ? '发布中…' : '发布到服务器' }}
        </button>
      </div>
      <textarea
        v-model="manifestJson"
        rows="10"
        spellcheck="false"
        placeholder='粘贴符合 schemas/update-manifest.schema.json 且含 signature.value 的 JSON…'
      />
    </div>

    <div class="panel">
      <h2>推送到设备（DPC 远程升级）</h2>
      <div class="row toolbar">
        <label>
          目标 ring
          <select v-model="pushRing">
            <option v-for="ring in RINGS" :key="ring" :value="ring">{{ ring }}</option>
          </select>
        </label>
        <label class="search">
          筛选设备
          <input v-model="query" placeholder="名称 / ID / 版本 / 升级状态" />
        </label>
        <button
          type="button"
          :disabled="saving === 'push' || !selectedIds.length"
          @click="pushSelected"
        >
          {{ saving === 'push' ? '推送中…' : `推送所选（${selectedIds.length}）` }}
        </button>
        <button type="button" class="ghost" @click="load">刷新</button>
      </div>
      <table class="fleet">
        <thead>
          <tr>
            <th></th>
            <th>设备</th>
            <th>在线</th>
            <th>当前 Agent</th>
            <th>升级状态</th>
            <th>明细</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="device in filteredDevices" :key="device.device_id">
            <td>
              <input v-model="selected[device.device_id]" type="checkbox" />
            </td>
            <td>
              <strong>{{ deviceDisplayName(device) }}</strong>
              <div class="muted mono">{{ device.device_id }}</div>
            </td>
            <td>{{ device.online === false ? '离线' : '在线' }}</td>
            <td>{{ device.agent_version || '—' }}</td>
            <td>{{ upgradeLabel(device) }}</td>
            <td class="muted">{{ device.agent_upgrade?.detail || '—' }}</td>
            <td>
              <button type="button" class="ghost tiny" @click="pushOne(device)">推送</button>
            </td>
          </tr>
          <tr v-if="!filteredDevices.length">
            <td colspan="7" class="muted">暂无设备</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
h1 { margin: 0 0 8px; }
h2 { margin: 0 0 10px; font-size: 1.05rem; }
h3 { margin: 18px 0 8px; font-size: 0.95rem; }
.hint { color: #5c6770; margin: 0 0 16px; max-width: 52rem; }
.panel {
  background: #f7f8fa;
  border: 1px solid #e4e7eb;
  border-radius: 8px;
  padding: 16px 18px;
  margin-bottom: 16px;
}
.meta { margin: 0 0 10px; }
.muted { color: #5c6770; font-size: 0.9rem; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
.ok { color: #0b6b3a; }
.ok-inline { color: #0b6b3a; font-weight: 600; }
.warn { color: #9a5b00; }
.error { color: #b42318; }
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: end;
  margin-bottom: 10px;
}
.toolbar { margin-bottom: 12px; }
label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.85rem;
  color: #5c6770;
}
select, input, textarea {
  font: inherit;
  padding: 6px 8px;
  border: 1px solid #c9d0d8;
  border-radius: 6px;
  min-width: 10rem;
}
textarea {
  width: 100%;
  min-height: 10rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}
.search { flex: 1; min-width: 14rem; }
.search input { width: 100%; }
.download {
  display: inline-block;
  background: #1f4b7a;
  color: #fff;
  text-decoration: none;
  padding: 8px 14px;
  border-radius: 6px;
}
button {
  font: inherit;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #1f4b7a;
  background: #1f4b7a;
  color: #fff;
  cursor: pointer;
}
button:disabled { opacity: 0.5; cursor: not-allowed; }
button.ghost {
  background: #fff;
  color: #1f4b7a;
}
button.tiny { padding: 4px 8px; font-size: 0.85rem; }
.guide-row { margin: 10px 0 0; }
.guide-link { color: #1f4b7a; }
table { width: 100%; border-collapse: collapse; font-size: 0.92rem; }
th, td { text-align: left; padding: 8px 6px; border-bottom: 1px solid #e4e7eb; vertical-align: top; }
code { font-size: 0.85em; }
</style>
