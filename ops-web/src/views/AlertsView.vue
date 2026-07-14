<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { alertKindLabel, formatDateTime } from '@/lib/labels'

type Alert = {
  alert_id: string
  kind: string
  message: string
  ref_id: string | null
  created_at: string
}

const route = useRoute()
const router = useRouter()
const rows = ref<Alert[]>([])
const error = ref('')

const focusAlertId = computed(() => (route.query.alert_id as string) || '')

const visibleRows = computed(() => {
  if (!focusAlertId.value) return rows.value
  return rows.value.filter((row) => row.alert_id === focusAlertId.value)
})

const focused = computed(() =>
  focusAlertId.value ? visibleRows.value[0] || null : null,
)

function clearFocus() {
  router.push({ name: 'alerts' })
}

watch(focusAlertId, () => {
  /* recompute via computed */
})

onMounted(async () => {
  try {
    rows.value = await api<Alert[]>('/api/v1/alerts')
  } catch {
    error.value = '加载异常失败'
  }
})
</script>

<template>
  <section>
    <h1>异常</h1>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-else-if="focusAlertId">
      <p class="focus-banner">
        正在查看单条异常
        <button type="button" class="link-btn" @click="clearFocus">返回全部异常</button>
      </p>
      <article v-if="focused" class="detail-card">
        <h2>{{ alertKindLabel(focused.kind) }}</h2>
        <p class="time">时间：{{ formatDateTime(focused.created_at) }}</p>
        <p class="label">异常内容</p>
        <p class="message">{{ focused.message }}</p>
        <p v-if="focused.ref_id" class="muted">关联编号：{{ focused.ref_id }}</p>
      </article>
      <p v-else class="empty">未找到该异常，可能已被清理。</p>
    </template>

    <template v-else>
      <table v-if="rows.length">
        <thead>
          <tr>
            <th>类型</th>
            <th>说明</th>
            <th>关联</th>
            <th>时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.alert_id">
            <td>{{ alertKindLabel(row.kind) }}</td>
            <td>{{ row.message }}</td>
            <td>{{ row.ref_id || '—' }}</td>
            <td>{{ formatDateTime(row.created_at) }}</td>
            <td>
              <router-link
                class="link"
                :to="{ name: 'alerts', query: { alert_id: row.alert_id } }"
              >
                查看详情
              </router-link>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">暂无异常。</p>
    </template>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.error { color: #b42318; }
.empty { color: #5c6770; background: #fff; border-radius: 12px; padding: 20px; }
.focus-banner {
  background: #fff7ed;
  border: 1px solid #fdba74;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  gap: 16px;
  align-items: center;
}
.detail-card {
  background: #fff;
  border-radius: 12px;
  padding: 22px;
  border: 1px solid #e5e7eb;
}
.detail-card h2 { margin: 0 0 8px; font-size: 1.2rem; }
.time, .muted { color: #5c6770; }
.label { font-weight: 600; margin: 16px 0 6px; }
.message { margin: 0; white-space: pre-wrap; line-height: 1.5; }
.link, .link-btn {
  color: #2d6a4f;
  font-weight: 600;
  text-decoration: none;
  background: none;
  border: 0;
  cursor: pointer;
  padding: 0;
  font: inherit;
}
</style>
