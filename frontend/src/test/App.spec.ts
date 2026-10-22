import { refreshDashboard } from './refreshDashboard'
import DateRangePicker from '../components/DateRangePicker.vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import styles from '../styles.css?raw'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { ProjectModule, ReviewQuery, ReviewResult, TopResult } from '../domain/dashboard'

const SAMPLE: ProjectModule = {
  id: 'sample-project',
  accountId: 'SYNTHETIC_ACCOUNT_DEMO',
  name: '示例项目',
  enabled: true,
}

const FAN: ProjectModule = {
  id: 'fan',
  accountId: 'SYNTHETIC_ACCOUNT_FAN',
  name: '吹风机',
  enabled: true,
}

class RecordingAdapter extends SyntheticMockAdapter implements DashboardDataAdapter {
  readonly fetchReviewSpy = vi.fn(
    (projectId: string, query: ReviewQuery, topComparisonDisplayLimit: number) =>
      super.fetchReview(projectId, query, topComparisonDisplayLimit),
  )
  readonly fetchTopSpy = vi.fn((projectId: string, date: string, displayLimit: number) =>
    super.fetchTop(projectId, date, displayLimit),
  )

  override fetchReview(
    projectId: string,
    query: ReviewQuery,
    topComparisonDisplayLimit: number,
  ) {
    return this.fetchReviewSpy(projectId, query, topComparisonDisplayLimit)
  }

  override fetchTop(projectId: string, date: string, displayLimit: number) {
    return this.fetchTopSpy(projectId, date, displayLimit)
  }
}

interface Deferred<T> {
  promise: Promise<T>
  resolve(value: T | PromiseLike<T>): void
  reject(reason?: unknown): void
}

