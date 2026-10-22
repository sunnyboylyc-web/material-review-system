import {flushPromises,mount} from '@vue/test-utils'
import {describe,expect,it,vi} from 'vitest'
import App from '../App.vue'
import PeriodReview from '../components/PeriodReview.vue'
import {SyntheticMockAdapter} from '../adapters/syntheticMockAdapter'
import {validateTopResponse,validateReviewResponse} from '../adapters/httpDashboardAdapter'
describe('D073 AIGC presentation',()=>{
 it('shows inapplicable creation and existing-data accumulation without invented creation warnings',async()=>{
  const adapter=new SyntheticMockAdapter(),original=adapter.fetchTop.bind(adapter)
  vi.spyOn(adapter,'fetchTop').mockImplementation(async(...args)=>{const data=await original(...args);data.rows[0].materialId='-2';data.rows[0].materialName='AIGC动态创意视频素材集合';data.rows[0].creationDate={status:'NOT_APPLICABLE',date:null,reason:null};return data})
  const page=mount(App,{props:{adapter}});await flushPromises()
  await page.get('.date-form--top').trigger('submit');await flushPromises()
  expect(page.get('.selected-material-date').text()).toContain('不适用')
  expect(page.get('.selected-metrics-grid').text()).toContain('累计消耗（已有数据）')
  expect(page.get('.material-row').text()).not.toContain('历史待核')
  expect(page.get('.material-row').text()).not.toContain('自首次投放')
  await page.get('.expand-button').trigger('click')
  expect(page.get('.expanded-material').text()).not.toContain('创建日期待核')
  const data=await adapter.fetchTop('sample-project','2026-10-13',10);data.evidenceKind='CURRENT_FACTS'
  expect(validateTopResponse(data)).toBe(data)
  data.rows[0].materialId='ordinary'
  expect(()=>validateTopResponse(data)).toThrow()
 })
 it('separates AIGC from true pending portions and retains only two performance cards',async()=>{
  const data=await new SyntheticMockAdapter().fetchReview('sample-project',{current:{start:'2026-10-01',end:'2026-10-07'},previous:{start:'2026-09-01',end:'2026-09-07'}},20)
  for(const period of data.periods)for(const group of period.groups){const n=group.key==='overall'?100:group.key==='new'?20:group.key==='historical'?50:10;group.metrics.spend={status:'VALID',value:n,partial:false,partialReasons:[]};group.metrics.materialCount={status:'VALID',value:n/10,partial:false,partialReasons:[]}}
  const page=mount(PeriodReview,{props:{result:data,projectName:'合成'}})
  expect(page.get('.pr-composition').text()).toContain('AIGC 10.00%')
  expect(page.get('.pr-composition').text()).toContain('待核 20.0%')
  expect(page.get('.pr-ratio-track .aigc').attributes('style')).toContain('10%')
  expect(page.findAll('.pr-group')).toHaveLength(2)
  data.evidenceKind='CURRENT_FACTS';expect(validateReviewResponse(data)).toBe(data)
  const missing=structuredClone(data);missing.periods[0].groups=missing.periods[0].groups.filter(g=>g.key!=='aigc')
  expect(()=>validateReviewResponse(missing)).toThrow()
  expect(()=>validateReviewResponse({...data,schemaVersion:'dashboard-query-1.5.0'})).toThrow()
 })
})
