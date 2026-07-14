import { describe, expect, it } from 'vitest'
import {
  accountHealth,
  accountOptionLabel,
  buildDeviceMap,
  buildFleetHealthTodos,
  isAccountRunnable,
  type FleetAccount,
  type FleetDevice,
} from './fleet'

const account: FleetAccount = {
  account_id: 'a1',
  display_name: '红米主号',
  role: 'PUBLISHER_MAIN',
  device_id: 'd1',
  status: 'active',
}

describe('fleet helpers', () => {
  it('marks ready when device online and a11y bound', () => {
    const devices = buildDeviceMap([
      { device_id: 'd1', online: true, a11y_bound: true } satisfies FleetDevice,
    ])
    expect(accountHealth(account, devices)).toBe('ready')
    expect(isAccountRunnable(account, devices)).toBe(true)
    expect(accountOptionLabel(account, devices)).toContain('可用')
  })

  it('disables offline and a11y-down accounts', () => {
    expect(
      accountHealth(account, buildDeviceMap([{ device_id: 'd1', online: false, a11y_bound: true }])),
    ).toBe('offline')
    expect(
      accountHealth(account, buildDeviceMap([{ device_id: 'd1', online: true, a11y_bound: false }])),
    ).toBe('a11y_down')
    expect(accountHealth({ ...account, device_id: null }, {})).toBe('unbound')
  })

  it('treats paused/blocked/needs_login as not runnable', () => {
    const devices = buildDeviceMap([{ device_id: 'd1', online: true, a11y_bound: true }])
    expect(accountHealth({ ...account, status: 'paused' }, devices)).toBe('paused')
    expect(isAccountRunnable({ ...account, status: 'paused' }, devices)).toBe(false)
    expect(accountHealth({ ...account, status: 'blocked' }, devices)).toBe('blocked')
    expect(accountHealth({ ...account, status: 'needs_login' }, devices)).toBe('needs_login')
    expect(accountOptionLabel({ ...account, status: 'paused' }, devices)).toContain('已暂停')
  })

  it('builds fleet health todos for non-ready accounts', () => {
    const devices = buildDeviceMap([
      { device_id: 'd1', online: false, a11y_bound: true, last_seen_at: '2026-07-14T01:00:00Z' },
    ])
    const todos = buildFleetHealthTodos(
      [account, { ...account, account_id: 'a2', display_name: '互动号', status: 'paused', device_id: 'd1' }],
      devices,
      { a1: '红米主号', a2: '互动号' },
    )
    expect(todos.map((t) => t.subtitle)).toEqual(['设备离线', '已暂停'])
    expect(todos[0].to).toBe('/devices')
    expect(todos[1].to).toBe('/accounts')
  })
})
