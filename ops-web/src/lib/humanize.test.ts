import { describe, expect, it } from 'vitest'
import { humanizeError } from './humanize'

describe('humanizeError', () => {
  it('maps A11Y_DOWN', () => {
    expect(humanizeError('A11Y_DOWN').title).toContain('辅助功能')
  })

  it('falls back unknown codes', () => {
    expect(humanizeError('WEIRD').detail).toContain('WEIRD')
  })

  it('handles empty', () => {
    expect(humanizeError(null).title).toContain('问题')
  })

  it('maps device and screen readiness codes', () => {
    expect(humanizeError('DEVICE_UNAVAILABLE').nextStep).toContain('设备')
    expect(humanizeError('SCREEN_NOT_READY').title).toContain('亮屏')
    expect(humanizeError('PLAYBOOK_FORBIDDEN').title).toContain('角色')
    expect(humanizeError('DEVICE_NOT_FOUND').title).toContain('手机')
    expect(humanizeError('DEVICE_ALREADY_BOUND').title).toContain('绑')
    expect(humanizeError('SET_TEXT_FAILED').title).toContain('输入框')
    expect(humanizeError('EDITOR_NOT_READY').title).toContain('编辑页')
  })
})

