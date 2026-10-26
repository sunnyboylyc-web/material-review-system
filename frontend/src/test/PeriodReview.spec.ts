import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PeriodReview from '../components/PeriodReview.vue'
import PeriodMaterialChart from '../components/PeriodMaterialChart.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateReviewResponse } from '../adapters/httpDashboardAdapter'
import type { NumericResult, ReviewResult } from '../domain/dashboard'
const valid = (value: number): NumericResult => ({ status: 'VALID', value, partial: false, partialReasons: [] })
const unknown: NumericResult = { status: 'UNKNOWN_INPUT', value: null, partial: false, partialReasons: [] }
async function fixture(): Promise<ReviewResult> {
  const result = await new SyntheticMockAdapter().fetchReview('sample-project', {
    current: { start: '2026-10-01', end: '2026-10-12' }, previous: { start: '2026-09-01', end: '2026-09-05' },
  }, 20)
  for (const period of result.periods) for (const group of period.groups) {
    group.metrics.spend = valid(group.key === 'overall' ? 100 : group.key === 'new' ? 30 : group.key === 'aigc' ? 0 : 50)
    group.metrics.materialCount = valid(group.key === 'overall' ? 10 : group.key === 'new' ? 3 : group.key === 'aigc' ? 0 : 5)
  }
  result.cohortMaterials.new = Array.from({ length: 12 }, (_, i) => ({ ...structuredClone(result.topMaterials[0]),
    rank: i + 1, materialId: `SYNTHETIC_${i}`, materialName: `虚构素材${i}`, currentSpend: valid(120 - i), previousSpend: i === 0 ? unknown : valid(10), ctr: valid(.02), cvr: unknown, cpm: valid(20),
    firstSpend: { status: 'VERIFIED', date: '2026-10-01', earliestObservedDate: '2026-10-01' },
    dailySpend: Array.from({ length: 12 }, (_, day) => ({ date: `2026-10-${String(day + 1).padStart(2, '0')}`, spend: day === 3 ? unknown : valid(day) })),
  }))
  result.topMaterials = structuredClone(result.cohortMaterials.new)
  return result
}
describe('period review v2', () => {
  it('labels the existing creation date as first delivery time without using first positive spend', async () => {
    const result = await fixture()
    result.topMaterials[0].creationDate = { status: 'VERIFIED', date: '2026-08-15', reason: null }
    const wrapper = mount(PeriodReview, { props: { result, projectName: '合成项目' } })
    await wrapper.get('.pr-column-item').trigger('click'); await flushPromises()
    expect(wrapper.get('.pr-detail-table tbody tr small').text()).toContain('首次投放时间 2026-08-15')
    expect(wrapper.get('.pr-detail-table tbody tr small').text()).not.toContain('2026-10-01')
    wrapper.unmount()
  })

  it('shows seven metrics for each group and keeps independent default-ten detail limits', async () => {
    const result = await fixture()
    result.cohortMaterials.historical = structuredClone(result.cohortMaterials.new)
    const wrapper = mount(PeriodReview, {props:{result,projectName:'合成'}})
    expect(wrapper.findAll('.pr-kpis>.pr-kpi')).toHaveLength(3)
    expect(wrapper.findAll('.pr-kpis .pr-secondary-kpis article')).toHaveLength(4)
    for (const group of wrapper.findAll('.pr-group')) {
      expect(group.findAll('.pr-group-metrics>article')).toHaveLength(3)
      expect(group.findAll('.pr-secondary-kpis article')).toHaveLength(4)
      expect(group.text().match(/对比上期/g)).toHaveLength(7)
      expect(group.get('.pr-section-head').text()).toContain('本期消耗占比')
      expect(group.find('.pr-group-footer').exists()).toBe(false)
    }
    await wrapper.findAll('.pr-detail-button')[0].trigger('click')
    await wrapper.get('[aria-label="增加明细显示数量"]').trigger('click')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(11)
    await wrapper.get('[aria-label="关闭素材明细"]').trigger('click')
    await wrapper.findAll('.pr-detail-button')[1].trigger('click')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    await wrapper.get('[aria-label="关闭素材明细"]').trigger('click')
    await wrapper.findAll('.pr-detail-button')[0].trigger('click')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(11)
    wrapper.unmount()
  })
  it('uses overall TOP10 directly, retains pending-creation members and opens their full period details', async () => {
    const result=await fixture()
    result.cohortMaterials.new=[];result.cohortMaterials.historical=[]
    result.topMaterials[0].creationDate={status:'PENDING',date:null,reason:'MISSING_CREATION_DATE'}
    const wrapper=mount(PeriodReview,{props:{result,projectName:'合成项目'}})
    expect(wrapper.findAll('.period-material-chart')).toHaveLength(1)
    expect(wrapper.findAll('.pr-column-item')).toHaveLength(10)
    expect(wrapper.get('.pr-column-item').text()).toContain('虚构素材0')
    expect(wrapper.findAll('.pr-group')).toHaveLength(2)
    await wrapper.get('.pr-column-item').trigger('click');await flushPromises()
    expect(wrapper.get('#pr-dialog-title').text()).toContain('整体素材')
    expect(wrapper.find('.pr-dialog-summary').exists()).toBe(false)
    expect(wrapper.get('.pr-detail-table th').text()).toBe('本期素材')
    expect(wrapper.get('.pr-detail-table').text()).toContain('创建日期缺失')
    expect(wrapper.get('.pr-expanded').text()).toContain('2026-10-12')
    expect(wrapper.get('.pr-expanded').text()).toContain('数据未知')
  })
  it('distinguishes missing coverage from unknown metrics and unknown rankings from genuinely empty cohorts', async () => {
    const result = await fixture(); result.cohortMaterials.new = []; result.topMaterials = []
    result.periods.forEach(period => { period.coverage = { state: 'COMPLETE', expectedDays: period.coverage.expectedDays, coveredDays: period.coverage.expectedDays, missingDates: [] } })
    result.periods[0].groups[0].metrics.spend = unknown
    const wrapper = mount(PeriodReview, { props: { result, projectName: '合成项目' } })
    expect(wrapper.find('.form-error').exists()).toBe(false)
    expect(wrapper.get('.pr-kpis').text()).toContain('数据未知')
    expect(wrapper.findAll('.period-material-chart')[0].text()).toContain('无法确定榜单')
    await wrapper.get('.pr-detail-button').trigger('click'); await flushPromises()
    expect(wrapper.get('.pr-detail-table').text()).toContain('无法确定榜单')
    const empty = structuredClone(result); empty.periods[0].groups[0].metrics.spend = valid(0); empty.periods[0].pendingMaterialCount = 0
    await wrapper.setProps({ result: empty })
    expect(wrapper.findAll('.period-material-chart')[0].text()).toContain('本期无符合条件')
  })
  it('uses overall denominators and visibly retains pending shares instead of normalizing known groups', async () => {
    const result = await fixture()
    const wrapper = mount(PeriodReview, { props: { result, projectName: '合成项目' } })
    expect(wrapper.get('.pr-composition').text()).toContain('30.00%')
    expect(wrapper.get('.pr-composition').text()).toContain('50.00%')
    expect(wrapper.get('.pr-composition').text()).toContain('待核 20.0%')
    expect(wrapper.find('.pr-composition .new').attributes('style')).toContain('30%')
    const changed = structuredClone(result)
    changed.periods[0].groups[0].metrics.materialCount = unknown
    await wrapper.setProps({ result: changed })
    expect(wrapper.get('.pr-composition').text()).toContain('占比不可计算')
    expect(wrapper.get('.pr-composition').text()).toContain('数据未知')
  })
  it('limits comparison chart to ten, opens all cohort members and draws the full date curve only on expand', async () => {
    const result = await fixture()
    const wrapper = mount(PeriodReview, { props: { result, projectName: '合成项目' } })
    expect(wrapper.findAll('.pr-column-item')).toHaveLength(10)
    expect(wrapper.find('.spend-curve').exists()).toBe(false)
    await wrapper.get('.pr-detail-button').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    await wrapper.get('[aria-label="快速选择明细显示数量"]').setValue('20')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(12)
    expect(wrapper.find('.pr-dialog-summary').exists()).toBe(true)
    expect(wrapper.find('.spend-curve').exists()).toBe(false)
    const row = wrapper.get('.pr-detail-table tbody tr')
    expect(row.findAll('td').at(-1)!.find('.expand-button').exists()).toBe(true)
    await row.get('.expand-button').trigger('click')
    const curve = wrapper.get('[aria-label="本周期消耗曲线，缺失日期断线"]')
    expect(curve.findAll('circle')).toHaveLength(11)
    expect(curve.findAll('polyline')).toHaveLength(2)
    expect(wrapper.findAll('.pr-expanded .daily-value-list > span')).toHaveLength(12)
    expect(wrapper.get('.pr-expanded').text()).toContain('2026-10-12')
    await row.get('.expand-button').trigger('click')
    expect(wrapper.find('.spend-curve').exists()).toBe(false)
    await wrapper.setProps({ result: structuredClone(result) })
    expect(wrapper.find('.pr-dialog').exists()).toBe(false)
  })
  it('does not draw an unknown prior spend as zero and keeps bars side by side without stacking', async () => {
    const result = await fixture()
    const wrapper = mount(PeriodMaterialChart, { props: { title: '新素材', rows: result.cohortMaterials.new } })
    expect(wrapper.findAll('.pr-column-item')).toHaveLength(10)
    expect(wrapper.get('.pr-column-item').findAll('.pr-column')).toHaveLength(2)
    expect(wrapper.get('.unknown').text()).toBe('未知')
    await wrapper.get('.pr-column-item').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['SYNTHETIC_0'])
  })
  it('requires every requested day exactly once in ascending order in the 1.3 contract', async () => {
    const result = await fixture(); result.evidenceKind = 'CURRENT_FACTS'
    expect(validateReviewResponse(result)).toBe(result)
    const missing = structuredClone(result); missing.cohortMaterials.new[0].dailySpend.pop()
    expect(() => validateReviewResponse(missing)).toThrow()
    const reversed = structuredClone(result); reversed.cohortMaterials.new[0].dailySpend.reverse()
    expect(() => validateReviewResponse(reversed)).toThrow()
    const old = structuredClone(result); old.schemaVersion = 'dashboard-query-1.1.0'
    expect(() => validateReviewResponse(old)).toThrow()
  })
})
