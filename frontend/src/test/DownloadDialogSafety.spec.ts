import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import CollectedExportPanel from '../components/CollectedExportPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'
import type { CollectedExportPreview } from '../domain/collectedExport'

function gate<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); this.dispatchEvent(new Event('close')) }
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('R05 download modal safety (synthetic adapters only)', () => {
  it.each(['button', 'escape', 'backdrop'] as const)('blocks %s during both phases, never duplicates and delivers the file', async method => {
    const previewGate = gate<CollectedExportPreview>(), downloadGate = gate<Blob>()
    const preview = vi.fn(() => previewGate.promise), download = vi.fn(() => downloadGate.promise)
    const fixture = new SyntheticMockAdapter([{ id: 'synthetic-export', name: '合成下载项目', enabled: true }])
    // Test-only transport simulates ordinary-user production capability without any network.
    const adapter: DashboardDataAdapter = {
      listProjects: () => fixture.listProjects(), fetchFreshness: id => fixture.fetchFreshness(id),
      fetchTop: (...args) => fixture.fetchTop(...args), fetchReview: (...args) => fixture.fetchReview(...args),
    }
    const create = vi.fn(() => 'blob:synthetic-download'), revoke = vi.fn()
    vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke })
    const handoff = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const page = mount(App, { attachTo: document.body, props: {
      adapter, collectedExportAdapter: { preview, download },
      session: { username: 'synthetic-viewer', operations: false, administrator: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'SYNTHETIC_ONLY' },
      periodPreferences: { load: async () => null, save: async () => {} },
      periodSnapshots: { load: async () => null, refresh: vi.fn() },
    }, global: { stubs: { StarScene: true } } })
    await flushPromises()
    await page.findAll('.global-nav button')[1]!.trigger('click')
    const trigger = page.get('.download-data-button')
    await trigger.trigger('click'); await flushPromises()
    const dialog = page.get('.download-dialog'), close = page.get('[aria-label="关闭下载数据面板"]')
    expect(document.activeElement).toBe(close.element)
    expect(preview).not.toHaveBeenCalled()
    await close.trigger('keydown', { key: 'Tab', shiftKey: true })
    const rangeButton = page.get('.collected-export button[type="submit"]')
    expect(document.activeElement).toBe(rangeButton.element)
    await rangeButton.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close.element)
    const tryClose = async () => {
      if (method === 'button') (close.element as HTMLButtonElement).click()
      if (method === 'escape') {
        const event = new Event('cancel', { cancelable: true })
        dialog.element.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(true)
      }
      if (method === 'backdrop') await dialog.trigger('click')
      await flushPromises()
    }
    const expectBusy = () => {
      expect(dialog.attributes('open')).toBeDefined()
      expect(close.attributes('disabled')).toBeDefined()
      expect(page.findComponent(CollectedExportPanel).props('active')).toBe(true)
      expect(page.get('#download-dialog-notice').text()).toContain('请勿关闭面板')
      expect(page.get('#download-dialog-notice').classes()).toContain('download-attention')
    }
    await page.get('.collected-export form').trigger('submit')
    expect(document.activeElement).toBe(dialog.element)
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(dialog.element)
    expectBusy(); await tryClose(); expectBusy()
    await trigger.trigger('click') // attempted reopen cannot invalidate the in-flight panel
    await page.get('.collected-export form').trigger('submit')
    expect(preview).toHaveBeenCalledTimes(1)
    const [from, to] = page.findAll('.collected-export input').map(input => (input.element as HTMLInputElement).value)
    previewGate.resolve({ projectId: 'synthetic-export', projectName: '合成下载项目', accountId: 'SYNTHETIC', startDate: from!, endDate: to!, rowCount: 1, days: [{ statDate: from!, rowCount: 1 }], excludedDates: [] })
    await flushPromises()
    expect(page.get('.export-preview').text()).toContain('共 1 行')
    await page.get('.collected-export .primary-button').trigger('click')
    expectBusy(); await tryClose(); expectBusy()
    await trigger.trigger('click')
    await page.get('.collected-export .primary-button').trigger('click')
    await page.get('.collected-export form').trigger('submit')
    expect(download).toHaveBeenCalledTimes(1); expect(preview).toHaveBeenCalledTimes(1)
    const file = new Blob(['SYNTHETIC_EXCEL_BYTES'])
    downloadGate.resolve(file); await flushPromises()
    expect(create).toHaveBeenCalledWith(file); expect(handoff).toHaveBeenCalledOnce()
    expect(page.get('.collected-export').text()).toContain('交给浏览器下载')
    expect(close.attributes('disabled')).toBeUndefined()
    await tryClose()
    expect(dialog.attributes('open')).toBeUndefined()
    expect(document.activeElement).toBe(trigger.element)
    await trigger.trigger('click'); await flushPromises()
    expect(preview).toHaveBeenCalledTimes(1); expect(download).toHaveBeenCalledTimes(1)
    expect(page.find('.export-preview').exists()).toBe(false)
    page.unmount()
  })
})
