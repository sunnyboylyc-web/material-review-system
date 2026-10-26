import { describe,it,expect,beforeEach } from 'vitest'
import { flushPromises,mount } from '@vue/test-utils'
import MaterialColumnFilter from '../components/MaterialColumnFilter.vue'
import MaterialTypeReview from '../components/MaterialTypeReview.vue'
import PeriodReview from '../components/PeriodReview.vue'
import MaterialTestPeriodHelp from '../components/MaterialTestPeriodHelp.vue'
import { emptyFilters,filterOptions,matchingMaterialIds,type FilterRecord,type FilterColumn } from '../domain/materialFilters'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateReviewMaterials } from '../adapters/httpDashboardAdapter'
import type { MaterialTypeReview as TypeResult,MaterialTypeRow,NumericResult,MetricKey } from '../domain/dashboard'
const n=(value:number):NumericResult=>({status:'VALID',value,partial:false,partialReasons:[]})
const unknown:NumericResult={status:'UNKNOWN_INPUT',value:null,partial:true,partialReasons:['MISSING_FIELD']}
const zero:NumericResult={status:'ZERO_DENOMINATOR',value:null,partial:false,partialReasons:[]}
const columns:FilterColumn[]=[{key:'spend',label:'消耗',kind:'number'},{key:'ctr',label:'点击率',kind:'percent'},{key:'date',label:'日期',kind:'date'}]
beforeEach(()=>{HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','')};HTMLDialogElement.prototype.close=function(){this.removeAttribute('open')}})

describe('complete-scope material filters',()=>{
 it('matches case-insensitive name or textual ID beyond row 200 and combines inclusive conditions',()=>{
  const data:FilterRecord[]=Array.from({length:225},(_,i)=>({materialId:`900719925474099${String(i).padStart(3,'0')}`,materialName:i===224?'ai目标素材':'示例',values:{spend:n(i),ctr:n(.025),date:{status:'VERIFIED',date:'2026-01-01',reason:null}}}))
  const filters=emptyFilters();filters.search='AI目标'
  filters.columns.spend={operator:'between',from:'224',to:'224'};filters.columns.ctr={operator:'gte',from:'2.5',to:''}
  filters.columns.date={operator:'eq',from:'2026-01-01',to:''}
  expect([...matchingMaterialIds(data,filters,columns)]).toEqual([data[224].materialId])
  filters.search=data[224].materialId.slice(-6);expect(matchingMaterialIds(data,filters,columns).size).toBe(1)
  filters.columns.ctr.from='2.6';expect(matchingMaterialIds(data,filters,columns).size).toBe(0)
 })
 it('keeps unknown and zero denominators separate from zero and applies checkbox choices',()=>{
  const data:FilterRecord[]=[n(0),unknown,zero].map((spend,i)=>({materialId:String(i),materialName:null,values:{spend}}))
  const column=columns[0];const options=filterOptions(data,column)
  expect(options.map(x=>x.label)).toEqual(['0','数据未知','分母为0'])
  const filters=emptyFilters();filters.columns.spend={operator:'eq',from:'0',to:''}
  expect([...matchingMaterialIds(data,filters,[column])]).toEqual(['0'])
  filters.columns.spend={operator:'all',from:'',to:'',selected:[options[1].key]}
  expect([...matchingMaterialIds(data,filters,[column])]).toEqual(['1'])
  filters.columns.spend.selected=[];expect(matchingMaterialIds(data,filters,[column]).size).toBe(0)
 })
 it('does not apply invalid input and accepts explicit empty selection',async()=>{
  const wrapper=mount(MaterialColumnFilter,{props:{column:columns[0],options:[{key:'VALID:0',label:'0'},{key:'UNKNOWN_INPUT:',label:'数据未知'}]}})
  await wrapper.get('summary').trigger('click')
  await wrapper.get('select').setValue('between');await wrapper.get('[aria-label="消耗筛选值"]').setValue('10');await wrapper.get('[aria-label="消耗筛选结束值"]').setValue('1')
  await wrapper.get('.apply-filter').trigger('click');expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  expect(wrapper.get('[role="alert"]').text()).toContain('不能大于')
  await wrapper.get('select').setValue('all');await wrapper.findAll('.column-filter-choice-actions button')[1].trigger('click');await wrapper.get('.apply-filter').trigger('click')
  expect(wrapper.emitted('update:modelValue')![0][0]).toMatchObject({selected:[]});wrapper.unmount()
 })
 it('reads the complete overall list then filters before limiting, preserving chart and totals',async()=>{
  const data=await new SyntheticMockAdapter().fetchReview('sample-project',{current:{start:'2026-01-01',end:'2026-01-10'},previous:{start:'2025-12-22',end:'2025-12-31'}},10)
  const list=Array.from({length:225},(_,i)=>({...structuredClone(data.topMaterials[0]),rank:i+1,materialId:`SYNTHETIC_${i}`,materialName:i===224?'AI末尾目标':'演示素材'}))
  const wrapper=mount(PeriodReview,{props:{result:data,projectName:'合成',loadAllOverallMaterials:async()=>list}})
  const totals=wrapper.get('.pr-kpis').text()
  const chartCount=wrapper.findAll('.pr-column-item').length
  await wrapper.get('.pr-column-item').trigger('click');await flushPromises()
  expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(10)
  await wrapper.get('[aria-label="搜索素材名称或ID"]').setValue('ai末尾目标')
  expect(wrapper.findAll('.pr-detail-table tbody tr')).toHaveLength(1);expect(wrapper.get('.pr-detail-table tbody').text()).toContain('SYNTHETIC_224')
  expect(wrapper.get('.material-filter-count').text()).toContain('匹配 1 个 / 共 225 个')
  expect(wrapper.get('.pr-kpis').text()).toBe(totals);expect(wrapper.findAll('.pr-column-item')).toHaveLength(chartCount)
  wrapper.unmount()
 })
 it('uses the same filters in all five type and AI detail groups without sharing criteria',async()=>{
  const current={start:'2026-01-01',end:'2026-01-01'},previous={start:'2025-12-31',end:'2025-12-31'}
  const metrics=Object.fromEntries(['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare'].map(k=>[k,n(k==='spend'?100:1)])) as Record<MetricKey,NumericResult>
  const rows=(label:string):MaterialTypeRow[]=>Array.from({length:225},(_,i)=>({rank:i+1,materialId:`SYNTHETIC_${i}`,materialName:label+(i===224?'目标':'演示'),creationDate:{status:'VERIFIED',date:current.start,reason:null},metrics,previousSpend:n(0),dailySpend:[{date:current.start,spend:n(1)}]}))
  const groups=['original','mixed','other'].map(key=>({key,metrics})) as TypeResult['comparisons'];const aiGroups=['ai','business','department'].map(key=>({key,metrics})) as NonNullable<TypeResult['ai']>['comparisons']
  const periods=([{key:'CURRENT',range:current},{key:'PREVIOUS',range:previous}] as const).map(p=>({...p,groups,coverage:{state:'COMPLETE' as const,expectedDays:1,coveredDays:1,missingDates:[]},excludedUndatedMaterials:0,unresolvedDateMaterials:0}))
  const result:TypeResult={schemaVersion:'material-type-review-2.0.0',periods,comparisons:groups,materials:{original:rows('原创'),mixed:rows('混剪'),other:rows('其他')},ai:{periods:periods.map(p=>({...p,groups:aiGroups})),comparisons:aiGroups,materials:{business:rows('ai商单'),department:rows('ai部门')}}}
  const w=mount(MaterialTypeReview,{props:{result,projectName:'合成'}})
  for(const type of ['original','mixed','other','business','department']){
   if(type==='business'||type==='department'){
    if(!w.find('.ai-breakdown-dialog').exists()){await w.get('.ai-summary-row .ai-summary-button').trigger('click');await flushPromises()}
    await w.findAll('.ai-breakdown-card .pr-detail-button')[type==='business'?0:1].trigger('click')
   }else if(type==='other')await w.get('[aria-label="查看其他素材"]').trigger('click')
   else await w.findAll('.material-type-performance .pr-detail-button')[type==='original'?0:1].trigger('click')
   await flushPromises();expect(w.get('[aria-label="搜索素材名称或ID"]').element).toHaveProperty('value','')
   const totals=w.get('.pr-dialog-summary').text()
   await w.get('[aria-label="搜索素材名称或ID"]').setValue('目标')
   expect(w.get('.pr-detail-table tbody').text()).toContain('SYNTHETIC_224');expect(w.get('.pr-dialog-summary').text()).toBe(totals)
   expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(1)
   await w.get('[aria-label="关闭类型素材明细"]').trigger('click')
  }
  w.unmount()
 })
 it('also filters new, historical and AIGC lists with independent searches',async()=>{
  const data=await new SyntheticMockAdapter().fetchReview('sample-project',{current:{start:'2026-01-01',end:'2026-01-10'},previous:{start:'2025-12-22',end:'2025-12-31'}},10)
  for(const key of ['new','historical','aigc'] as const)data.cohortMaterials[key]=Array.from({length:225},(_,i)=>({...structuredClone(data.topMaterials[0]),rank:i+1,materialId:`SYNTHETIC_${key}_${i}`,materialName:i===224?'尾部目标':'示例'}))
  const w=mount(PeriodReview,{props:{result:data,projectName:'合成'}})
  for(const selector of ['.pr-group.new .pr-detail-button','.pr-group.historical .pr-detail-button','.pr-aigc-button']){
   await w.get(selector).trigger('click');await flushPromises()
   expect(w.get('[aria-label="搜索素材名称或ID"]').element).toHaveProperty('value','')
   await w.get('[aria-label="搜索素材名称或ID"]').setValue('尾部目标')
   expect(w.get('.pr-detail-table tbody').text()).toContain('_224');expect(w.findAll('.pr-detail-table tbody tr')).toHaveLength(1)
   await w.get('[aria-label="关闭素材明细"]').trigger('click')
  }
  w.unmount()
 })
 it('rejects complete-list responses from a different project, range, duplicate ID or broken curve',async()=>{
  const query={current:{start:'2026-01-01',end:'2026-01-10'},previous:{start:'2025-12-22',end:'2025-12-31'}}
  const data=await new SyntheticMockAdapter().fetchReview('sample-project',query,10)
  const wire={schemaVersion:'review-materials-1.0.0',projectId:'synthetic',evidenceKind:'CURRENT_FACTS',...query,snapshotFingerprint:'synthetic',rows:data.topMaterials}
  expect(()=>validateReviewMaterials(wire,'synthetic',query)).not.toThrow()
  for(const mutate of [
   (v:typeof wire)=>{v.projectId='other'},(v:typeof wire)=>{v.current.start='2026-01-02'},
   (v:typeof wire)=>{v.rows.push({...v.rows[0],rank:2})},(v:typeof wire)=>{v.rows[0].dailySpend.pop()},
  ]){const invalid=structuredClone(wire);mutate(invalid);expect(()=>validateReviewMaterials(invalid,'synthetic',query)).toThrow('契约')}
 })
 it('explains uploaded materials and the two independent windows without modifying dates',async()=>{
  const w=mount(MaterialTestPeriodHelp)
  await w.get('button').trigger('click');await flushPromises()
  expect(w.get('#test-period-help-title').text()).toBe('测试周期说明')
  expect(w.get('dialog').text()).toContain('上传的素材');expect(w.get('dialog').text()).not.toContain('首次投放')
  expect(w.get('dialog').text()).toContain('2024年1月1日—2026年1月31日')
  await w.get('[aria-label="关闭测试周期说明"]').trigger('click');expect(w.find('dialog').exists()).toBe(false);w.unmount()
 })
})
