<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

type Account = { account_id: string; display_name?: string | null; platform: string }
type Thread = {
  thread_id: string
  account_id: string
  title_summary: string
  preview_summary: string | null
  unread: boolean
}

const accounts = ref<Account[]>([])
const threads = ref<Thread[]>([])
const accountId = ref('')
const message = ref('')
const error = ref('')
const syncing = ref(false)
const { status, errorCode, poll } = useTaskPoll()

async function loadThreads() {
  if (!accountId.value) {
    threads.value = []
    return
  }
  threads.value = await api<Thread[]>(`/api/v1/inbox/threads?account_id=${encodeURIComponent(accountId.value)}`)
}

async function syncInbox() {
  if (!accountId.value) return
  syncing.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>('/api/v1/inbox/sync', {
      method: 'POST',
      body: JSON.stringify({ account_id: accountId.value }),
    })
    message.value = '已提交同步，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '私信已同步'
      await loadThreads()
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '同步失败'
  } finally {
    syncing.value = false
  }
}

async function onAccountChange() {
  try {
    await loadThreads()
  } catch {
    error.value = '加载私信列表失败'
  }
}

onMounted(async () => {
  try {
    accounts.value = await api<Account[]>('/api/v1/accounts?platform=xhs')
    if (accounts.value[0]) {
      accountId.value = accounts.value[0].account_id
      await loadThreads()
    }
  } catch {
    error.value = '加载账号失败'
  }
})
</script>

<template>
  <section>
    <h1>私信</h1>
    <div class="panel">
      <label>
        账号
        <select v-model="accountId" @change="onAccountChange">
          <option v-for="a in accounts" :key="a.account_id" :value="a.account_id">
            {{ a.display_name || a.account_id }}
          </option>
        </select>
      </label>
      <button type="button" :disabled="!accountId || syncing" @click="syncInbox">
        {{ syncing ? '同步中…' : '同步私信' }}
      </button>
      <p v-if="status" class="muted">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <table v-if="threads.length">
      <thead>
        <tr>
          <th>会话</th>
          <th>预览</th>
          <th>未读</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in threads" :key="t.thread_id">
          <td>{{ t.title_summary }}</td>
          <td>{{ t.preview_summary || '—' }}</td>
          <td>{{ t.unread ? '是' : '否' }}</td>
          <td>
            <router-link
              class="link"
              :to="{ path: `/inbox/${t.thread_id}`, query: { account_id: accountId } }"
            >
              查看
            </router-link>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无私信会话，可点「同步私信」从手机拉取。</p>
  </section>
</template>

<style scoped>
.panel {
  max-width: 560px;
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 20px;
}
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.link { color: #2d6a4f; font-weight: 600; text-decoration: none; }
.muted { color: #5c6770; margin: 0; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
