<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

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
const threadId = computed(() => route.params.threadId as string)
const accountId = computed(() => (route.query.account_id as string) || '')
const messages = ref<Message[]>([])
const replyText = ref('')
const message = ref('')
const error = ref('')
const sending = ref(false)
const opening = ref(false)
const { status, errorCode, poll } = useTaskPoll()

function parseErrorCode(body: string): string | null {
  try {
    const json = JSON.parse(body)
    return json?.detail?.code ?? null
  } catch {
    return null
  }
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
    error.value = err instanceof ApiError ? err.body : '打开会话失败'
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
    if (err instanceof ApiError) {
      const code = parseErrorCode(err.body)
      if (code === 'CONTACT_FORBIDDEN') {
        error.value = '不能直接在私信里写微信号或手机号，请用平台内私信沟通。'
      } else {
        error.value = err.body
      }
    } else {
      error.value = '回复失败'
    }
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  try {
    await loadMessages()
    if (!messages.value.length) {
      await openThreadOnDevice()
    }
  } catch {
    error.value = '加载消息失败'
  }
})
</script>

<template>
  <section>
    <p class="back">
      <router-link to="/inbox">← 返回私信列表</router-link>
    </p>
    <h1>私信会话</h1>
    <div class="toolbar">
      <button type="button" class="secondary" :disabled="opening" @click="openThreadOnDevice">
        {{ opening ? '拉取中…' : '从手机打开并同步消息' }}
      </button>
    </div>
    <div class="thread">
      <article v-for="m in messages" :key="m.message_id" class="bubble">
        <header>{{ m.sender_summary || '对方' }}</header>
        <p>{{ m.body_summary || '（无内容）' }}</p>
        <time class="muted">{{ m.posted_at_text || m.created_at }}</time>
      </article>
      <p v-if="!messages.length && !opening" class="empty">暂无消息，可点上方按钮从手机拉取。</p>
    </div>
    <form class="panel" @submit.prevent="sendReply">
      <label>
        回复内容
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
.toolbar { margin-bottom: 12px; }
.thread { display: flex; flex-direction: column; gap: 10px; margin-bottom: 20px; }
.bubble {
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
}
.bubble header { font-weight: 600; margin-bottom: 6px; }
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
