import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { useSubmittedTasks } from './useSubmittedTasks'

vi.mock('@/lib/api', () => ({
  api: vi.fn(),
}))

import { api } from '@/lib/api'

describe('useSubmittedTasks', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(api).mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('tracks a task immediately without waiting for poll', () => {
    vi.mocked(api).mockResolvedValue({
      task_id: 't1',
      status: 'accepted',
      error_code: null,
    })
    const { tasks, track } = useSubmittedTasks()
    track('t1', '发布笔记 · 测试号')
    expect(tasks.value).toHaveLength(1)
    expect(tasks.value[0]).toMatchObject({
      task_id: 't1',
      label: '发布笔记 · 测试号',
      status: 'accepted',
    })
  })
})
