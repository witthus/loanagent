<script setup lang="ts">
import type { SubmittedTask } from '@/composables/useSubmittedTasks'

defineProps<{
  tasks: SubmittedTask[]
  statusText: (row: SubmittedTask) => string
  isRunning: (row: SubmittedTask) => boolean
}>()

const emit = defineEmits<{
  dismiss: [taskId: string]
}>()
</script>

<template>
  <div v-if="tasks.length" class="submitted">
    <strong>本页已提交的任务</strong>
    <ul>
      <li v-for="row in tasks" :key="row.task_id">
        <div class="meta">
          <span class="label">{{ row.label }}</span>
          <span :class="['status', isRunning(row) ? 'running' : row.status]">
            {{ statusText(row) }}
          </span>
        </div>
        <div class="actions">
          <router-link :to="{ path: '/tasks', query: { task_id: row.task_id } }">
            在任务页查看
          </router-link>
          <button type="button" class="link-btn" @click="emit('dismiss', row.task_id)">
            关闭
          </button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.submitted {
  background: #f0f7f4;
  border: 1px solid #b7c4bc;
  border-radius: 10px;
  padding: 12px 14px;
  margin: 0 0 14px;
}
.submitted strong { display: block; margin-bottom: 8px; color: #1f2329; }
ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  justify-content: space-between;
  align-items: center;
  padding-top: 8px;
  border-top: 1px solid #d8e3dd;
}
li:first-child { border-top: 0; padding-top: 0; }
.meta { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: baseline; }
.label { font-weight: 600; color: #1f2329; }
.status { font-size: 0.9rem; color: #5c6770; }
.status.running { color: #b54708; }
.status.succeeded { color: #2d6a4f; }
.status.failed,
.status.rejected,
.status.timed_out,
.status.cancelled { color: #b42318; }
.actions { display: flex; gap: 12px; align-items: center; }
a, .link-btn {
  color: #2d6a4f;
  font-weight: 600;
  text-decoration: none;
  background: none;
  border: 0;
  padding: 0;
  cursor: pointer;
  font: inherit;
}
</style>
