<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { useFleetAccounts } from '@/composables/useFleetAccounts'
import { useSubmittedTasks } from '@/composables/useSubmittedTasks'
import SubmittedTasksBanner from '@/components/SubmittedTasksBanner.vue'

type Thread = {
  thread_id: string
  account_id: string
  title_summary: string
  preview_summary: string | null
  unread: boolean
}

const route = useRoute()
const { accounts, accountNames, loadFleet, optionLabel, runnable } = useFleetAccounts()
const threads = ref<Thread[]>([])
/** Empty string = all accounts */
const accountId = ref('')
const message = ref('')
const error = ref('')
const syncing = ref(false)
const markingId = ref<string | null>(null)
const { tasks: submittedTasks, track, dismiss, statusText, isRunning } = useSubmittedTasks()

const showAllAccounts = computed(() => !accountId.value)

function accountName(id: string): string {
  return accountNames.value[id] || '未命名账号'
}

async function loadThreads() {
  const q = accountId.value
    ? `/api/v1/inbox/threads?account_id=${encodeURIComponent(accountId.value)}`
    : '/api/v1/inbox/threads'
  threads.value = await api<Thread[]>(q)
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
    error.value = formatApiError(err, '标记线索失败')
  } finally {
    markingId.value = null
  }
}

async function syncInbox() {
  if (!accountId.value) {
    error.value = '请先选择具体账号再同步（「全部账号」仅用于查看）。'
    return
  }
  const selected = accounts.value.find((a) => a.account_id === accountId.value)
  if (selected && !runnable(selected)) {
    error.value = '该账号设备不可用（离线或无障碍未开），请先到「设备」处理。'
    return
  }
  syncing.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>('/api/v1/inbox/sync', {
      method: 'POST',
      body: JSON.stringify({ account_id: accountId.value }),
    })
    track(task.task_id, `同步私信 · ${accountName(accountId.value)}`)
    message.value = '已提交同步私信任务，手机在后台执行。完成后可点「刷新列表」。'
  } catch (err) {
    error.value = formatApiError(err, '同步失败')
  } finally {
    syncing.value = false
  }
}

async function syncAllRunnable() {
  const targets = accounts.value.filter((a) => runnable(a))
  if (!targets.length) {
    error.value = '没有可用账号可同步（需在线且无障碍已开）。'
    return
  }
  syncing.value = true
  error.value = ''
  message.value = ''
  const submitted: string[] = []
  const fail: string[] = []
  try {
    for (const account of targets) {
      try {
        const task = await api<{ task_id: string }>('/api/v1/inbox/sync', {
          method: 'POST',
          body: JSON.stringify({ account_id: account.account_id }),
        })
        track(task.task_id, `同步私信 · ${accountName(account.account_id)}`)
        submitted.push(account.account_id)
      } catch (err) {
        fail.push(`${accountName(account.account_id)}: ${formatApiError(err, '失败')}`)
      }
    }
    if (submitted.length) {
      message.value = `已提交 ${submitted.length} 个私信同步任务，手机在后台执行。完成后可点「刷新列表」。`
    }
    if (fail.length) error.value = fail.join('；')
  } finally {
    syncing.value = false
  }
}

async function refreshThreads() {
  error.value = ''
  try {
    await loadThreads()
    message.value = '已刷新私信列表'
  } catch {
    error.value = '刷新私信列表失败'
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
    await loadFleet()
    const qAccount = route.query.account_id as string | undefined
    if (qAccount && accounts.value.some((a) => a.account_id === qAccount)) {
      accountId.value = qAccount
    }
    await loadThreads()
  } catch {
    error.value = '加载账号失败'
  }
})
</script>

<template>
  <section>
    <h1>私信</h1>
    <SubmittedTasksBanner
      :tasks="submittedTasks"
      :status-text="statusText"
      :is-running="isRunning"
      @dismiss="dismiss"
    />
    <div class="panel">
      <label>
        账号
        <select v-model="accountId" @change="onAccountChange">
          <option value="">全部账号</option>
          <option
            v-for="a in accounts"
            :key="a.account_id"
            :value="a.account_id"
            :disabled="!runnable(a)"
          >
            {{ optionLabel(a) }}
          </option>
        </select>
      </label>
      <div class="row">
        <button type="button" :disabled="!accountId || syncing" @click="syncInbox">
          {{ syncing ? '提交中…' : '同步最新私信' }}
        </button>
        <button type="button" class="ghost" @click="refreshThreads">刷新列表</button>
      </div>
      <button type="button" class="secondary-block" :disabled="syncing" @click="syncAllRunnable">
        {{ syncing ? '提交中…' : '同步全部可用账号' }}
      </button>
      <p class="hint">
        同步立刻返回，不等待手机跑完。进度看上方任务条或「任务」页；完成后点「刷新列表」看最新会话。
      </p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <table v-if="threads.length">
      <thead>
        <tr>
          <th v-if="showAllAccounts" style="width: 14%">所属账号</th>
          <th style="width: 16%">对方</th>
          <th>最近对话预览</th>
          <th style="width: 8%">未读</th>
          <th style="width: 18%"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in threads" :key="t.thread_id">
          <td v-if="showAllAccounts" class="account">{{ accountName(t.account_id) }}</td>
          <td class="title">{{ t.title_summary }}</td>
          <td class="preview">{{ t.preview_summary || '（暂无正文，可点同步最新私信拉取）' }}</td>
          <td>{{ t.unread ? '是' : '否' }}</td>
          <td class="row-actions">
            <router-link
              class="link"
              :to="{ path: `/inbox/${t.thread_id}`, query: { account_id: t.account_id } }"
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
.row { display: flex; flex-wrap: wrap; gap: 10px; }
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
button.ghost {
  background: #fff;
  color: #2d6a4f;
  border: 1px solid #2d6a4f;
}
button.secondary {
  background: #fff;
  color: #2d6a4f;
  border: 1px solid #2d6a4f;
  padding: 6px 10px;
  font-size: 0.85rem;
  align-self: auto;
}
button.secondary-block {
  background: #344054;
  color: #fff;
  border: 0;
  font-weight: 600;
  padding: 10px 12px;
  align-self: flex-start;
}
.row-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; vertical-align: top; }
th { background: #f3f4f6; }
.account { color: #344054; white-space: nowrap; }
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
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
