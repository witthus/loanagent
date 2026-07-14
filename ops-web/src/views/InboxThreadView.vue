<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { useFleetAccounts } from '@/composables/useFleetAccounts'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

type ThreadMeta = {
  thread_id: string
  account_id: string
  title_summary: string
  preview_summary: string | null
  unread: boolean
}

type Message = {
  message_id: string
  thread_id: string
  sender_summary: string | null
  body_summary: string | null
  created_at: string
  posted_at_text?: string | null
  sort_index?: number
}

const route = useRoute()
const { accountNames, loadFleet } = useFleetAccounts()
const threadId = computed(() => route.params.threadId as string)
const accountId = ref((route.query.account_id as string) || '')
const thread = ref<ThreadMeta | null>(null)
const messages = ref<Message[]>([])
const replyText = ref('')
const message = ref('')
const error = ref('')
const sending = ref(false)
const opening = ref(false)
const { status, errorCode, poll } = useTaskPoll()

const accountLabel = computed(() => {
  const id = accountId.value || thread.value?.account_id
  if (!id) return '未知账号'
  return accountNames.value[id] || '未命名账号'
})

const peerTitle = computed(() => thread.value?.title_summary || '私信会话')

function isOutbound(sender: string | null | undefined): boolean {
  const s = (sender || '').trim()
  if (!s) return false
  const mine = accountLabel.value
  if (s === mine) return true
  // Common self labels from device extractors
  return s === '我' || s === '自己' || s.includes(mine)
}

async function loadThread() {
  thread.value = await api<ThreadMeta>(`/api/v1/inbox/threads/${threadId.value}`)
  if (!accountId.value) accountId.value = thread.value.account_id
}

async function loadMessages() {
  messages.value = await api<Message[]>(`/api/v1/inbox/threads/${threadId.value}/messages`)
}

async function openThreadOnDevice() {
  opening.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(
      `/api/v1/inbox/threads/${threadId.value}/open`,
      { method: 'POST' },
    )
    message.value = '正在打开会话并拉取消息…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '会话消息已更新'
      await loadMessages()
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = formatApiError(err, '打开会话失败')
  } finally {
    opening.value = false
  }
}

async function sendReply() {
  const text = replyText.value.trim()
  if (!text || !accountId.value) return
  sending.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(`/api/v1/inbox/threads/${threadId.value}/reply`, {
      method: 'POST',
      body: JSON.stringify({ account_id: accountId.value, text }),
    })
    message.value = '已提交回复，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '回复已发送'
      replyText.value = ''
      await loadMessages()
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = formatApiError(err, '回复失败')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  try {
    await loadFleet()
    await loadThread()
    await loadMessages()
    if (!messages.value.length) {
      await openThreadOnDevice()
    }
  } catch (err) {
    error.value = formatApiError(err, '加载会话失败')
  }
})
</script>

<template>
  <section>
    <p class="back">
      <router-link :to="{ path: '/inbox', query: accountId ? { account_id: accountId } : {} }">
        ← 返回私信列表
      </router-link>
    </p>
    <header class="page-head">
      <h1>{{ peerTitle }}</h1>
      <p class="meta">
        所属账号：<strong>{{ accountLabel }}</strong>
      </p>
    </header>
    <div class="toolbar">
      <button type="button" class="secondary" :disabled="opening" @click="openThreadOnDevice">
        {{ opening ? '拉取中…' : '从手机打开并同步消息' }}
      </button>
    </div>
    <div class="thread">
      <article
        v-for="m in messages"
        :key="m.message_id"
        class="bubble"
        :class="{ mine: isOutbound(m.sender_summary) }"
      >
        <header>
          <span class="who">{{ isOutbound(m.sender_summary) ? `${accountLabel}（本号）` : (m.sender_summary || '对方') }}</span>
        </header>
        <p>{{ m.body_summary || '（无内容）' }}</p>
        <time class="muted">{{ m.posted_at_text || m.created_at }}</time>
      </article>
      <p v-if="!messages.length && !opening" class="empty">暂无消息，可点上方按钮从手机拉取。</p>
    </div>
    <form class="panel" @submit.prevent="sendReply">
      <label>
        回复内容（将以「{{ accountLabel }}」发送）
        <textarea v-model="replyText" rows="3" maxlength="4000" placeholder="输入回复…" required />
      </label>
      <button type="submit" :disabled="sending || !accountId">
        {{ sending ? '发送中…' : '发送回复' }}
      </button>
      <p v-if="!accountId" class="error">缺少账号信息，请从私信列表进入此会话。</p>
      <p v-if="status" class="muted">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </form>
  </section>
</template>

<style scoped>
.back { margin: 0 0 12px; }
.back a { color: #2d6a4f; text-decoration: none; font-weight: 600; }
.page-head { margin-bottom: 12px; }
.page-head h1 { margin: 0 0 6px; }
.meta { margin: 0; color: #5c6770; }
.toolbar { margin-bottom: 12px; }
.thread { display: flex; flex-direction: column; gap: 10px; margin-bottom: 20px; }
.bubble {
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  max-width: 640px;
  align-self: flex-start;
}
.bubble.mine {
  align-self: flex-end;
  background: #eef6f1;
  border-color: #c5ddd0;
}
.bubble header { font-weight: 600; margin-bottom: 6px; }
.who { font-size: 0.92rem; }
.bubble p { margin: 0 0 6px; }
.empty { color: #5c6770; }
.panel {
  max-width: 560px;
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
textarea, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
textarea { border: 1px solid #d0d5dd; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button.secondary { background: #344054; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.muted { color: #5c6770; margin: 0; font-size: 0.85rem; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
</style>
