import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CollectedExportDialog from '../components/CollectedExportDialog.vue'
import CollectedExportPanel from '../components/CollectedExportPanel.vue'

afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })
describe('daily download dialog', () => {
  it('opens only on demand, uses native modal behavior and restores trigger focus on close', async () => {
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
    HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); this.dispatchEvent(new Event('close')) }
    const show = vi.spyOn(HTMLDialogElement.prototype, 'showModal')
    const adapter = { preview: vi.fn(), download: vi.fn() }
    const wrapper = mount(CollectedExportDialog, { attachTo: document.body, props: { projectId: 'demo', projects: [{ id: 'demo', name: '示例' }], adapter } })
    expect(wrapper.findComponent(CollectedExportPanel).exists()).toBe(false)
    const trigger = wrapper.get('.export-trigger')
    await trigger.trigger('click'); await flushPromises()
    expect(show).toHaveBeenCalledOnce()
    expect(wrapper.findComponent(CollectedExportPanel).props('projectId')).toBe('demo')
    expect(adapter.preview).not.toHaveBeenCalled()
    expect(document.querySelector('dialog')?.getAttribute('aria-labelledby')).toBe('export-dialog-title')
    wrapper.findComponent(CollectedExportPanel).vm.$emit('busy', true)
    await flushPromises()
    expect((document.querySelector('dialog header button') as HTMLButtonElement).disabled).toBe(true)
    const cancel = new Event('cancel', { cancelable: true })
    document.querySelector('dialog')!.dispatchEvent(cancel)
    expect(cancel.defaultPrevented).toBe(true)
    wrapper.findComponent(CollectedExportPanel).vm.$emit('busy', false)
    await flushPromises()
    ;(document.querySelector('dialog header button') as HTMLButtonElement).click()
    await flushPromises()
    expect(wrapper.findComponent(CollectedExportPanel).exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})
