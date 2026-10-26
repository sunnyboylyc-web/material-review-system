import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ConfigurationPanel from '../components/ConfigurationPanel.vue'
import { TestConfigurationAdapter } from './configurationFixture'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.useRealTimers() })
async function setup(enabled = true, time = '2026-10-22T14:00:00+08:00') {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date(time))
  const adapter = new TestConfigurationAdapter()
  adapter.snapshot.themeChangesEnabled = enabled
  const wrapper = mount(ConfigurationPanel, { props: { adapter } }); wrappers.push(wrapper)
  await flushPromises(); return { wrapper, adapter }
}
describe('Theme controls in existing project configuration', () => {
  it('keeps existing modules and edits theme without committing unrelated drafts', async () => {
    const { wrapper, adapter } = await setup()
    expect(wrapper.text()).toContain('大屏项目账户配置')
    await wrapper.get('[aria-label="采集项目 1 名称"]').setValue('未保存名称')
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    await wrapper.get('[aria-label="项目 1 采集主题"]').setValue('CHENGFANG')
    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click'); await flushPromises()
    expect(adapter.snapshot.projects[0].collectionTheme).toBe('CHENGFANG')
    expect(adapter.snapshot.projects[0].name).toBe('合成示例项目')
    expect((wrapper.get('[aria-label="采集项目 1 名称"]').element as HTMLInputElement).value).toBe('未保存名称')
  })
  it('cancels theme edits without saving', async () => {
    const { wrapper, adapter } = await setup()
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    await wrapper.get('[aria-label="项目 1 采集主题"]').setValue('CHENGFANG')
    await wrapper.findAll('button').find(button => button.text() === '取消')!.trigger('click')
    expect(adapter.apply).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('全域')
  })
  it('does not send new theme fields to a legacy server', async () => {
    const { wrapper, adapter } = await setup(false)
    expect(wrapper.findAll('button').find(button => button.text() === '编辑')!.attributes('disabled')).toBeDefined()
    await wrapper.findAll('button').find(button => button.text() === '预检草稿')!.trigger('click'); await flushPromises()
    expect(adapter.preflight.mock.calls[0][0].projects[0].collectionTheme).toBeUndefined()
  })
  it('resolves a changed advertiser before preflight and saves the stable Douyin binding', async () => {
    const { wrapper, adapter } = await setup()
    await wrapper.get('[aria-label="项目 1 账户 ID"]').setValue('99999999999999990009')
    await wrapper.get('[aria-label="项目 1 公开抖音号"]').setValue('synthetic-stable')
    await wrapper.get('.configuration-check').trigger('click'); await flushPromises()
    expect(adapter.resolveAnchor).toHaveBeenCalledWith({ advertiserId:'99999999999999990009', publicDouyinId:'synthetic-stable' })
    expect(adapter.preflight.mock.lastCall![0].projects[0].douyinIds).toEqual(['99999999999999990004'])
    await wrapper.get('.configuration-save').trigger('click'); await flushPromises()
    expect(adapter.apply).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })
  it('defaults a new project to QUANYU', async () => {
    const { wrapper } = await setup()
    await wrapper.findAll('button').find(button => button.text() === '新增草稿项目')!.trigger('click')
    expect((wrapper.get('[aria-label="项目 2 采集主题"]').element as HTMLSelectElement).value).toBe('QUANYU')
  })
  it('disables theme edits in the morning', async () => {
    const { wrapper } = await setup(true, '2026-10-22T08:00:00+08:00')
    expect(wrapper.findAll('button').find(button => button.text() === '编辑')!.attributes('disabled')).toBeDefined()
  })
})
