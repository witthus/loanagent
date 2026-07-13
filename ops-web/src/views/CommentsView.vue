<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

type Account = { account_id: string; role: string; display_name?: string | null; platform: string }
type Note = {
  note_id: string
  account_id: string
  title_summary: string | null
  xhs_hint: string | null
}
type Comment = {
  comment_id: string
  note_id: string
  author_summary: string
  body_summary: string
  locator_hint: string | null
}

const route = useRoute()
const accounts = ref<Account[]>([])
const notes = ref<Note[]>([])
const comments = ref<Comment[]>([])
const accountId = ref('')
const noteId = ref('')
const replyDrafts = ref<Record<string, string>>({})
const message = ref('')
const error = ref('')
const syncing = ref(false)
const replyingId = ref<string | null>(null)
const { status, errorCode, poll } = useTaskPoll()

const filteredNotes = computed(() =>
  accountId.value ? notes.value.filter((n) => n.account_id === accountId.value) : notes.value,
)

async function loadNotes() {
  notes.value = await api<Note[]>('/api/v1/notes')
}

async function loadComments() {
  if (!noteId.value) {
    comments.value = []
    return
  }
  comments.value = await api<Comment[]>(`/api/v1/notes/${noteId.value}/comments`)
}

async function syncComments() {
  if (!noteId.value) return
  syncing.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(`/api/v1/notes/${noteId.value}/sync-comments`, {
      method: 'POST',
    })
    message.value = '已提交同步，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '评论已同步'
      await loadComments()
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

async function replyComment(commentId: string) {
  const text = (replyDrafts.value[commentId] || '').trim()
  if (!text) return
  replyingId.value = commentId
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(`/api/v1/comments/${commentId}/reply`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    })
    message.value = '已提交回复，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '回复已发送'
      replyDrafts.value[commentId] = ''
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    if (err instanceof ApiError && err.body.includes('COMPLIANCE_REJECTED')) {
      error.value = '回复内容未通过合规检查，请改写后再试。'
    } else {
      error.value = err instanceof ApiError ? err.body : '回复失败'
    }
  } finally {
    replyingId.value = null
  }
}

watch(accountId, () => {
  const stillValid = filteredNotes.value.some((n) => n.note_id === noteId.value)
  if (!stillValid && filteredNotes.value[0]) {
    noteId.value = filteredNotes.value[0].note_id
  }
})

watch(noteId, () => {
  loadComments().catch(() => {
    error.value = '加载评论失败'
  })
})

onMounted(async () => {
  try {
    accounts.value = await api<Account[]>('/api/v1/accounts?platform=xhs')
    await loadNotes()
    const qAccount = route.query.account_id as string | undefined
    const qNote = route.query.note_id as string | undefined
    if (qAccount && accounts.value.some((a) => a.account_id === qAccount)) {
      accountId.value = qAccount
    } else if (accounts.value[0]) {
      accountId.value = accounts.value[0].account_id
    }
    if (qNote && notes.value.some((n) => n.note_id === qNote)) {
      noteId.value = qNote
    } else if (filteredNotes.value[0]) {
      noteId.value = filteredNotes.value[0].note_id
    }
    if (noteId.value) await loadComments()
  } catch {
    error.value = '加载账号或笔记失败'
  }
})
</script>

<template>
  <section>
    <h1>评论</h1>
    <div class="panel">
      <label>
        账号
        <select v-model="accountId">
          <option v-for="a in accounts" :key="a.account_id" :value="a.account_id">
            {{ a.display_name || a.account_id }}
          </option>
        </select>
      </label>
      <label>
        已发笔记
        <select v-model="noteId">
          <option v-for="n in filteredNotes" :key="n.note_id" :value="n.note_id">
            {{ n.title_summary || n.note_id }}
          </option>
        </select>
      </label>
      <button type="button" :disabled="!noteId || syncing" @click="syncComments">
        {{ syncing ? '同步中…' : '同步最新评论' }}
      </button>
      <p v-if="status" class="muted">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <div v-if="comments.length" class="comments">
      <article v-for="c in comments" :key="c.comment_id" class="card">
        <header>
          <strong>{{ c.author_summary }}</strong>
          <span v-if="c.locator_hint" class="muted">{{ c.locator_hint }}</span>
        </header>
        <p class="body">{{ c.body_summary }}</p>
        <form class="reply" @submit.prevent="replyComment(c.comment_id)">
          <textarea
            v-model="replyDrafts[c.comment_id]"
            rows="2"
            placeholder="输入回复内容…"
            maxlength="4000"
          />
          <button type="submit" :disabled="replyingId === c.comment_id">
            {{ replyingId === c.comment_id ? '发送中…' : '回复' }}
          </button>
        </form>
      </article>
    </div>
    <p v-else-if="noteId" class="empty">暂无评论，可点「同步最新评论」从手机拉取。</p>
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
select, button, textarea { padding: 10px 12px; border-radius: 8px; font: inherit; }
select, textarea { border: 1px solid #d0d5dd; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.comments { display: flex; flex-direction: column; gap: 12px; }
.card {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  border: 1px solid #e5e7eb;
}
.card header { display: flex; justify-content: space-between; gap: 12px; }
.body { margin: 8px 0 12px; color: #1f2329; }
.reply { display: flex; flex-direction: column; gap: 8px; }
.muted { color: #5c6770; margin: 0; font-size: 0.9rem; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
