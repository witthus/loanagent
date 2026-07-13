<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'

type Task = {
  task_id: string
  status: string
  playbook: string
  account_id: string
  error_code: string | null
  created_at: string
}

const rows = ref<Task[]>([])
const expanded = ref<Record<string, boolean>>({})
const error = ref('')

function toggle(taskId: string) {
  expanded.value[taskId] = !expanded.value[taskId]
}

onMounted(async () => {
  try {
    rows.value = await api<Task[]>('/api/v1/tasks')
  } catch {
    error.value = '加载任务失败'
  }
})
</script>

<template>
  <section>
    <h1>任务</h1>
    <p v-if="error" class="error">{{ error }}</p>
    <table v-else>
      <thead>
        <tr>
          <th>任务 ID</th>
          <th>状态</th>
          <th>Playbook</th>
          <th>账号</th>
          <th>创建时间</th>
          <th>错误</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="row.task_id">
          <tr>
            <td>{{ row.task_id }}</td>
            <td>{{ row.status }}</td>
            <td>{{ row.playbook }}</td>
            <td>{{ row.account_id }}</td>
            <td>{{ row.created_at }}</td>
            <td>
              <button
                v-if="row.error_code"
                type="button"
                class="link-btn"
                @click="toggle(row.task_id)"
              >
                {{ expanded[row.task_id] ? '收起' : '展开' }}
              </button>
              <span v-else>—</span>
            </td>
          </tr>
          <tr v-if="row.error_code && expanded[row.task_id]" class="detail">
            <td colspan="6">
              <strong>错误码：</strong>{{ row.error_code }}
              <p>{{ humanizeError(row.error_code).title }} — {{ humanizeError(row.error_code).detail }}</p>
              <p class="muted">{{ humanizeError(row.error_code).nextStep }}</p>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid #eee; }
th { background: #f3f4f6; }
.detail td { background: #f9fafb; }
.link-btn {
  background: none;
  border: 0;
  color: #2d6a4f;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}
.muted { color: #5c6770; margin: 4px 0 0; }
.error { color: #b42318; }
</style>
