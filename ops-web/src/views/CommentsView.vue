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
  like_count?: number | null
  collect_count?: number | null
  read_count?: number | null
}
type Comment = {
  comment_id: string
  note_id: string
  author_summary: string
  body_summary: string
  locator_hint: string | null
  parent_node_id?: string | null
  root_node_id?: string | null
  depth?: number
  posted_at_text?: string | null
  reply_to_author?: string | null
  sort_index?: number
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
const syncingNotes = ref(false)
const syncingComments = ref(false)
const replyingId = ref<string | null>(null)
const { status, errorCode, poll } = useTaskPoll()

const filteredNotes = computed(() =>
  accountId.value ? notes.value.filter((n) => n.account_id === accountId.value) : notes.value,
)

const selectedNote = computed(() =>
  filteredNotes.value.find((n) => n.note_id === noteId.value) || null,
)

const orderedComments = computed(() =>
  [...comments.value].sort((a, b) => (a.sort_index ?? 0) - (b.sort_index ?? 0)),
)

async function loadNotes() {
  const q = accountId.value
    ? `/api/v1/notes?account_id=${encodeURIComponent(accountId.value)}`
    : '/api/v1/notes'
  notes.value = await api<Note[]>(q)
}

async function loadComments() {
  if (!noteId.value) {
    comments.value = []
    return
  }
  comments.value = await api<Comment[]>(`/api/v1/notes/${noteId.value}/comments`)
}

async function syncNotes() {
  if (!accountId.value) return
  syncingNotes.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>('/api/v1/notes/sync', {
      method: 'POST',
      body: JSON.stringify({ account_id: accountId.value }),
    })
    message.value = '已提交同步我的笔记，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '笔记列表已与手机对账更新'
      await loadNotes()
      if (!filteredNotes.value.some((n) => n.note_id === noteId.value)) {
        noteId.value = filteredNotes.value[0]?.note_id || ''
      }
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '同步笔记失败'
  } finally {
    syncingNotes.value = false
  }
}

async function syncComments() {
  if (!noteId.value) return
  syncingComments.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(`/api/v1/notes/${noteId.value}/sync-comments`, {
      method: 'POST',
    })
    message.value = '已提交同步评论，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '评论已与手机对账更新'
      await loadComments()
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '同步失败'
  } finally {
    syncingComments.value = false
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

watch(accountId, async () => {
  try {
    await loadNotes()
    const stillValid = filteredNotes.value.some((n) => n.note_id === noteId.value)
    if (!stillValid) {
      noteId.value = filteredNotes.value[0]?.note_id || ''
    }
  } catch {
    error.value = '加载笔记失败'
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
    const qAccount = route.query.account_id as string | undefined
    const qNote = route.query.note_id as string | undefined
    if (qAccount && accounts.value.some((a) => a.account_id === qAccount)) {
      accountId.value = qAccount
    } else if (accounts.value[0]) {
      accountId.value = accounts.value[0].account_id
    }
    await loadNotes()
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
      <div class="row align-end">
        <label class="grow">
          已发笔记（数据库缓存）
          <select v-model="noteId" class="control">
            <option v-if="!filteredNotes.length" value="">暂无笔记，请先同步</option>
            <option v-for="n in filteredNotes" :key="n.note_id" :value="n.note_id">
              {{ n.title_summary || n.note_id }}
            </option>
          </select>
        </label>
        <button
          type="button"
          class="secondary control"
          :disabled="!accountId || syncingNotes"
          @click="syncNotes"
        >
          {{ syncingNotes ? '同步笔记中…' : '同步我的笔记' }}
        </button>
      </div>
      <div v-if="selectedNote" class="stats">
        <span>点赞 {{ selectedNote.like_count ?? '—' }}</span>
        <span>收藏 {{ selectedNote.collect_count ?? '—' }}</span>
        <span v-if="selectedNote.read_count != null">阅读 {{ selectedNote.read_count }}</span>
      </div>
      <button type="button" :disabled="!noteId || syncingComments" @click="syncComments">
        {{ syncingComments ? '同步评论中…' : '同步最新评论' }}
      </button>
      <p class="hint">默认显示数据库缓存；同步会与手机对账，并保留评论层级与时间。</p>
      <p v-if="status" class="muted">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <div v-if="orderedComments.length" class="comments">
      <article
        v-for="c in orderedComments"
        :key="c.comment_id"
        class="card"
        :class="{ reply: (c.depth || 0) > 0 }"
      >
        <header>
          <div class="who">
            <strong>{{ c.author_summary }}</strong>
            <span v-if="c.reply_to_author" class="reply-to">回复 @{{ c.reply_to_author }}</span>
          </div>
          <span class="time">{{ c.posted_at_text || '—' }}</span>
        </header>
        <p class="body">{{ c.body_summary }}</p>
        <form class="reply-form" @submit.prevent="replyComment(c.comment_id)">
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
    <p v-else-if="noteId" class="empty">暂无评论，可点「同步最新评论」从手机拉取并对账。</p>
  </section>
</template>

<style scoped>
.panel {
  max-width: 720px;
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 20px;
}
.row { display: flex; gap: 10px; }
.align-end { align-items: flex-end; }
.grow { flex: 1; min-width: 0; }
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
.control, select, button, textarea { padding: 10px 12px; border-radius: 8px; font: inherit; box-sizing: border-box; }
select.control, button.control { min-height: 42px; }
select, textarea { border: 1px solid #d0d5dd; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button.secondary { background: #344054; white-space: nowrap; flex-shrink: 0; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.stats { display: flex; gap: 16px; color: #344054; font-size: 0.95rem; }
.comments { display: flex; flex-direction: column; gap: 10px; max-width: 720px; }
.card {
  background: #fff;
  border-radius: 12px;
  padding: 16px 18px;
  border: 1px solid #e5e7eb;
}
.card.reply {
  margin-left: 28px;
  border-left: 3px solid #b7c4bc;
  background: #f8faf9;
}
.card header { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
.who { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
.reply-to { color: #5c6770; font-size: 0.88rem; font-weight: 500; }
.time { color: #5c6770; font-size: 0.88rem; white-space: nowrap; }
.body { margin: 8px 0 12px; color: #1f2329; }
.reply-form { display: flex; flex-direction: column; gap: 8px; }
.muted { color: #5c6770; margin: 0; font-size: 0.9rem; }
.hint { color: #5c6770; margin: 0; font-size: 0.88rem; font-weight: 400; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
