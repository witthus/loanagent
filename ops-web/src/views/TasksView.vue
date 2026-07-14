<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import {
  buildAccountNameMap,
  formatDateTime,
  playbookLabel,
  taskStatusLabel,
  type AccountLike,
} from '@/lib/labels'

type Task = {
  task_id: string
  status: string
  playbook: string
  account_id: string
  error_code: string | null
  created_at: string
  updated_at?: string
}

const PAGE_SIZE = 20
const TERMINAL = new Set(['succeeded', 'failed', 'cancelled', 'rejected', 'timed_out'])
const route = useRoute()
const router = useRouter()
const allRows = ref<Task[]>([])
const accountNames = ref<Record<string, string>>({})
const accounts = ref<AccountLike[]>([])
const filterAccountId = ref('')
const filterStatus = ref('')
const expanded = ref<Record<string, boolean>>({})
const error = ref('')
const message = ref('')
const page = ref(1)
const loading = ref(false)
const cancelling = ref<string | null>(null)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const ACTIVE = new Set(['queued', 'accepted', 'executing'])
const focusTaskId = computed(() => (route.query.task_id as string) || '')

const hasActiveTasks = computed(() =>
  allRows.value.some((row) => !TERMINAL.has(row.status)),
)

const sortedRows = computed(() =>
  [...allRows.value].sort((a, b) => {
    const ta = new Date(a.created_at).getTime()
    const tb = new Date(b.created_at).getTime()
    return tb - ta
  }),
)

const filteredRows = computed(() => {
  let rows = sortedRows.value
  if (filterAccountId.value) {
    rows = rows.filter((row) => row.account_id === filterAccountId.value)
  }
  if (filterStatus.value) {
    rows = rows.filter((row) => row.status === filterStatus.value)
  }
  return rows
})

const focusedRows = computed(() => {
  if (!focusTaskId.value) return filteredRows.value
  return filteredRows.value.filter((row) => row.task_id === focusTaskId.value)
})

const totalPages = computed(() =>
  Math.max(1, Math.ceil(focusedRows.value.length / PAGE_SIZE)),
)

const pageRows = computed(() => {
  if (focusTaskId.value) return focusedRows.value
  const start = (page.value - 1) * PAGE_SIZE
  return focusedRows.value.slice(start, start + PAGE_SIZE)
})

function toggle(taskId: string) {
  expanded.value[taskId] = !expanded.value[taskId]
}

function accountName(accountId: string): string {
  return accountNames.value[accountId] || '未命名账号'
}

function clearFocus() {
  router.push({ name: 'tasks' })
}

watch([filterAccountId, filterStatus], () => {
  page.value = 1
})

watch(focusTaskId, (id) => {
  page.value = 1
  if (id) expanded.value[id] = true
})

watch(totalPages, (n) => {
  if (page.value > n) page.value = n
})

