import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import CollectionStatusPanel from '../components/CollectionStatusPanel.vue'
import { validateCollectionStatus, type CollectionStatus } from '../adapters/httpCollectionStatusAdapter'
const sample = (): CollectionStatus => ({ schemaVersion: 'collection-scheduler-v1', observation: 'OBSERVED', updatedAt: '2026-10-16T02:00:00Z', statDate: '2026-10-15', status: 'RUNNING', projects: [{ projectId: 'p1', projectName: '模拟项目', statDate: '2026-10-15', updatedAt: '2026-10-16T02:00:00Z', status: 'RETRY_WAIT', retryNotBefore: '2026-10-16T02:10:00Z', errorCode: '51010' }] })
describe('collection execution observation', () => {
  it('explains dual-theme conflict and manual handling', async () => {
    const value=sample();value.status='REVIEW_REQUIRED';value.projects[0].status='REVIEW_REQUIRED';value.projects[0].errorCode='BACKFILL_THEME_CONFLICT'
    const wrapper=mount(CollectionStatusPanel,{props:{read:async()=>value}});await flushPromises()
    expect(wrapper.text()).toContain('全域和乘方均返回数据')
    expect(wrapper.text()).toContain('本日未入库，请人工核对处理')
    expect(wrapper.findAll('button')).toHaveLength(1);wrapper.unmount()
  })
  it('keeps separate dates for the same project only in the backfill window', () => {
    const value=sample();value.mode='GAP_BACKFILL'
    value.projects=[{...value.projects[0],statDate:'2026-10-08'},{...value.projects[0],statDate:'2026-10-14'}]
    expect(validateCollectionStatus(value).projects).toHaveLength(2)
    value.projects[0].statDate='2026-10-07';expect(()=>validateCollectionStatus(value)).toThrow()
  })
  it('explains bounded platform failure without a retry action', async () => {
    const value = sample(); value.projects[0].status = 'FAILED'
    value.projects[0].errorCode = 'PLATFORM_51010_BASE_PAGE_2_RETRIES_12'
    const wrapper = mount(CollectionStatusPanel, { props: { read: async () => value } }); await flushPromises()
    expect(wrapper.text()).toContain('base 组第 2 页')
    expect(wrapper.text()).toContain('已延迟重试 12/12 次')
    expect(wrapper.text()).toContain('若当天时点已结束')
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('最早重试时间'); wrapper.unmount()
  })
  it('terminal failure hides stale retry time from old scheduler records', async () => {
    const value = sample(); value.projects[0].status = 'FAILED'
    const wrapper = mount(CollectionStatusPanel, { props: { read: async () => value } }); await flushPromises()
    expect(wrapper.text()).toContain('本轮失败'); expect(wrapper.text()).not.toContain('最早重试时间')
    expect(wrapper.text()).not.toContain('请勿重复启动'); wrapper.unmount()
  })
  it('reads once and manual refresh only; wait explains retry without collecting', async () => {
    const read = vi.fn().mockResolvedValue(sample()); const wrapper = mount(CollectionStatusPanel, { props: { read } }); await flushPromises()
    expect(read).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('等待重试'); expect(wrapper.text()).toContain('10:10:00'); expect(wrapper.text()).toContain('不触发采集')
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(read).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('stale is not represented as a live running promise', async () => {
    const value = sample(); value.observation = 'STALE'
    const wrapper = mount(CollectionStatusPanel, { props: { read: async () => value } }); await flushPromises()
    expect(wrapper.text()).toContain('状态待核（运行记录已过期）')
    expect(wrapper.text()).not.toContain('执行中')
    expect(wrapper.text()).toContain('状态过期，待核'); expect(wrapper.text()).toContain('上次记录：'); wrapper.unmount()
  })
  it('missing does not assert enabled, failure or success', async () => {
    const wrapper = mount(CollectionStatusPanel, { props: { read: async () => ({ schemaVersion: 'collection-scheduler-v1', observation: 'NOT_OBSERVED', projects: [] }) } }); await flushPromises()
    expect(wrapper.text()).toContain('尚无采集执行记录'); expect(wrapper.text()).toContain('不能据此判断定时任务是否启用'); wrapper.unmount()
  })
  it('failed refresh clears obsolete success and inactive pending responses are ignored', async () => {
    const read = vi.fn().mockResolvedValueOnce(sample()).mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mount(CollectionStatusPanel, { props: { read } }); await flushPromises()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('读取失败'); expect(wrapper.text()).not.toContain('模拟项目')
    await wrapper.setProps({ active: false }); expect(read).toHaveBeenCalledTimes(2); wrapper.unmount()
  })
  it('validates status enums, timestamps, counts and duplicate projects', () => {
    expect(validateCollectionStatus(sample()).projects).toHaveLength(1)
    for (const patch of [{ status: 'secret' }, { updatedAt: 'bad' }, { projects: [sample().projects[0], sample().projects[0]] }, { projects: [{ ...sample().projects[0], rows: -1 }] }]) expect(() => validateCollectionStatus({ ...sample(), ...patch })).toThrow()
  })
})
