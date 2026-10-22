import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SpendCurve from '../components/SpendCurve.vue'
describe('seven-day evidence curve', () => {
  it('orders oldest to newest, preserves real zero and breaks across unknown days', () => {
    const wrapper = mount(SpendCurve, { props: { expanded: true, days: [
      { date: '2026-10-03', spend: { status: 'VALID', value: 100 } },
      { date: '2026-10-02', spend: { status: 'UNKNOWN_INPUT', value: null } },
      { date: '2026-10-01', spend: { status: 'VALID', value: 0 } },
    ] } })
    expect(wrapper.findAll('polyline')).toHaveLength(2)
    expect(wrapper.findAll('circle')).toHaveLength(2)
    expect(wrapper.findAll('circle')[0].attributes('cy')).toBe('142')
    expect(wrapper.findAll('circle')[0].text()).toContain('2026-10-01 · 0 元')
    expect(wrapper.text()).toContain('缺数')
    expect(wrapper.findAll('polyline').every(p => !p.attributes('points')!.includes(' '))).toBe(true)
  })
})
