import {flushPromises,mount} from '@vue/test-utils'
import {describe,expect,it,vi} from 'vitest'
import App from '../App.vue'
import {SyntheticMockAdapter} from '../adapters/syntheticMockAdapter'
import {HttpDashboardAdapter} from '../adapters/httpDashboardAdapter'
import type {DashboardDataAdapter} from '../adapters/dashboardAdapter'

const freshness=(projectId:string,date:string|null)=>({schemaVersion:'freshness-1.0.0' as const,projectId,latestMcpStatDate:date})
describe('freshness and seven-day light',()=>{
  it('strictly validates the standalone endpoint without query dates',async()=>{
    let value:unknown=freshness('p','2026-10-13')
    const fetcher=vi.fn<typeof fetch>().mockImplementation(async()=>({ok:true,json:async()=>value} as Response))
    const adapter=new HttpDashboardAdapter(fetcher)
    expect(await adapter.fetchFreshness('p')).toEqual(value)
    expect(fetcher.mock.lastCall?.[0]).toBe('/api/projects/p/freshness')
    for(const bad of [{...freshness('p',null),latestMcpStatDate:undefined},freshness('other',null),freshness('p','2026-02-30'),{...freshness('p',null),schemaVersion:'old'}]){
      value=bad;await expect(adapter.fetchFreshness('p')).rejects.toThrow('契约不一致')
    }
    value=freshness('p',null);expect((await adapter.fetchFreshness('p')).latestMcpStatDate).toBeNull()
  })
  it('ignores stale project freshness and clears failures instead of retaining an old date',async()=>{
    const source=new SyntheticMockAdapter([{id:'a',accountId:'a',name:'A',enabled:true},{id:'b',accountId:'b',name:'B',enabled:true}])
    const pending:((v:ReturnType<typeof freshness>)=>void)[]=[]
    const fetchFreshness=vi.fn((id:string)=>id==='a'?new Promise<ReturnType<typeof freshness>>(resolve=>pending.push(resolve)):Promise.resolve(freshness(id,'2026-10-13')))
    const adapter:DashboardDataAdapter={evidenceKind:'CURRENT_FACTS',listProjects:()=>source.listProjects(),fetchReview:(...a)=>source.fetchReview(...a),fetchTop:(...a)=>source.fetchTop(...a),fetchFreshness}
    const wrapper=mount(App,{props:{adapter}});await flushPromises()
    expect(fetchFreshness).not.toHaveBeenCalled()
    await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    await wrapper.findAll('.project-button')[1].trigger('click');await flushPromises()
    expect(wrapper.get('.freshness-label').text()).toBe('最新数据未知')
    await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    expect(wrapper.get('.freshness-label').text()).toBe('最新数据2026.10.13日')
    pending.forEach(resolve=>resolve(freshness('a','2026-10-01')));await flushPromises()
    expect(wrapper.get('.freshness-label').text()).toContain('2026.10.13')
    fetchFreshness.mockRejectedValue(new Error('unavailable'))
    await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    expect(wrapper.get('.freshness-label').text()).toBe('最新数据未知')
    fetchFreshness.mockResolvedValue(freshness('b',null))
    await wrapper.get('.period-toolbar').trigger('submit');await flushPromises()
    expect(wrapper.get('.freshness-label').text()).toBe('暂无MCP数据')
    const periodLight=wrapper.get('.period-toolbar .period-status-dot')
    expect(periodLight.text()).toBe('')
    expect(periodLight.attributes('aria-label')).toBe('周期数据缺失或待核')
    expect(periodLight.classes()).toContain('period-status-dot--red')
    wrapper.unmount()
  })
  it('uses seven-day account coverage, not lifetime or individual missing fields',async()=>{
    const adapter=new SyntheticMockAdapter(),original=adapter.fetchTop.bind(adapter)
    let complete=true
    vi.spyOn(adapter,'fetchTop').mockImplementation(async(...args)=>{
      const result=await original(...args)
      result.coverage=complete?{state:'COMPLETE',expectedDays:7,coveredDays:7,missingDates:[]}:result.coverage
      result.rows[0].lifetimeSpend={status:'UNKNOWN_INPUT',value:null,partial:true}
      return result
    })
    const wrapper=mount(App,{props:{adapter}});await flushPromises()
    await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    expect(wrapper.get('.daily-status').text()).toBe('')
    expect(wrapper.get('.daily-status').attributes('aria-label')).toBe('近7天数据正常')
    expect(wrapper.get('.daily-status').classes()).toContain('period-status-dot--green')
    complete=false;await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    expect(wrapper.get('.daily-status').text()).toBe('')
    expect(wrapper.get('.daily-status').attributes('aria-label')).toBe('近7天数据缺失')
    expect(wrapper.get('.daily-status').classes()).toContain('period-status-dot--red')
    vi.spyOn(adapter,'fetchTop').mockRejectedValue(new Error('offline'))
    await wrapper.get('.date-form--top').trigger('submit');await flushPromises()
    expect(wrapper.get('.daily-status').classes()).toContain('period-status-dot--loading')
    expect(wrapper.get('.daily-status').text()).toBe('')
    expect(wrapper.get('.daily-status').attributes('aria-label')).toBe('近7天数据读取失败')
    wrapper.unmount()
  })
})
