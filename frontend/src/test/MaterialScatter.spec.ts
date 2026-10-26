import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MaterialScatter from '../components/MaterialScatter.vue'
import type { TopRow } from '../domain/dashboard'

const rows = [1, 2, 3].map(rank => ({ rank, materialId: String(rank), materialName: `合成素材${rank}`,
  dailySpend: [{ date: '2026-10-12', spend: { status: 'VALID', value: 10 } }],
  dayRoi: rank === 3 ? { status: 'UNKNOWN_INPUT', value: null } : { status: 'VALID', value: 0 },
})) as TopRow[]

describe('scatter material locator', () => {
  it('uses the taller plotting area without changing data scales or horizontal coordinates', () => {
    const wrapper = mount(MaterialScatter, { props: { rows, selectedId: '', selectedDate: '2026-10-12' } })
    expect(wrapper.get('svg').attributes('viewBox')).toBe('0 0 560 390')
    expect(wrapper.get('.chart-axis').attributes('d')).toBe('M58 25 V338 H530')
    expect(wrapper.get('.scatter-point').attributes('transform')).toBe(`translate(${58 + 10 / 10.8 * 472}, 338)`)
  })
  it('allows selecting overlapping and unknown-coordinate list members without changing data', async () => {
    const wrapper = mount(MaterialScatter, { props: { rows, selectedId: '', selectedDate: '2026-10-12' } })
    expect(wrapper.findAll('option')).toHaveLength(4)
    expect(wrapper.findAll('.scatter-point')).toHaveLength(2)
    await wrapper.get('select').setValue('3')
    expect(wrapper.emitted('select')).toEqual([['3']])
    expect(wrapper.text()).toContain('1 条素材的坐标指标未知')
  })
  it('paints selected point above overlapping points without shifting its coordinate', async () => {
    const wrapper = mount(MaterialScatter, { props: { rows, selectedId: '', selectedDate: '2026-10-12' } })
    const coordinate = wrapper.findAll('.scatter-point')[0].attributes('transform')
    await wrapper.setProps({ selectedId: '1' })
    const last = wrapper.findAll('.scatter-point').at(-1)!
    expect(last.attributes('aria-label')).toContain('第 1 名')
    expect(last.attributes('transform')).toBe(coordinate)
    expect(wrapper.find('.scatter-selection').exists()).toBe(false)
  })
})
