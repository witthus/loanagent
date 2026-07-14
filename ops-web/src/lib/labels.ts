/** Shared Chinese labels for ops-web. Prefer account display_name over raw ids. */

export type AccountLike = {
  account_id: string
  display_name?: string | null
}

export function accountDisplayName(
  account: AccountLike | null | undefined,
  fallback = '未命名账号',
): string {
  if (!account) return fallback
  const name = account.display_name?.trim()
  return name || fallback
}

export function accountNameFromMap(
  accountId: string | null | undefined,
  byId: Record<string, string>,
  fallback = '未命名账号',
): string {
  if (!accountId) return fallback
  return byId[accountId] || fallback
}

export function buildAccountNameMap(accounts: AccountLike[]): Record<string, string> {
  const map: Record<string, string> = {}
  for (const account of accounts) {
    map[account.account_id] = accountDisplayName(account)
  }
  return map
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${d} ${hh}:${mm}`
}

const TASK_STATUS: Record<string, string> = {
  accepted: '已下发',
  executing: '执行中',
  succeeded: '成功',
  failed: '失败',
  timed_out: '超时',
  cancelled: '已取消',
}

export function taskStatusLabel(status: string): string {
  return TASK_STATUS[status] || status
}

const PLAYBOOK: Record<string, string> = {
  ensure_app_ready: '准备小红书',
  read_comments: '同步评论',
  reply_comment: '回复评论',
  inbox_sync: '同步私信',
  inbox_open_thread: '打开私信会话',
  reply_dm: '回复私信',
  sync_notes: '同步笔记',
  publish_note: '发布笔记',
  dismiss_interruptions: '关闭干扰弹窗',
}

export function playbookLabel(playbook: string): string {
  const name = playbook.split('@')[0] || playbook
  return PLAYBOOK[name] || name
}

const ALERT_KIND: Record<string, string> = {
  engagement_skipped: '互动跳过',
  engagement_stopped: '互动已停止',
}

export function alertKindLabel(kind: string): string {
  return ALERT_KIND[kind] || kind
}

const ROLE_LABEL: Record<string, string> = {
  PUBLISHER_MAIN: '主号',
  PUBLISHER_MATRIX: '矩阵号',
  ENGAGER: '互动号',
}

export function roleLabel(role: string): string {
  return ROLE_LABEL[role] || role
}

export function mediaPreviewUrl(mediaId: string): string {
  return `/api/v1/media/${encodeURIComponent(mediaId)}/download`
}
