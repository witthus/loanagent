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
})
