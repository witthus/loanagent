<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { zhCN } from '@/i18n/zh-CN'
import { api } from '@/lib/api'
import { buildFleetHealthTodos } from '@/lib/fleet'
import { buildTodoItems, type TodoItem } from '@/lib/todoAggregate'
import { buildAccountNameMap, formatDateTime, type AccountLike } from '@/lib/labels'
import { DEFAULT_PLATFORM } from '@/platform'
import { useFleetAccounts } from '@/composables/useFleetAccounts'

const loading = ref(true)
const error = ref('')
const items = ref<TodoItem[]>([])
const filterAccountId = ref('')
const { accounts, devicesById, loadFleet, optionLabel } = useFleetAccounts()

const filteredItems = computed(() => {
  if (!filterAccountId.value) return items.value
  return items.value.filter(
    (item) => !item.account_id || item.account_id === filterAccountId.value,
  )
})

onMounted(async () => {
  try {
    await loadFleet()
    const [threads, schedules, alerts, tasks, leads] = await Promise.all([
      api<any[]>(`/api/v1/inbox/threads`).catch(() => []),
      api<any[]>(`/api/v1/schedules`).catch(() => []),
      api<any[]>(`/api/v1/alerts`).catch(() => []),
      api<any[]>(`/api/v1/tasks`).catch(() => []),
      api<any[]>(`/api/v1/inbox/leads`).catch(() => []),
    ])
    const accountNames = buildAccountNameMap(accounts.value as AccountLike[])
    const failed = (tasks || [])
      .filter((t: any) => t.status === 'failed')
      .sort(
        (a: any, b: any) =>
          new Date(b.updated_at || b.created_at || 0).getTime() -
          new Date(a.updated_at || a.created_at || 0).getTime(),
      )
      .slice(0, 5)
    const fleetHealth = buildFleetHealthTodos(accounts.value, devicesById.value, accountNames)
    items.value = buildTodoItems({
      threads,
      schedules,
      alerts,
      failedTasks: failed,
      leads,
      fleetHealth,
      accountNames,
    })
  } catch {
    error.value = '加载待办失败，请刷新重试。'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <h1>{{ zhCN.nav.today }}</h1>
    <p class="lead">先处理下面这些事即可。当前平台：小红书（{{ DEFAULT_PLATFORM }}）。</p>
    <div class="filters">
      <label>
        按账号筛选
        <select v-model="filterAccountId">
          <option value="">全部账号</option>
          <option v-for="a in accounts" :key="a.account_id" :value="a.account_id">
            {{ optionLabel(a) }}
          </option>
        </select>
      </label>
    </div>
    <p v-if="loading">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <div v-else-if="!filteredItems.length" class="empty">暂时没有待办。可以从左侧「发笔记」开始。</div>
    <ul v-else class="list">
      <li v-for="item in filteredItems" :key="item.id" class="card">
        <div>
          <strong>{{ item.title }}</strong>
          <p>{{ item.subtitle }}</p>
          <p class="time">时间：{{ formatDateTime(item.at) }}</p>
        </div>
        <router-link class="btn" :to="item.to">{{ item.actionLabel }}</router-link>
      </li>
    </ul>
  </section>
</template>

<style scoped>
h1 { margin: 0 0 8px; font-size: 1.6rem; }
.lead { color: #5c6770; margin: 0 0 16px; }
.filters {
  margin-bottom: 16px;
  background: #fff;
  border-radius: 12px;
  padding: 12px 16px;
  max-width: 420px;
}
.filters label { display: flex; flex-direction: column; gap: 6px; font-weight: 600; }
.filters select {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #d0d5dd;
  font: inherit;
}
.error { color: #b42318; }
.empty, .card {
  background: #fff;
  border-radius: 12px;
  padding: 20px 22px;
}
.empty { border: 1px dashed #d0d5dd; color: #5c6770; }
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.card { display: flex; justify-content: space-between; align-items: center; gap: 16px; border: 1px solid #e5e7eb; }
.card p { margin: 6px 0 0; color: #5c6770; }
.time { font-size: 0.9rem; }
.btn {
  flex-shrink: 0;
  background: #2d6a4f;
  color: #fff;
  text-decoration: none;
  padding: 10px 16px;
  border-radius: 8px;
  font-weight: 600;
}
</style>
