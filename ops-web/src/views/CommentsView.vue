<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/lib/api'
import { formatApiError } from '@/lib/apiError'
import { useFleetAccounts } from '@/composables/useFleetAccounts'
import { accountDisplayName } from '@/lib/labels'
import { useSubmittedTasks } from '@/composables/useSubmittedTasks'
import SubmittedTasksBanner from '@/components/SubmittedTasksBanner.vue'

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
type CommentNode = Comment & { children: CommentNode[] }

const route = useRoute()
const { accounts, loadFleet, optionLabel, runnable } = useFleetAccounts()
const notes = ref<Note[]>([])
const comments = ref<Comment[]>([])
const accountId = ref('')
const noteId = ref('')
const replyDrafts = ref<Record<string, string>>({})
const collapsed = ref<Record<string, boolean>>({})
const newCommentText = ref('')
const message = ref('')
const error = ref('')
const syncingNotes = ref(false)
const syncingComments = ref(false)
const postingComment = ref(false)
const replyingId = ref<string | null>(null)
const { tasks: submittedTasks, track, dismiss, statusText, isRunning } = useSubmittedTasks()

const filteredNotes = computed(() =>
  accountId.value ? notes.value.filter((n) => n.account_id === accountId.value) : notes.value,
)

const selectedNote = computed(() =>
  filteredNotes.value.find((n) => n.note_id === noteId.value) || null,
)

const commentForest = computed((): CommentNode[] => {
  const sorted = [...comments.value].sort((a, b) => (a.sort_index ?? 0) - (b.sort_index ?? 0))
  const nodes = new Map<string, CommentNode>()
  for (const c of sorted) {
    nodes.set(c.comment_id, { ...c, children: [] })
  }
  const roots: CommentNode[] = []
  for (const c of sorted) {
    const node = nodes.get(c.comment_id)!
    const parentId = c.parent_node_id
    if (parentId && nodes.has(parentId) && parentId !== c.comment_id) {
      nodes.get(parentId)!.children.push(node)
    } else {
      roots.push(node)
    }
  }
  return roots
})

function toggleReplies(commentId: string) {
  collapsed.value[commentId] = !collapsed.value[commentId]
}

function accountName(id: string): string {
  return accountDisplayName(accounts.value.find((a) => a.account_id === id))
}

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
  const selected = accounts.value.find((a) => a.account_id === accountId.value)
  if (selected && !runnable(selected)) {
    error.value = '该账号设备不可用（离线或无障碍未开），请先到「设备」处理。'
    return
  }
  syncingNotes.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>('/api/v1/notes/sync', {
      method: 'POST',
      body: JSON.stringify({ account_id: accountId.value }),
    })
    track(task.task_id, `同步笔记 · ${accountName(accountId.value)}`)
    message.value = '已提交同步笔记任务，手机在后台执行。完成后可点「刷新列表」。'
  } catch (err) {
    error.value = formatApiError(err, '同步笔记失败')
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
    const title = selectedNote.value?.title_summary || '当前笔记'
    track(task.task_id, `同步评论 · ${title}`)
    message.value = '已提交同步评论任务，手机在后台执行。完成后可点「刷新评论」。'
  } catch (err) {
    error.value = formatApiError(err, '同步失败')
  } finally {
    syncingComments.value = false
  }
}

async function refreshNotes() {
  error.value = ''
  try {
    await loadNotes()
    if (!filteredNotes.value.some((n) => n.note_id === noteId.value)) {
      noteId.value = filteredNotes.value[0]?.note_id || ''
    }
    message.value = '已刷新笔记列表'
  } catch {
    error.value = '刷新笔记失败'
  }
}

async function refreshComments() {
  error.value = ''
  try {
    await loadComments()
    message.value = '已刷新评论'
  } catch {
    error.value = '刷新评论失败'
  }
}

