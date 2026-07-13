<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '@/lib/api'

type Lead = {
  lead_id: string
  thread_id: string
  status: string
  note: string | null
  updated_at: string
}

const STATUS_LABEL: Record<string, string> = {
  new: '新线索',
  warm: '跟进中',
  hot: '高意向',
  closed: '已关闭',
}

const rows = ref<Lead[]>([])
const draftNotes = ref<Record<string, string>>({})
const error = ref('')
const savingId = ref<string | null>(null)

async function load() {
  rows.value = await api<Lead[]>('/api/v1/inbox/leads')
}

async function markLead(threadId: string, status: string) {
  savingId.value = threadId
  error.value = ''
  try {
    await api('/api/v1/inbox/leads', {
      method: 'POST',
      body: JSON.stringify({
        thread_id: threadId,
        status,
        note: draftNotes.value[threadId]?.trim() || undefined,
      }),
    })
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '更新线索失败'
  } finally {
    savingId.value = null
  }
}

onMounted(() => {
  load().catch(() => {
    error.value = '加载线索失败'
  })
})
</script>

<template>
  <section>
    <h1>线索</h1>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-if="rows.length">
      <thead>
        <tr>
          <th>会话</th>
          <th>状态</th>
          <th>备注</th>
          <th>更新时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.lead_id">
          <td>
            <router-link class="link" :to="`/inbox/${row.thread_id}`">
              {{ row.thread_id }}
            </router-link>
          </td>
          <td>{{ STATUS_LABEL[row.status] || row.status }}</td>
          <td>
            <input v-model="draftNotes[row.thread_id]" :placeholder="row.note || '添加备注…'" />
          </td>
          <td>{{ row.updated_at }}</td>
          <td class="actions">
            <button
              v-for="s in ['new', 'warm', 'hot', 'closed']"
              :key="s"
              type="button"
              :disabled="savingId === row.thread_id"
              @click="markLead(row.thread_id, s)"
            >
              {{ STATUS_LABEL[s] }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="empty">暂无线索记录。</p>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; vertical-align: top; }
th { background: #f3f4f6; }
input { width: 100%; padding: 8px 10px; border: 1px solid #d0d5dd; border-radius: 8px; font: inherit; }
.actions { display: flex; flex-wrap: wrap; gap: 6px; }
button {
  background: #2d6a4f;
  color: #fff;
  border: 0;
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 0.85rem;
  cursor: pointer;
}
button:disabled { opacity: 0.6; cursor: not-allowed; }
.link { color: #2d6a4f; font-weight: 600; text-decoration: none; }
.error { color: #b42318; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
</style>
