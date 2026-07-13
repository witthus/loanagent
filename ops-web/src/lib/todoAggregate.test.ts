import { describe, expect, it } from 'vitest'
import { buildTodoItems } from './todoAggregate'

describe('buildTodoItems', () => {
  it('includes unread dms and ready schedules', () => {
    const items = buildTodoItems({
      threads: [
        { thread_id: 't1', title_summary: '客户甲', unread: true },
        { thread_id: 't2', title_summary: '客户乙', unread: false },
      ],
      schedules: [{ schedule_id: 's1', status: 'ready', account_id: 'a1', content_id: 'c1' }],
    })
    expect(items.map((i) => i.kind)).toEqual(['dm', 'publish'])
    expect(items[0].actionLabel).toBe('去回复')
  })
})