function deferred<T>(): Deferred<T> {
  let resolve!: Deferred<T>['resolve']
  let reject!: Deferred<T>['reject']
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

class ControlledSyntheticAdapter extends SyntheticMockAdapter {
  readonly failedProjects = new Set<string>()
  readonly reviewGates = new Map<string, Deferred<void>>()
  readonly topGates = new Map<string, Deferred<void>>()

  override async fetchReview(
    projectId: string,
    query: ReviewQuery,
    topComparisonDisplayLimit: number,
  ): Promise<ReviewResult> {
    if (this.failedProjects.has(projectId)) {
      throw new Error(`合成周期请求失败：${projectId}`)
    }
    await this.reviewGates.get(`${query.current.start}:${topComparisonDisplayLimit}`)?.promise
    return super.fetchReview(projectId, query, topComparisonDisplayLimit)
  }

  override async fetchTop(
    projectId: string,
    selectedDate: string,
    displayLimit: number,
  ): Promise<TopResult> {
    if (this.failedProjects.has(projectId)) {
      throw new Error(`合成 TOP 请求失败：${projectId}`)
    }
    await this.topGates.get(`${selectedDate}:${displayLimit}`)?.promise
    return super.fetchTop(projectId, selectedDate, displayLimit)
  }
}

describe('dashboard shell', () => {
  it('groups projects into weighted independent collapsible lists without changing the active account', async () => {
    const adapter = new SyntheticMockAdapter([{ ...SAMPLE, category: 'SMALL_APPLIANCE' }, FAN])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    const large = wrapper.get('[data-category="LARGE_APPLIANCE"]')
    const small = wrapper.get('[data-category="SMALL_APPLIANCE"]')
    const other = wrapper.get('[data-category="OTHER"]')
    expect(wrapper.findAll('[data-category]').map(group => group.attributes('data-category'))).toEqual(['SMALL_APPLIANCE', 'LARGE_APPLIANCE', 'OTHER'])
    expect(large.text()).toContain('暂无已启用项目')
    expect(small.text()).toContain('示例项目')
    expect(other.text()).toContain('吹风机')
    expect([large, small, other].map(group => (group.element as HTMLElement).style.flexGrow)).toEqual(['4', '4', '2'])
    const toggle = small.get('.project-category-toggle')
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.attributes('aria-controls')).toBe('category-SMALL_APPLIANCE')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect((small.get('.project-category-list').element as HTMLElement).style.display).toBe('none')
    expect(wrapper.get('.workspace-header h2').text()).toBe('示例项目')
    expect((small.element as HTMLElement).style.flexGrow).toBe('0')
    adapter.replaceProjects([{ ...SAMPLE, category: 'LARGE_APPLIANCE' }, FAN])
    await flushPromises()
    expect(large.find('.project-button--active').text()).toContain('示例项目')
    expect(small.findAll('.project-button')).toHaveLength(0)
    expect(styles).toContain('.project-category-list { display: flex;')
    expect(styles).toContain('overflow-y: auto; overflow-x: hidden; overscroll-behavior: contain;')
    wrapper.unmount()
  })
  it('submits only completed picker ranges, keeps the other period unchanged and refreshes the committed query', async () => {
    const adapter=new RecordingAdapter([SAMPLE])
    const wrapper=mount(App,{props:{adapter}});await flushPromises(); await refreshDashboard(wrapper)
    const picker=wrapper.findAllComponents(DateRangePicker)[0]
    const previous={...wrapper.findAllComponents(DateRangePicker)[1].props('modelValue')}
    adapter.fetchReviewSpy.mockClear()
    await picker.get('[aria-label="本周期开始日期"]').trigger('click')
    await picker.get('input[data-part="start"]').setValue('2026-09-01')
    expect(adapter.fetchReviewSpy).not.toHaveBeenCalled()
    await picker.get('input[data-part="end"]').setValue('2026-08-31')
    expect(adapter.fetchReviewSpy).not.toHaveBeenCalled()
    await picker.get('input[data-part="end"]').setValue('2026-09-05');await flushPromises()
    expect(adapter.fetchReviewSpy).not.toHaveBeenCalled()
    await wrapper.get('.date-form--review').trigger('submit');await flushPromises()
    expect(adapter.fetchReviewSpy).toHaveBeenCalledTimes(1)
    expect(adapter.fetchReviewSpy).toHaveBeenLastCalledWith('sample-project',{current:{start:'2026-09-01',end:'2026-09-05'},previous},20)
    expect(wrapper.findAllComponents(DateRangePicker)[1].props('modelValue')).toEqual(previous)
  })
  it('uses the approved material-data brand and independent business pages', async () => {
    const wrapper=mount(App,{props:{adapter:new SyntheticMockAdapter([SAMPLE])}});await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('.brand h1').text()).toBe('素材数据')
    expect(wrapper.find('.brand .eyebrow').exists()).toBe(false)
    expect(wrapper.find('.dashboard-tabs').exists()).toBe(false)
    expect(wrapper.find('.constellation-band').exists()).toBe(false)
    expect(wrapper.findAll('.global-nav button span').map(button=>button.text())).toEqual(['工作台','每日TOP素材','周期数据','数据运维'])
    expect(wrapper.get('#review-title').text()).toBe('周期素材数据')
    expect(wrapper.findAll('.top-coverage-indicators .coverage-indicator')).toHaveLength(0)
  })
  it('quick-selects limits, retains custom values and never changes the period limit', async () => {
    const adapter = new RecordingAdapter([SAMPLE])
    const wrapper = mount(App, { props: { adapter } }); await flushPromises(); await refreshDashboard(wrapper)
    const select = wrapper.get('[aria-label="快速选择 TOP 显示数量"]')
    expect(select.findAll('option').map(option => option.text())).toEqual(['10 条', '20 条', '30 条', '40 条', '50 条'])
    adapter.fetchReviewSpy.mockClear()
    await select.setValue('50'); await flushPromises()
    await wrapper.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 50)
    await wrapper.get('[aria-label="增加 TOP 显示数量"]').trigger('click'); await flushPromises()
    expect((select.element as HTMLSelectElement).value).toBe('51')
    expect(select.text()).toContain('51 条')
    expect(adapter.fetchReviewSpy).not.toHaveBeenCalled()
  })

  it('clears selected and expanded details during a new query and restores the first rank', async () => {
    const adapter = new ControlledSyntheticAdapter([SAMPLE, FAN])
    const wrapper = mount(App, { props: { adapter } }); await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.find('.selected-material-name').exists()).toBe(true)
    await wrapper.get('.expand-button').trigger('click')
    expect(wrapper.get('.expand-button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.expand-button').text()).toBe('收起')
    const gate = deferred<void>()
    adapter.topGates.set('2026-10-08:10', gate)
    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.get('.date-form--top').trigger('submit')
    expect(wrapper.find('.selected-material-name').exists()).toBe(false)
    expect(wrapper.find('.expanded-material').exists()).toBe(false)
    gate.resolve(); await flushPromises()
    expect(wrapper.get('.selected-material-name').text()).toBe('合成素材（非业务数据）')
    expect(wrapper.get('.expand-button').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.expand-button').text()).toBe('展开')
    expect(wrapper.find('.expand-button svg').exists()).toBe(true)
    const limitGate = deferred<void>(); adapter.topGates.set('2026-10-08:30', limitGate)
    await wrapper.get('[aria-label="快速选择 TOP 显示数量"]').setValue('30')
    await wrapper.get('.date-form--top').trigger('submit')
    expect(wrapper.find('.selected-material-name').exists()).toBe(false)
    limitGate.resolve(); await flushPromises()
    adapter.failedProjects.add('fan')
    await wrapper.findAll('button').find(button => button.text().includes('吹风机'))!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.selected-material-name').exists()).toBe(false)
  })

  it('does not present an unavailable ranking day as zero new materials or an empty numeric scatter', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const original = adapter.fetchTop.bind(adapter)
    vi.spyOn(adapter, 'fetchTop').mockImplementation(async (...args) => ({
      ...await original(...args), rankingState: 'UNAVAILABLE', rows: [],
    }))
    const wrapper = mount(App, { props: { adapter } }); await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('.selected-material-empty').text()).toContain('排名日缺失')
    expect(wrapper.find('.selected-material-name').exists()).toBe(false)
    expect(wrapper.get('.selected-material-empty').text()).toContain('排名日缺失')
    expect(wrapper.get('.top-table-header').text()).not.toContain('实际 0 条')
    expect(wrapper.find('.scatter-wrap').exists()).toBe(false)
    expect(wrapper.get('.scatter-unavailable').text()).toContain('散点图暂不可用')
  })

  it('does not draw an unknown period spend as a zero-height bar', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const original = adapter.fetchReview.bind(adapter)
    vi.spyOn(adapter, 'fetchReview').mockImplementation(async (...args) => {
      const result = await original(...args)
      result.periods[0].groups.find(g => g.key === 'overall')!.metrics.spend = { status: 'UNKNOWN_INPUT', value: null }
      return result
    })
    const wrapper = mount(App, { props: { adapter } }); await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.findAll('.pr-kpis>.pr-kpi')).toHaveLength(3)
    expect(wrapper.get('.pr-kpi').text()).toContain('数据未知')
  })

  it('defaults to daily TOP and expands curves without showing retired fields', async () => {
    const wrapper = mount(App, { props: { adapter: new SyntheticMockAdapter([SAMPLE]) } })
    await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('[aria-labelledby="top-title"]').isVisible()).toBe(true)
    expect(wrapper.get('[aria-labelledby="review-title"]').isVisible()).toBe(false)
    expect(wrapper.find('.expanded-material').exists()).toBe(false)
    await wrapper.get('.expand-button').trigger('click')
    expect(wrapper.get('.expanded-material').text()).toContain('近 3 天 CPC')
    expect(wrapper.get('.expanded-material').text()).not.toContain('订单')
    expect(wrapper.find('.spend-curve--expanded').exists()).toBe(true)
    expect(wrapper.get('.top-results').text()).not.toContain('阶梯下降')
    await wrapper.get('.expand-button').trigger('click')
    expect(wrapper.find('.expanded-material').exists()).toBe(false)
  })

  it('invalidates pending queries when a date is cleared or a range becomes invalid', async () => {
    const adapter = new ControlledSyntheticAdapter([SAMPLE])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    const topGate = deferred<void>(); const reviewGate = deferred<void>()
    adapter.topGates.set('2026-10-08:10', topGate)
    adapter.reviewGates.set('2026-09-01:20', reviewGate)
    await wrapper.get('#top-date').setValue('2026-10-08')
    await setRangeField(wrapper, 'current', 'start', '2026-09-01')
    await wrapper.get('#top-date').setValue('')
    await wrapper.get('.date-form--top').trigger('submit')
    await setRangeField(wrapper, 'current', 'start', '2027-01-01')
    await wrapper.get('.date-form--review').trigger('submit')
    topGate.resolve(); reviewGate.resolve()
    await flushPromises()
    expect(wrapper.find('.top-results').exists()).toBe(false)
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)
    expect(wrapper.text()).toContain('TOP 统计日不能为空')
    expect(wrapper.text()).toContain('本期开始日期不能晚于结束日期')
    const topSpy = vi.spyOn(adapter, 'fetchTop')
    const reviewSpy = vi.spyOn(adapter, 'fetchReview')
    await wrapper.get('[aria-label="增加 TOP 显示数量"]').trigger('click')
    await flushPromises()
    expect(topSpy).not.toHaveBeenCalled()
    expect(reviewSpy).not.toHaveBeenCalled()
    expect(wrapper.find('.top-results').exists()).toBe(false)
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)
  })

  it('defaults details to first rank and links scatter selection without expanding the table', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const original = adapter.fetchTop.bind(adapter)
    vi.spyOn(adapter, 'fetchTop').mockImplementation(async (...args) => {
      const result = await original(...args)
      const day = result.selectedDate
      const shift = (offset: number) => new Date(Date.parse(`${day}T00:00:00Z`) + offset * 86400000).toISOString().slice(0, 10)
      result.rows = [-29, -30, 0, 1].map((offset, i) => ({ ...structuredClone(result.rows[0]), rank: i + 1,
        materialId: String(i), materialName: `测试素材${i}`, firstSpend: { status: 'VERIFIED', date: shift(offset), earliestObservedDate: shift(offset) } }))
      result.rows[2].dayRoi = { status: 'UNKNOWN_INPUT', value: null }
      return result
    })
    const wrapper = mount(App, { props: { adapter } }); await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('.selected-material-name').text()).toBe('测试素材0')
    expect(wrapper.find('.new-material-panel').exists()).toBe(false)
    expect(wrapper.findAll('.scatter-point')).toHaveLength(3)
    expect(wrapper.get('.scatter-wrap').text()).toContain('1 条素材的坐标指标未知')
    await wrapper.get('.scatter-point').trigger('click')
    expect(wrapper.get('.material-row.is-selected').text()).toContain('测试素材1')
    expect(wrapper.get('.selected-material-name').text()).toBe('测试素材1')
    expect(wrapper.find('.expanded-material').exists()).toBe(false)
    await wrapper.get('.scatter-locator select').setValue('2')
    expect(wrapper.get('.selected-material-name').text()).toBe('测试素材2')
    expect(wrapper.get('.selected-metrics-grid').text()).toContain('数据未知')
  })

  it('switches projects and follows adapter-driven project changes', async () => {
    const adapter = new RecordingAdapter([SAMPLE, FAN])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)

    expect(wrapper.text()).toContain('示例项目')
    expect(wrapper.text()).toContain('吹风机')

    const fanButton = wrapper.findAll('button').find((button) => button.text().includes('吹风机'))
    await fanButton!.trigger('click')
    await flushPromises()
    await refreshDashboard(wrapper)

    expect(adapter.fetchReviewSpy).toHaveBeenLastCalledWith('fan', expect.any(Object), 20)
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('fan', expect.any(String), 10)

    adapter.replaceProjects([SAMPLE])
    await flushPromises()
    await refreshDashboard(wrapper)

    expect(wrapper.text()).not.toContain('吹风机')
    expect(adapter.fetchReviewSpy).toHaveBeenLastCalledWith('sample-project', expect.any(Object), 20)
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 10)
  })

  it('queries only on explicit refresh, not date changes', async () => {
    const adapter = new RecordingAdapter([SAMPLE])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    adapter.fetchReviewSpy.mockClear()
    adapter.fetchTopSpy.mockClear()

    await setRangeField(wrapper, 'current', 'start', '2026-09-01')
    expect(adapter.fetchReviewSpy).not.toHaveBeenCalled()
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()

    expect(adapter.fetchReviewSpy).toHaveBeenCalledTimes(1)
    expect(adapter.fetchReviewSpy).toHaveBeenCalledWith(
      'sample-project',
      expect.objectContaining({ current: expect.objectContaining({ start: '2026-09-01' }) }),
      20,
    )

    await wrapper.get('#top-date').setValue('2026-10-08')
    expect(adapter.fetchTopSpy).not.toHaveBeenCalled()
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()

    expect(adapter.fetchTopSpy).toHaveBeenCalledTimes(1)
    expect(adapter.fetchTopSpy).toHaveBeenCalledWith('sample-project', '2026-10-08', 10)
    expect(wrapper.text()).toContain('10/02')
    expect(wrapper.text()).toContain('10/08')
  })

  it('distinguishes complete, partial and UNKNOWN states in synthetic data', async () => {
    const wrapper = mount(App, { props: { adapter: new SyntheticMockAdapter([SAMPLE]) } })
    await flushPromises(); await refreshDashboard(wrapper)

    expect(wrapper.text()).toContain('合成演示数据')
    expect(wrapper.get('.period-status-dot').classes()).toContain('period-status-dot--red')
    expect(wrapper.find('.period-toolbar-lights').exists()).toBe(false)
    expect(wrapper.text()).toContain('数据未知')
    expect(wrapper.text()).toContain('数据未知')
  })

  it('does not keep project A results when project B requests fail', async () => {
    const adapter = new ControlledSyntheticAdapter([SAMPLE, FAN])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)

    expect(wrapper.find('.period-review-v2').exists()).toBe(true)
    expect(wrapper.find('.top-results').exists()).toBe(true)

    adapter.failedProjects.add('fan')
    const fanButton = wrapper.findAll('button').find((button) => button.text().includes('吹风机'))
    await fanButton!.trigger('click')
    await flushPromises()
    await refreshDashboard(wrapper)

    expect(wrapper.get('.workspace-header h2').text()).toBe('吹风机')
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)
    expect(wrapper.find('.top-results').exists()).toBe(false)
    expect(wrapper.text()).toContain('合成周期请求失败：fan')
    expect(wrapper.text()).toContain('合成 TOP 请求失败：fan')
  })

  it('keeps only the newest confirmed dates when slow synthetic requests finish last', async () => {
    const adapter = new ControlledSyntheticAdapter([SAMPLE])
    const slowReview = deferred<void>()
    const slowTop = deferred<void>()
    adapter.reviewGates.set('2026-09-01:20', slowReview)
    adapter.topGates.set('2026-10-08:10', slowTop)
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)

    await setRangeField(wrapper, 'current', 'start', '2026-09-01')
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)

    await setRangeField(wrapper, 'current', 'start', '2026-09-02')
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'PeriodReview' }).props('result').periods[0].range.start).toBe('2026-09-02')

    slowReview.resolve()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'PeriodReview' }).props('result').periods[0].range.start).toBe('2026-09-02')

    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(wrapper.find('.top-results').exists()).toBe(false)

    await wrapper.get('#top-date').setValue('2026-10-09')
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(wrapper.get('.top-results').text()).toContain('10/09')

    slowTop.resolve()
    await flushPromises()
    expect(wrapper.get('.top-results').text()).toContain('10/09')
    expect(wrapper.get('.top-results').text()).not.toContain('10/02')

    const slowLimit = deferred<void>()
    adapter.topGates.set('2026-10-09:9', slowLimit)
    await wrapper.get('[aria-label="减少 TOP 显示数量"]').trigger('click')
    await wrapper.get('.date-form--top').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.top-results').exists()).toBe(false)

    await wrapper.get('[aria-label="减少 TOP 显示数量"]').trigger('click')
    await wrapper.get('.date-form--top').trigger('submit')
    await flushPromises()
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('8')

    slowLimit.resolve()
    await flushPromises()
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('8')
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).not.toBe('9')
  })

  it('defaults TOP display count to 10 and applies changed count on refresh with a floor of 1', async () => {
    const adapter = new RecordingAdapter([SAMPLE])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)

    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('10')
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 10)

    await wrapper.get('[aria-label="增加 TOP 显示数量"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('11')
    await wrapper.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 11)

    const decreaseButton = wrapper.get('[aria-label="减少 TOP 显示数量"]')
    for (let index = 0; index < 20; index += 1) {
      await decreaseButton.trigger('click')
    }
    await flushPromises()

    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('1')
    expect(decreaseButton.attributes('disabled')).toBeDefined()
    await wrapper.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 1)
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('1')
  })

  it('renders the approved period hierarchy, fixed cohort TOP10 and keeps details out of the home view', async () => {
    const wrapper = mount(App, { props: { adapter: new SyntheticMockAdapter([SAMPLE]) } })
    await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.findAll('.pr-kpis>.pr-kpi')).toHaveLength(3)
    expect(wrapper.findAll('.pr-group')).toHaveLength(2)
    expect(wrapper.findAll('.period-material-chart')).toHaveLength(1)
    expect(wrapper.get('.period-review-v2').text()).toContain('投放素材数量占比')
    expect(wrapper.find('.pr-status-line').exists()).toBe(false)
    const toolbar = wrapper.get('.period-toolbar')
    expect(toolbar.get('.period-query-time').text()).toContain('查询时间')
    expect(toolbar.get('.period-query-time').element.nextElementSibling?.classList.contains('period-status-dot')).toBe(true)
    expect(toolbar.get('.period-status-dot').element.nextElementSibling?.classList.contains('primary-button')).toBe(true)
    expect(wrapper.get('.period-review-v2').text()).toContain('TOP10')
    expect(wrapper.find('.pr-dialog').exists()).toBe(false)
    expect(wrapper.get('.period-review-v2').text()).toContain('待核')
    expect(wrapper.get('.period-review-v2').text()).toContain('120.00%')
    expect(wrapper.find('.review-top-limit__value').exists()).toBe(false)
    const lastCell = wrapper.get('.material-row').findAll('td').at(-1)!
    expect(lastCell.find('.expand-button').exists()).toBe(true)
  })

  it('uses a medium-width grid that gives the review submit button its own row', () => {
    expect(styles).toContain('@media (max-width: 1250px)')
    expect(styles).toMatch(
      /@media \(max-width: 1250px\)[\s\S]*?\.date-form--review \{[\s\S]*?display: grid;[\s\S]*?grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/,
    )
    expect(styles).toMatch(
      /\.date-form--review > \.primary-button \{[\s\S]*?grid-column: 1 \/ -1;[\s\S]*?width: 100%;/,
    )
  })

  it('displays project-list failures and removes old project results', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const list = vi.spyOn(adapter, 'listProjects')
    let notifyProjects: (() => void) | undefined
    vi.spyOn(adapter, 'subscribeProjects').mockImplementation((listener) => {
      notifyProjects = listener
      return () => {}
    })
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    list.mockRejectedValueOnce(new Error('项目接口不可用'))
    expect(wrapper.find('.project-nav .text-button').exists()).toBe(false)
    expect(wrapper.get('.sidebar').text()).not.toContain('入库状态只读')
    notifyProjects?.()
    await flushPromises()
    expect(wrapper.text()).toContain('项目接口不可用')
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)
    expect(wrapper.find('.top-results').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('99999999999999990001')
  })

  it('rejects response identity mismatches without restoring old rows', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const fetchTop = vi.spyOn(adapter, 'fetchTop')
    const fetchReview = vi.spyOn(adapter, 'fetchReview')
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    const wrongTop = await new SyntheticMockAdapter([FAN]).fetchTop('fan', '2026-10-08', 20)
    fetchTop.mockResolvedValueOnce(wrongTop)
    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.get('.date-form--top').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('TOP 返回的项目、统计日或显示数量与当前查询不一致')
    expect(wrapper.find('.top-results').exists()).toBe(false)
    const oldReview = await new SyntheticMockAdapter([SAMPLE]).fetchReview('sample-project', {
      current: { start: '2026-01-01', end: '2026-01-03' }, previous: { start: '2026-01-01', end: '2026-01-02' },
    }, 20)
    fetchReview.mockResolvedValueOnce(oldReview)
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(wrapper.find('.period-review-v2').exists()).toBe(false)
    expect(wrapper.find('.pr-detail-table').exists()).toBe(false)
  })

  it('enforces the independent maximum 200 and explains both limits', async () => {
    const adapter = new RecordingAdapter([SAMPLE])
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    const increaseTop = wrapper.get('[aria-label="增加 TOP 显示数量"]')
    for (let index = 0; index < 190; index += 1) {
      await increaseTop.trigger('click')
    }
    await flushPromises()
    expect(increaseTop.attributes('disabled')).toBeDefined()
    await wrapper.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(adapter.fetchTopSpy).toHaveBeenLastCalledWith('sample-project', expect.any(String), 200)
    expect(adapter.fetchReviewSpy).toHaveBeenLastCalledWith('sample-project', expect.any(Object), 20)
    expect(wrapper.findAll('.limit-note')).toHaveLength(0)
    expect(wrapper.get('[aria-label="增加 TOP 显示数量"]').attributes('disabled')).toBeDefined()
  })

  it('shows no ranks when the selected ranking day is missing or spend is unknown', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const missing = await adapter.fetchTop('sample-project', '2026-10-08', 10)
    missing.rankingState = 'UNAVAILABLE'
    missing.rows = []
    missing.days[0].availability = 'MISSING'
    missing.coverage.coveredDays -= 1
    missing.coverage.missingDates.push(missing.selectedDate)
    vi.spyOn(adapter, 'fetchTop').mockResolvedValue(missing)
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(wrapper.get('.top-results').text()).toContain('排名日缺失，无法生成真实名次')
    expect(wrapper.get('.top-results').findAll('.material-id')).toHaveLength(0)
    const unknown = structuredClone(missing)
    unknown.rankingState = 'UNKNOWN_INPUT'
    vi.mocked(adapter.fetchTop).mockResolvedValue(unknown)
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(wrapper.get('.top-results').text()).toContain('排名所需消耗字段未知')
  })

  it('does not confuse complete empty rankings with unavailable rankings', async () => {
    const adapter = new SyntheticMockAdapter([SAMPLE])
    const empty = await adapter.fetchTop('sample-project', '2026-10-08', 10)
    empty.rows = []
    vi.spyOn(adapter, 'fetchTop').mockResolvedValue(empty)
    const wrapper = mount(App, { props: { adapter } })
    await flushPromises(); await refreshDashboard(wrapper)
    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(wrapper.get('.top-results').text()).toContain('所选完整排名日没有正消耗素材')
    expect(wrapper.get('.top-results').text()).not.toContain('无法生成真实名次')
  })
})


async function setRangeField(wrapper: ReturnType<typeof mount>, period: 'current'|'previous', part:'start'|'end', value:string) {
 const picker=wrapper.findAllComponents(DateRangePicker)[period==='current'?0:1]
 const range={...picker.props('modelValue'),[part]:value}
 picker.vm.$emit('update:modelValue',range)
 await wrapper.vm.$nextTick()
 picker.vm.$emit('confirm',range)
 await wrapper.vm.$nextTick()
}
