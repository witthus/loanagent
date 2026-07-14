<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

type Account = { account_id: string; role: string; display_name?: string | null; platform: string }
type Content = { content_id: string; title: string; platform: string }
type Schedule = {
  schedule_id: string
  account_id: string
  content_id: string
  status: string
}

const accounts = ref<Account[]>([])
const contents = ref<Content[]>([])
const selectedAccountIds = ref<string[]>([])
const contentId = ref('')
const manualEngagement = ref(false)
const message = ref('')
const error = ref('')
const publishing = ref(false)
const { status, errorCode, poll } = useTaskPoll()
const router = useRouter()
const route = useRoute()

const publishers = computed(() =>
  accounts.value.filter((a) => a.role === 'PUBLISHER_MAIN' || a.role === 'PUBLISHER_MATRIX'),
)

watch(
  publishers,
  (list) => {
    if (!selectedAccountIds.value.length && list[0]) {
      selectedAccountIds.value = [list[0].account_id]
    }
  },
  { immediate: true },
)

function toggleAccount(id: string) {
  if (selectedAccountIds.value.includes(id)) {
    selectedAccountIds.value = selectedAccountIds.value.filter((x) => x !== id)
  } else {
    selectedAccountIds.value = [...selectedAccountIds.value, id]
  }
}

onMounted(async () => {
  try {
    ;[accounts.value, contents.value] = await Promise.all([
      api<Account[]>('/api/v1/accounts?platform=xhs'),
      api<Content[]>('/api/v1/content?platform=xhs'),
    ])
    const scheduleId = route.query.schedule_id as string | undefined
    if (scheduleId) {
      try {
        const schedules = await api<Schedule[]>('/api/v1/schedules')
        const hit = schedules.find((s) => s.schedule_id === scheduleId)
        if (hit) {
          selectedAccountIds.value = [hit.account_id]
          contentId.value = hit.content_id
        }
      } catch {
        /* ignore missing schedule endpoint shape */
      }
    }
    if (!contentId.value && contents.value[0]) contentId.value = contents.value[0].content_id
  } catch {
    error.value = '加载账号或内容失败'
  }
})

async function publishNow() {
  error.value = ''
  message.value = ''
  if (!selectedAccountIds.value.length) {
    error.value = '请至少选择一个发帖账号'
    return
  }
  if (!contentId.value) {
    error.value = '请选择素材'
    return
  }
  publishing.value = true
  const results: { account_id: string; status: string; error?: string }[] = []
  try {
    for (const account_id of selectedAccountIds.value) {
      message.value = `正在向 ${account_id} 提交发布…`
      try {
        const task = await api<{ task_id: string }>('/api/v1/publish/immediate', {
          method: 'POST',
          body: JSON.stringify({
            account_id,
            content_id: contentId.value,
            engagement_mode: manualEngagement.value ? 'manual' : 'auto',
          }),
        })
        const final = await poll(task.task_id)
        if (final?.status === 'succeeded') {
          results.push({ account_id, status: 'succeeded' })
          if (manualEngagement.value && selectedAccountIds.value.length === 1) {
            await router.push({ path: '/engagement', query: { arrange: task.task_id } })
            return
          }
        } else {
          const human = humanizeError(final?.error_code || errorCode.value)
          results.push({
            account_id,
            status: 'failed',
            error: `${human.title}。${human.nextStep}`,
          })
        }
      } catch (err) {
        results.push({
          account_id,
          status: 'failed',
          error: err instanceof ApiError ? err.body : '发布失败',
        })
      }
    }
    const ok = results.filter((r) => r.status === 'succeeded').length
    const fail = results.length - ok
    message.value = `发布完成：成功 ${ok}，失败 ${fail}`
    if (fail) {
      error.value = results
        .filter((r) => r.status === 'failed')
        .map((r) => `${r.account_id}: ${r.error}`)
        .join('；')
    }
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <section>
    <h1>发笔记</h1>
    <form class="form" @submit.prevent="publishNow">
      <fieldset>
        <legend>发帖账号（可多选，将依次发布到各绑定设备）</legend>
        <label v-for="a in publishers" :key="a.account_id" class="check">
          <input
            type="checkbox"
            :checked="selectedAccountIds.includes(a.account_id)"
            @change="toggleAccount(a.account_id)"
          />
          {{ a.display_name?.trim() || '未命名账号' }}（{{
            a.role === 'PUBLISHER_MAIN' ? '主号' : '矩阵号'
          }}）
        </label>
      </fieldset>
      <label>
        选用素材
        <select v-model="contentId" required>
          <option v-for="c in contents" :key="c.content_id" :value="c.content_id">
            {{ c.title }}
          </option>
        </select>
      </label>
      <label class="check">
        <input v-model="manualEngagement" type="checkbox" />
        这是重要笔记，发布后手动编排互动（仅单账号时跳转）
      </label>
      <button type="submit" :disabled="publishing || !selectedAccountIds.length">
        {{ publishing ? '发布中…' : '立即发布' }}
      </button>
      <p v-if="status">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">没有素材？先去「内容库」保存一篇。多账号时按顺序逐台手机执行。</p>
    </form>
  </section>
</template>

<style scoped>
.form {
  max-width: 560px;
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
fieldset {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
legend { font-weight: 600; padding: 0 4px; }
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
.check { flex-direction: row; align-items: center; font-weight: 500; gap: 8px; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.hint { color: #5c6770; margin: 0; }
</style>
