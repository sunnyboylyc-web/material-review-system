import { describe, expect, it, vi } from 'vitest'
import { HttpOperationsAdapter, validateOperationsResponse } from '../adapters/httpOperationsAdapter'
import { validateOperationsRange } from '../domain/operations'
import type { OperationsResult } from '../domain/operations'
import { operationsFixture } from './operationsFixture'

const range = { start: '2026-10-01', end: '2026-10-04' }
const response = (value: unknown) => ({ ok: true, status: 200, json: async () => value }) as Response

describe('read-only operations HTTP adapter', () => {
  it('requests only the project/day GET endpoint and validates daily metadata', async () => {
    const fixture = operationsFixture('synthetic-project', range)
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(fixture))
    const result = await new HttpOperationsAdapter(fetcher).fetchDays('synthetic-project', range)
    expect(result).toEqual(fixture)
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/operations/projects/synthetic-project/days?start=2026-10-01&end=2026-10-04', {
      cache: 'no-store', headers: { Accept: 'application/json' },
    })
    expect(result.days[1].currentVersion?.rowCount).toBe(0)
    expect(result.days[2].currentVersion).toBeNull()
    expect(result.summary).toEqual({ queriedDays: 4, availableDays: 3, emptyDays: 1, missingDays: 1, isolatedDays: 1 })
  })

  it.each<[string, (value: OperationsResult) => void]>([
    ['project', value => { value.projectId = 'other' }],
    ['range', value => { value.range.end = '2026-10-05' }],
    ['summary', value => { value.summary.availableDays += 1 }],
    ['day-order', value => { value.days.reverse() }],
    ['missing-zero', value => { value.days[2].currentVersion = value.days[1].currentVersion }],
    ['missing-previous', value => { value.days[2].previousVersionId = 1 }],
    ['empty-with-rows', value => { value.days[1].currentVersion!.rowCount = 3 }],
    ['hash', value => { value.days[0].currentVersion!.contentHash = 'A'.repeat(64) }],
    ['timestamp', value => { value.days[0].currentVersion!.updatedAt = '2026-10-13 00:00:00' }],
    ['version-pointer', value => { value.days[0].previousVersionId = value.days[0].currentVersion!.versionId }],
    ['negative-isolated', value => { value.days[3].isolatedVersionCount = -1 }],
    ['observed-range', value => { value.observedRange.first = null }],
    ['available-outside-observed', value => { value.observedRange.first = '2026-10-02' }],
    ['empty-outside-observed', value => { value.observedRange.first = '2026-10-03'; value.days[0].state = 'MISSING'; value.days[0].currentVersion = null }],
  ])('rejects inconsistent %s metadata', (_label, mutate) => {
    const fixture = operationsFixture('synthetic-project', range)
    mutate(fixture)
    expect(() => validateOperationsResponse(fixture, 'synthetic-project', range)).toThrow('不一致')
  })

  it('rejects claimed source-file recheck, collector state and old contract version', () => {
    const fixture = operationsFixture('synthetic-project', range)
    for (const override of [{ sourceFilesRechecked: true }, { collectionStatus: 'SUCCESS' }, { schemaVersion: 'old' }]) {
      expect(() => validateOperationsResponse({ ...fixture, ...override }, 'synthetic-project', range)).toThrow('不一致')
    }
  })

  it('validates inclusive dates and the 366-day maximum before any request', async () => {
    expect(validateOperationsRange({ start: '2024-01-01', end: '2024-12-31' })).toBe('')
    expect(validateOperationsRange({ start: '2024-01-01', end: '2025-01-01' })).toContain('366')
    expect(validateOperationsRange({ start: '2026-02-30', end: '2026-03-01' })).toContain('有效')
    expect(validateOperationsRange({ start: '1899-12-31', end: '1900-01-01' })).toContain('1900')
    expect(validateOperationsRange({ start: '2200-12-31', end: '2201-01-01' })).toContain('2200')
    const fetcher = vi.fn<typeof fetch>()
    await expect(new HttpOperationsAdapter(fetcher).fetchDays('synthetic-project', { start: '2026-10-04', end: '2026-10-01' })).rejects.toThrow('不能晚于')
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('shows sanitized failure status without exposing server diagnostics or inventing empty success', async () => {
    const json = vi.fn().mockResolvedValue({ secret: 'PRIVATE_INTERNAL_DETAIL' })
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue({ ok: false, status: 503, json } as unknown as Response)
    await expect(new HttpOperationsAdapter(fetcher).fetchDays('synthetic-project', range)).rejects.toThrow('HTTP 503：当前数据状态暂不可验证')
    expect(json).not.toHaveBeenCalled()
    fetcher.mockRejectedValueOnce(new Error('PRIVATE_NETWORK_DETAIL'))
    await expect(new HttpOperationsAdapter(fetcher).fetchDays('synthetic-project', range)).rejects.toThrow('本地运维接口连接失败')
    fetcher.mockResolvedValueOnce(response({}))
    await expect(new HttpOperationsAdapter(fetcher).fetchDays('synthetic-project', range)).rejects.toThrow('不一致')
  })
})
