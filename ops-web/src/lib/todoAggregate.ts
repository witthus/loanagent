export type TodoKind = 'dm' | 'comment' | 'publish' | 'lead' | 'alert'

export type TodoItem = {
  id: string
  kind: TodoKind
  title: string
  subtitle: string
  actionLabel: string
  to: string
  /** ISO timestamp for display; may be empty if source has none */
  at: string | null
}

type Thread = {
  thread_id: string
  title_summary: string
  unread?: boolean
  account_id?: string
  updated_at?: string | null
  created_at?: string | null
}
type Schedule = {
  schedule_id: string
  status: string
  account_id: string
  content_id: string
  window_start?: string | null
  updated_at?: string | null
  created_at?: string | null
}
type Alert = {
  alert_id: string
  kind: string
  message: string
  created_at?: string | null
}
type Task = {
  task_id: string
  status: string
  error_code?: string | null
  playbook?: string
  created_at?: string | null
  updated_at?: string | null
}
type Note = {
  note_id: string
  title_summary?: string | null
  account_id: string
  updated_at?: string | null
  created_at?: string | null
  synced_at?: string | null
}
type Lead = {
  lead_id: string
  status: string
  thread_id: string
  updated_at?: string | null
  created_at?: string | null
}

function pickTime(...values: Array<string | null | undefined>): string | null {
  for (const value of values) {
    if (value && String(value).trim()) return String(value)
  }
  return null
}

export function buildTodoItems(input: {
  threads?: Thread[]
  schedules?: Schedule[]
  alerts?: Alert[]
  failedTasks?: Task[]
  notes?: Note[]
  leads?: Lead[]
  accountNames?: Record<string, string>
}): TodoItem[] {
  const names = input.accountNames || {}
  const accountLabel = (accountId: string) => names[accountId] || '未命名账号'
  const items: TodoItem[] = []

  for (const thread of input.threads || []) {
    if (!thread.unread) continue
    items.push({
      id: `dm-${thread.thread_id}`,
      kind: 'dm',
      title: `待回复私信：${thread.title_summary}`,
      subtitle: '有人给你发了私信',
      actionLabel: '去回复',
      to: `/inbox/${thread.thread_id}?account_id=${encodeURIComponent(thread.account_id || '')}`,
      at: pickTime(thread.updated_at, thread.created_at),
    })
  }
  for (const note of input.notes || []) {
    items.push({
      id: `note-${note.note_id}`,
      kind: 'comment',
      title: `查看评论：${note.title_summary || '未命名笔记'}`,
      subtitle: `账号：${accountLabel(note.account_id)}`,
      actionLabel: '去回复',
      to: `/comments?note_id=${encodeURIComponent(note.note_id)}&account_id=${encodeURIComponent(note.account_id)}`,
      at: pickTime(note.synced_at, note.updated_at, note.created_at),
    })
  }
  for (const schedule of input.schedules || []) {
    if (schedule.status !== 'ready') continue
    items.push({
      id: `sch-${schedule.schedule_id}`,
      kind: 'publish',
      title: '有一条排期可以发布',
      subtitle: `账号：${accountLabel(schedule.account_id)}`,
      actionLabel: '去发布',
      to: `/publish?schedule_id=${encodeURIComponent(schedule.schedule_id)}`,
      at: pickTime(schedule.window_start, schedule.updated_at, schedule.created_at),
    })
  }
  for (const lead of input.leads || []) {
    if (lead.status === 'closed') continue
    items.push({
      id: `lead-${lead.lead_id}`,
      kind: 'lead',
      title: '待跟进线索',
      subtitle: `状态：${lead.status === 'new' ? '新线索' : lead.status === 'warm' ? '跟进中' : lead.status === 'hot' ? '高意向' : lead.status}`,
      actionLabel: '去处理',
      to: `/leads`,
      at: pickTime(lead.updated_at, lead.created_at),
    })
  }
  for (const alert of input.alerts || []) {
    items.push({
      id: `alert-${alert.alert_id}`,
      kind: 'alert',
      title: '需要处理的异常',
      subtitle: alert.message,
      actionLabel: '查看原因',
      to: `/alerts?alert_id=${encodeURIComponent(alert.alert_id)}`,
      at: pickTime(alert.created_at),
    })
  }
  for (const task of input.failedTasks || []) {
    if (task.status !== 'failed') continue
    const reason = task.error_code || '未知原因'
    items.push({
      id: `task-${task.task_id}`,
      kind: 'alert',
      title: '最近有任务失败',
      subtitle: `异常：${reason}`,
      actionLabel: '查看原因',
      to: `/tasks?task_id=${encodeURIComponent(task.task_id)}`,
      at: pickTime(task.updated_at, task.created_at),
    })
  }
  return items
}
