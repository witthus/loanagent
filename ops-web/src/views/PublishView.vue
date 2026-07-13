<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, ApiError } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { useTaskPoll } from '@/composables/useTaskPoll'

type Account = { account_id: string; role: string; display_name?: string | null; platform: string }
type Content = { content_id: string; title: string; platform: string }

const accounts = ref<Account[]>([])
const contents = ref<Content[]>([])
const accountId = ref('')
const contentId = ref('')
const manualEngagement = ref(false)
const message = ref('')
const error = ref('')
const { status, errorCode, poll } = useTaskPoll()
const router = useRouter()

const publishers = computed(() =>
  accounts.value.filter((a) => a.role === 'PUBLISHER_MAIN' || a.role === 'PUBLISHER_MATRIX'),
)

onMounted(async () => {
  try {
    ;[accounts.value, contents.value] = await Promise.all([
      api<Account[]>('/api/v1/accounts?platform=xhs'),
      api<Content[]>('/api/v1/content?platform=xhs'),
    ])
    if (publishers.value[0]) accountId.value = publishers.value[0].account_id
    if (contents.value[0]) contentId.value = contents.value[0].content_id
  } catch {
    error.value = '加载账号或内容失败'
  }
})

async function publishNow() {
  error.value = ''
  message.value = ''
  try {
    const task = await api<{ task_id: string }>('/api/v1/publish/immediate', {
      method: 'POST',
      body: JSON.stringify({
        account_id: accountId.value,
        content_id: contentId.value,
        engagement_mode: manualEngagement.value ? 'manual' : 'auto',
      }),
    })
    message.value = '已提交发布，正在等待手机执行…'
    const final = await poll(task.task_id)
    if (final?.status === 'succeeded') {
      message.value = '发布成功'
      if (manualEngagement.value) {
        await router.push({ path: '/engagement', query: { arrange: task.task_id } })
      }
    } else {
      const human = humanizeError(final?.error_code || errorCode.value)
      error.value = `${human.title}。${human.nextStep}`
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '发布失败'
  }
}
</script>

<template>
  <section>
    <h1>发笔记</h1>
    <form class="form" @submit.prevent="publishNow">
      <label>
        发帖账号
        <select v-model="accountId" required>
          <option v-for="a in publishers" :key="a.account_id" :value="a.account_id">
            {{ a.display_name || a.account_id }}（{{ a.role }}）
          </option>
        </select>
      </label>
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
        这是重要笔记，发布后手动编排互动
      </label>
      <button type="submit">立即发布</button>
      <p v-if="status">任务状态：{{ status }}</p>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">没有素材？先去「内容库」保存一篇。</p>
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
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
.check { flex-direction: row; align-items: center; font-weight: 500; }
select, button { padding: 10px 12px; border-radius: 8px; font: inherit; }
button { background: #2d6a4f; color: #fff; border: 0; font-weight: 600; cursor: pointer; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.hint { color: #5c6770; margin: 0; }
</style>
