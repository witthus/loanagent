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
const markingId = ref<string | null>(null)
const { status, errorCode, poll } = useTaskPoll()

async function loadThreads() {
  if (!accountId.value) {
    threads.value = []
    return
  }
  threads.value = await api<Thread[]>(`/api/v1/inbox/threads?account_id=${encodeURIComponent(accountId.value)}`)
}

async function markAsLead(threadId: string) {
  markingId.value = threadId
  error.value = ''
  message.value = ''
  try {
    await api('/api/v1/inbox/leads', {
      method: 'POST',
      body: JSON.stringify({ thread_id: threadId, status: 'new' }),
    })
    message.value = '已标记为线索，可在「线索」页跟进'
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '标记线索失败'
  } finally {
    markingId.value = null
  }
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
    message.value = '已提交对账同步，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '私信已与手机对账更新'
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
            {{ a.display_name?.trim() || '未命名账号' }}
          </option>
        </select>
      </label>
      <button type="button" :disabled="!accountId || syncing" @click="syncInbox">
        {{ syncing ? '对账同步中…' : '同步最新私信' }}
      </button>
      <p class="hint">默认显示数据库缓存；同步会与手机对账，设备上已删除的会话会从缓存移除。</p>
      <p v-if="status" class="muted">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <table v-if="threads.length">
      <thead>
        <tr>
          <th style="width: 18%">会话</th>
          <th>最近对话预览</th>
          <th style="width: 8%">未读</th>
          <th style="width: 18%"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in threads" :key="t.thread_id">
          <td class="title">{{ t.title_summary }}</td>
          <td class="preview">{{ t.preview_summary || '（暂无正文，可点同步最新私信拉取）' }}</td>
          <td>{{ t.unread ? '是' : '否' }}</td>
          <td class="row-actions">
            <router-link
              class="link"
              :to="{ path: `/inbox/${t.thread_id}`, query: { account_id: accountId } }"
            >
              查看并回复
            </router-link>
            <button
              type="button"
              class="secondary"
              :disabled="markingId === t.thread_id"
              @click="markAsLead(t.thread_id)"
            >
              标为线索
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无私信会话（数据库缓存）。可点「同步最新私信」从手机拉取并对账。</p>
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
button.secondary {
  background: #fff;
  color: #2d6a4f;
  border: 1px solid #2d6a4f;
  padding: 6px 10px;
  font-size: 0.85rem;
  align-self: auto;
}
.row-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; vertical-align: top; }
th { background: #f3f4f6; }
.title { font-weight: 600; white-space: nowrap; }
.preview {
  color: #344054;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.45;
  max-width: 520px;
}
.link { color: #2d6a4f; font-weight: 600; text-decoration: none; }
.hint { color: #5c6770; margin: 0; font-size: 0.88rem; font-weight: 400; }
.muted { color: #5c6770; margin: 0; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
