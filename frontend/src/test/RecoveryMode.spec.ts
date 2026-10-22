import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'

describe('recovery read-only shell', () => {
  it('labels the recovery baseline and never restores old personal state or write panels', async () => {
    const load = vi.fn()
    const wrapper = mount(App, { props: {
      session: { username: 'admin', administrator: true, operations: true, recoveryReadOnly: true, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'synthetic' },
      adapter: new SyntheticMockAdapter(),
      periodPreferences: { load, save: vi.fn() },
      periodSnapshots: { load, refresh: vi.fn() },
    }, global: { stubs: { ConfigurationPanel: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('管理员只读恢复版')
    expect(wrapper.text()).toContain('项目配置来自旧迁移基线')
    expect(wrapper.get('.recovery-notice').isVisible()).toBe(true)
    expect(load).not.toHaveBeenCalled()
    const button = wrapper.findAll('button').find(b => b.text() === '数据运维')!
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'AdminAccountsPanel' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'HistoryUploadPanel' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'CollectedExportPanel' }).exists()).toBe(false)
    wrapper.unmount()
  })
})
