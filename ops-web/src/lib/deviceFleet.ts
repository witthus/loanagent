/** Device fleet helpers for the Devices management page. */

import type { FleetDevice } from '@/lib/fleet'

export const MIN_AGENT_VERSION = { major: 0, minor: 1, patch: 2 } as const

export function deviceDisplayName(device: Pick<FleetDevice, 'device_id' | 'display_name' | 'model'>): string {
  return device.display_name?.trim() || device.model?.trim() || device.device_id
}

export function isLegacySharedDevice(device: Pick<FleetDevice, 'device_id'>): boolean {
  return device.device_id === 'redmi-note-12'
}

export function isOutdatedAgent(device: Pick<FleetDevice, 'device_id' | 'agent_version'>): boolean {
  const v = (device.agent_version || '').toLowerCase()
  if (!v) return true
  if (isLegacySharedDevice(device)) return true
  const match = v.match(/(\d+)\.(\d+)\.(\d+)/)
  if (!match) return true
  const [maj, min, patch] = match.slice(1).map(Number)
  if (maj > MIN_AGENT_VERSION.major) return false
  if (maj < MIN_AGENT_VERSION.major) return true
  if (min > MIN_AGENT_VERSION.minor) return false
  if (min < MIN_AGENT_VERSION.minor) return true
  return patch < MIN_AGENT_VERSION.patch
}

export function deviceRowWarning(device: FleetDevice): string {
  if (isLegacySharedDevice(device)) return '旧包共用 ID，请重装 0.1.2+'
  if (isOutdatedAgent(device)) return 'Agent 版本过旧，请升级到 0.1.2+'
  if (device.a11y_bound === false) return '无障碍未开，绑定后仍无法执行任务'
  return ''
}

export function networkLabel(device: FleetDevice | null | undefined): string {
  if (!device) return '—'
  const parts: string[] = []
  if (device.wifi_connected === true) parts.push('Wi‑Fi')
  else if (device.wifi_connected === false) parts.push('无 Wi‑Fi')
  if (device.cellular_ok === true) parts.push('蜂窝正常')
  else if (device.cellular_ok === false) parts.push('蜂窝异常')
  return parts.length ? parts.join(' · ') : '—'
}

export function deviceStatusLabel(device: FleetDevice): string {
  if (device.online === false) return '离线'
  if (device.a11y_bound === false) return '无障碍未开'
  if (isOutdatedAgent(device) || isLegacySharedDevice(device)) return '版本异常'
  return '在线'
}

export function locationLabel(device: Pick<FleetDevice, 'geo_label' | 'public_ip'>): {
  primary: string
  secondary: string
} {
  const geo = device.geo_label?.trim() || ''
  const ip = device.public_ip?.trim() || ''
  if (geo) return { primary: geo, secondary: ip }
  if (ip) return { primary: ip, secondary: '' }
  return { primary: '—', secondary: '' }
}

export function relativeHeartbeat(value: string | null | undefined, nowMs = Date.now()): string {
  if (!value) return '—'
  const ts = Date.parse(value)
  if (Number.isNaN(ts)) return value
  const deltaSec = Math.max(0, Math.round((nowMs - ts) / 1000))
  if (deltaSec < 60) return `${deltaSec} 秒前`
  if (deltaSec < 3600) return `${Math.floor(deltaSec / 60)} 分钟前`
  if (deltaSec < 86400) return `${Math.floor(deltaSec / 3600)} 小时前`
  return `${Math.floor(deltaSec / 86400)} 天前`
}

export function slugifyAccountId(name: string): string {
  const base = name
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9._-]/g, '')
    .slice(0, 40)
  const stamp = Date.now().toString(36).slice(-4)
  return (base || 'account') + '-' + stamp
}
