<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from '@/lib/api'
import { mediaPreviewUrl } from '@/lib/labels'
import { DEFAULT_PLATFORM } from '@/platform'

type Content = {
  content_id: string
  title: string
  body: string
  media_ids: string[]
  sensitivity_status: string
  platform: string
}

const PAGE_SIZE = 10
const rows = ref<Content[]>([])
const editingId = ref<string | null>(null)
const title = ref('')
const body = ref('')
const mediaIds = ref<string[]>([])
const uploading = ref(false)
const message = ref('')
const error = ref('')
const saving = ref(false)
const page = ref(1)
const previewIndex = ref<number | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(rows.value.length / PAGE_SIZE)))
const pageRows = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return rows.value.slice(start, start + PAGE_SIZE)
})

async function load() {
  rows.value = await api<Content[]>('/api/v1/content?platform=xhs')
  if (page.value > totalPages.value) page.value = totalPages.value
}

function resetForm() {
  editingId.value = null
  title.value = ''
  body.value = ''
  mediaIds.value = []
  previewIndex.value = null
}

function startEdit(row: Content) {
  editingId.value = row.content_id
  title.value = row.title
  body.value = row.body
  mediaIds.value = [...(row.media_ids || [])]
  previewIndex.value = null
  message.value = ''
  error.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function uploadFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files?.length) return
  uploading.value = true
  error.value = ''
  try {
    for (const file of Array.from(files)) {
      const form = new FormData()
      form.append('file', file)
      const media = await api<{ media_id: string }>('/api/v1/media', {
        method: 'POST',
        body: form,
      })
      mediaIds.value = [...mediaIds.value, media.media_id]
    }
    message.value = `已上传 ${files.length} 张图片`
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '图片上传失败'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function removeMedia(id: string) {
  mediaIds.value = mediaIds.value.filter((m) => m !== id)
}

async function saveContent() {
  saving.value = true
  error.value = ''
  message.value = ''
  const payload = {
    title: title.value.trim(),
    body: body.value.trim(),
    media_ids: mediaIds.value,
    platform: DEFAULT_PLATFORM,
  }
  try {
    if (editingId.value) {
      await api(`/api/v1/content/${editingId.value}`, {
        method: 'PATCH',
        body: JSON.stringify(payload),
      })
      message.value = '素材已更新'
    } else {
      await api('/api/v1/content', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      message.value = '已保存到内容库'
    }
    resetForm()
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '保存失败'
  } finally {
    saving.value = false
  }
}

async function deleteContent(row: Content) {
  if (!window.confirm(`确认删除素材「${row.title}」？`)) return
  error.value = ''
  message.value = ''
  try {
    await api(`/api/v1/content/${row.content_id}`, { method: 'DELETE' })
    if (editingId.value === row.content_id) resetForm()
    message.value = '已删除'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.body : '删除失败'
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
    <form class="form" @submit.prevent="saveContent">
      <h2>{{ editingId ? '编辑素材' : '新建素材' }}</h2>
      <label>标题<input v-model="title" required maxlength="200" /></label>
      <label>正文<textarea v-model="body" required rows="5" /></label>
      <label class="upload">
        图片（可多选）
        <input type="file" accept="image/*" multiple :disabled="uploading" @change="uploadFiles" />
      </label>
      <div v-if="mediaIds.length" class="media-grid">
        <figure v-for="(id, index) in mediaIds" :key="id" class="media-card">
          <button type="button" class="thumb" @click="previewIndex = index">
            <img :src="mediaPreviewUrl(id)" :alt="`素材图片 ${index + 1}`" />
          </button>
          <figcaption>
            <span>图片 {{ index + 1 }}</span>
            <button type="button" class="link" @click="removeMedia(id)">移除</button>
          </figcaption>
        </figure>
      </div>
      <div class="actions">
        <button type="submit" :disabled="saving || uploading">
          {{ saving ? '保存中…' : editingId ? '保存修改' : '保存素材' }}
        </button>
        <button v-if="editingId" type="button" class="secondary" @click="resetForm">取消编辑</button>
      </div>
      <p v-if="message" class="ok">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </form>

    <div v-if="previewIndex != null && mediaIds[previewIndex]" class="lightbox" @click.self="previewIndex = null">
      <button type="button" class="close" @click="previewIndex = null">关闭</button>
      <img :src="mediaPreviewUrl(mediaIds[previewIndex])" alt="素材大图" />
    </div>

    <table>
      <thead>
        <tr><th>标题</th><th>图片</th><th>敏感词</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in pageRows" :key="row.content_id">
          <td>{{ row.title }}</td>
          <td>{{ (row.media_ids || []).length }} 张</td>
          <td>{{ row.sensitivity_status === 'clean' ? '通过' : '需改写' }}</td>
          <td class="ops">
            <button type="button" class="link" @click="startEdit(row)">编辑</button>
            <button type="button" class="link danger" @click="deleteContent(row)">删除</button>
          </td>
        </tr>
        <tr v-if="!pageRows.length">
          <td colspan="4" class="empty">暂无素材。</td>
        </tr>
      </tbody>
    </table>
    <div v-if="rows.length > PAGE_SIZE" class="pager">
      <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
      <span>第 {{ page }} / {{ totalPages }} 页（共 {{ rows.length }} 条，每页 {{ PAGE_SIZE }} 条）</span>
      <button type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
    </div>
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
h2 { margin: 0; font-size: 1.05rem; }
label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
input, textarea { padding: 10px 12px; border: 1px solid #d0d5dd; border-radius: 8px; font: inherit; }
.upload input { font-weight: 400; }
.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 12px;
}
.media-card { margin: 0; }
.thumb {
  display: block;
  width: 100%;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f3f4f6;
  cursor: zoom-in;
}
.thumb img {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
}
.media-card figcaption {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  font-size: 0.85rem;
  font-weight: 400;
}
.actions { display: flex; gap: 10px; }
button { align-self: flex-start; background: #2d6a4f; color: #fff; border: 0; border-radius: 8px; padding: 10px 16px; font-weight: 600; cursor: pointer; }
button.secondary { background: #667085; }
button.link { background: transparent; color: #2d6a4f; padding: 0; font-weight: 600; }
button.link.danger { color: #b42318; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.ops { display: flex; gap: 12px; }
.ok { color: #2d6a4f; margin: 0; }
.error { color: #b42318; margin: 0; }
.empty { color: #5c6770; }
.pager {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-top: 14px;
  color: #5c6770;
}
.pager button {
  border: 1px solid #d0d5dd;
  background: #fff;
  color: #111;
  border-radius: 8px;
  padding: 8px 12px;
}
.pager button:disabled { opacity: 0.5; cursor: default; }
.lightbox {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 24px;
}
.lightbox img {
  max-width: min(920px, 100%);
  max-height: 85vh;
  object-fit: contain;
  border-radius: 8px;
  background: #111;
}
.lightbox .close {
  position: absolute;
  top: 20px;
  right: 20px;
  background: #fff;
  color: #111;
}
</style>
