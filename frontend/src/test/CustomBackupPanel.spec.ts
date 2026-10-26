import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import CustomBackupPanel from '../components/CustomBackupPanel.vue'
import { createCustomBackup, readCustomBackup } from '../adapters/httpDatabaseBackupAdapter'
vi.mock('../adapters/httpDatabaseBackupAdapter', () => ({ createCustomBackup: vi.fn(), readCustomBackup: vi.fn(), customBackupDownloadUrl: (id: string) => '/download/' + id }))
const empty = { backup: { available: false, latest: null, lastAttempt: null, schedule: '每周日23:00（上海时间）', retention: 1 as const }, job: { running: false, errorCode: null } }
afterEach(() => { vi.resetAllMocks(); vi.useRealTimers() })
it('does not query or generate in preview or when hidden', async () => {
  const page = mount(CustomBackupPanel, { props: { active: true, preview: true } }); await flushPromises()
  expect(readCustomBackup).not.toHaveBeenCalled(); expect(page.get('button').attributes('disabled')).toBeDefined()
  await page.setProps({ active: false, preview: false }); expect(readCustomBackup).not.toHaveBeenCalled(); page.unmount()
})
it('validates date order and submits an explicit historical range, never on date changes', async () => {
  vi.setSystemTime(new Date('2026-10-22T10:00:00Z')); vi.mocked(readCustomBackup).mockResolvedValue(empty)
  const page = mount(CustomBackupPanel, { props: { active: true, preview: false } }); await flushPromises()
  const inputs = page.findAll('input'); await inputs[0]!.setValue('2026-10-21'); await inputs[1]!.setValue('2026-10-20')
  expect(page.get('button').attributes('disabled')).toBeDefined()
  await inputs[0]!.setValue('2024-03-31'); await inputs[1]!.setValue('2026-10-21')
  expect(createCustomBackup).not.toHaveBeenCalled(); await page.get('button').trigger('click'); await flushPromises()
  expect(createCustomBackup).toHaveBeenCalledExactlyOnceWith('2024-03-31','2026-10-21')
  expect(page.text()).toContain('不还原过去某时刻'); page.unmount()
})
it('shows only verified current download and its actual range', async () => {
  vi.mocked(readCustomBackup).mockResolvedValue({ ...empty, backup: { ...empty.backup, available: true,
    latest: { generation: 'a'.repeat(32), createdAt: '2026-10-22T10:00:00Z', bytes: 1048576, sha256: 'b'.repeat(64), validation: 'DUMP_EXIT_AND_HASH_READBACK', restoreTested: false, scope: 'STAT_DATE_RANGE', startDate: '2026-08-15', endDate: '2026-08-30' } } })
  const page = mount(CustomBackupPanel, { props: { active: true, preview: false } }); await flushPromises()
  expect(page.text()).toContain('2026-08-15 至 2026-08-30'); expect(page.get('a').attributes('href')).toBe('/download/' + 'a'.repeat(32)); page.unmount()
})
