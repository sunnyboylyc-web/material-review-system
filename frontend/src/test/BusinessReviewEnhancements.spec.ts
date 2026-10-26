import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import CalculationSchemaViewer from '../components/CalculationSchemaViewer.vue'
import MaterialTypeReview from '../components/MaterialTypeReview.vue'
import PeriodReview from '../components/PeriodReview.vue'
import DateRangePicker from '../components/DateRangePicker.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateMaterialTypeResponse, validateReviewResponse } from '../adapters/httpDashboardAdapter'
import type { AiMaterialGroup, MaterialTypeGroup, MaterialTypeQuery, MaterialTypeReview as TypeResult, MaterialTypeRow, MetricKey, NumericResult, ReviewMaterialRow } from '../domain/dashboard'
import { enumerateIsoDates } from '../time/shanghai'

const n = (value: number): NumericResult => ({ status: 'VALID', value, partial: false, partialReasons: [] })
const metrics = (spend: number) => ({ spend: n(spend), roi: n(2), materialCount: n(spend ? 1 : 0), ctr: n(.02), cvr: n(.1), cpm: n(20), cpc: n(1), spendShare: n(1) }) as Record<MetricKey,NumericResult>
const initial: MaterialTypeQuery = { current: { start: '2026-01-05', end: '2026-01-10' }, currentTest: { start: '2026-01-01', end: '2026-01-04' }, previous: { start: '2025-12-25', end: '2025-12-31' }, previousTest: { start: '2025-12-01', end: '2025-12-24' } }
function typeResult(query = initial): TypeResult {
  const detail = (label: string): MaterialTypeRow[] => Array.from({length:24},(_,i) => ({rank:i+1,materialId:`SYNTHETIC_${label}_${i}`,materialName:`ai${label}示例${i}`,
    creationDate:{status:'VERIFIED',date:query.currentTest.start,reason:null},metrics:metrics(24-i),previousSpend:n(0),dailySpend:enumerateIsoDates(query.current.start,query.current.end).map(date => ({date,spend:n(1)}))}))
  const groups = (): MaterialTypeGroup[] => ['original','mixed','other'].map((key,i) => ({ key, metrics: metrics([100,50,0][i]) })) as MaterialTypeGroup[]
  const aiGroups = (): AiMaterialGroup[] => ['ai','business','department'].map((key,i) => ({ key, metrics: metrics([150,50,100][i]) })) as AiMaterialGroup[]
  const periods = (['CURRENT','PREVIOUS'] as const).map(key => {
    const range = key === 'CURRENT' ? query.current : query.previous, testRange = key === 'CURRENT' ? query.currentTest : query.previousTest
    const days = enumerateIsoDates(range.start,range.end)
    return { key, range, testRange, coverage: { state: 'COMPLETE' as const, coveredDays: days.length, expectedDays: days.length, missingDates: [] }, groups: groups(), excludedUndatedMaterials: 0, unresolvedDateMaterials: 0 }
  })
  return { schemaVersion: 'material-type-review-2.0.0', periods, comparisons: groups(), materials: { original: [], mixed: [], other: detail('其他') },
    ai: { periods: periods.map(p => ({ ...p, groups: aiGroups() })), comparisons: aiGroups(), materials:{business:detail('商单'),department:detail('部门')} } }
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open','') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})

describe('local business review enhancements', () => {
  it('shows actual indexed structures as read-only references without requests or business values',async () => {
    const fetch = vi.spyOn(globalThis,'fetch')
    const wrapper = mount(CalculationSchemaViewer,{attachTo:document.body})
    expect(wrapper.findAll('button').map(button => button.text())).toEqual(['维度表 只读','汇总表 只读'])
    await wrapper.findAll('button')[0].trigger('click'); await flushPromises()
    expect(document.body.textContent).toContain('不是独立 MySQL 业务表')
    expect(document.body.textContent).toContain('creation.status / date / reason')
    expect(document.body.querySelector('details')?.open).toBe(false)
    expect([...document.body.querySelectorAll('.sheet-letters th')].map(cell => cell.textContent)).toEqual(['','A','B','C','D','E','F','G'])
    expect([...document.body.querySelectorAll('.sheet-headers th')].map(cell => cell.textContent)).toContain('首次投放时间')
    expect(document.body.querySelectorAll('.schema-sheet tbody tr')).toHaveLength(3)
    expect(document.body.querySelectorAll('input,textarea,select')).toHaveLength(0)
    ;(document.querySelector('[aria-label="关闭结构参考"]') as HTMLButtonElement).click(); await flushPromises()
    await wrapper.findAll('button')[1].trigger('click'); await flushPromises()
    expect(document.body.textContent).toContain('pairNumerators[5]')
    expect(document.body.textContent).toContain('不平均每日比率')
    expect(document.body.querySelectorAll('.sheet-letters th')).toHaveLength(15)
    expect(document.body.querySelector('.schema-sheet tbody tr')?.textContent).toContain('3,000.00')
    expect(document.body.querySelector('.schema-business-help')?.textContent).toContain('是便于阅读的展示列')
    expect(fetch).not.toHaveBeenCalled(); wrapper.unmount(); fetch.mockRestore()
  })

  it('opens AI subgroup details on top of the breakdown with independent limits and returns to its card',async () => {
    const wrapper=mount(MaterialTypeReview,{props:{result:typeResult(),projectName:'合成项目'},attachTo:document.body})
    await wrapper.get('.ai-summary-button').trigger('click'); await flushPromises()
    await wrapper.get('.ai-breakdown-card .pr-detail-button').trigger('click'); await flushPromises()
    expect(wrapper.get('#material-type-dialog-title').text()).toContain('含 AI · 商单')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    const total=wrapper.get('.pr-dialog-summary').text()
    await wrapper.get('select[aria-label="类型明细显示数量"]').setValue('20')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(20)
    expect(wrapper.get('.pr-dialog-summary').text()).toBe(total)
    await wrapper.get('[aria-label="关闭类型素材明细"]').trigger('click')
    expect(wrapper.get('.ai-breakdown-dialog').attributes('open')).toBeDefined()
    await wrapper.findAll('.ai-breakdown-card .pr-detail-button')[1].trigger('click'); await flushPromises()
    expect(wrapper.get('#material-type-dialog-title').text()).toContain('部门')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    await wrapper.get('[aria-label="关闭类型素材明细"]').trigger('click')
    await wrapper.get('.ai-breakdown-card .pr-detail-button').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(20)
    await wrapper.get('[aria-label="关闭类型素材明细"]').trigger('click')
    await wrapper.get('[aria-label="关闭含AI细分"]').trigger('click')
    await wrapper.get('[aria-label="查看其他素材"]').trigger('click'); await flushPromises()
    expect(wrapper.get('#material-type-dialog-title').text()).toBe('其他素材 · 明细')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    await wrapper.get('[aria-label="增加类型明细显示数量"]').trigger('click')
    expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(11)
    await wrapper.setProps({result:typeResult()}); expect(wrapper.find('dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps old AI totals readable without pretending missing detail is an empty list',async () => {
    const result=typeResult(); delete result.ai!.materials
    const wrapper=mount(MaterialTypeReview,{props:{result,projectName:'合成项目'}})
    await wrapper.get('.ai-summary-row .ai-summary-button').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.ai-breakdown-card .pr-detail-button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.get('.ai-breakdown-dialog [role="status"]').text()).toContain('请刷新素材类型数据')
    expect(wrapper.find('.material-type-dialog').exists()).toBe(false); wrapper.unmount()
  })

  it('keeps the reference tied to the real dimension and summary implementation',() => {
    const source = readFileSync(resolve(process.cwd(),'../backend/src/main/java/cn/datadashboard/calculation/DashboardCalculator.java'),'utf8')
    expect(source).toContain('record DimensionPoint(LocalDate observed, String name, CreationDate creation, FirstSpend firstSpend)')
    for (const field of ['sums','known','pairNumerators','pairDenominators','paired','included','uncertain','unknownSpend','eligibleCount']) expect(source).toMatch(new RegExp(`\\b${field}\\b`))
    expect(source).toContain('BigDecimal[] values = {row.spend(), row.gmv(), row.impressions(), row.clicks(), row.orders()}')
  })

  it('keeps three primary types and adds overlapping AI with two non-additive breakdown cards',async () => {
    const wrapper = mount(MaterialTypeReview,{props:{result:typeResult(),projectName:'合成项目'}})
    expect(wrapper.findAll('.material-type-table tbody th').map(cell => cell.text())).toEqual(['原创','混剪','其他','含 AI ↗'])
    await wrapper.get('.ai-summary-button').trigger('click'); await flushPromises()
    expect(wrapper.get('#ai-breakdown-title').text()).toBe('含 AI · 商单与部门')
    expect(wrapper.findAll('.ai-breakdown-card')).toHaveLength(2)
    for (const card of wrapper.findAll('.ai-breakdown-card')) {
      expect(card.findAll('.pr-group-metrics .pr-kpi')).toHaveLength(3)
      expect(card.findAll('.pr-secondary-kpis article')).toHaveLength(4)
    }
    expect(wrapper.get('.ai-breakdown-note').text()).toContain('两个词都有时两块均计入')
    expect(wrapper.get('.ai-breakdown-note').text()).toContain('两块不可相加')
    await wrapper.get('[aria-label="关闭含AI细分"]').trigger('click')
    expect(wrapper.find('.ai-breakdown-dialog').exists()).toBe(false); wrapper.unmount()
  })

  it('refreshes only 05/06 using four independent ranges and retains earlier output on failure',async () => {
    const data = await new SyntheticMockAdapter().fetchReview('sample-project',{current:initial.current,previous:initial.previous},10)
    data.materialTypes = typeResult()
    const load = vi.fn(async (query: MaterialTypeQuery) => typeResult(query))
    const wrapper = mount(PeriodReview,{props:{result:data,projectName:'合成项目',loadMaterialTypes:load}})
    const original = wrapper.get('.pr-kpis').text()
    const pickers = wrapper.findAllComponents(DateRangePicker)
    expect(pickers.map(p => p.props('label'))).toEqual(['本周期','本周期素材测试周期','上周期','上周期素材测试周期'])
    pickers[1].vm.$emit('update:modelValue',{start:'2025-12-20',end:'2026-01-04'}); await flushPromises()
    expect(load).not.toHaveBeenCalled()
    expect(wrapper.get('[role="status"].form-error').text()).toContain('仍显示上次结果')
    await wrapper.get('.material-type-toolbar').trigger('submit'); await flushPromises()
    expect(load).toHaveBeenCalledWith({...initial,currentTest:{start:'2025-12-20',end:'2026-01-04'}})
    expect(wrapper.get('.pr-kpis').text()).toBe(original)
    expect(wrapper.emitted('materialTypeUpdated')).toHaveLength(1)
    load.mockRejectedValueOnce(new Error('SYNTHETIC_QUERY_FAILURE'))
    const before = wrapper.get('.material-type-table').text()
    await wrapper.get('.material-type-toolbar').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('SYNTHETIC_QUERY_FAILURE')
    expect(wrapper.get('.material-type-table').text()).toBe(before); wrapper.unmount()
  })

  it('validates independent dates and AI group contracts without forcing creation into the statistic range',() => {
    const analysis = typeResult()
    const wire = {schemaVersion:'material-type-analysis-1.0.0',projectId:'synthetic',evidenceKind:'CURRENT_FACTS',requestedAt:'2026-01-20T00:00:00Z',snapshotFingerprint:'synthetic',analysis}
    expect(validateMaterialTypeResponse(wire,'synthetic',initial)).toBe(analysis)
    const wrong = structuredClone(wire); wrong.analysis.periods[0].testRange!.start='2026-01-05'
    expect(() => validateMaterialTypeResponse(wrong,'synthetic',initial)).toThrow('契约')
    const missing = structuredClone(wire); missing.analysis.ai!.periods[0].groups.pop()
    expect(() => validateMaterialTypeResponse(missing,'synthetic',initial)).toThrow('契约')
    const overlapping=structuredClone(wire)
    const row=overlapping.analysis.ai!.materials!.business[0]; row.materialName='AI商单部门示例'
    overlapping.analysis.ai!.materials!.department[0]=structuredClone(row)
    expect(() => validateMaterialTypeResponse(overlapping,'synthetic',initial)).not.toThrow()
    for (const mutate of [
      (r:TypeResult) => {r.ai!.materials!.business[0].materialName='AIR商单'},
      (r:TypeResult) => {r.ai!.materials!.business[0].creationDate.date='2026-01-05'},
      (r:TypeResult) => {r.ai!.materials!.business[1].materialId=r.ai!.materials!.business[0].materialId},
      (r:TypeResult) => {r.ai!.materials!.business[0].dailySpend.pop()},
    ]) {const bad=structuredClone(wire);mutate(bad.analysis);expect(() => validateMaterialTypeResponse(bad,'synthetic',initial)).toThrow('契约')}
  })

  it('extends overall detail limits without altering the TOP10 chart or accepting late results after close',async () => {
    const result = await new SyntheticMockAdapter().fetchReview('sample-project',{current:initial.current,previous:initial.previous},10)
    const rows: ReviewMaterialRow[] = Array.from({length:35},(_,index) => ({...structuredClone(result.topMaterials[0]),rank:index+1,materialId:`SYNTHETIC_${index}`,materialName:`合成素材${index}`}))
    result.topMaterials = rows.slice(0,10)
    const load = vi.fn(async (limit: number) => rows.slice(0,limit))
    const wrapper = mount(PeriodReview,{props:{result,projectName:'合成项目',loadOverallMaterials:load}})
    await wrapper.get('.pr-column-item').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.pr-detail-table tbody tr:not(.pr-expanded)')).toHaveLength(10)
    await wrapper.get('[aria-label="快速选择明细显示数量"]').setValue('20'); await flushPromises()
    expect(load).toHaveBeenCalledWith(20)
    expect(wrapper.findAll('.pr-detail-table tbody tr:not(.pr-expanded)')).toHaveLength(20)
    expect(wrapper.findAll('.pr-column-item')).toHaveLength(10)
    let accept!: (value: ReviewMaterialRow[]) => void
    load.mockImplementationOnce(() => new Promise(resolve => { accept=resolve }))
    await wrapper.get('[aria-label="快速选择明细显示数量"]').setValue('50')
    await wrapper.get('[aria-label="关闭素材明细"]').trigger('click')
    accept(rows); await flushPromises()
    expect(wrapper.find('.pr-dialog').exists()).toBe(false); wrapper.unmount()
  })
})
