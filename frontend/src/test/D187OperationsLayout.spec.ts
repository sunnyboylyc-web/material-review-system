import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import FileStoragePanel from '../components/FileStoragePanel.vue'
import CollectionOperationsPanel from '../components/CollectionOperationsPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { TestConfigurationAdapter } from './configurationFixture'
import { operationsFixture } from './operationsFixture'
import type { DateRange } from '../domain/dashboard'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const section = (page: ReturnType<typeof mount>, text: string) => page.findAll('.operations-nav button').find(button => button.text().includes(text))!
describe('D187 semantic operation groups', () => {
  it('labels the isolated synthetic preview without claiming local business data', async () => {
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter(), localReadOnlyPreview: true } })
    await flushPromises()
    const notice = page.get('.local-preview-notice')
    expect(notice.attributes('aria-label')).toBe('本地合成数据只读说明')
    expect(notice.attributes('data-evidence-kind')).toBe('SYNTHETIC')
    expect(notice.text()).toContain('本地合成数据 · 隔离只读预览')
    expect(notice.text()).toContain('不读取本机业务数据或云端数据')
    expect(notice.text()).not.toContain('当前读取本机已有数据')
    expect(notice.text()).not.toContain('本地真实数据')
    await page.findAll('.global-nav button')[3].trigger('click'); await flushPromises()
    expect(page.get('.preview-operation-note').text()).toContain('均不执行')
    expect(page.get('.preview-operation-note').text()).not.toContain('保存仅在本页内生效')
    page.unmount()
  })
  it('keeps the genuine local read-only mode description unchanged', async () => {
    const adapter = new SyntheticMockAdapter([])
    Object.defineProperty(adapter, 'evidenceKind', { value: 'CURRENT_FACTS' })
    const page = mount(App, { props: { adapter, localReadOnlyPreview: true } })
    await flushPromises()
    const notice = page.get('.local-preview-notice')
    expect(notice.attributes('aria-label')).toBe('本地真实数据只读说明')
    expect(notice.attributes('data-evidence-kind')).toBe('CURRENT_FACTS')
    expect(notice.text()).toContain('本地真实数据 · 只读预览')
    expect(notice.text()).toContain('当前读取本机已有数据，不是云端实时副本')
    expect(notice.text()).not.toContain('虚构样本')
    page.unmount()
  })
  it('keeps the configuration draft and preflight through sections, and reads history only in history', async () => {
    const configuration = new TestConfigurationAdapter(), fetchDays = vi.fn(async (id: string, range: DateRange) => operationsFixture(id, range))
    const page = mount(App, { attachTo: document.body, props: { adapter: new SyntheticMockAdapter(), configurationAdapter: configuration, operationsAdapter: { evidenceKind: 'SYNTHETIC', fetchDays } } })
    await flushPromises()
    await page.findAll('.global-nav button')[3].trigger('click'); await flushPromises()
    expect(page.findAll('.operations-nav button').map(button => button.text().split(/CONFIGURATION|COLLECTION STATUS|HISTORY DATA|FILE STORAGE/)[0])).toEqual(['项目配置', '采集状态', '历史数据', '文件存储'])
    await page.get('[aria-label="项目 1 名称"]').setValue('仍在草稿中的项目')
    await page.get('.configuration-check').trigger('click'); await flushPromises()
    const readCount = configuration.read.mock.calls.length
    await section(page, '采集状态').trigger('click'); await flushPromises()
    expect(page.get('.collection-operations').isVisible()).toBe(true)
    expect(page.get('.configuration-panel').isVisible()).toBe(false)
    expect(page.get('.configuration-panel').find('.configuration-schedule').exists()).toBe(false)
    expect(page.get('.configuration-panel').find('.file-storage').exists()).toBe(false)
    expect(fetchDays).not.toHaveBeenCalled()
    await section(page, '文件存储').trigger('click'); await flushPromises()
    expect(page.get('.database-backup').isVisible()).toBe(true)
    expect(page.get('.collection-operations').isVisible()).toBe(false)
    expect(page.html().indexOf('class="database-backup"')).toBeLessThan(page.html().indexOf('class="file-storage"'))
    const fileElement = page.findComponent(FileStoragePanel).element
    await section(page, '历史数据').trigger('click'); await flushPromises()
    expect(page.get('.operations-panel').isVisible()).toBe(true)
    expect(fetchDays).toHaveBeenCalledTimes(1)
    await section(page, '项目配置').trigger('click'); await flushPromises()
    expect((page.get('[aria-label="项目 1 名称"]').element as HTMLInputElement).value).toBe('仍在草稿中的项目')
    expect(configuration.read).toHaveBeenCalledTimes(readCount + 1) // Metrics has its own snapshot, loaded on first entry.
    expect(page.get('.configuration-save').attributes('disabled')).toBeUndefined()
    expect(page.findComponent(FileStoragePanel).element).toBe(fileElement)
    expect(configuration.apply).not.toHaveBeenCalled(); page.unmount()
  })
  it('synthetic pages never call real files, backup, collection, history or account APIs', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter(), session: { username: 'synthetic', operations: true, administrator: true, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'synthetic' } }, global: { stubs: { CollectedExportPanel: true } } })
    await flushPromises(); await page.findAll('.global-nav button')[3].trigger('click'); await flushPromises()
    for (const label of ['采集状态', '历史数据', '文件存储', '账号管理']) { await section(page, label).trigger('click'); await flushPromises() }
    await section(page, '文件存储').trigger('click')
    const files = page.get('.file-storage'); (files.element as HTMLDetailsElement).open = true; await files.trigger('toggle'); await flushPromises()
    expect(fetcher).not.toHaveBeenCalled(); expect(page.find('.database-backup a').exists()).toBe(false); page.unmount()
  })
  it('does not mount privileged backup for ordinary users or recovery mode', async () => {
    for (const mode of ['user', 'recovery']) {
      const page = mount(App, { props: { adapter: new SyntheticMockAdapter(), session: { username: 'test', operations: mode === 'recovery', recoveryReadOnly: mode === 'recovery', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'fixture' } }, global: { stubs: { CollectedExportPanel: true } } })
      await flushPromises(); await page.get('.operations-toggle').trigger('click'); await flushPromises()
      expect(page.find('.database-backup').exists()).toBe(false); expect(page.findComponent(FileStoragePanel).exists()).toBe(false); page.unmount()
    }
  })
  it('keeps all collection rules in the collection group, no execution buttons', () => {
    const panel = mount(CollectionOperationsPanel, { props: { active: true, preview: true } })
    for (const text of ['09:00、09:30、10:00、10:30、11:00、11:30、12:00', '每页最多延迟重试五次', '每个项目每轮最多延迟重试十二次', '首次成功不代表平台最终数据']) expect(panel.text()).toContain(text)
    expect(panel.findAll('button')).toHaveLength(0); panel.unmount()
  })
  it('preserves a selected manual file while another section is active without a hidden GET', async () => {
    const fetcher = vi.fn(async () => ({ ok: true, json: async () => ({ files: [], writeEnabled: true, chunkBytes: 4194304 }) })); vi.stubGlobal('fetch', fetcher)
    const panel = mount(FileStoragePanel); (panel.element as HTMLDetailsElement).open = true; await panel.trigger('toggle'); await flushPromises()
    const input = panel.get('input'); Object.defineProperty(input.element, 'files', { value: [new File(['fixture'], 'fixture.zip')] }); await input.trigger('change')
    await flushPromises(); const callsBeforeHiding = fetcher.mock.calls.length
    await panel.setProps({ active: false }); await flushPromises(); expect(fetcher).toHaveBeenCalledTimes(callsBeforeHiding)
    await panel.setProps({ active: true }); await flushPromises()
    expect(panel.findAll('button').find(button => button.text() === '上传文件')!.attributes('disabled')).toBeUndefined(); panel.unmount()
  })
})
