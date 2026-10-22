import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DatabaseBackupPanel from '../components/DatabaseBackupPanel.vue'
vi.mock('../components/CustomBackupPanel.vue', () => ({ default: { template: '<div />' } }))
import { databaseBackupDownloadUrl, readDatabaseBackup, validateDatabaseBackupStatus, type DatabaseBackupStatus } from '../adapters/httpDatabaseBackupAdapter'

const status = (): DatabaseBackupStatus => ({ available: true, retention: 1, schedule: '每周日23:00（上海时间）',
  latest: { generation: 'a'.repeat(32), createdAt: '2026-10-18T15:01:00Z', bytes: 1024 * 1024, sha256: 'b'.repeat(64), validation: 'DUMP_EXIT_AND_HASH_READBACK', restoreTested: false },
  lastAttempt: { status: 'SUCCESS', startedAt: '2026-10-18T15:00:00Z', completedAt: '2026-10-18T15:01:00Z', errorCode: null } })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('fixed weekly database backup download', () => {
  it('explains compressed download size separately from original SQL size', async () => {
    const value = status()
    Object.assign(value.latest!, { compression: 'gzip', sqlBytes: 10 * 1024 * 1024, sqlSha256: 'c'.repeat(64) })
    expect(validateDatabaseBackupStatus(value)).toEqual(value)
    const page = mount(DatabaseBackupPanel, { props: { read: async () => value } }); await flushPromises()
    expect(page.text()).toContain('.sql.gz')
    expect(page.text()).toContain('10.00 MiB')
    expect(page.text()).toContain('1.00 MiB')
    page.unmount()
  })
  it('loads status only while visible and uses a bounded native download without reading a blob', async () => {
    const read = vi.fn(async () => status()), fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const page = mount(DatabaseBackupPanel, { props: { active: false, read } })
    expect(read).not.toHaveBeenCalled()
    await page.setProps({ active: true }); await flushPromises()
    expect(read).toHaveBeenCalledTimes(1)
    expect(page.text()).toContain('23:01:00')
    expect(page.text()).toContain('1.00 MiB')
    expect(page.text()).toContain('不是全库或整套系统备份')
    expect(page.text()).toContain('尚未完成此份备份的数据库恢复演练')
    expect(page.findAll('a')).toHaveLength(1)
    expect(page.get('a').attributes('href')).toBe('/api/operations/database-backup/download?generation=' + 'a'.repeat(32))
    expect(page.get('a').attributes('download')).toBeDefined()
    page.get('a').element.addEventListener('click', event => event.preventDefault(), { once: true }) // jsdom has no download navigation.
    await page.get('a').trigger('click')
    expect(fetcher).not.toHaveBeenCalled()
    expect(page.text()).toContain('本站不能确认下载完成')
    expect(page.text()).toContain('HTTP 409')
    expect(page.findAll('button')).toHaveLength(1)
    page.unmount()
  })
  it('reflects a replacement in the same slot after a read-only refresh', async () => {
    const next = status(); next.latest!.generation = 'c'.repeat(32)
    const read = vi.fn().mockResolvedValueOnce(status()).mockResolvedValueOnce(next)
    const page = mount(DatabaseBackupPanel, { props: { read } }); await flushPromises()
    await page.get('button').trigger('click'); await flushPromises()
    expect(page.findAll('a')).toHaveLength(1)
    expect(page.get('a').attributes('href')).toContain('c'.repeat(32))
    page.unmount()
  })
  it('does not mistake running or failed cleanup for loss of a valid current generation', async () => {
    for (const attempt of ['RUNNING', 'FAILED'] as const) {
      const value = status(); value.lastAttempt!.status = attempt
      if (attempt === 'RUNNING') value.lastAttempt!.completedAt = null
      const page = mount(DatabaseBackupPanel, { props: { read: async () => value } }); await flushPromises()
      expect(page.get('a').attributes('href')).toContain('a'.repeat(32))
      expect(page.text()).not.toContain('下方不是本次新备份')
      expect(page.text()).toContain(attempt === 'RUNNING' ? '可能仍在处理或已经中断' : '旧份清理未全部完成')
      page.unmount()
    }
  })
  it('distinguishes no successful backup, read failure, and preview with no private requests', async () => {
    const page = mount(DatabaseBackupPanel, { props: { read: async () => ({ ...status(), available: false, latest: null, lastAttempt: null }) } }); await flushPromises()
    expect(page.text()).toContain('尚无已核验的周备份'); expect(page.find('a').exists()).toBe(false); page.unmount()
    const fail = mount(DatabaseBackupPanel, { props: { read: async () => { throw new Error('private path /secret') } } }); await flushPromises()
    expect(fail.text()).toContain('暂不可核验'); expect(fail.text()).not.toContain('/secret'); expect(fail.find('a').exists()).toBe(false); fail.unmount()
    const read = vi.fn(async () => status()), preview = mount(DatabaseBackupPanel, { props: { read, preview: true } }); await flushPromises()
    expect(read).not.toHaveBeenCalled(); expect(preview.get('button').attributes('disabled')).toBeDefined(); expect(preview.find('a').exists()).toBe(false); preview.unmount()
  })
  it('drops an old visibility response and never leaks stale download links after a read error', async () => {
    let resolve!: (value: DatabaseBackupStatus) => void
    const read = vi.fn().mockImplementationOnce(() => new Promise<DatabaseBackupStatus>(r => { resolve = r })).mockRejectedValueOnce(new Error('gone'))
    const page = mount(DatabaseBackupPanel, { props: { read } })
    await page.setProps({ active: false }); resolve(status()); await flushPromises()
    expect(page.find('a').exists()).toBe(false)
    await page.setProps({ active: true }); await flushPromises()
    expect(page.find('a').exists()).toBe(false); expect(page.text()).toContain('暂停下载'); page.unmount()
  })
  it.each([
    { latest: { ...status().latest, generation: '../private' } },
    { latest: { ...status().latest, sha256: 'bad' } },
    { latest: { ...status().latest, bytes: 0 } },
    { latest: { ...status().latest, compression: 'gzip', sqlBytes: 0, sqlSha256: 'c'.repeat(64) } },
    { latest: { ...status().latest, compression: 'zip', sqlBytes: 1024, sqlSha256: 'c'.repeat(64) } },
    { latest: { ...status().latest, restoreTested: true } },
    { latest: { ...status().latest, validation: 'UNTESTED' } },
    { available: false }, { schedule: '<script>private</script>' }, { retention: 7 },
    { lastAttempt: { ...status().lastAttempt, errorCode: 'private password value' } },
  ])('rejects unsafe or contradictory metadata: %j', mutation => {
    expect(() => validateDatabaseBackupStatus({ ...status(), ...mutation })).toThrow()
  })
  it('only GETs safe status, reports denied reads without leaking server text, rejects download path injection', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => status() }).mockResolvedValueOnce({ ok: false, status: 403, json: async () => ({ raw: 'secret' }) })
    vi.stubGlobal('fetch', fetcher)
    expect((await readDatabaseBackup()).retention).toBe(1)
    expect(fetcher).toHaveBeenCalledWith('/api/operations/database-backup', { credentials: 'same-origin', cache: 'no-store' })
    await expect(readDatabaseBackup()).rejects.toThrow('运维权限')
    expect(() => databaseBackupDownloadUrl('../secret')).toThrow()
  })
})
