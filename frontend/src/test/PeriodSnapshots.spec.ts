import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import DateRangePicker from '../components/DateRangePicker.vue'
import PeriodReview from '../components/PeriodReview.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { httpPeriodSnapshots, type PeriodSnapshots, type PeriodSnapshot } from '../adapters/periodSnapshots'

const projects = [{ id: 'a', name: 'A', enabled: true }, { id: 'b', name: 'B', enabled: true }]
const query = { current: { start: '2026-10-01', end: '2026-10-07' }, previous: { start: '2026-09-24', end: '2026-09-30' } }
const savedAt = '2026-10-15T12:00:00Z'
const response = (data: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(data), { status })
afterEach(() => vi.unstubAllGlobals())

describe('period result snapshots', () => {
  it('validates response contract/project and handles absent/unavailable or unsaved results', async () => {
    const result = { ...await new SyntheticMockAdapter(projects).fetchReview('a', query, 20), evidenceKind: 'CURRENT_FACTS' }
    const fetcher = vi.fn().mockImplementation(async () => response({ result, savedAt }))
    vi.stubGlobal('fetch', fetcher)
    expect(await httpPeriodSnapshots.load('a')).toEqual({ result, savedAt })
    await expect(httpPeriodSnapshots.load('b')).rejects.toThrow('项目不一致')
    fetcher.mockResolvedValueOnce(response(null, 204))
    expect(await httpPeriodSnapshots.load('a')).toBeNull()
    fetcher.mockResolvedValueOnce(response({}, 409))
    await expect(httpPeriodSnapshots.load('a')).rejects.toThrow('暂不可用')
    fetcher.mockResolvedValueOnce(response({ result, savedAt: null, persisted: false, warning: 'REVIEW_SNAPSHOT_SAVE_FAILED' }))
    expect((await httpPeriodSnapshots.refresh('a', query, 20)).warning).toContain('未能保存')
    fetcher.mockResolvedValueOnce(response({ result, savedAt: null, persisted: false, warning: 'REVIEW_SNAPSHOT_TOO_LARGE' }))
    const tooLarge = await httpPeriodSnapshots.refresh('a', query, 20)
    expect(tooLarge.result).toEqual(result)
    expect(tooLarge.persisted).toBe(false)
    expect(tooLarge.warning).toContain('超过保存容量')
    expect(tooLarge.warning).toContain('未覆盖上次保存的结果')
    expect(tooLarge.warning).toContain('缩短周期')
    expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ currentStart: query.current.start, currentEnd: query.current.end,
      previousStart: query.previous.start, previousEnd: query.previous.end, limit: 20 })
  })
  it('restores period only across remounts without recalculation, preserves later date preferences and project isolation', async () => {
    const adapter = new SyntheticMockAdapter(projects)
    const result = await adapter.fetchReview('a', query, 20)
    const snapshots: PeriodSnapshots = { load: vi.fn(async id => id === 'a' ? { result, savedAt } : null), refresh: vi.fn() }
    const prefs = { load: vi.fn(async () => ({ ...query, current: { start: '2026-10-08', end: '2026-10-14' } })), save: vi.fn() }
    const review = vi.spyOn(adapter, 'fetchReview'), top = vi.spyOn(adapter, 'fetchTop'), freshness = vi.spyOn(adapter, 'fetchFreshness')
    for (let i = 0; i < 2; i++) {
      const page = mount(App, { props: { adapter, periodSnapshots: snapshots, periodPreferences: prefs } }); await flushPromises()
      expect(page.findComponent(PeriodReview).props('result')).toEqual(result)
      expect(page.text()).toContain('上次保存的结果')
      expect(page.text()).toContain('可能不是最新数据')
      expect(page.text()).toContain('条件已修改，请刷新数据')
      expect(page.findAllComponents(DateRangePicker)[0].props('modelValue').start).toBe('2026-10-08')
      expect(page.find('.top-results').exists()).toBe(false)
      await page.findAll('.project-button')[1].trigger('click'); await flushPromises()
      expect(page.findComponent(PeriodReview).exists()).toBe(false)
      await page.findAll('.project-button')[0].trigger('click'); await flushPromises()
      expect(page.findComponent(PeriodReview).props('result')).toEqual(result)
      page.unmount()
    }
    expect(review).not.toHaveBeenCalled(); expect(top).not.toHaveBeenCalled(); expect(freshness).not.toHaveBeenCalled()
  })
  it('manual refresh supersedes late restore and shows save failure without discarding newly computed result', async () => {
    const adapter = new SyntheticMockAdapter(projects)
    const result = await adapter.fetchReview('a', query, 20)
    let release!: (value: { result: typeof result; savedAt: string }) => void
    const snapshots: PeriodSnapshots = { load: vi.fn(() => new Promise<PeriodSnapshot>(resolve => { release = resolve })),
      refresh: vi.fn(async () => ({ result, savedAt: null, persisted: false, warning: '本次结果未保存' })) }
    const page = mount(App, { props: { adapter, periodSnapshots: snapshots,
      periodPreferences: { load: async () => query, save: async () => {} } } }); await flushPromises()
    expect(page.text()).toContain('正在读取上次保存')
    await page.get('.date-form--review').trigger('submit'); await flushPromises()
    expect(page.text()).toContain('本次结果未保存')
    release({ result: { ...result, requestedAt: '2000-01-01T00:00:00Z' }, savedAt }); await flushPromises()
    expect(page.findComponent(PeriodReview).props('result').requestedAt).toBe(result.requestedAt)
    expect(page.text()).not.toContain('上次保存的结果 · 保存时间')
    page.unmount()
  })
  it('unavailable saved results do not block an explicit refresh and successful save survives a new page', async () => {
    const adapter = new SyntheticMockAdapter(projects)
    const result = await adapter.fetchReview('a', query, 20)
    const snapshots: PeriodSnapshots = { load: vi.fn().mockRejectedValueOnce(new Error('上次结果不可用')).mockResolvedValue({ result, savedAt }),
      refresh: vi.fn().mockResolvedValue({ result, savedAt, persisted: true, warning: null }) }
    const props = { adapter, periodSnapshots: snapshots, periodPreferences: { load: async () => query, save: async () => {} } }
    const page = mount(App, { props }); await flushPromises()
    expect(page.text()).toContain('上次结果不可用')
    expect(page.findComponent(PeriodReview).exists()).toBe(false)
    await page.get('.date-form--review').trigger('submit'); await flushPromises()
    expect(snapshots.refresh).toHaveBeenCalledWith('a', query, 20)
    expect(page.text()).toContain('上次保存的结果 · 保存时间')
    expect(page.text()).not.toContain('上次结果不可用')
    page.unmount()
    const second = mount(App, { props }); await flushPromises()
    expect(second.findComponent(PeriodReview).props('result')).toEqual(result)
    expect(snapshots.refresh).toHaveBeenCalledTimes(1)
    second.unmount()
  })
})
