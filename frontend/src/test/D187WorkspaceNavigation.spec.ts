import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import WorkspaceHome from '../components/WorkspaceHome.vue'
import workspaceSource from '../components/WorkspaceHome.vue?raw'
import ReturnToTop from '../components/ReturnToTop.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
const projects = [{ id: 'a', name: '项目 A', enabled: true }, { id: 'b', name: '项目 B', enabled: true }]
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('D187 home navigation and bounded motion', () => {
  it('applies the same lightweight motion and pause/reduced-motion guards to every project moon', () => {
    expect(workspaceSource).toContain('.project-satellite :deep(.moon-disc) { animation: workspace-moon-drift 14s ease-in-out infinite; }')
    expect(workspaceSource).toContain('.motion-paused .project-satellite :deep(.moon-disc) { animation-play-state: paused; }')
    expect(workspaceSource).toContain('@media(prefers-reduced-motion:reduce) { .cosmic-center :deep(.star-scene), .project-satellite :deep(.moon-disc) { animation:none; } }')
    expect(workspaceSource).not.toContain('.project-satellite.is-selected :deep(.moon-disc)')
  })
  it('the large project name card selects overview and scrolls there, without a daily query', async () => {
    const scroll = vi.fn()
    const previous = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
    const adapter = new SyntheticMockAdapter(projects), top = vi.spyOn(adapter, 'fetchTop'), period = vi.spyOn(adapter, 'fetchReview')
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const page = mount(App, { props: { adapter }, attachTo: document.body }); await flushPromises()
    await page.get('[data-project-id="b"] .satellite-label').trigger('click'); await flushPromises()
    expect(page.get('#home-overview-title').text()).toBe('项目 B')
    expect(page.get('.workspace-home').isVisible()).toBe(true)
    expect(document.activeElement?.id).toBe('home-overview-title')
    expect(scroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(top).not.toHaveBeenCalled(); expect(period).not.toHaveBeenCalled()
    page.unmount()
    if (previous) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', previous)
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  })
  it('return-to-top appears after scrolling, is hidden behind dialogs, and honors reduced motion', async () => {
    const scrollTo = vi.fn(); vi.stubGlobal('scrollTo', scrollTo)
    vi.stubGlobal('matchMedia', () => ({ matches: true }))
    const position = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(0)
    const page = mount(ReturnToTop)
    expect(page.find('button').exists()).toBe(false)
    let measure!: FrameRequestCallback
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { measure = callback; return 17 })
    position.mockReturnValue(800); window.dispatchEvent(new Event('scroll')); measure(1); await page.vm.$nextTick()
    expect(page.get('button').attributes('aria-label')).toBe('返回顶部')
    await page.setProps({ blocked: true }); expect(page.find('button').exists()).toBe(false)
    await page.setProps({ blocked: false }); await page.get('button').trigger('click')
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'auto' })
    page.unmount()
  })
  it('pauses the existing canvas and decorative motion when offscreen, hidden or reduced motion is requested', async () => {
    let visibility!: IntersectionObserverCallback, mediaChange!: () => void
    const preference = { matches: false, addEventListener: (_: string, callback: () => void) => { mediaChange = callback }, removeEventListener: vi.fn() }
    vi.stubGlobal('matchMedia', () => preference)
    const disconnect = vi.fn()
    vi.stubGlobal('IntersectionObserver', class { constructor(callback: IntersectionObserverCallback) { visibility = callback } observe() {} disconnect = disconnect })
    const page = mount(WorkspaceHome, { props: { projects, selectedProjectId: 'a', projectName: '项目 A', canOperate: true, active: true, top: null, review: null }, global: { stubs: { StarScene: true } } })
    await flushPromises()
    expect(page.get('star-scene-stub').attributes('paused')).toBe('false')
    visibility([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver); await page.vm.$nextTick()
    expect(page.get('star-scene-stub').attributes('paused')).toBe('true')
    expect(page.get('.project-universe').classes()).toContain('motion-paused')
    visibility([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver); await page.vm.$nextTick()
    expect(page.get('star-scene-stub').attributes('paused')).toBe('false')
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange')); await page.vm.$nextTick()
    expect(page.get('star-scene-stub').attributes('paused')).toBe('true')
    hidden.mockReturnValue(false); document.dispatchEvent(new Event('visibilitychange'))
    preference.matches = true; mediaChange(); await page.vm.$nextTick()
    expect(page.get('star-scene-stub').attributes('paused')).toBe('true')
    expect(page.get('.cosmic-controls button').attributes('disabled')).toBeDefined()
    page.unmount(); expect(disconnect).toHaveBeenCalledTimes(1); expect(preference.removeEventListener).toHaveBeenCalled()
  })
})