onMounted(async () => {
  await loadTasks()
  refreshTimer = setInterval(() => {
    if (hasActiveTasks.value || focusTaskId.value) void loadTasks()
  }, 4000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

async function loadTasks() {
  if (loading.value) return
  loading.value = true
  try {
    const [tasks, accountRows] = await Promise.all([
      api<Task[]>('/api/v1/tasks'),
      api<AccountLike[]>('/api/v1/accounts?platform=xhs'),
    ])
    allRows.value = tasks
    accounts.value = accountRows
    accountNames.value = buildAccountNameMap(accountRows)
    if (focusTaskId.value) expanded.value[focusTaskId.value] = true
    error.value = ''
  } catch {
    error.value = '加载任务失败'
  } finally {
    loading.value = false
  }
}

async function cancelTask(taskId: string) {
  if (!window.confirm('确认取消该进行中的任务？')) return
  cancelling.value = taskId
  message.value = ''
  error.value = ''
  try {
    await api(`/api/v1/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' })
    message.value = '任务已取消'
    await loadTasks()
  } catch {
    error.value = '取消任务失败（可能已结束）'
  } finally {
    cancelling.value = null
  }
}
</script>

<template>
  <section>
    <h1>任务</h1>
    <p v-if="focusTaskId" class="focus-banner">
      正在查看单条任务
      <button type="button" class="link-btn" @click="clearFocus">返回全部任务</button>
      <button type="button" class="link-btn" :disabled="loading" @click="loadTasks">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </p>
    <div v-if="!focusTaskId" class="filters">
      <button type="button" class="link-btn refresh" :disabled="loading" @click="loadTasks">
        {{ loading ? '刷新中…' : '刷新任务' }}
      </button>
      <label>
        账号
        <select v-model="filterAccountId">
          <option value="">全部账号</option>
          <option v-for="a in accounts" :key="a.account_id" :value="a.account_id">
            {{ accountNames[a.account_id] || a.account_id }}
          </option>
        </select>
      </label>
      <label>
        状态
        <select v-model="filterStatus">
          <option value="">全部状态</option>
          <option value="accepted">已下发</option>
          <option value="executing">执行中</option>
          <option value="succeeded">成功</option>
          <option value="failed">失败</option>
          <option value="timed_out">超时</option>
          <option value="cancelled">已取消</option>
        </select>
      </label>
    </div>
    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>
    <template v-if="!error || allRows.length">
      <table>
        <thead>
          <tr>
            <th>下发时间</th>
            <th>状态</th>
            <th>任务类型</th>
            <th>账号</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in pageRows" :key="row.task_id">
            <tr>
              <td>{{ formatDateTime(row.created_at) }}</td>
              <td>{{ taskStatusLabel(row.status) }}</td>
              <td>{{ playbookLabel(row.playbook) }}</td>
              <td>{{ accountName(row.account_id) }}</td>
              <td class="actions-cell">
                <button
                  v-if="ACTIVE.has(row.status)"
                  type="button"
                  class="link-btn"
                  :disabled="cancelling === row.task_id"
                  @click="cancelTask(row.task_id)"
                >
                  {{ cancelling === row.task_id ? '取消中…' : '取消任务' }}
                </button>
                <button
                  v-if="row.error_code"
                  type="button"
                  class="link-btn"
                  @click="toggle(row.task_id)"
                >
                  {{ expanded[row.task_id] ? '收起异常' : '查看异常' }}
                </button>
                <span v-else-if="!ACTIVE.has(row.status)" class="ok">正常</span>
              </td>
            </tr>
            <tr v-if="row.error_code && expanded[row.task_id]" class="detail">
              <td colspan="5">
                <p><strong>异常说明：</strong>{{ humanizeError(row.error_code).title }}</p>
                <p>{{ humanizeError(row.error_code).detail }}</p>
                <p class="muted">建议：{{ humanizeError(row.error_code).nextStep }}</p>
                <p class="muted">系统代码：{{ row.error_code }}</p>
              </td>
            </tr>
          </template>
          <tr v-if="!pageRows.length">
            <td colspan="5" class="empty">暂无任务。</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!focusTaskId && focusedRows.length > PAGE_SIZE" class="pager">
        <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页（共 {{ focusedRows.length }} 条，每页 {{ PAGE_SIZE }} 条）</span>
        <button type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 14px;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
}
.filters label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-weight: 600;
  font-size: 0.9rem;
}
.filters select {
  min-width: 180px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #d0d5dd;
  font: inherit;
}
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.detail td { background: #f9fafb; }
.link-btn {
  background: none;
  border: 0;
  color: #2d6a4f;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  font: inherit;
}
.link-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.actions-cell { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.filters .refresh { align-self: flex-end; padding-bottom: 8px; }
.muted { color: #5c6770; margin: 4px 0 0; }
.ok { color: #067647; }
.error { color: #b42318; }
.empty { color: #5c6770; }
.focus-banner {
  background: #fff7ed;
  border: 1px solid #fdba74;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  gap: 16px;
  align-items: center;
}
.pager {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-top: 14px;
  color: #5c6770;
}
.pager button {
  border: 1px solid #d0d5dd;
  background: #fff;
  border-radius: 8px;
  padding: 8px 12px;
  cursor: pointer;
}
.pager button:disabled { opacity: 0.5; cursor: default; }
</style>
