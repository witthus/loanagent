export type TodoKind = 'dm' | 'publish' | 'lead' | 'alert'

export type TodoItem = {
  id: string
  kind: TodoKind
  title: string
  subtitle: string
  actionLabel: string
  to: string
  /** ISO timestamp for display; may be empty if source has none */
  at: string | null
  account_id?: string
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
  account_id?: string
  created_at?: string | null
  updated_at?: string | null
}
type Lead = {
  lead_id: string
  status: string
  thread_id: string
  account_id?: string
  title_summary?: string | null
  updated_at?: string | null
  created_at?: string | null
}

export type FleetHealthTodo = {
  id: string
  kind: 'alert'
  title: string
  subtitle: string
  actionLabel: string
  to: string
  at: string | null
  account_id?: string
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
  leads?: Lead[]
  fleetHealth?: FleetHealthTodo[]
  accountNames?: Record<string, string>
  /** When set, only include todos for this account. */
  accountId?: string | null
}): TodoItem[] {
  const names = input.accountNames || {}
  const accountLabel = (accountId: string) => names[accountId] || '未命名账号'
  const filterAccount = input.accountId || ''
  const items: TodoItem[] = []

  const include = (accountId?: string | null) =>
    !filterAccount || !accountId || accountId === filterAccount

  for (const thread of input.threads || []) {
    if (!thread.unread) continue
    if (!include(thread.account_id)) continue
    items.push({
      id: `dm-${thread.thread_id}`,
      kind: 'dm',
      title: `待回复私信：${thread.title_summary}`,
      subtitle: `账号：${accountLabel(thread.account_id || '')}`,
      actionLabel: '去回复',
      to: `/inbox/${thread.thread_id}?account_id=${encodeURIComponent(thread.account_id || '')}`,
      at: pickTime(thread.updated_at, thread.created_at),
      account_id: thread.account_id,
    })
  }
  for (const schedule of input.schedules || []) {
    if (schedule.status !== 'ready') continue
    if (!include(schedule.account_id)) continue
    items.push({
      id: `sch-${schedule.schedule_id}`,
      kind: 'publish',
      title: '有一条排期可以发布',
      subtitle: `账号：${accountLabel(schedule.account_id)}`,
      actionLabel: '去发布',
      to: `/schedules?schedule_id=${encodeURIComponent(schedule.schedule_id)}`,
      at: pickTime(schedule.window_start, schedule.updated_at, schedule.created_at),
      account_id: schedule.account_id,
    })
  }
  for (const lead of input.leads || []) {
    if (lead.status === 'closed') continue
    if (!include(lead.account_id)) continue
    const peer = lead.title_summary || '私信线索'
    const accountId = lead.account_id || ''
    items.push({
      id: `lead-${lead.lead_id}`,
      kind: 'lead',
      title: `待跟进线索：${peer}`,
      subtitle: `账号：${accountLabel(accountId)} · ${lead.status === 'new' ? '新线索' : lead.status === 'warm' ? '跟进中' : lead.status === 'hot' ? '高意向' : lead.status}`,
      actionLabel: '去处理',
      to: accountId
        ? `/inbox/${lead.thread_id}?account_id=${encodeURIComponent(accountId)}`
        : `/leads`,
      at: pickTime(lead.updated_at, lead.created_at),
      account_id: accountId || undefined,
    })
  }
  for (const health of input.fleetHealth || []) {
    if (!include(health.account_id)) continue
    items.push({
      id: health.id,
      kind: 'alert',
      title: health.title,
      subtitle: health.subtitle,
      actionLabel: health.actionLabel,
      to: health.to,
      at: health.at,
      account_id: health.account_id,
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
    if (!include(task.account_id)) continue
    const reason = task.error_code || '未知原因'
    items.push({
      id: `task-${task.task_id}`,
      kind: 'alert',
      title: '最近有任务失败',
      subtitle: `异常：${reason}`,
      actionLabel: '查看原因',
      to: `/tasks?task_id=${encodeURIComponent(task.task_id)}`,
      at: pickTime(task.updated_at, task.created_at),
      account_id: task.account_id,
    })
  }
  return items
}
