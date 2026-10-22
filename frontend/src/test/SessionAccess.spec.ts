import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'

describe('trial role navigation', () => {
  it('public access exposes operations without login/account controls', async () => {
    const adapter = new SyntheticMockAdapter()
    Object.defineProperty(adapter, 'evidenceKind', { value: 'CURRENT_FACTS' })
    const wrapper = mount(App, { props: { adapter,
      session: { username: '访客', publicAccess: true, operations: true, administrator: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test-token' } },
      global: { stubs: { ConfigurationPanel: true, HistoryUploadPanel: true, OperationsPanel: true, CollectedExportPanel: true } } })
    await flushPromises()
    expect(wrapper.find('form[action="/logout"]').exists()).toBe(false)
    expect(wrapper.find('.global-username').exists()).toBe(false)
    expect(wrapper.get('.user-avatar').attributes('aria-label')).toBe('打开项目面板')
    await wrapper.get('button.operations-toggle').trigger('click')
    expect(wrapper.find('configuration-panel-stub').exists()).toBe(true)
    expect(wrapper.find('history-upload-panel-stub').exists()).toBe(true)
    expect(wrapper.get('.operations-nav').text()).not.toContain('账号管理')
    wrapper.unmount()
  })
  for (const operations of [false, true]) it(`operations=${operations} exposes only its controls`, async () => {
    const adapter = new SyntheticMockAdapter()
    Object.defineProperty(adapter, 'evidenceKind', { value: 'CURRENT_FACTS' }) // Synthetic fixture in the production-role branch.
    const wrapper = mount(App, { props: { adapter,
      session: { username: 'test-user', operations, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test-token' } },
      global: { stubs: { ConfigurationPanel: true, HistoryUploadPanel: true, OperationsPanel: true, CollectedExportPanel: true } } })
    await flushPromises()
    const toggle = wrapper.find('button.operations-toggle')
    expect(toggle.text()).toBe(operations ? '数据运维' : '下载数据')
    await toggle.trigger('click')
    expect(wrapper.find('configuration-panel-stub').exists()).toBe(operations)
    expect(wrapper.find('history-upload-panel-stub').exists()).toBe(operations)
    expect(wrapper.find('operations-panel-stub').exists()).toBe(operations)
    if (operations) {
      const status = wrapper.findAll('.operations-nav button').find(button => button.text().includes('采集状态'))!
      expect(status.text()).toContain('COLLECTION STATUS')
      expect(wrapper.get('.operations-nav').text()).not.toContain('运行状态')
      await status.trigger('click')
      expect(wrapper.get('.workspace-content').attributes('data-operation')).toBe('status')
    }
    expect(wrapper.find('collected-export-panel-stub').exists()).toBe(true)
    expect(wrapper.find('form[action="/logout"] input').attributes('value')).toBe('test-token')
    wrapper.unmount()
  })
})
