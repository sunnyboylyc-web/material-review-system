import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MaterialTypeReview from '../components/MaterialTypeReview.vue'
import PeriodReview from '../components/PeriodReview.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateReviewResponse } from '../adapters/httpDashboardAdapter'
import type { MaterialTypeReview as TypeReview, MetricKey, NumericResult } from '../domain/dashboard'
const n = (value:number):NumericResult => ({status:'VALID',value,partial:false,partialReasons:[]})
const types = ['original','mixed','other'] as const
const metrics = () => Object.fromEntries(['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare'].map(k => [k,n(k === 'spend' ? 1234 : 2)])) as Record<MetricKey,NumericResult>
function fixture(): TypeReview {
  const current = {start:'2026-01-01',end:'2026-01-10'}, previous = {start:'2025-12-22',end:'2025-12-31'}
  const groups = () => types.map(key => ({key,metrics:metrics()}))
  const materials = Object.fromEntries(types.map(type => [type,Array.from({length:14},(_,i) => ({rank:i+1,materialId:`${type}-${i}`,materialName:`${type === 'original' ? '原创' : type === 'mixed' ? '混剪' : '星图'}示例${i}`,creationDate:{status:'VERIFIED',date:'2026-01-01',reason:null},metrics:metrics(),previousSpend:n(0),dailySpend:Array.from({length:10},(_,day) => ({date:`2026-01-${String(day+1).padStart(2,'0')}`,spend:n(day+1)}))}))])) as TypeReview['materials']
  return {schemaVersion:'material-type-review-1.0.0',periods:[{key:'CURRENT',range:current,coverage:{state:'COMPLETE',expectedDays:10,coveredDays:10,missingDates:[]},groups:groups(),excludedUndatedMaterials:0,unresolvedDateMaterials:0},{key:'PREVIOUS',range:previous,coverage:{state:'COMPLETE',expectedDays:10,coveredDays:10,missingDates:[]},groups:groups(),excludedUndatedMaterials:0,unresolvedDateMaterials:0}],comparisons:groups(),materials}
}
describe('material types with period first-date filter',() => {
  it('shows only three rows and seven requested columns, plus two seven-metric cards',() => {
    const w = mount(MaterialTypeReview,{props:{result:fixture(),projectName:'合成'}})
    expect(w.findAll('.material-type-table thead th').map(x => x.text())).toEqual(['本期素材类型','消耗','ROI','素材投放数量','点击率','转化率','CPM','CPC'])
    expect(w.findAll('.material-type-table tbody th').map(x => x.text())).toEqual(['原创','混剪','其他'])
    expect(w.get('.material-type-table').text()).not.toContain('合计')
    expect(w.findAll('.pr-group')).toHaveLength(2)
    for (const card of w.findAll('.pr-group')) {
      expect(card.findAll('.pr-group-metrics>article')).toHaveLength(3)
      expect(card.findAll('.pr-secondary-kpis>article')).toHaveLength(4)
      expect(card.text().match(/对比上期/g)).toHaveLength(7)
    }
    w.unmount()
  })
  it('keeps independent limits and curves, while display limits never change totals',async () => {
    const w = mount(MaterialTypeReview,{props:{result:fixture(),projectName:'合成'}})
    await w.findAll('.pr-detail-button')[0].trigger('click')
    expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    const total = w.get('.pr-dialog-summary').text()
    await w.get('[aria-label="增加类型明细显示数量"]').trigger('click')
    expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(11)
    expect(w.get('.pr-dialog-summary').text()).toBe(total)
    await w.get('[aria-label="展开第 1 名类型素材消耗曲线"]').trigger('click')
    expect(w.get('.pr-expanded').text()).toContain('2026-01-10')
    expect(w.findAll('.daily-value-list>span')).toHaveLength(10)
    await w.get('[aria-label="关闭类型素材明细"]').trigger('click')
    await w.findAll('.pr-detail-button')[1].trigger('click')
    expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
    await w.get('[aria-label="关闭类型素材明细"]').trigger('click')
    await w.findAll('.pr-detail-button')[0].trigger('click')
    expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(11)
    await w.setProps({result:fixture()})
    expect(w.find('dialog').exists()).toBe(false)
    w.unmount()
  })
  it('distinguishes complete empty, partial empty and unavailable',async () => {
    const data=fixture(); data.materials.original=[]
    const w=mount(MaterialTypeReview,{props:{result:data,projectName:'合成'}})
    await w.findAll('.pr-detail-button')[0].trigger('click')
    expect(w.get('dialog').text()).toContain('本周期无符合双日期条件的素材')
    const partial=fixture(); partial.materials.original=[]; partial.periods[0].unresolvedDateMaterials=1
    await w.setProps({result:partial}); await w.findAll('.pr-detail-button')[0].trigger('click')
    expect(w.get('dialog').text()).toContain('仍有缺失或待核')
    const unknown=fixture(); unknown.materials.original=[]; unknown.periods[0].groups[0].metrics.spend={status:'UNKNOWN_INPUT',value:null,partial:true,partialReasons:['MISSING_ACCOUNT_DAYS']}
    await w.setProps({result:unknown}); await w.findAll('.pr-detail-button')[0].trigger('click')
    expect(w.get('dialog').text()).toContain('当前数据不可用')
    w.unmount()
  })
  it('accepts validated additive block and rejects category/date/daily mismatches',async () => {
    const data=fixture()
    const result=await new SyntheticMockAdapter().fetchReview('sample-project',{current:data.periods[0].range,previous:data.periods[1].range},10)
    const wire={...result,evidenceKind:'CURRENT_FACTS' as const,materialTypes:data}
    expect(() => validateReviewResponse(wire)).not.toThrow()
    for (const mutate of [
      (r:TypeReview) => {r.periods[0].range.start='2026-01-02'},
      (r:TypeReview) => {r.materials.original[0].creationDate.date='2025-12-31'},
      (r:TypeReview) => {r.materials.original[0].materialName='混剪错组'},
      (r:TypeReview) => {r.materials.original[0].dailySpend.pop()},
      (r:TypeReview) => {r.materials.mixed[0].materialId=r.materials.original[0].materialId},
    ]) {
      const invalid=structuredClone(wire); mutate(invalid.materialTypes)
      expect(() => validateReviewResponse(invalid)).toThrow('契约')
    }
  })
  it('does not manufacture type statistics for old saved reviews',async () => {
    const data=fixture()
    const result=await new SyntheticMockAdapter().fetchReview('sample-project',{current:data.periods[0].range,previous:data.periods[1].range},10)
    const w=mount(PeriodReview,{props:{result,projectName:'旧结果'}})
    expect(w.find('.material-type-table').exists()).toBe(false)
    expect(w.text()).toContain('此份结果尚无素材类型统计')
    w.unmount()
  })
})
