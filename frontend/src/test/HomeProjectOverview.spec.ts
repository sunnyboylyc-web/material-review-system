import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { toRaw } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HomeProjectOverview from '../components/HomeProjectOverview.vue'
import MetricValue from '../components/MetricValue.vue'
import SpendCurve from '../components/SpendCurve.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { NumericResult, ReviewQuery, ReviewResult } from '../domain/dashboard'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'

// These fixtures contain no business data. No HTTP, preference or snapshot service is used.
const NOW = '2026-10-17T16:05:00.000Z' // Shanghai 2026-10-18 00:05; UTC yesterday would be the wrong day.
const QUERY: ReviewQuery = {
  current: { start: '2026-10-11', end: '2026-10-17' },
  previous: { start: '2026-10-04', end: '2026-10-10' },
}
const KEYS = ['spend', 'gmv', 'impressions', 'clicks'] as const
type OverviewMetrics = ReviewResult['periods'][number]['groups'][number]['metrics'] & Partial<Record<'gmv' | 'impressions' | 'clicks', NumericResult>>
const valid = (value: number): NumericResult => ({ status: 'VALID', value, partial: false, partialReasons: [] })
const unknown = (): NumericResult => ({ status: 'UNKNOWN_INPUT', value: null, partial: false, partialReasons: [] })
const currentPeriod = (data: ReviewResult) => data.periods.find(period => period.key === 'CURRENT')!
const overall = (data: ReviewResult): OverviewMetrics => currentPeriod(data).groups.find(group => group.key === 'overall')!.metrics

async function response(projectId = 'synthetic-a', query = QUERY, limit = 3): Promise<ReviewResult> {
  const source = new SyntheticMockAdapter([{ id: projectId, name: `SYNTHETIC ${projectId}`, enabled: true }])
  const data = await source.fetchReview(projectId, query, limit)
  // Preserve the source fixture's incomplete PREVIOUS period and PARTIAL overall result.
  // The four CURRENT overall fields are independently complete and must control this overview.
  Object.assign(overall(data), { spend: valid(1000), gmv: valid(6500), impressions: valid(32000), clicks: valid(600) })
  const previous = data.periods.find(period => period.key === 'PREVIOUS')!
  Object.assign(previous.groups.find(group => group.key === 'overall')!.metrics, {
    spend: valid(8000), gmv: valid(9000), impressions: valid(999000), clicks: valid(9999),
  })
  data.topMaterials = Array.from({ length: 3 }, (_, index) => ({
    ...structuredClone(data.topMaterials[0]!), rank: index + 1,
    materialId: `SYNTHETIC_${projectId}_${index + 1}`,
    materialName: `SYNTHETIC ${projectId} 视频 ${index + 1}`,
    currentSpend: valid(30 - index * 10), roi: valid(2 + index), cpm: valid(20 + index),
    ctr: valid(.1 + index / 100), cvr: valid(.02 + index / 100), spendShare: valid(.03 - index / 100),
    creationDate: { status: 'VERIFIED', date: query.current.start, reason: null },
    dailySpend: data.topMaterials[0]!.dailySpend.map((day, dayIndex) => ({ date: day.date, spend: valid(dayIndex + index) })),
  }))
  return data
}

function adapter(change?: (data: ReviewResult) => void) {
  return { fetchReview: vi.fn(async (id: string, query: ReviewQuery, limit: number) => {
    const data = await response(id, query, limit); change?.(data); return data
  }) }
}
const wrappers: VueWrapper[] = []
let network: ReturnType<typeof vi.fn>
function render(api: Pick<DashboardDataAdapter, 'fetchReview'>, active = true) {
  const page = mount(HomeProjectOverview, { props: { projectId: 'synthetic-a', projectName: 'SYNTHETIC 项目 A', active, adapter: api } })
  wrappers.push(page); return page
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] }); vi.setSystemTime(NOW)
  network = vi.fn(() => { throw new Error('Real network requests are forbidden in this synthetic test') })
  vi.stubGlobal('fetch', network)
})
afterEach(() => {
  wrappers.splice(0).forEach(page => page.unmount())
  expect(network).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
  vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals()
})

