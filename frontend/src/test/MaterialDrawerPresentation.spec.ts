import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MaterialDrawer from '../components/MaterialDrawer.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'

describe('material drawer date terminology', () => {
  it('renames only the creation-date label while preserving the date and its missing-state formatter', async () => {
    const result = await new SyntheticMockAdapter().fetchTop('sample-project', '2026-10-12', 10)
    const row = { ...result.rows[0]!,
      creationDate: { status: 'VERIFIED' as const, date: '2026-08-15', reason: null },
      firstSpend: { status: 'VERIFIED' as const, date: '2026-09-19', earliestObservedDate: '2026-09-19' },
    }
    const page = mount(MaterialDrawer, { props: { row, date: '2026-10-12' } })
    expect(page.get('.drawer-context').text()).toContain('首次投放时间 2026-08-15')
    expect(page.get('.drawer-context').text()).not.toContain('2026-09-19')
    await page.setProps({ row: { ...row, creationDate: { status: 'PENDING', date: null, reason: 'MISSING_CREATION_DATE' } } })
    expect(page.get('.drawer-context').text()).toContain('首次投放时间 待核（创建日期缺失）')
    page.unmount()
  })
})
