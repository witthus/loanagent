import { ref } from 'vue'
import { api } from '@/lib/api'

export type TaskStatus = {
  task_id: string
  status: string
  error_code?: string | null
  effect_committed?: boolean
}

export function useTaskPoll() {
  const status = ref('')
  const errorCode = ref<string | null>(null)

  async function poll(taskId: string, timeoutMs = 120_000): Promise<TaskStatus | null> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const task = await api<TaskStatus>(`/api/v1/tasks/${taskId}`)
      status.value = task.status
      errorCode.value = task.error_code || null
      if (['succeeded', 'failed', 'cancelled', 'rejected'].includes(task.status)) {
        return task
      }
      await new Promise((r) => setTimeout(r, 2000))
    }
    return null
  }

  return { status, errorCode, poll }
}
