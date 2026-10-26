import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CoverageStatus from '../components/CoverageStatus.vue'
describe('coverage missing-date disclosure', () => {
  it('exposes only count and first/last missing date in business summary mode', () => {
    const missingDates=['2026-10-01','2026-10-03','2026-10-05']
    const wrapper=mount(CoverageStatus,{props:{coverage:{state:'PARTIAL',expectedDays:5,coveredDays:2,missingDates},summaryOnly:true}})
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.text()).toContain('缺失 3 天')
    expect(wrapper.text()).toContain('首日 2026-10-01')
    expect(wrapper.text()).toContain('末日 2026-10-05')
    expect(wrapper.text()).not.toContain('2026-10-03')
  })
  it('keeps short missing lists inline', () => {
    const missingDates=['2026-10-01','2026-10-03']
    const wrapper=mount(CoverageStatus,{props:{coverage:{state:'PARTIAL',expectedDays:3,coveredDays:1,missingDates}}})
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.text()).toContain(missingDates.join('、'))
  })
  it('collapses long lists while retaining every missing date in native disclosure', () => {
    const missingDates=Array.from({length:600},(_,i)=>new Date(Date.UTC(2024,2,18+i)).toISOString().slice(0,10))
    const wrapper=mount(CoverageStatus,{props:{coverage:{state:'PARTIAL',expectedDays:700,coveredDays:100,missingDates}}})
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('summary').text()).toContain('600 天')
    expect(wrapper.get('.coverage > .coverage__missing').text()).toContain('首日 2024-03-18')
    expect(wrapper.get('details p').text().split('、')).toEqual(missingDates)
  })
})
