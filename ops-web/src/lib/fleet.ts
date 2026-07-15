/** Fleet helpers: account labels + device/account health for ops pickers. */

import { accountDisplayName, roleLabel, type AccountLike } from '@/lib/labels'

export type FleetAccount = AccountLike & {
  role?: string
  status?: string | null
  device_id?: string | null
}

export type AgentUpgradeStatus = {
  device_id?: string
  status?: string | null
  ring?: string | null
  manifest_url?: string | null
  detail?: string | null
  pending?: boolean
  requested_at?: string | null
  updated_at?: string | null
}

export type FleetDevice = {
  device_id: string
  online?: boolean
  a11y_bound?: boolean
  wifi_connected?: boolean | null
  cellular_ok?: boolean | null
  last_seen_at?: string | null
  agent_version?: string | null
  display_name?: string | null
  manufacturer?: string | null
  model?: string | null
  public_ip?: string | null
  geo_label?: string | null
  agent_upgrade?: AgentUpgradeStatus | null
}

export type AccountHealth =
  | 'ready'
  | 'offline'
  | 'a11y_down'
  | 'unbound'
  | 'paused'
  | 'blocked'
  | 'needs_login'

export function accountHealth(
  account: FleetAccount,
  devicesById: Record<string, FleetDevice>,
): AccountHealth {
  const status = (account.status || 'active').toLowerCase()
  if (status === 'paused') return 'paused'
  if (status === 'blocked') return 'blocked'
  if (status === 'needs_login') return 'needs_login'

  const deviceId = account.device_id
  if (!deviceId) return 'unbound'
  const device = devicesById[deviceId]
  if (!device || device.online === false) return 'offline'
  if (device.a11y_bound === false) return 'a11y_down'
  return 'ready'
}

export function healthLabel(health: AccountHealth): string {
  switch (health) {
    case 'ready':
      return '可用'
    case 'offline':
      return '设备离线'
    case 'a11y_down':
      return '无障碍未开'
    case 'unbound':
      return '未绑设备'
    case 'paused':
      return '已暂停'
    case 'blocked':
      return '已封禁'
    case 'needs_login':
      return '需重新登录'
  }
}

export function accountStatusLabel(status: string | null | undefined): string {
  switch ((status || 'active').toLowerCase()) {
    case 'active':
      return '启用中'
    case 'paused':
      return '已暂停'
    case 'blocked':
      return '已封禁'
    case 'needs_login':
      return '需重新登录'
    default:
      return status || '未知'
  }
}

export function accountOptionLabel(
  account: FleetAccount,
  devicesById: Record<string, FleetDevice> = {},
): string {
  const name = accountDisplayName(account)
  const role = account.role ? roleLabel(account.role) : ''
  const health = healthLabel(accountHealth(account, devicesById))
  const parts = [name]
  if (role) parts.push(role)
  parts.push(health)
  return parts.join(' · ')
}

export function buildDeviceMap(devices: FleetDevice[]): Record<string, FleetDevice> {
  const map: Record<string, FleetDevice> = {}
  for (const device of devices) {
    map[device.device_id] = device
  }
  return map
}

export function isAccountRunnable(
  account: FleetAccount,
  devicesById: Record<string, FleetDevice>,
): boolean {
  return accountHealth(account, devicesById) === 'ready'
}

/** Build device-health todos for Today hub. */
export function buildFleetHealthTodos(
  accounts: FleetAccount[],
  devicesById: Record<string, FleetDevice>,
  accountNames: Record<string, string> = {},
): Array<{
  id: string
  kind: 'alert'
  title: string
  subtitle: string
  actionLabel: string
  to: string
  at: string | null
  account_id?: string
}> {
  const items: Array<{
    id: string
    kind: 'alert'
    title: string
    subtitle: string
    actionLabel: string
    to: string
    at: string | null
    account_id?: string
  }> = []
  for (const account of accounts) {
    const health = accountHealth(account, devicesById)
    if (health === 'ready') continue
    const name = accountNames[account.account_id] || accountDisplayName(account)
    const device = account.device_id ? devicesById[account.device_id] : undefined
    items.push({
      id: `fleet-${account.account_id}-${health}`,
      kind: 'alert',
      title: `账号不可用：${name}`,
      subtitle: healthLabel(health),
      actionLabel: '去处理',
      to: health === 'unbound' || health === 'offline' || health === 'a11y_down' ? '/devices' : '/accounts',
      at: device?.last_seen_at || null,
      account_id: account.account_id,
    })
  }
  return items
}
