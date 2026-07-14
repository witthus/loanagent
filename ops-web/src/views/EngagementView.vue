<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/lib/api'
import { DEFAULT_PLATFORM } from '@/platform'

type Account = { account_id: string; role: string; display_name?: string | null }
type Chain = {
  chain_id: string
  publish_task_id: string
  account_id: string
  engager_account_id: string | null
  status: string
  mode?: string
  platform?: string
  engager_account_ids?: string[]
  stop_reason: string | null
}

const route = useRoute()
const chains = ref<Chain[]>([])
const accounts = ref<Account[]>([])
const selectedEngagers = ref<string[]>([])
const message = ref('')
const error = ref('')
const stoppingId = ref<string | null>(null)
const arranging = ref(false)

const arrangeTaskId = computed(() => route.query.arrange as string | undefined)
const engagers = computed(() => accounts.value.filter((a) => a.role === 'ENGAGER'))

async function loadChains() {
  chains.value = await api<Chain[]>('/api/v1/engagement/chains')
}

async function stopChain(chainId: string) {
  stoppingId.value = chainId
  error.value = ''
  message.value = ''
  try {
    await api(`/api/v1/engagement/chains/${chainId}/stop`, { method: 'POST' })
    message.value = '互动链已停止'
    await loadChains()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '停止失败'
  } finally {
    stoppingId.value = null
  }
}

async function arrangeManual() {
  if (!arrangeTaskId.value || !selectedEngagers.value.length) return
  arranging.value = true
  error.value = ''
  message.value = ''
  try {
    await api('/api/v1/engagement/chains', {
      method: 'POST',
      body: JSON.stringify({
        publish_task_id: arrangeTaskId.value,
        engager_account_ids: selectedEngagers.value,
        platform: DEFAULT_PLATFORM,
      }),
    })
    message.value = '手动互动链已创建'
    selectedEngagers.value = []
    await loadChains()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '创建互动链失败'
  } finally {
    arranging.value = false
  }
}

function toggleEngager(id: string) {
  if (selectedEngagers.value.includes(id)) {
    selectedEngagers.value = selectedEngagers.value.filter((x) => x !== id)
  } else {
    selectedEngagers.value = [...selectedEngagers.value, id]
  }
}

function accountLabel(id: string | null | undefined): string {
  if (!id) return '—'
  const found = accounts.value.find((a) => a.account_id === id)
  return found?.display_name?.trim() || '未命名账号'
}

const STATUS_LABEL: Record<string, string> = {
  pending: '待开始',
  running: '进行中',
  done: '已完成',
  stopped: '已停止',
  failed: '失败',
}

const MODE_LABEL: Record<string, string> = {
  auto: '自动',
  manual: '手动',
}

onMounted(async () => {
  try {
    ;[chains.value, accounts.value] = await Promise.all([
      api<Chain[]>('/api/v1/engagement/chains'),
      api<Account[]>('/api/v1/accounts?platform=xhs'),
    ])
  } catch {
    error.value = '加载互动链失败'
  }
})
</script>

<template>
  <section>
    <h1>互动链</h1>
    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="arrangeTaskId" class="panel">
      <h2>手动编排互动</h2>
      <p class="muted">发布任务：{{ arrangeTaskId }}</p>
      <p>选择参与互动的互动号：</p>
      <div class="engagers">
        <label v-for="a in engagers" :key="a.account_id" class="check">
          <input
            type="checkbox"
            :checked="selectedEngagers.includes(a.account_id)"
            @change="toggleEngager(a.account_id)"
          />
          {{ a.display_name?.trim() || '未命名账号' }}
        </label>
      </div>
      <button
        type="button"
        :disabled="!selectedEngagers.length || arranging"
        @click="arrangeManual"
      >
        {{ arranging ? '提交中…' : '创建手动互动链' }}
      </button>
    </div>

    <table v-if="chains.length">
      <thead>
        <tr>
          <th>模式</th>
          <th>状态</th>
          <th>发帖账号</th>
          <th>互动账号</th>
          <th>发布任务</th>
          <th>停止原因</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in chains" :key="c.chain_id">
          <td>{{ MODE_LABEL[c.mode || 'auto'] || c.mode || '自动' }}</td>
          <td>{{ STATUS_LABEL[c.status] || c.status }}</td>
          <td>{{ accountLabel(c.account_id) }}</td>
          <td>
            <template v-if="c.engager_account_ids?.length">
              {{ c.engager_account_ids.map(accountLabel).join('、') }}
            </template>
            <template v-else>{{ accountLabel(c.engager_account_id) }}</template>
          </td>
          <td>{{ c.publish_task_id }}</td>
          <td>{{ c.stop_reason || '—' }}</td>
          <td>
            <button
              v-if="!['done', 'stopped', 'failed'].includes(c.status)"
              type="button"
              :disabled="stoppingId === c.chain_id"
              @click="stopChain(c.chain_id)"
            >
              {{ stoppingId === c.chain_id ? '停止中…' : '停止' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无互动链。</p>
  </section>
</template>

<style scoped>
.panel {
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.panel h2 { margin: 0; font-size: 1.1rem; }
.engagers { display: flex; flex-wrap: wrap; gap: 12px; }
.check { display: flex; align-items: center; gap: 6px; font-weight: 500; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
button {
  background: #2d6a4f;
  color: #fff;
  border: 0;
  border-radius: 8px;
  padding: 8px 14px;
  font-weight: 600;
  cursor: pointer;
}
button:disabled { opacity: 0.6; cursor: not-allowed; }
.muted { color: #5c6770; margin: 0; }
.ok { color: #2d6a4f; }
.error { color: #b42318; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
