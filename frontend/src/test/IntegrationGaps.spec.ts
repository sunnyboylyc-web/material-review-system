import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import StarScene from '../components/StarScene.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { ReviewQuery } from '../domain/dashboard'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('R05 integration gap regressions (synthetic only)', () => {
  it.each(['每日TOP素材', '周期数据'])('keeps the latest home name selection after a slow %s shortcut resolves', async destination => {
    let finishPreference!: (query: ReviewQuery | null) => void
    const delayed = new Promise<ReviewQuery | null>(resolve => { finishPreference = resolve })
    const adapter = new SyntheticMockAdapter([{ id: 'a', name: '合成甲', enabled: true }, { id: 'b', name: '合成乙', enabled: true }])
    const top = vi.spyOn(adapter, 'fetchTop'), review = vi.spyOn(adapter, 'fetchReview')
    const preferences = { load: vi.fn((id: string) => id === 'b' ? delayed : Promise.resolve(null)), save: vi.fn() }
    const page = mount(App, { attachTo: document.body, props: { adapter, periodPreferences: preferences }, global: { stubs: { StarScene: true } } })
    try {
      await flushPromises()
      await page.get(`[aria-label="合成乙：${destination}"]`).trigger('click'); await flushPromises()
      expect(preferences.load).toHaveBeenCalledWith('b')
      expect(page.get('.workspace-home').isVisible()).toBe(true)
      await page.get('[aria-label="选择项目：合成乙，查看近7天数据"]').trigger('click')
      finishPreference(null); await flushPromises()
      expect(page.get('.workspace-home').isVisible()).toBe(true)
      expect(page.get('[data-project-id="b"] .satellite-select').attributes('aria-pressed')).toBe('true')
      expect(top).not.toHaveBeenCalled(); expect(review).not.toHaveBeenCalled(); expect(preferences.save).not.toHaveBeenCalled()
    } finally { page.unmount() }
  })

  it('pauses the hidden star scene and resumes only if the user has not paused it', async () => {
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter() }, global: { stubs: { StarScene: true } } })
    try {
      await flushPromises()
      const scene = page.getComponent(StarScene)
      expect(scene.props('paused')).toBe(false)
      await page.findAll('.global-nav button')[1]!.trigger('click')
      expect(scene.props('paused')).toBe(true)
      await page.findAll('.global-nav button')[0]!.trigger('click')
      expect(scene.props('paused')).toBe(false)
      await page.get('.cosmic-controls button').trigger('click')
      expect(scene.props('paused')).toBe(true)
      await page.findAll('.global-nav button')[2]!.trigger('click')
      await page.findAll('.global-nav button')[0]!.trigger('click')
      expect(scene.props('paused')).toBe(true)
    } finally { page.unmount() }
  })
})
