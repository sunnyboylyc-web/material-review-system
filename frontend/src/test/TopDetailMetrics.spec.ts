import { refreshDashboard } from './refreshDashboard'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateTopResponse } from '../adapters/httpDashboardAdapter'
describe('D067 selected material metrics',()=>{
  it('places identity in the heading and renders the same six recent-three-day metrics in both detail views',async()=>{
    const adapter=new SyntheticMockAdapter(),original=adapter.fetchTop.bind(adapter)
    vi.spyOn(adapter,'fetchTop').mockImplementation(async(...args)=>{
      const result=await original(...args)
      result.rows[0].lifetimeCoverage={state:'PARTIAL',expectedDays:5,coveredDays:2,missingDates:['2026-01-01','2026-01-02','2026-01-03']}
      result.rows[0].lifetimeSpend={status:'VALID',value:777,partial:true,partialReasons:['MISSING_ACCOUNT_DAYS']}
      result.rows[0].lifetimeRoi={status:'UNKNOWN_INPUT',value:null,partial:true,partialReasons:['MISSING_FIELD']}
      result.rows[0].windowSpend={status:'VALID',value:120,partial:true,partialReasons:['MISSING_FIELD']}
      return result
    })
    const wrapper=mount(App,{props:{adapter}});await flushPromises(); await refreshDashboard(wrapper)
    expect(wrapper.get('.selected-material-titlebar').text()).toContain('素材ID 99999999999999990001')
    expect(wrapper.get('.selected-material-titlebar').text()).toContain('首次投放时间')
    expect(wrapper.find('.selected-material-meta').exists()).toBe(false)
    const labels=wrapper.findAll('.selected-metrics-grid>div>span').map(v=>v.text())
    expect(labels.slice(4,10)).toEqual(['近 3 天 CPM','近 3 天 CPC','近 3 天展现次数','近 3 天点击次数','近 3 天点击率','近 3 天转化率'])
    expect(wrapper.get('.selected-metrics-grid').text()).toContain('1,000')
    expect(wrapper.get('.selected-metrics-grid').text()).toContain('10.00%')
    const cumulative=wrapper.findAll('.selected-metrics-grid>div').slice(-2)
    expect(cumulative[0].text()).toContain('777.00')
    expect(cumulative[1].text()).toContain('数据未知')
    cumulative.forEach(cell=>expect(cell.find('.metric-partial').exists()).toBe(false))
    expect(wrapper.get('.material-row').findAll('.metric-partial')).toHaveLength(0)
    expect(wrapper.get('.material-row').get('[aria-label="120.00，部分数据缺失，按已有匹配输入计算"]').text()).toBe('120.00')
    await wrapper.get('.expand-button').trigger('click')
    const extra=wrapper.get('.expanded-material aside')
    labels.slice(4,10).forEach(label=>expect(extra.text()).toContain(label))
    expect(extra.text()).not.toContain('缺失 3 天')
    expect(extra.find('details').exists()).toBe(false)
    expect(extra.text()).not.toContain('2026-01-02')
  })
  it('requires all four new metric results and accepts partial and unknown without fabricating zero',async()=>{
    const result=await new SyntheticMockAdapter().fetchTop('sample-project','2026-10-12',20);result.evidenceKind='CURRENT_FACTS'
    result.rows[0].recent3Ctr={status:'VALID',value:.1,partial:true,partialReasons:['MISSING_FIELD']}
    result.rows[0].recent3Cvr={status:'UNKNOWN_INPUT',value:null,partial:true,partialReasons:['MISSING_FIELD']}
    expect(validateTopResponse(result)).toBe(result)
    for(const key of ['recent3Impressions','recent3Clicks','recent3Ctr','recent3Cvr']){
      const broken=structuredClone(result) as unknown as {rows:Record<string,unknown>[]};delete broken.rows[0][key]
      expect(()=>validateTopResponse(broken)).toThrow()
    }
    expect(()=>validateTopResponse({...result,schemaVersion:'dashboard-query-1.3.0'})).toThrow()
  })
})
