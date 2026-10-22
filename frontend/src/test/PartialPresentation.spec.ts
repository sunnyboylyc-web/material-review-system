import { refreshDashboard } from './refreshDashboard'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import MetricValue from '../components/MetricValue.vue'
import PeriodReview from '../components/PeriodReview.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateTopResponse } from '../adapters/httpDashboardAdapter'
import { validateOperationsResponse } from '../adapters/httpOperationsAdapter'
import { operationsFixture } from './operationsFixture'

describe('D065 source dates and partial values', () => {
  it('does not describe empty partial ranks or unclassified cohort ranks as complete empty results', async () => {
    const adapter = new SyntheticMockAdapter(), original = adapter.fetchTop.bind(adapter)
    vi.spyOn(adapter,'fetchTop').mockImplementation(async (...args) => ({...await original(...args),rankingState:'PARTIAL',rows:[]}))
    const app=mount(App,{props:{adapter}}); await flushPromises()
    await refreshDashboard(app)
    expect(app.get('.selected-material-empty').text()).toContain('无法确定完整榜单')
    expect(app.get('.top-results').text()).not.toContain('完整排名日没有正消耗')
    const result=await adapter.fetchReview('sample-project',{current:{start:'2026-10-01',end:'2026-10-03'},previous:{start:'2026-09-01',end:'2026-09-03'}},20)
    result.periods[0].groups.find(g=>g.key==='new')!.metrics.spend={status:'UNKNOWN_INPUT',value:null,partial:true,partialReasons:['COHORT_UNASSIGNED']}
    const page=mount(PeriodReview,{props:{result,projectName:'合成'}})
    expect(page.get('.period-material-chart').find('.pr-column-item').exists()).toBe(true)
    expect(page.get('.pr-composition').text()).not.toContain('部分')
    expect(page.get('.pr-composition').findAll('[aria-label*="部分数据缺失"]').length).toBeGreaterThan(0)
    await page.get('.pr-detail-button').trigger('click'); await flushPromises()
    expect(page.get('.pr-detail-table').text()).toContain('分类待核，无法确定榜单')
  })
  it('renders available partial numbers and distinguishes partial zero denominator from genuine zero', () => {
    const wrapper = mount(MetricValue, { props: { result: { status: 'VALID', value: 42, partial: true, partialReasons: ['MISSING_FIELD'] } } })
    expect(wrapper.text()).toBe('42.00'); expect(wrapper.attributes('aria-label')).toContain('部分数据缺失')
    expect(wrapper.props('result')).toEqual({ status: 'VALID', value: 42, partial: true, partialReasons: ['MISSING_FIELD'] })
    const zero = mount(MetricValue, { props: { result: { status: 'ZERO_DENOMINATOR', value: null, partial: true, partialReasons: ['MISSING_FIELD'] } } })
    expect(zero.text()).toContain('分母为 0'); expect(zero.text()).not.toContain('部分')
    expect(zero.attributes('aria-label')).toContain('部分数据缺失')
  })
  it('uses creation date rather than first positive day and displays a partial known ranking and seven-day spend', async () => {
    const adapter = new SyntheticMockAdapter()
    const original = adapter.fetchTop.bind(adapter)
    vi.spyOn(adapter,'fetchTop').mockImplementation(async (...args) => {
      const result = await original(...args); result.rankingState = 'PARTIAL'
      result.rows[0].creationDate = { status:'VERIFIED', date:'2026-08-15', reason:null }
      result.rows[0].firstSpend = { status:'VERIFIED', date:'2026-09-19', earliestObservedDate:'2026-09-19' }
      result.rows[0].windowSpend = { status:'VALID', value:321.25, partial:true, partialReasons:['MISSING_ACCOUNT_DAYS'] }
      return result
    })
    const wrapper = mount(App,{props:{adapter}}); await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('#top-title').text()).toBe('每日TOP素材数据')
    expect(wrapper.get('.selected-material-date').text()).toContain('首次投放时间 2026-08-15')
    expect(wrapper.get('.material-row').text()).not.toContain('2026-09-19')
    expect(wrapper.get('.material-row').text()).toContain('321.25')
    expect(wrapper.get('.material-row').text()).not.toContain('部分')
    expect(wrapper.get('.material-row').findAll('[aria-label*="部分数据缺失"]').length).toBeGreaterThan(0)
    expect(wrapper.get('.top-results').text()).not.toContain('排名仅包含已知消耗素材')
    expect(wrapper.get('.top-results').text()).not.toContain('部分素材消耗缺失')
    expect(wrapper.find('.selected-extra-metrics').exists()).toBe(false)
    expect(wrapper.find('.scatter-selection').exists()).toBe(false)
    expect(wrapper.get('.top-results').text()).not.toContain('查询时间')
    expect(wrapper.get('.material-row').findAll('.row-metric-label')).toHaveLength(5)
    const response = await adapter.fetchTop('sample-project','2026-10-12',20); response.evidenceKind='CURRENT_FACTS'
    expect(validateTopResponse(response)).toBe(response)
    response.rows[0].creationDate = {status:'PENDING',date:null,reason:'CONFLICTING_CREATION_DATE'}
    expect(validateTopResponse(response)).toBe(response)
  })
  it('retains partial metadata in period proportions', async () => {
    const result = await new SyntheticMockAdapter().fetchReview('sample-project',{current:{start:'2026-10-01',end:'2026-10-03'},previous:{start:'2026-09-01',end:'2026-09-03'}},20)
    result.periods[0].groups[0].metrics.materialCount.partial = true
    result.periods[0].groups[0].metrics.materialCount.partialReasons = ['MISSING_MATERIAL_NAME']
    const wrapper = mount(PeriodReview,{props:{result,projectName:'合成'}})
    expect(wrapper.get('.pr-composition').text()).not.toContain('部分')
    expect(wrapper.findAllComponents(MetricValue).filter(metric => metric.element.closest('.pr-composition')).some(metric => metric.props('result')?.partialReasons?.includes('MISSING_MATERIAL_NAME'))).toBe(true)
    expect(wrapper.get('.pr-composition').findAll('[aria-label*="部分数据缺失"]').length).toBeGreaterThan(0)
  })
  it('validates missing impact rows within the requested range without accepting absent detail arrays', () => {
    const range={start:'2026-10-01',end:'2026-10-03'}
    const response=operationsFixture('synthetic',range)
    response.missingDetails=[{date:'2026-10-01',materialId:'SYNTHETIC_1',field:'createdDate',reason:'MISSING_CREATION_DATE',affectedMetrics:['classification'],sourceVersionId:10}]
    response.missingDetailTotal=1
    expect(validateOperationsResponse(response,'synthetic',range)).toBe(response)
    response.missingDetails[0].date='2026-10-04'
    expect(()=>validateOperationsResponse(response,'synthetic',range)).toThrow()
  })
})
