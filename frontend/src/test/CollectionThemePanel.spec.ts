import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CollectionThemePanel from '../components/CollectionThemePanel.vue'
import { inCollectionWindow } from '../domain/collectionTheme'
import type { CollectionAccountSnapshot, CollectionThemeAdapter } from '../domain/collectionTheme'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0 })
function fixture(collecting = false) {
  let state: CollectionAccountSnapshot = { revision: '0', accounts: [
    { projectId: 'one', projectName: '演示一', advertiserId: '123', publicDouyinId: 'douyin1', theme: 'QUANYU', collecting },
    { projectId: 'two', projectName: '演示二', advertiserId: '456', publicDouyinId: 'douyin2', theme: 'QUANYU', collecting: false },
  ] }
  const adapter: CollectionThemeAdapter = {
    read: vi.fn(async () => structuredClone(state)),
    save: vi.fn(async input => {
      state = { revision: '1', accounts: state.accounts.map(account => account.projectId === input.projectId ? { ...account, theme: input.theme } : account) }
      return structuredClone(state)
    }),
  }
  return adapter
}
async function setup(adapter = fixture(), now = () => new Date('2026-10-22T14:00:00+08:00')) {
  const wrapper = mount(CollectionThemePanel, { props: { adapter, now } }); wrappers.push(wrapper)
  await flushPromises(); return { wrapper, adapter }
}
describe('Collection theme configuration preview', () => {
  it.each([
    ['2026-10-22T07:59:59+08:00', false], ['2026-10-22T08:00:00+08:00', true],
    ['2026-10-22T11:59:59+08:00', true], ['2026-10-22T12:00:00+08:00', false],
    ['2026-10-22T02:00:00Z', true],
  ])('checks Shanghai window at %s', (time, expected) => expect(inCollectionWindow(new Date(time))).toBe(expected))
  it('offers both choices and cancellation does not save', async () => {
    const { wrapper, adapter } = await setup()
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    expect(wrapper.findAll('option').map(item => item.text())).toEqual(['全域', '乘方'])
    await wrapper.get('select').setValue('CHENGFANG')
    await wrapper.findAll('button').find(button => button.text() === '取消')!.trigger('click')
    expect(adapter.save).not.toHaveBeenCalled(); expect(wrapper.findAll('.theme-badge').map(item => item.text())).toEqual(['全域', '全域'])
  })
  it('saves only the selected account and independently reads back', async () => {
    const { wrapper, adapter } = await setup()
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    await wrapper.get('select').setValue('CHENGFANG')
    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click'); await flushPromises()
    expect(adapter.save).toHaveBeenCalledWith({ expectedRevision: '0', projectId: 'one', theme: 'CHENGFANG' })
    expect(adapter.read).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.theme-badge').map(item => item.text())).toEqual(['乘方', '全域'])
    expect(wrapper.text()).toContain('历史数据保持不变')
  })
  it('locks all rows inside collection window', async () => {
    const { wrapper } = await setup(fixture(), () => new Date('2026-10-22T10:00:00+08:00'))
    expect(wrapper.findAll('button').filter(button => button.text() === '编辑').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })
  it('locks a running account outside the window', async () => {
    const { wrapper } = await setup(fixture(true))
    const buttons = wrapper.findAll('button').filter(button => button.text() === '编辑')
    expect(buttons[0].attributes('disabled')).toBeDefined(); expect(buttons[1].attributes('disabled')).toBeUndefined()
  })
  it('checks the clock again when saving across 08:00', async () => {
    let time = new Date('2026-10-22T07:59:59+08:00')
    const { wrapper, adapter } = await setup(fixture(), () => time)
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    await wrapper.get('select').setValue('CHENGFANG'); time = new Date('2026-10-22T08:00:00+08:00')
    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')
    expect(adapter.save).not.toHaveBeenCalled()
  })
  it('requires reload after an uncertain write without retrying', async () => {
    const adapter = fixture(); adapter.save = vi.fn(async () => { throw new Error('NETWORK') })
    const { wrapper } = await setup(adapter)
    await wrapper.findAll('button').find(button => button.text() === '编辑')!.trigger('click')
    await wrapper.get('select').setValue('CHENGFANG')
    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('可能已生效')
    expect(adapter.save).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('button').find(button => button.text() === '保存')!.attributes('disabled')).toBeDefined()
  })
})
