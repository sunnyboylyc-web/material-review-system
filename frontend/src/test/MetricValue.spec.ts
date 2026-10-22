import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MetricValue from '../components/MetricValue.vue'

describe('metric decimal typography', () => {
  it.each([
    [1234.56, 'decimal', '1,234.56', '56'],
    [-12.5, 'decimal', '-12.50', '50'],
    [0, 'decimal', '0.00', '00'],
    [0.1234, 'percent', '12.34%', '34'],
  ] as const)('preserves formatted value %s %s', (value, format, text, fraction) => {
    const wrapper = mount(MetricValue, { props: { result: { status: 'VALID', value }, format } })
    expect(wrapper.text()).toBe(text)
    expect(wrapper.get('.metric-fraction').text()).toBe(fraction)
  })
  it('does not split counts or unknown values', () => {
    const count = mount(MetricValue, { props: { result: { status: 'VALID', value: 1234 }, format: 'integer' } })
    expect(count.text()).toBe('1,234')
    expect(count.find('.metric-fraction').exists()).toBe(false)
    const unknown = mount(MetricValue)
    expect(unknown.text()).toBe('—（未查询）')
    expect(unknown.find('.metric-fraction').exists()).toBe(false)
  })
})
