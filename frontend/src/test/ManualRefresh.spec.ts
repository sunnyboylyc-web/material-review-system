import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import DateRangePicker from '../components/DateRangePicker.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { PeriodPreferences } from '../adapters/periodPreferences'

const projects = [{ id: 'a', accountId: 'a', name: 'A', enabled: true }, { id: 'b', accountId: 'b', name: 'B', enabled: true }]
const saved = { current: { start: '2025-01-01', end: '2025-01-07' }, previous: { start: '2024-12-25', end: '2024-12-31' } }
describe('D128 manual refresh and per-project state', () => {
  it('does not query on entry, date/count edits, tab or project switches; retains separate results and actual date labels', async () => {
    const adapter = new SyntheticMockAdapter(projects)
    const top = vi.spyOn(adapter, 'fetchTop'), review = vi.spyOn(adapter, 'fetchReview'), freshness = vi.spyOn(adapter, 'fetchFreshness')
    const page = mount(App, { props: { adapter } }); await flushPromises()
    await page.get('#top-date').setValue('2026-10-01')
    await page.get('[aria-label="增加 TOP 显示数量"]').trigger('click')
    await page.findAll('.global-nav button')[2].trigger('click')
    expect(top).not.toHaveBeenCalled(); expect(review).not.toHaveBeenCalled(); expect(freshness).not.toHaveBeenCalled()
    await page.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(top).toHaveBeenLastCalledWith('a', '2026-10-01', 11)
    await page.get('#top-date').setValue('2026-10-02')
    expect(page.text()).toContain('条件已修改，请刷新数据')
    expect(page.text()).toContain('当前结果：统计日 2026-10-01')
    await page.findAll('.project-button')[1].trigger('click'); await flushPromises()
    expect(page.find('.top-results').exists()).toBe(false)
    await page.get('#top-date').setValue('2026-10-08')
    await page.get('.date-form--top').trigger('submit'); await flushPromises()
    await page.findAll('.project-button')[0].trigger('click'); await flushPromises()
    expect((page.get('#top-date').element as HTMLInputElement).value).toBe('2026-10-02')
    expect(page.text()).toContain('当前结果：统计日 2026-10-01')
    expect(page.findAll('.global-nav button')[2].classes()).toContain('active')
    expect(top).toHaveBeenCalledTimes(2); expect(review).not.toHaveBeenCalled()
    page.unmount()
  })
  it('restores account period dates without querying and saves valid edits before refresh; a new page contains no cached results', async () => {
    const prefs: PeriodPreferences = { load: vi.fn().mockResolvedValue(saved), save: vi.fn().mockResolvedValue(undefined) }
    const adapter = new SyntheticMockAdapter(projects), top = vi.spyOn(adapter, 'fetchTop'), review = vi.spyOn(adapter, 'fetchReview')
    const page = mount(App, { props: { adapter, periodPreferences: prefs } }); await flushPromises()
    const picker = page.findAllComponents(DateRangePicker)[0]
    expect(picker.props('modelValue')).toEqual(saved.current)
    expect(top).not.toHaveBeenCalled(); expect(review).not.toHaveBeenCalled()
    const next = { start: '2025-02-01', end: '2025-02-07' }
    picker.vm.$emit('update:modelValue', next); await page.vm.$nextTick(); picker.vm.$emit('confirm', next); await flushPromises()
    expect(prefs.save).toHaveBeenLastCalledWith('a', { ...saved, current: next })
    expect(review).not.toHaveBeenCalled()
    picker.vm.$emit('update:modelValue', { start: '2025-03-01', end: '2025-02-07' }); await page.vm.$nextTick(); picker.vm.$emit('confirm'); await flushPromises()
    expect(prefs.save).toHaveBeenCalledTimes(1)
    page.unmount()
    const second = mount(App, { props: { adapter, periodPreferences: prefs } }); await flushPromises()
    expect(second.find('.top-results').exists()).toBe(false)
    expect(second.find('.period-review-v2').exists()).toBe(false)
    expect(second.findAllComponents(DateRangePicker)[0].props('modelValue')).toEqual(saved.current)
    second.unmount()
  })
  it('drops late project responses rather than placing another project result on the active screen', async () => {
    const adapter = new SyntheticMockAdapter(projects), original = adapter.fetchTop.bind(adapter)
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    vi.spyOn(adapter, 'fetchTop').mockImplementation(async (...args) => { await gate; return original(...args) })
    const page = mount(App, { props: { adapter } }); await flushPromises()
    await page.get('.date-form--top').trigger('submit')
    await page.findAll('.project-button')[1].trigger('click'); await flushPromises()
    release(); await flushPromises()
    expect(page.get('.workspace-header h2').text()).toBe('B')
    expect(page.find('.top-results').exists()).toBe(false)
    expect(page.get('.date-form--top button[type="submit"]').attributes('disabled')).toBeUndefined()
    page.unmount()
  })
  it('retains the requested review result if a draft count changes in flight and labels it pending', async () => {
    const adapter = new SyntheticMockAdapter(projects), original = adapter.fetchReview.bind(adapter)
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    vi.spyOn(adapter, 'fetchReview').mockImplementation(async (...args) => { await gate; return original(...args) })
    const page = mount(App, { props: { adapter } }); await flushPromises()
    await page.get('.date-form--review').trigger('submit')
    ;(page.vm as unknown as { reviewTopComparisonLimit: number }).reviewTopComparisonLimit = 21
    release(); await flushPromises()
    expect(page.findComponent({ name: 'PeriodReview' }).props('result').topComparisonDisplayLimit).toBe(20)
    expect(page.text()).toContain('条件已修改，请刷新数据')
    page.unmount()
  })
  it('reloads saved period dates after a project is removed and re-enabled in the same page', async () => {
    const prefs: PeriodPreferences = { load: vi.fn().mockResolvedValue(saved), save: vi.fn().mockResolvedValue(undefined) }
    const adapter = new SyntheticMockAdapter(projects)
    const page = mount(App, { props: { adapter, periodPreferences: prefs } }); await flushPromises()
    expect(prefs.load).toHaveBeenCalledWith('a')
    adapter.replaceProjects([projects[1]]); await flushPromises()
    adapter.replaceProjects(projects); await flushPromises()
    await page.findAll('.project-button')[0].trigger('click'); await flushPromises()
    expect(vi.mocked(prefs.load).mock.calls.filter(call => call[0] === 'a')).toHaveLength(2)
    expect(page.findAllComponents(DateRangePicker)[0].props('modelValue')).toEqual(saved.current)
    page.unmount()
  })
})
