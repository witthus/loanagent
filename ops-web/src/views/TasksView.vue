<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
const route = useRoute()
const router = useRouter()
const allRows = ref<Task[]>([])
const accountNames = ref<Record<string, string>>({})
const expanded = ref<Record<string, boolean>>({})
const error = ref('')
const message = ref('')
const page = ref(1)
const cancelling = ref<string | null>(null)

const ACTIVE = new Set(['queued', 'accepted', 'executing'])
const focusTaskId = computed(() => (route.query.task_id as string) || '')

const sortedRows = computed(() =>
  [...allRows.value].sort((a, b) => {
    const ta = new Date(a.created_at).getTime()
    const tb = new Date(b.created_at).getTime()
    return tb - ta
  }),
)

const focusedRows = computed(() => {
  if (!focusTaskId.value) return sortedRows.value
  return sortedRows.value.filter((row) => row.task_id === focusTaskId.value)
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

async function loadTasks() {
  try {
    const [tasks, accounts] = await Promise.all([
      api<Task[]>('/api/v1/tasks'),
      api<AccountLike[]>('/api/v1/accounts?platform=xhs'),
    ])
    allRows.value = tasks
    accountNames.value = buildAccountNameMap(accounts)
    if (focusTaskId.value) expanded.value[focusTaskId.value] = true
    error.value = ''
  } catch {
    error.value = '加载任务失败'
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

watch(focusTaskId, (id) => {
  page.value = 1
  if (id) expanded.value[id] = true
})

watch(totalPages, (n) => {
  if (page.value > n) page.value = n
})

onMounted(loadTasks)
</script>

<template>
  <section>
    <h1>任务</h1>
    <p v-if="focusTaskId" class="focus-banner">
      正在查看单条任务
      <button type="button" class="link-btn" @click="clearFocus">返回全部任务</button>
    </p>
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
}
.link-btn:disabled { opacity: 0.6; cursor: default; }
.actions-cell { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
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