describe('home project overview (synthetic fixtures only)', () => {
  it('uses the dynamic project name as the main heading and the seven-day label as its subordinate description', async () => {
    const page = render(adapter()); await flushPromises()
    expect(page.get('#home-overview-title').element.tagName).toBe('H2')
    expect(page.get('#home-overview-title').text()).toBe('SYNTHETIC 项目 A')
    expect(page.get('.home-overview-heading p').text()).toBe('项目近7天数据2026-10-11 — 2026-10-17')
    await page.setProps({ projectName: 'SYNTHETIC 项目 B' })
    expect(page.get('#home-overview-title').text()).toBe('SYNTHETIC 项目 B')
    expect(page.get('.home-overview-heading p').text()).not.toContain('SYNTHETIC 项目 A')
  })

  it('requests seven complete Shanghai days ending yesterday with limit 3 and renders CURRENT overall totals directly', async () => {
    const data = await response(); const api = { fetchReview: vi.fn(async () => data) }
    const page = render(api); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledExactlyOnceWith('synthetic-a', QUERY, 3)
    expect(page.get('.home-overview-heading').text()).toContain('2026-10-11 — 2026-10-17')
    expect(page.get('[data-home-metric="spend"]').text()).toContain('1,000.00')
    expect(page.get('[data-home-metric="gmv"]').text()).toContain('6,500.00')
    expect(page.get('[data-home-metric="impressions"]').text()).toContain('32,000')
    expect(page.get('[data-home-metric="clicks"]').text()).toContain('600')
    for (const key of KEYS) expect(toRaw(page.get(`[data-home-metric="${key}"]`).getComponent(MetricValue).props('result')!)).toBe(overall(data)[key])
    expect(data.topMaterials.reduce((sum, row) => sum + row.currentSpend.value!, 0)).toBe(60)
    expect(overall(data).gmv!.value).not.toBe(overall(data).spend.value! * overall(data).roi.value!)
    expect(page.get('.home-metric-strip').text()).not.toContain('9,000')
    expect(data.periods.find(period => period.key === 'PREVIOUS')!.coverage.state).toBe('PARTIAL')
    expect(data.metricsState).toBe('PARTIAL')
    expect(page.get('.home-data-signal').classes()).toContain('home-data-signal--green')
    expect(page.get('.home-data-signal').attributes('aria-label')).toBe('近7天数据完整')
    expect(page.get('.home-data-signal').text()).toBe('')
    expect(page.get('.home-data-signal').attributes('title')).toBeUndefined()
  })

  it.each(['current-day-missing', 'partial-field', 'missing-field', 'missing-partial-flag', 'missing-partial-reasons', 'unknown-field'] as const)('marks %s red without substituting zero or deriving totals', async problem => {
    const api = adapter(data => {
      if (problem === 'current-day-missing') currentPeriod(data).coverage = { state: 'PARTIAL', expectedDays: 7, coveredDays: 6, missingDates: [QUERY.current.start] }
      if (problem === 'partial-field') overall(data).gmv = { ...valid(6500), partial: true, partialReasons: ['SYNTHETIC_MISSING_GMV_INPUT'] }
      if (problem === 'missing-field') delete overall(data).gmv
      if (problem === 'missing-partial-flag') delete overall(data).gmv!.partial
      if (problem === 'missing-partial-reasons') delete overall(data).gmv!.partialReasons
      if (problem === 'unknown-field') overall(data).gmv = unknown()
    })
    const page = render(api); await flushPromises()
    expect(page.get('.home-data-signal').classes()).toContain('home-data-signal--red')
    expect(page.get('.home-data-signal').attributes('aria-label')).toBe('近7天数据有缺失或指标不可用')
    expect(page.get('.home-data-signal').text()).toBe('')
    expect(page.get('.home-data-signal').attributes('title')).toBeUndefined()
    const amount = page.get('[data-home-metric="gmv"]')
    if (problem === 'missing-field' || problem === 'missing-partial-flag' || problem === 'missing-partial-reasons') {
      expect(amount.findComponent(MetricValue).exists()).toBe(false)
      expect(amount.get('[aria-label="数据暂不可用"]').text()).toBe('—')
    } else if (problem === 'unknown-field') expect(amount.text()).toContain('数据未知')
    else expect(amount.text()).toContain('6,500.00')
    expect(amount.get('strong').text()).not.toBe('0.00元')
    expect(api.fetchReview).toHaveBeenCalledTimes(1)
  })

  it('treats verified zero totals as complete values', async () => {
    const api = adapter(data => {
      for (const key of KEYS) overall(data)[key] = valid(0)
      data.topMaterials = []
    })
    const page = render(api); await flushPromises()
    expect(page.get('.home-data-signal').classes()).toContain('home-data-signal--green')
    for (const key of KEYS) {
      const value = page.get(`[data-home-metric="${key}"]`).getComponent(MetricValue)
      expect(value.props('result')).toEqual(valid(0))
      expect(value.text()).toBe(key === 'spend' || key === 'gmv' ? '0.00' : '0')
    }
    expect(page.get('.home-ranking-list').text()).toContain('暂无可展示素材')
  })

  it('selects the first material by default and changes only the detail for selections 2 and 3', async () => {
    const data = await response(); const api = { fetchReview: vi.fn(async () => data) }
    const page = render(api); await flushPromises()
    const buttons = page.findAll('.home-ranking-list button')
    expect(buttons).toHaveLength(3)
    expect(buttons[0]!.attributes('aria-pressed')).toBe('true')
    expect(page.get('.home-material-detail h3').text()).toBe(data.topMaterials[0]!.materialName)
    for (const index of [1, 2]) {
      await buttons[index]!.trigger('click')
      const row = data.topMaterials[index]!
      expect(page.get('.home-material-detail h3').text()).toBe(row.materialName)
      expect(page.get('.home-detail-meta').text()).toContain(row.materialId)
      expect(buttons[index]!.attributes('aria-pressed')).toBe('true')
      expect(buttons[0]!.attributes('aria-pressed')).toBe('false')
      const detailValues = page.findAllComponents(MetricValue).filter(value => value.element.closest('.home-detail-metrics'))
      expect(detailValues.map(value => toRaw(value.props('result')!)))
        .toEqual([row.currentSpend, row.roi, row.cpm, row.ctr, row.cvr, row.spendShare])
      expect(toRaw(page.getComponent(SpendCurve).props('days'))).toBe(row.dailySpend)
      expect(page.getComponent(SpendCurve).props('days')).toHaveLength(7)
      expect(page.get('[data-home-metric="spend"]').text()).toContain('1,000.00')
    }
    expect(api.fetchReview).toHaveBeenCalledTimes(1)
  })

  it('ignores slow project A after project B has completed', async () => {
    const a = deferred<ReviewResult>(), b = deferred<ReviewResult>()
    const api = { fetchReview: vi.fn((id: string) => id === 'synthetic-a' ? a.promise : b.promise) }
    const page = render(api)
    await page.setProps({ projectId: 'synthetic-b', projectName: 'SYNTHETIC 项目 B' })
    const bData = await response('synthetic-b'); overall(bData).spend = valid(2222)
    b.resolve(bData); await flushPromises()
    expect(page.get('[data-home-metric="spend"]').text()).toContain('2,222.00')
    a.resolve(await response()); await flushPromises()
    expect(page.get('[data-home-metric="spend"]').text()).toContain('2,222.00')
    expect(page.get('.home-material-detail h3').text()).toContain('synthetic-b')
    expect(page.get('.home-material-detail').text()).not.toContain('synthetic-a')
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
  })

  it('does not request while inactive and ignores an in-flight result after leaving', async () => {
    const gate = deferred<ReviewResult>(); const api = { fetchReview: vi.fn(() => gate.promise) }
    const page = render(api, false); await flushPromises()
    expect(api.fetchReview).not.toHaveBeenCalled()
    await page.setProps({ projectId: 'synthetic-b', projectName: 'SYNTHETIC 项目 B' })
    await page.get('.home-refresh').trigger('click'); await flushPromises()
    expect(api.fetchReview).not.toHaveBeenCalled()
    await page.setProps({ active: true }); expect(api.fetchReview).toHaveBeenCalledTimes(1)
    await page.setProps({ active: false })
    gate.resolve(await response('synthetic-b')); await flushPromises()
    expect(page.findAll('.home-ranking-list button')).toHaveLength(0)
    expect(page.findAllComponents(MetricValue)).toHaveLength(0)
    expect(page.get('.home-overview').attributes('aria-busy')).toBe('false')
    expect(api.fetchReview).toHaveBeenCalledTimes(1)
  })

  it('reuses a same-project same-day cache on return and re-queries only on explicit refresh', async () => {
    const api = adapter(); const page = render(api); await flushPromises()
    await page.setProps({ projectId: 'synthetic-b', projectName: 'SYNTHETIC 项目 B' }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    await page.setProps({ projectId: 'synthetic-a', projectName: 'SYNTHETIC 项目 A' }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    expect(page.get('.home-material-detail h3').text()).toContain('synthetic-a')
    await page.setProps({ active: false }); await page.setProps({ active: true }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    await page.get('.home-refresh').trigger('click'); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(3)
    expect(api.fetchReview).toHaveBeenLastCalledWith('synthetic-a', QUERY, 3)
  })

  it('clears every project cache when the configuration revision changes while inactive', async () => {
    let amount = 1000
    const api = adapter(data => { overall(data).spend = valid(amount) })
    const page = render(api); await flushPromises()
    await page.setProps({ projectId: 'synthetic-b', projectName: 'SYNTHETIC 项目 B' }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    await page.setProps({ active: false })
    amount = 5000
    await page.setProps({ revision: 1 }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    expect(page.findAllComponents(MetricValue)).toHaveLength(0)
    await page.setProps({ active: true }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(3)
    expect(page.get('[data-home-metric="spend"]').text()).toContain('5,000.00')
    await page.setProps({ projectId: 'synthetic-a', projectName: 'SYNTHETIC 项目 A' }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(4)
    expect(api.fetchReview).toHaveBeenLastCalledWith('synthetic-a', QUERY, 3)
    expect(page.get('[data-home-metric="spend"]').text()).toContain('5,000.00')
    await page.setProps({ active: false }); await page.setProps({ active: true }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(4)
  })

  it('replaces both current and other-project cached values when the adapter changes', async () => {
    const original = adapter(), replacement = adapter(data => { overall(data).spend = valid(7000) })
    const page = render(original); await flushPromises()
    await page.setProps({ projectId: 'synthetic-b', projectName: 'SYNTHETIC 项目 B' }); await flushPromises()
    expect(original.fetchReview).toHaveBeenCalledTimes(2)
    await page.setProps({ adapter: replacement }); await flushPromises()
    expect(replacement.fetchReview).toHaveBeenCalledExactlyOnceWith('synthetic-b', QUERY, 3)
    expect(page.get('[data-home-metric="spend"]').text()).toContain('7,000.00')
    await page.setProps({ projectId: 'synthetic-a', projectName: 'SYNTHETIC 项目 A' }); await flushPromises()
    expect(replacement.fetchReview).toHaveBeenCalledTimes(2)
    expect(replacement.fetchReview).toHaveBeenLastCalledWith('synthetic-a', QUERY, 3)
    expect(page.get('[data-home-metric="spend"]').text()).toContain('7,000.00')
    expect(original.fetchReview).toHaveBeenCalledTimes(2)
    await page.setProps({ active: false }); await page.setProps({ active: true }); await flushPromises()
    expect(replacement.fetchReview).toHaveBeenCalledTimes(2)
  })

  it.each(['revision', 'adapter'] as const)('ignores an old same-project response after %s changes, including the cached result', async changed => {
    const oldResponse = deferred<ReviewResult>()
    const original = adapter(data => { overall(data).spend = valid(4321) })
    original.fetchReview.mockReturnValueOnce(oldResponse.promise)
    const replacement = adapter(data => { overall(data).spend = valid(7654) })
    const page = render(original); await flushPromises()
    expect(page.get('.home-overview').attributes('aria-busy')).toBe('true')
    if (changed === 'revision') await page.setProps({ revision: 1 })
    else await page.setProps({ adapter: replacement })
    await flushPromises()
    const amount = changed === 'revision' ? '4,321.00' : '7,654.00'
    expect(page.get('[data-home-metric="spend"]').text()).toContain(amount)
    oldResponse.resolve(await response()); await flushPromises()
    expect(page.get('[data-home-metric="spend"]').text()).toContain(amount)
    expect(page.get('.home-overview').attributes('aria-busy')).toBe('false')
    expect(page.find('[role=alert]').exists()).toBe(false)
    await page.setProps({ active: false }); await page.setProps({ active: true }); await flushPromises()
    expect(page.get('[data-home-metric="spend"]').text()).toContain(amount)
    expect(original.fetchReview).toHaveBeenCalledTimes(changed === 'revision' ? 2 : 1)
    expect(replacement.fetchReview).toHaveBeenCalledTimes(changed === 'adapter' ? 1 : 0)
  })

  it('discards prior results on read failure and allows an explicit retry without a hidden loop', async () => {
    const api = adapter(); const page = render(api); await flushPromises()
    api.fetchReview.mockRejectedValueOnce(new Error('SYNTHETIC_READ_FAILURE'))
    await page.get('.home-refresh').trigger('click'); await flushPromises()
    expect(page.get('[role=alert]').text()).toBe('SYNTHETIC_READ_FAILURE')
    expect(page.findAll('.home-ranking-list button')).toHaveLength(0)
    expect(page.findAllComponents(MetricValue)).toHaveLength(0)
    expect(page.get('.home-data-signal').attributes('aria-label')).toBe('近7天数据读取失败')
    expect(page.get('.home-data-signal').classes()).toContain('home-data-signal--red')
    await vi.advanceTimersByTimeAsync(60_000); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    await page.get('.home-refresh').trigger('click'); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(3)
    expect(page.find('[role=alert]').exists()).toBe(false)
    expect(page.findAll('.home-ranking-list button')).toHaveLength(3)
  })

  it('does not query in the background across Shanghai midnight and uses the new seven-day range on return', async () => {
    const api = adapter(); const page = render(api); await flushPromises()
    await page.setProps({ active: false })
    vi.setSystemTime('2026-10-18T16:05:00.000Z')
    await vi.advanceTimersByTimeAsync(30_000); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(1)
    await page.setProps({ active: true }); await flushPromises()
    expect(api.fetchReview).toHaveBeenCalledTimes(2)
    expect(api.fetchReview).toHaveBeenLastCalledWith('synthetic-a', {
      current: { start: '2026-10-12', end: '2026-10-18' },
      previous: { start: '2026-10-05', end: '2026-10-11' },
    }, 3)
    expect(page.get('.home-overview-heading').text()).toContain('2026-10-12 — 2026-10-18')
  })

  it.each(['CURRENT', 'PREVIOUS'] as const)('rejects same-project results with a wrong %s date before showing any values', async periodKey => {
    const api = adapter(data => { data.periods.find(period => period.key === periodKey)!.range.end = '2026-01-01' })
    const page = render(api); await flushPromises()
    expect(page.get('[role=alert]').text()).toContain('项目或日期不一致，未展示本次结果')
    expect(page.findAll('.home-ranking-list button')).toHaveLength(0)
    expect(page.findAllComponents(MetricValue)).toHaveLength(0)
    expect(page.text()).not.toContain('SYNTHETIC synthetic-a 视频')
    expect(api.fetchReview).toHaveBeenCalledExactlyOnceWith('synthetic-a', QUERY, 3)
  })
})
