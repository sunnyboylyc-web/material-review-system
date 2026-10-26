import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'

describe('authenticated username placement', () => {
  it('shows the session name before the account icon, not in the project header', async () => {
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter(),
      session: { username: 'sample-user', administrator: false, operations: false,
        csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'TEST_ONLY' } },
      global: { stubs: { StarScene: true } } })
    await flushPromises()
    expect(page.get('.global-actions .global-username').text()).toBe('sample-user')
    expect(page.get('.global-username').element.nextElementSibling?.classList.contains('usage-guide')).toBe(true)
    expect(page.get('.usage-guide').element.nextElementSibling?.classList.contains('user-avatar')).toBe(true)
    await page.findAll('.global-nav button')[1].trigger('click')
    expect(page.find('.workspace-header .mode-pill').exists()).toBe(false)
    const dialog = page.get('.project-dialog').element as HTMLDialogElement
    const showModal = vi.fn(() => dialog.setAttribute('open', ''))
    dialog.showModal = showModal
    await page.get('.user-avatar').trigger('click')
    expect(showModal).toHaveBeenCalledOnce()
    expect(dialog.hasAttribute('open')).toBe(true)
    expect(page.get('.user-avatar').attributes('aria-label')).toContain('sample-user')
    page.unmount()
  })
  it('retains local preview mode text without inventing a session username', async () => {
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter() },
      global: { stubs: { StarScene: true } } })
    await flushPromises()
    expect(page.find('.global-username').exists()).toBe(false)
    await page.findAll('.global-nav button')[1].trigger('click')
    expect(page.get('.workspace-header .mode-pill').text()).toBe('本地 · 合成')
    page.unmount()
  })
})
