import { describe, expect, it } from 'vitest'
import {
  deviceDisplayName,
  isOutdatedAgent,
  locationLabel,
  relativeHeartbeat,
} from './deviceFleet'

describe('deviceFleet helpers', () => {
  it('prefers display name then model', () => {
    expect(deviceDisplayName({ device_id: 'dev-1', display_name: '黄冈-01', model: 'Turbo' })).toBe(
      '黄冈-01',
    )
    expect(deviceDisplayName({ device_id: 'dev-1', model: 'Turbo' })).toBe('Turbo')
  })

  it('detects outdated agents', () => {
    expect(isOutdatedAgent({ device_id: 'dev-1', agent_version: '0.1.1-debug' })).toBe(true)
    expect(isOutdatedAgent({ device_id: 'dev-1', agent_version: '0.1.2-debug' })).toBe(false)
    expect(isOutdatedAgent({ device_id: 'redmi-note-12', agent_version: '0.1.2-debug' })).toBe(true)
    expect(isOutdatedAgent({ device_id: 'dev-1', agent_version: null })).toBe(true)
    expect(isOutdatedAgent({ device_id: 'dev-1', agent_version: '' })).toBe(true)
  })

  it('formats location with geo primary', () => {
    expect(locationLabel({ geo_label: '湖北黄冈', public_ip: '1.2.3.4' })).toEqual({
      primary: '湖北黄冈',
      secondary: '1.2.3.4',
    })
  })

  it('formats relative heartbeat', () => {
    const now = Date.parse('2026-07-14T10:00:00Z')
    expect(relativeHeartbeat('2026-07-14T09:58:00Z', now)).toBe('2 分钟前')
  })
})
