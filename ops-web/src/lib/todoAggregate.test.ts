import { describe, expect, it } from 'vitest'
import { buildTodoItems } from './todoAggregate'

describe('buildTodoItems', () => {
  it('includes unread dms and ready schedules with times and account names', () => {
    const items = buildTodoItems({
      threads: [
        {
          thread_id: 't1',
          account_id: 'a1',
          title_summary: '客户甲',
          unread: true,
          updated_at: '2026-07-14T01:00:00Z',
        },
        { thread_id: 't2', account_id: 'a1', title_summary: '客户乙', unread: false },
      ],
      schedules: [
        {
          schedule_id: 's1',
          status: 'ready',
          account_id: 'a1',
          content_id: 'c1',
          window_start: '2026-07-14T08:00:00Z',
        },
      ],
      accountNames: { a1: '红米主号' },
    })
    expect(items.map((i) => i.kind)).toEqual(['dm', 'publish'])
    expect(items[0].actionLabel).toBe('去回复')
    expect(items[0].to).toContain('account_id=a1')
    expect(items[0].at).toBe('2026-07-14T01:00:00Z')
    expect(items[1].subtitle).toContain('红米主号')
    expect(items[1].at).toBe('2026-07-14T08:00:00Z')
  })

  it('links alerts and failed tasks to focused detail pages', () => {
    const items = buildTodoItems({
      alerts: [
        {
          alert_id: 'al1',
          kind: 'engagement_stopped',
          message: '互动链已停止',
          created_at: '2026-07-14T02:00:00Z',
        },
      ],
      failedTasks: [
        {
          task_id: 'tk1',
          status: 'failed',
          error_code: 'NAV_TIMEOUT',
          created_at: '2026-07-14T03:00:00Z',
        },
      ],
    })
    expect(items[0].to).toBe('/alerts?alert_id=al1')
    expect(items[0].at).toBe('2026-07-14T02:00:00Z')
    expect(items[1].to).toBe('/tasks?task_id=tk1')
    expect(items[1].at).toBe('2026-07-14T03:00:00Z')
  })
})
