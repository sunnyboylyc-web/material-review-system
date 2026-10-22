import { mount } from '@vue/test-utils'
import { toRaw } from 'vue'
import { describe, expect, it } from 'vitest'
import MaterialGallery from '../components/MaterialGallery.vue'
import SpendCurve from '../components/SpendCurve.vue'
import MetricValue from '../components/MetricValue.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { CreationDate, NumericResult, TopRow } from '../domain/dashboard'

const DATE = '2026-10-08'
const valid = (value: number): NumericResult => ({ status: 'VALID', value })
const unknown: NumericResult = { status: 'UNKNOWN_INPUT', value: null }

async function syntheticRow(overrides: Partial<TopRow> = {}): Promise<TopRow> {
  const fixture = new SyntheticMockAdapter([{ id: 'synthetic-gallery', name: '合成卡片项目', enabled: true }])
  const result = await fixture.fetchTop('synthetic-gallery', DATE, 10)
  return {
    ...result.rows[0]!, materialId: '99999999999999990123', materialName: '合成视频名称',
    creationDate: { status: 'VERIFIED', date: '2026-08-15', reason: null },
    ...overrides,
  }
}

describe('R05 material gallery presentation (synthetic fixtures only)', () => {
  it('shows identity, full video name and existing creation date without the old decorative labels', async () => {
    const name = '合成超长视频名称：展示完整标题且不因连续字符影响卡片宽度_' + 'SYNTHETIC_VIDEO_'.repeat(8)
    const row = await syntheticRow({ materialName: name, firstSpend: { status: 'VERIFIED', date: '2026-09-20', earliestObservedDate: '2026-09-20' } })
    const page = mount(MaterialGallery, { props: { rows: [row], date: DATE, selected: row.materialId } })
    const card = page.get('.material-card')
    expect(card.attributes('type')).toBe('button')
    expect(card.get('.material-card-id').text()).toBe('素材ID 99999999999999990123')
    expect(card.get('.material-card-title').text()).toBe(name)
    expect(card.get('.material-card-name').text()).toContain('视频名称')
    expect(card.get('.material-card-date').text()).toBe('首次投放时间2026-08-15')
    expect(card.get('.material-card-date').text()).not.toContain('2026-09-20')
    expect(card.attributes('aria-label')).toContain(name)
    expect(card.attributes('aria-label')).toContain('素材ID 99999999999999990123')
    expect(card.attributes('aria-pressed')).toBe('true')
    expect(page.find('.card-rank').exists()).toBe(false)
    expect(page.find('.art-caption').exists()).toBe(false)
    expect(page.text()).not.toContain('MATERIAL /')
    expect(page.text()).not.toContain('素材数据卡片')
    expect(page.text()).not.toContain('统计日')
    await card.trigger('click')
    expect(page.emitted('select')).toEqual([[row.materialId]])
    page.unmount()
  })

  it.each(['AIGC动态创意素材集合', 'AIGC动态创意视频素材集合'])('keeps the display-only AIGC exception for %s without changing its selection ID', async materialName => {
    const row = await syntheticRow({ materialId: '-2', materialName, creationDate: { status: 'NOT_APPLICABLE', date: null, reason: null } })
    const page = mount(MaterialGallery, { props: { rows: [row], date: DATE, selected: '' } })
    expect(page.get('.material-card-id').text()).toBe('素材ID -')
    expect(page.get('.material-card-title').text()).toBe(materialName)
    expect(page.get('.material-card-date').text()).toBe('首次投放时间不适用')
    expect(page.get('.material-card').attributes('aria-pressed')).toBe('false')
    await page.get('.material-card').trigger('click')
    expect(page.emitted('select')).toEqual([['-2']])
    expect(row.materialId).toBe('-2'); page.unmount()
  })

  it.each([
    ['MISSING_CREATION_DATE', '待核（创建日期缺失）'],
    ['CONFLICTING_CREATION_DATE', '待核（创建日期冲突）'],
  ] as const)('keeps missing video names and %s explicit', async (reason, explanation) => {
    const creationDate: CreationDate = { status: 'PENDING', date: null, reason }
    const row = await syntheticRow({ materialName: null, creationDate })
    const page = mount(MaterialGallery, { props: { rows: [row], date: DATE, selected: '' } })
    expect(page.get('.material-card-title').text()).toBe('名称未知')
    expect(page.get('.material-card').attributes('aria-label')).toContain('名称未知')
    expect(page.get('.material-card-id').text()).toContain(row.materialId)
    expect(page.get('.material-card-date').text()).toContain(explanation)
    expect(page.get('.material-card-date').text()).not.toContain('2026-08-15')
    page.unmount()
  })

  it('labels the existing seven-day curve and uses the selected-day spend and ROI without replacing fields', async () => {
    const dailySpend = Array.from({ length: 7 }, (_, i) => ({
      date: `2026-10-${String(i + 2).padStart(2, '0')}`,
      spend: i === 2 ? unknown : valid(i === 6 ? 42 : i),
    }))
    const row = await syntheticRow({ dailySpend, dayRoi: valid(2.75), windowSpend: valid(999), recent3Spend: valid(888) })
    const page = mount(MaterialGallery, { props: { rows: [row], date: DATE, selected: '' } })
    const curve = page.getComponent(SpendCurve)
    expect(toRaw(curve.props('days'))).toBe(dailySpend)
    expect(curve.props('days')).toHaveLength(7)
    expect(page.get('.material-card-curve-label').text()).toBe('近 7 天消耗曲线')
    expect(curve.get('svg').attributes('aria-label')).toBe('近 7 天消耗曲线，缺失日期断线')
    expect(curve.findAll('circle')).toHaveLength(6)
    expect(curve.findAll('polyline')).toHaveLength(2)
    const metrics = page.get('.material-card-metrics').findAllComponents(MetricValue)
    expect(toRaw(metrics[0]!.props('result')!)).toBe(dailySpend[6]!.spend)
    expect(toRaw(metrics[1]!.props('result')!)).toBe(row.dayRoi)
    expect(page.get('.material-card-metrics').text()).toContain('当天消耗42.00')
    expect(page.get('.material-card-metrics').text()).toContain('当天 ROI2.75')
    expect(page.get('.material-card-metrics').text()).not.toContain('999.00')
    expect(page.get('.material-card-metrics').text()).not.toContain('888.00')
    page.unmount()
  })

  it('distinguishes zero spend, unknown ROI and a selected day without an input', async () => {
    const row = await syntheticRow({ dailySpend: [{ date: DATE, spend: valid(0) }], dayRoi: unknown })
    const page = mount(MaterialGallery, { props: { rows: [row], date: DATE, selected: '' } })
    expect(page.get('.material-card-metrics').text()).toContain('当天消耗0.00')
    expect(page.get('.material-card-metrics').text()).toContain('当天 ROI—（数据未知）')
    await page.setProps({ date: '2026-10-09' })
    expect(page.get('.material-card-metrics').text()).toContain('当天消耗—（未查询）')
    expect(toRaw(page.getComponent(SpendCurve).props('days'))).toBe(row.dailySpend)
    page.unmount()
  })
})
