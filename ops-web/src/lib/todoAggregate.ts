export type TodoKind = 'dm' | 'comment' | 'publish' | 'lead' | 'alert'

export type TodoItem = {
  id: string
  kind: TodoKind
  title: string
  subtitle: string
  actionLabel: string
  to: string
}

type Thread = { thread_id: string; title_summary: string; unread?: boolean; account_id?: string }
type Schedule = { schedule_id: string; status: string; account_id: string; content_id: string }
type Alert = { alert_id: string; kind: string; message: string }
type Task = { task_id: string; status: string; error_code?: string | null; playbook?: string }
type Note = { note_id: string; title_summary?: string | null; account_id: string }
type Lead = { lead_id: string; status: string; thread_id: string }

export function buildTodoItems(input: {
  threads?: Thread[]
  schedules?: Schedule[]
  alerts?: Alert[]
  failedTasks?: Task[]
  notes?: Note[]
  leads?: Lead[]
}): TodoItem[] {
  const items: TodoItem[] = []
  for (const thread of input.threads || []) {
    if (!thread.unread) continue
    items.push({
      id: `dm-${thread.thread_id}`,
      kind: 'dm',
      title: `待回复私信：${thread.title_summary}`,
      subtitle: '有人给你发了私信',
      actionLabel: '去回复',
      to: `/inbox/${thread.thread_id}`,
    })
  }
  for (const note of input.notes || []) {
    items.push({
      id: `note-${note.note_id}`,
      kind: 'comment',
      title: `查看评论：${note.title_summary || '未命名笔记'}`,
      subtitle: '同步后可回复评论',
      actionLabel: '去回复',
      to: `/comments?note_id=${encodeURIComponent(note.note_id)}&account_id=${encodeURIComponent(note.account_id)}`,
    })
  }
  for (const schedule of input.schedules || []) {
    if (schedule.status !== 'ready') continue
    items.push({
      id: `sch-${schedule.schedule_id}`,
      kind: 'publish',
      title: '有一条排期可以发布',
      subtitle: `账号 ${schedule.account_id}`,
      actionLabel: '去发布',
      to: `/publish?schedule_id=${encodeURIComponent(schedule.schedule_id)}`,
    })
  }
  for (const lead of input.leads || []) {
    if (lead.status === 'closed') continue
    items.push({
      id: `lead-${lead.lead_id}`,
      kind: 'lead',
      title: '待跟进线索',
      subtitle: `状态：${lead.status}`,
      actionLabel: '去处理',
      to: `/leads`,
    })
  }
  for (const alert of input.alerts || []) {
    items.push({
      id: `alert-${alert.alert_id}`,
      kind: 'alert',
      title: '需要处理的异常',
      subtitle: alert.message,
      actionLabel: '查看原因',
      to: `/alerts`,
    })
  }
  for (const task of input.failedTasks || []) {
    if (task.status !== 'failed') continue
    items.push({
      id: `task-${task.task_id}`,
      kind: 'alert',
      title: '最近有任务失败',
      subtitle: task.error_code || task.playbook || task.task_id,
      actionLabel: '查看原因',
      to: `/tasks?task_id=${encodeURIComponent(task.task_id)}`,
    })
  }
  return items
}
