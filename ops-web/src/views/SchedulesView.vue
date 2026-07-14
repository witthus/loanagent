<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { useFleetAccounts } from '@/composables/useFleetAccounts'
import { humanizeError } from '@/lib/humanize'
import { formatDateTime } from '@/lib/labels'
import { useTaskPoll } from '@/composables/useTaskPoll'

type Content = { content_id: string; title: string }
type Schedule = {
  schedule_id: string
  account_id: string
  content_id: string
  status: string
  window_start?: string | null
  task_id?: string | null
  error_code?: string | null
  created_at?: string
  updated_at?: string
}

const route = useRoute()
const { accounts, accountNames, loadFleet, optionLabel, runnable } = useFleetAccounts()
const contents = ref<Content[]>([])
const schedules = ref<Schedule[]>([])
const accountId = ref('')
const contentId = ref('')
const message = ref('')
const error = ref('')
const creating = ref(false)
const dispatchingId = ref<string | null>(null)
const { status, errorCode, poll } = useTaskPoll()

const contentTitle = computed(() => {
  const map: Record<string, string> = {}
  for (const c of contents.value) map[c.content_id] = c.title
  return map
})

const publishers = computed(() =>
  accounts.value.filter((a) => a.role === 'PUBLISHER_MAIN' || a.role === 'PUBLISHER_MATRIX'),
)

const focusId = computed(() => (route.query.schedule_id as string) || '')

const sorted = computed(() => {
  const rows = [...schedules.value]
  rows.sort((a, b) => {
    if (focusId.value) {
      if (a.schedule_id === focusId.value) return -1
      if (b.schedule_id === focusId.value) return 1
    }
    return new Date(b.created_at || 0).getTime() - new Date(a.created_at || 0).getTime()
  })
  return rows
})

const STATUS_LABEL: Record<string, string> = {
  ready: '待发布',
  dispatched: '已下发',
  failed: '失败',
  cancelled: '已取消',
}

function accountName(id: string): string {
  return accountNames.value[id] || '未命名账号'
}

async function loadSchedules() {
  schedules.value = await api<Schedule[]>('/api/v1/schedules')
}

async function createSchedule() {
  if (!accountId.value || !contentId.value) {
    error.value = '请选择账号和素材'
    return
  }
  const selected = publishers.value.find((a) => a.account_id === accountId.value)
  if (selected && !runnable(selected)) {
    error.value = '该账号当前不可用，请先到「账号」或「设备」处理。'
    return
  }
  creating.value = true
  error.value = ''
  message.value = ''
  try {
    await api('/api/v1/schedules', {
      method: 'POST',
      body: JSON.stringify({
        account_id: accountId.value,
        content_id: contentId.value,
      }),
    })
    message.value = '已加入排期'
    await loadSchedules()
  } catch (err) {
    error.value = formatApiError(err, '加入排期失败')
  } finally {
    creating.value = false
  }
}

async function dispatchSchedule(row: Schedule) {
  dispatchingId.value = row.schedule_id
  error.value = ''
  message.value = ''
  try {
    const updated = await api<Schedule>(`/api/v1/schedules/${row.schedule_id}/dispatch`, {
      method: 'POST',
    })
    if (updated.status === 'failed') {
      const human = humanizeError(updated.error_code)
      error.value = `下发失败：${human.title}。${human.nextStep}`
      await loadSchedules()
      return
    }
    message.value = '排期已下发，正在等待手机执行…'
    if (updated.task_id) {
      const final = await poll(updated.task_id)
      if (final?.status === 'succeeded') {
        message.value = '排期发布成功'
      } else {
        const human = humanizeError(final?.error_code || errorCode.value)
        error.value = `${human.title}。${human.nextStep}`
      }
    }
    await loadSchedules()
  } catch (err) {
    error.value = formatApiError(err, '下发排期失败')
    await loadSchedules()
  } finally {
    dispatchingId.value = null
  }
}

onMounted(async () => {
  try {
    await loadFleet()
    contents.value = await api<Content[]>('/api/v1/content?platform=xhs')
    await loadSchedules()
    const firstReady = publishers.value.find((a) => runnable(a)) || publishers.value[0]
    if (firstReady) accountId.value = firstReady.account_id
    if (contents.value[0]) contentId.value = contents.value[0].content_id
  } catch {
    error.value = '加载排期失败'
  }
})
</script>

<template>
  <section>
    <h1>排期</h1>
    <p class="hint">先把素材排到账号上，再到时间点点「发布」下发到对应手机。多账号可分别建多条排期。</p>

    <form class="panel" @submit.prevent="createSchedule">
      <label>
        发帖账号
        <select v-model="accountId">
          <option
            v-for="a in publishers"
            :key="a.account_id"
            :value="a.account_id"
            :disabled="!runnable(a)"
          >
            {{ optionLabel(a) }}
          </option>
        </select>
      </label>
      <label>
        素材
        <select v-model="contentId">
          <option v-for="c in contents" :key="c.content_id" :value="c.content_id">
            {{ c.title }}
          </option>
        </select>
      </label>
      <button type="submit" :disabled="creating || !accountId || !contentId">
        {{ creating ? '加入中…' : '加入排期' }}
      </button>
    </form>

    <p v-if="status" class="muted">任务状态：{{ status }}</p>
    <p v-if="message" class="ok">{{ message }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <table v-if="sorted.length">
      <thead>
        <tr>
          <th>创建时间</th>
          <th>账号</th>
          <th>素材</th>
          <th>状态</th>
          <th>说明</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in sorted"
          :key="row.schedule_id"
          :class="{ focus: row.schedule_id === focusId }"
        >
          <td>{{ formatDateTime(row.created_at) }}</td>
          <td>{{ accountName(row.account_id) }}</td>
          <td>{{ contentTitle[row.content_id] || row.content_id }}</td>
          <td>{{ STATUS_LABEL[row.status] || row.status }}</td>
          <td>
            <span v-if="row.error_code">{{ humanizeError(row.error_code).title }}</span>
            <span v-else-if="row.task_id" class="muted">任务 {{ row.task_id.slice(0, 8) }}…</span>
            <span v-else class="muted">—</span>
          </td>
          <td>
            <button
              v-if="row.status === 'ready' || row.status === 'failed'"
              type="button"
              :disabled="dispatchingId === row.schedule_id"
              @click="dispatchSchedule(row)"
            >
              {{ dispatchingId === row.schedule_id ? '发布中…' : '发布到手机' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无排期。可在上方加入，或从「发笔记」页加入排期。</p>
  </section>
</template>

<style scoped>
.hint { color: #5c6770; margin: 0 0 14px; }
.panel {
  max-width: 560px;
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 18px;
}
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
tr.focus { background: #fff7ed; }
.ok { color: #2d6a4f; }
.error { color: #b42318; }
.muted { color: #5c6770; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
