<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '@/lib/api'
import { DEFAULT_PLATFORM } from '@/platform'

type Content = {
  content_id: string
  title: string
  body: string
  sensitivity_status: string
  platform: string
}

const rows = ref<Content[]>([])
const title = ref('')
const body = ref('')
const message = ref('')
const error = ref('')
const saving = ref(false)

async function load() {
  rows.value = await api<Content[]>('/api/v1/content?platform=xhs')
}

async function createContent() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await api('/api/v1/content', {
      method: 'POST',
      body: JSON.stringify({
        title: title.value.trim(),
        body: body.value.trim(),
        platform: DEFAULT_PLATFORM,
      }),
    })
    title.value = ''
    body.value = ''
    message.value = '已保存到内容库'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load().catch(() => {
    error.value = '加载内容库失败'
  })
})
</script>

<template>
  <section>
    <h1>内容库</h1>
    <form class="form" @submit.prevent="createContent">
      <label>标题<input v-model="title" required maxlength="200" /></label>
      <label>正文<textarea v-model="body" required rows="5" /></label>
      <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存素材' }}</button>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </form>
    <table>
      <thead>
        <tr><th>标题</th><th>敏感词</th><th>平台</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.content_id">
          <td>{{ row.title }}</td>
          <td>{{ row.sensitivity_status === 'clean' ? '通过' : '需改写' }}</td>
          <td>{{ row.platform }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.form {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
input, textarea { padding: 10px 12px; border: 1px solid #d0d5dd; border-radius: 8px; font: inherit; }
button { align-self: flex-start; background: #2d6a4f; color: #fff; border: 0; border-radius: 8px; padding: 10px 16px; font-weight: 600; cursor: pointer; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
</style>
