import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import UsageGuide from '../components/UsageGuide.vue'
import ConfigurationPanel from '../components/ConfigurationPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { metricConfigurationFixture, TestConfigurationAdapter, TEST_PROJECT } from './configurationFixture'
import { businessGuide, operationsGuide } from '../domain/usageGuide'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const navigate = async (page: ReturnType<typeof mount>, label: string) => {
  await page.findAll('.operations-nav button').find(button => button.text().includes(label))!.trigger('click')
  await flushPromises()
}

describe('D217 role-specific usage documents', () => {
  it.each([
    { operations: false, administrator: false, titles: businessGuide.map(section => section.title) },
    { operations: true, administrator: false, titles: operationsGuide.map(section => section.title) },
    { operations: true, administrator: true, titles: [...operationsGuide.map(section => section.title), '账号管理'] },
  ])('renders only the authenticated audience document ($operations/$administrator)', async ({ operations, administrator, titles }) => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter(),
      session: { username: 'TEST_ONLY', operations, administrator, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'TEST_ONLY' } } })
    await flushPromises()
    const guide = page.getComponent(UsageGuide)
    expect(guide.get('.usage-guide-trigger').text()).toBe('使用说明')
    expect(guide.findAll('nav button').map(button => button.text().slice(2))).toEqual(titles)
    for (const button of guide.findAll('nav button')) {
      await button.trigger('click')
      const text = guide.get('article').text()
      expect(text.length).toBeGreaterThan(100)
      if (!operations) for (const forbidden of ['确认导入', '明确保存配置', '取消并删除上传副本', '账号管理', '数据库备份']) expect(text).not.toContain(forbidden)
      else expect(text).not.toContain('每日 TOP：从榜单找到素材')
    }
    expect(fetcher).not.toHaveBeenCalled()
    page.unmount()
  })

  it('supports opening, chapter focus, keyboard containment, close and return focus', async () => {
    const guide = mount(UsageGuide, { attachTo: document.body, props: { operations: false } })
    const dialog = guide.get('dialog').element as HTMLDialogElement
    dialog.showModal = vi.fn(() => dialog.setAttribute('open', ''))
    dialog.close = vi.fn(() => { dialog.removeAttribute('open'); dialog.dispatchEvent(new Event('close')) })
    await guide.get('.usage-guide-trigger').trigger('click')
    expect(dialog.showModal).toHaveBeenCalledOnce()
    expect(guide.get('.usage-guide-trigger').attributes('aria-expanded')).toBe('true')
    const close = guide.get('[aria-label="关闭使用说明"]')
    expect(document.activeElement).toBe(close.element)
    await close.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(guide.get('article').element)
    await guide.get('article').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close.element)
    await guide.findAll('nav button')[1].trigger('click')
    expect(document.activeElement).toBe(guide.get('article').element)
    expect(guide.get('article').text()).toContain('消耗 & ROI')
    await close.trigger('click'); await flushPromises()
    expect(guide.get('.usage-guide-trigger').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(guide.get('.usage-guide-trigger').element)
    guide.unmount()
  })

  it('replaces an open document when permissions change and labels recovery mode', async () => {
    const guide = mount(UsageGuide, { props: { operations: true, administrator: true } })
    await guide.findAll('nav button').at(-1)!.trigger('click')
    expect(guide.get('article').text()).toContain('账号管理')
    await guide.setProps({ operations: false, administrator: false })
    expect(guide.get('article').text()).toContain('选择项目，开始查看')
    expect(guide.text()).not.toContain('账号管理')
    await guide.setProps({ operations: true, recoveryReadOnly: true })
    expect(guide.text()).toContain('当前为恢复验证只读模式')
    guide.unmount()
  })
})

