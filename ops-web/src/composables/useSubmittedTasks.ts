import { getCurrentInstance, onUnmounted, ref } from 'vue'
import { api } from '@/lib/api'
import { humanizeError } from '@/lib/humanize'
import { taskStatusLabel } from '@/lib/labels'

export type SubmittedTask = {
  task_id: string
  label: string
  status: string
  error_code?: string | null
}

type TaskPollRow = {
  task_id: string
  status: string
  error_code?: string | null
}

const TERMINAL = new Set(['succeeded', 'failed', 'cancelled', 'rejected', 'timed_out'])

export function useSubmittedTasks() {
  const tasks = ref<SubmittedTask[]>([])
  const timers = new Map<string, ReturnType<typeof setTimeout>>()

  function dismiss(taskId: string) {
    const timer = timers.get(taskId)
    if (timer) {
      clearTimeout(timer)
      timers.delete(taskId)
    }
    tasks.value = tasks.value.filter((row) => row.task_id !== taskId)
  }

  function upsert(row: SubmittedTask) {
    const existing = tasks.value.findIndex((item) => item.task_id === row.task_id)
    if (existing >= 0) {
      tasks.value[existing] = { ...tasks.value[existing], ...row }
    } else {
      tasks.value = [row, ...tasks.value]
    }
  }

  async function watch(taskId: string) {
    const deadline = Date.now() + 180_000
    const tick = async () => {
      try {
        const task = await api<TaskPollRow>(`/api/v1/tasks/${taskId}`)
        upsert({
          task_id: taskId,
          label: tasks.value.find((row) => row.task_id === taskId)?.label || '任务',
          status: task.status,
          error_code: task.error_code || null,
        })
        if (TERMINAL.has(task.status) || Date.now() >= deadline) {
          timers.delete(taskId)
          return
        }
      } catch {
        /* keep polling until deadline */
        if (Date.now() >= deadline) {
          timers.delete(taskId)
          return
        }
      }
      timers.set(taskId, setTimeout(() => void tick(), 2000))
    }
    void tick()
  }

  function track(taskId: string, label: string, status = 'accepted') {
    upsert({ task_id: taskId, label, status, error_code: null })
    void watch(taskId)
  }

  function statusText(row: SubmittedTask): string {
    if (row.error_code) {
      const human = humanizeError(row.error_code)
      return `${taskStatusLabel(row.status)} · ${human.title}`
    }
    return taskStatusLabel(row.status)
  }

  function isRunning(row: SubmittedTask): boolean {
    return !TERMINAL.has(row.status)
  }

  if (getCurrentInstance()) {
    onUnmounted(() => {
      for (const timer of timers.values()) clearTimeout(timer)
      timers.clear()
    })
  }

  return { tasks, track, dismiss, statusText, isRunning }
}
