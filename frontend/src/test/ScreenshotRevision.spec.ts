import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'

describe('five-screenshot presentation changes', () => {
  it('removes the requested decoration and uses the same business labels in home and navigation', async () => {
    const page = mount(App, { attachTo: document.body, props: { adapter: new SyntheticMockAdapter() }, global: { stubs: { StarScene: true } } })
    await flushPromises()
    expect(page.get('.cosmic-heading h1').text()).toContain('让每一份流量')
    for (const selector of ['.global-brand small', '.global-brand em', '.project-picker-button', '.moon-atlas-trigger', '.hero-subtitle', '.core-caption small', '.cosmic-foot', '.workspace-footer']) expect(page.find(selector).exists()).toBe(false)
    expect(page.findAll('.global-nav button span').map(node => node.text())).toEqual(['工作台', '每日TOP素材', '周期数据', '数据运维'])
    await page.findAll('.global-nav button')[1]!.trigger('click')
    expect(page.find('.workspace-header .overline').exists()).toBe(false)
    expect(page.find('.workspace-intro p').exists()).toBe(false)
    await page.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(page.find('.daily-summary-strip').exists()).toBe(false)
    expect(page.get('.top-overview').isVisible()).toBe(true)
    page.unmount()
  })
  it('selects a project from its name box while staying on the home overview, with separate shortcuts', async () => {
    const adapter = new SyntheticMockAdapter([{ id: 'a', name: '合成项目甲', enabled: true }, { id: 'b', name: '合成项目乙', enabled: true }])
    const page = mount(App, { attachTo: document.body, props: { adapter }, global: { stubs: { StarScene: true } } }); await flushPromises()
    await page.get('[data-project-id="b"] .satellite-select').trigger('click'); await flushPromises()
    expect(page.get('.cosmic-workspace').isVisible()).toBe(true)
    expect(page.get('[data-project-id="b"] .satellite-select').attributes('aria-pressed')).toBe('true')
    expect(page.get('.home-overview-heading').text()).toContain('合成项目乙')
    await page.get('[data-project-id="a"] .satellite-label').trigger('click'); await flushPromises()
    expect(page.get('.home-overview-heading').text()).toContain('合成项目甲')
    await page.get('[aria-label="合成项目乙：周期数据"]').trigger('click'); await flushPromises()
    expect(page.get('.review-panel').isVisible()).toBe(true)
    expect(page.get('.cosmic-workspace').isVisible()).toBe(false)
    expect(page.get('.workspace-header h2').text()).toBe('合成项目乙')
    page.unmount()
  })
})