describe('D217 separated project and metric editors', () => {
  it('saves metrics from the final collection section without submitting a project draft and blocks stale project preflight', async () => {
    const adapter = new TestConfigurationAdapter(); adapter.snapshot = metricConfigurationFixture()
    const page = mount(App, { props: { adapter: new SyntheticMockAdapter(), configurationAdapter: adapter } })
    await flushPromises()
    await page.findAll('.global-nav button')[3].trigger('click'); await flushPromises()
    const projectPanel = page.findAllComponents(ConfigurationPanel).find(panel => panel.props('scope') === 'projects')!
    expect(projectPanel.find('[data-metric-id]').exists()).toBe(false)
    await projectPanel.get('[aria-label="项目 1 名称"]').setValue('只在项目页的草稿')
    await projectPanel.get('.configuration-check').trigger('click'); await flushPromises()
    await navigate(page, '采集状态')
    const metrics = page.get('.metrics-configuration-panel')
    expect(metrics.isVisible()).toBe(true)
    expect(metrics.element.parentElement?.lastElementChild).toBe(metrics.element)
    expect(metrics.find('[aria-label="项目 1 名称"]').exists()).toBe(false)
    await metrics.get('.configuration-metrics-toggle').trigger('click')
    await metrics.get('[data-metric-id="synthetic_metric_0"]').setValue(false)
    await metrics.get('.configuration-check').trigger('click'); await flushPromises()
    await navigate(page, '文件存储')
    await navigate(page, '采集状态')
    expect(metrics.get('.configuration-save').attributes('disabled')).toBeUndefined()
    expect(adapter.read).toHaveBeenCalledTimes(2)
    await metrics.get('.configuration-save').trigger('click'); await flushPromises()
    expect(adapter.apply).toHaveBeenCalledOnce()
    expect(adapter.apply.mock.lastCall![0].projects).toEqual([TEST_PROJECT])
    expect(adapter.snapshot.enabledMetrics).not.toContain('synthetic_metric_0')
    await navigate(page, '项目配置')
    expect((projectPanel.get('[aria-label="项目 1 名称"]').element as HTMLInputElement).value).toBe('只在项目页的草稿')
    expect(projectPanel.get('.configuration-save').attributes('disabled')).toBeDefined()
    expect(projectPanel.text()).toContain('另一配置分区已更新')
    // Reread before editing. The project save must keep the latest saved metric selection.
    await projectPanel.get('.configuration-reload').trigger('click'); await flushPromises()
    await projectPanel.get('[aria-label="项目 1 名称"]').setValue('重新核对后改名')
    await projectPanel.get('.configuration-check').trigger('click'); await flushPromises()
    await projectPanel.get('.configuration-save').trigger('click'); await flushPromises()
    expect(adapter.snapshot.projects[0].name).toBe('重新核对后改名')
    expect(adapter.snapshot.enabledMetrics).not.toContain('synthetic_metric_0')
    expect(metrics.get('.configuration-check').attributes('disabled')).toBeDefined()
    page.unmount()
  })

  it('preserves projects and locks required metrics in readonly and recovery modes', async () => {
    const adapter = new TestConfigurationAdapter(); adapter.snapshot = { ...metricConfigurationFixture(), writeEnabled: false }
    const metrics = mount(ConfigurationPanel, { props: { adapter, scope: 'metrics' } })
    await flushPromises()
    expect(metrics.get('[data-metric-id="synthetic_metric_0"]').attributes('disabled')).toBeDefined()
    adapter.snapshot.writeEnabled = true
    await metrics.get('.configuration-reload').trigger('click'); await flushPromises()
    expect(metrics.get('[data-metric-id="synthetic_metric_0"]').attributes('disabled')).toBeUndefined()
    expect(metrics.get('[data-metric-id="stat_cost_for_roi2"]').attributes('disabled')).toBeDefined()
    await metrics.setProps({ recoveryReadOnly: true })
    expect(metrics.get('.configuration-check').attributes('disabled')).toBeDefined()
    expect(adapter.apply).not.toHaveBeenCalled()
    metrics.unmount()
  })

  it('keeps optional project metadata byte-for-value when saving only metrics', async () => {
    const adapter = new TestConfigurationAdapter()
    adapter.snapshot = metricConfigurationFixture()
    adapter.snapshot.projects[0] = { ...TEST_PROJECT, publicDouyinId: null, awemeName: null, deleted: null }
    const projects = structuredClone(adapter.snapshot.projects)
    const metrics = mount(ConfigurationPanel, { props: { adapter, scope: 'metrics' } })
    await flushPromises()
    await metrics.get('[data-metric-id="synthetic_metric_0"]').setValue(false)
    await metrics.get('.configuration-check').trigger('click'); await flushPromises()
    await metrics.get('.configuration-save').trigger('click'); await flushPromises()
    expect(adapter.apply.mock.lastCall![0].projects).toEqual(projects)
    expect(metrics.emitted('changed')).toHaveLength(1)
    metrics.unmount()
  })
})