async function postNewComment() {
  const text = newCommentText.value.trim()
  if (!text || !noteId.value) return
  postingComment.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>(`/api/v1/notes/${noteId.value}/comments`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    })
    track(task.task_id, '发表评论')
    message.value = '已提交发表评论任务，手机在后台执行。'
    newCommentText.value = ''
  } catch (err) {
    error.value = formatApiError(err, '发表评论失败')
  } finally {
    postingComment.value = false
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
    track(task.task_id, '回复评论')
    message.value = '已提交回复任务，手机在后台执行。'
    replyDrafts.value[commentId] = ''
  } catch (err) {
    error.value = formatApiError(err, '回复失败')
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
    await loadFleet()
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
    <SubmittedTasksBanner
      :tasks="submittedTasks"
      :status-text="statusText"
      :is-running="isRunning"
      @dismiss="dismiss"
    />
    <div class="panel">
      <label>
        账号
        <select v-model="accountId">
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
          {{ syncingNotes ? '提交中…' : '同步我的笔记' }}
        </button>
        <button type="button" class="ghost control" :disabled="!accountId" @click="refreshNotes">
          刷新列表
        </button>
      </div>
      <div v-if="selectedNote" class="stats">
        <span>点赞 {{ selectedNote.like_count ?? '—' }}</span>
        <span>收藏 {{ selectedNote.collect_count ?? '—' }}</span>
        <span v-if="selectedNote.read_count != null">阅读 {{ selectedNote.read_count }}</span>
      </div>
      <div class="row">
        <button type="button" :disabled="!noteId || syncingComments" @click="syncComments">
          {{ syncingComments ? '提交中…' : '同步最新评论' }}
        </button>
        <button type="button" class="ghost" :disabled="!noteId" @click="refreshComments">
          刷新评论
        </button>
      </div>
      <form v-if="noteId" class="post-form" @submit.prevent="postNewComment">
        <label>
          发表新评论（自己评论）
          <textarea
            v-model="newCommentText"
            rows="2"
            maxlength="4000"
            placeholder="输入要发到该笔记下的评论…"
          />
        </label>
        <button type="submit" :disabled="postingComment || !newCommentText.trim()">
          {{ postingComment ? '提交中…' : '发表评论' }}
        </button>
      </form>
      <p class="hint">
        同步/发表会立刻返回，手机在后台执行。进度看上方任务条或「任务」页；完成后点刷新看最新数据。
      </p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <div v-if="commentForest.length" class="comments">
      <template v-for="root in commentForest" :key="root.comment_id">
        <article class="card">
          <header>
            <div class="who">
              <strong>{{ root.author_summary }}</strong>
              <span v-if="root.reply_to_author" class="reply-to">回复 @{{ root.reply_to_author }}</span>
            </div>
            <span class="time">{{ root.posted_at_text || '—' }}</span>
          </header>
          <p class="body">{{ root.body_summary }}</p>
          <form class="reply-form" @submit.prevent="replyComment(root.comment_id)">
            <textarea
              v-model="replyDrafts[root.comment_id]"
              rows="2"
              :placeholder="`回复 @${root.author_summary}…`"
              maxlength="4000"
            />
            <button type="submit" :disabled="replyingId === root.comment_id">
              {{ replyingId === root.comment_id ? '提交中…' : '回复' }}
            </button>
          </form>
          <button
            v-if="root.children.length"
            type="button"
            class="toggle"
            @click="toggleReplies(root.comment_id)"
          >
            {{ collapsed[root.comment_id] ? `展开 ${root.children.length} 条回复` : `收起回复（${root.children.length}）` }}
          </button>
        </article>
        <template v-if="root.children.length && !collapsed[root.comment_id]">
          <article
            v-for="child in root.children"
            :key="child.comment_id"
            class="card reply"
          >
            <header>
              <div class="who">
                <strong>{{ child.author_summary }}</strong>
                <span class="reply-to">
                  回复 @{{ child.reply_to_author || root.author_summary }}
                </span>
              </div>
              <span class="time">{{ child.posted_at_text || '—' }}</span>
            </header>
            <p class="body">{{ child.body_summary }}</p>
            <form class="reply-form" @submit.prevent="replyComment(child.comment_id)">
              <textarea
                v-model="replyDrafts[child.comment_id]"
                rows="2"
                :placeholder="`回复 @${child.author_summary}…`"
                maxlength="4000"
              />
              <button type="submit" :disabled="replyingId === child.comment_id">
                {{ replyingId === child.comment_id ? '提交中…' : '回复' }}
              </button>
            </form>
          </article>
        </template>
      </template>
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
.post-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 8px;
  border-top: 1px solid #e5e7eb;
}
.row { display: flex; gap: 10px; flex-wrap: wrap; }
.align-end { align-items: flex-end; }
.grow { flex: 1; min-width: 0; }
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
.control, select, button, textarea { padding: 10px 12px; border-radius: 8px; font: inherit; box-sizing: border-box; }
select.control, button.control { min-height: 42px; }
select, textarea { border: 1px solid #d0d5dd; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; align-self: flex-start; }
button.secondary { background: #344054; white-space: nowrap; flex-shrink: 0; }
button.ghost {
  background: #fff;
  color: #2d6a4f;
  border: 1px solid #2d6a4f;
  white-space: nowrap;
}
button:disabled { opacity: 0.6; cursor: not-allowed; }
button.toggle {
  background: transparent;
  color: #2d6a4f;
  padding: 4px 0;
  font-size: 0.88rem;
  font-weight: 600;
}
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
.hint { color: #5c6770; margin: 0; font-size: 0.88rem; font-weight: 400; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
