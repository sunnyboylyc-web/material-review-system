import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import { HttpDashboardAdapter, validateReviewResponse, validateTopResponse } from '../adapters/httpDashboardAdapter'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { formatFirstSpend, formatMaterialId, formatMetric } from '../domain/formatting'
import type { ReviewQuery, ReviewResult, TopResult } from '../domain/dashboard'

const project = { id: 'synthetic-project', name: '测试项目（纯合成）', enabled: true }
const query: ReviewQuery = {
  current: { start: '2026-11-03', end: '2026-11-05' },
  previous: { start: '2026-11-01', end: '2026-11-02' },
}
const fixture = new SyntheticMockAdapter([project])
// Synthetic in-memory fixtures exercise the real JSON shape; no business data is used.
async function reviewFixture(): Promise<ReviewResult> {
  return { ...await fixture.fetchReview(project.id, query, 20), evidenceKind: 'CURRENT_FACTS' }
}
async function topFixture(): Promise<TopResult> {
  return { ...await fixture.fetchTop(project.id, '2026-11-05', 20), evidenceKind: 'CURRENT_FACTS' }
}
const response = (data: unknown) => ({ ok: true, status: 200, json: async () => data }) as Response
afterEach(() => vi.unstubAllGlobals())

describe('local HTTP dashboard adapter', () => {
  it('passes independent ranges and limits without altering source or selecting an account client-side', async () => {
    const data = await reviewFixture()
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(data))
    const adapter = new HttpDashboardAdapter(fetcher)
    expect(await adapter.fetchReview(project.id, query, 20)).toEqual(data)
    const [url, options] = fetcher.mock.calls[0]
    expect(url).toBe('/api/projects/synthetic-project/review?currentStart=2026-11-03&currentEnd=2026-11-05&previousStart=2026-11-01&previousEnd=2026-11-02&limit=20')
    expect(options?.cache).toBe('no-store')
    expect(String(url)).not.toContain('accountId')
  })

  it('reads seven descending days and retains long material IDs as text', async () => {
    const data = await topFixture()
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(data))
    const result = await new HttpDashboardAdapter(fetcher).fetchTop(project.id, '2026-11-05', 20)
    expect(result.days.map(day => day.date)).toEqual(['2026-11-05', '2026-11-04', '2026-11-03', '2026-11-02', '2026-11-01', '2026-10-31', '2026-10-30'])
    expect(result.rows[0].materialId).toBe('99999999999999990001')
    expect(result.recent3Coverage.expectedDays).toBe(3)
  })

  it('rejects malformed numeric, coverage, first-spend and ranking states', async () => {
    const data = await topFixture()
    const numericNull = structuredClone(data)
    numericNull.rows[0].dayRoi = { status: 'VALID', value: null }
    expect(() => validateTopResponse(numericNull)).toThrow('契约不一致')
    const hiddenZero = structuredClone(data)
    hiddenZero.rows[0].annualRoi = { status: 'UNKNOWN_INPUT', value: 0 }
    expect(() => validateTopResponse(hiddenZero)).toThrow('契约不一致')
    const wrongCoverage = structuredClone(data)
    wrongCoverage.coverage.state = 'COMPLETE'
    expect(() => validateTopResponse(wrongCoverage)).toThrow('契约不一致')
    const guessedDate = structuredClone(data)
    guessedDate.rows[0].firstSpend.date = '2026-11-05'
    expect(() => validateTopResponse(guessedDate)).toThrow('契约不一致')
    const missingDay = structuredClone(data)
    missingDay.rows[0].dailySpend.pop()
    expect(() => validateTopResponse(missingDay)).toThrow('契约不一致')
    const fakeRank = structuredClone(data)
    fakeRank.rankingState = 'UNAVAILABLE'
    expect(() => validateTopResponse(fakeRank)).toThrow('契约不一致')
  })

  it('rejects obsolete schema and incomplete period metric mappings', async () => {
    const data = await reviewFixture()
    expect(validateReviewResponse(data)).toEqual(data)
    expect(() => validateReviewResponse({ ...data, schemaVersion: 'old' })).toThrow('契约不一致')
    expect(() => validateReviewResponse({ ...data, comparisons: [] })).toThrow('契约不一致')
    const missingCpc = structuredClone(data)
    delete (missingCpc.periods[0].groups[0].metrics as Partial<typeof missingCpc.periods[0]['groups'][0]['metrics']>).cpc
    expect(() => validateReviewResponse(missingCpc)).toThrow('契约不一致')
    expect(() => validateReviewResponse({ ...data, evidenceKind: 'SYNTHETIC' })).toThrow('契约不一致')
  })

  it('does not expose raw server error bodies or use a synthetic fallback', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue({ ok: false, status: 500,
      json: async () => ({ internal: 'PRIVATE_DATABASE_DETAIL' }) } as unknown as Response)
    await expect(new HttpDashboardAdapter(fetcher).listProjects()).rejects.toThrow('HTTP 500')
    fetcher.mockRejectedValueOnce(new Error('PRIVATE_DRIVER_DETAIL'))
    await expect(new HttpDashboardAdapter(fetcher).listProjects()).rejects.toThrow('没有切换为演示数据')
  })

  it('defaults App to HTTP and binds valid-shaped results without synthetic coverage labels', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async input => {
      const url = new URL(String(input), 'http://local.test')
      if (url.pathname === '/api/projects') return response([project])
      if (url.pathname.endsWith('/freshness')) return response({schemaVersion:'freshness-1.0.0',projectId:project.id,latestMcpStatDate:'2026-10-13'})
      if (url.pathname.endsWith('/review')) {
        const params = url.searchParams
        return response({ ...await fixture.fetchReview(project.id, {
          current: { start: params.get('currentStart')!, end: params.get('currentEnd')! },
          previous: { start: params.get('previousStart')!, end: params.get('previousEnd')! },
        }, Number(params.get('limit'))), evidenceKind: 'CURRENT_FACTS' })
      }
      return response({ ...await fixture.fetchTop(project.id, url.searchParams.get('date')!, Number(url.searchParams.get('limit'))), evidenceKind: 'CURRENT_FACTS' })
    })
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(App)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    const homeRequest = new URL(String(fetcher.mock.calls[1][0]), 'http://local.test')
    expect(homeRequest.pathname).toBe(`/api/projects/${project.id}/review`)
    expect(homeRequest.searchParams.get('limit')).toBe('3')
    const homePayload = await (await fetcher.mock.results[1].value).json()
    expect(wrapper.findAll('.home-ranking-list button').map(row => row.text())).toEqual(homePayload.topMaterials.map((row: { materialName: string }) => row.materialName))
    expect(wrapper.find('.home-data-signal--red').exists()).toBe(true) // Older wire omits the three additive totals.
    await wrapper.get('.date-form--review').trigger('submit')
    await wrapper.get('.date-form--top').trigger('submit')
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(6)
    expect(wrapper.text()).toContain('本地 · 只读接口')
    expect(wrapper.text()).toContain('120.00%')
    expect(wrapper.text()).toContain('累计消耗（已有数据）')
    expect(wrapper.text()).not.toContain('年度累计 ROI')
    expect(wrapper.findAll('.synthetic-coverage-warning')).toHaveLength(0)
    expect(wrapper.findAll('.top-coverage-indicators .coverage')).toHaveLength(0)
  })
})

describe('number and first-spend presentation', () => {
  it('maps only exact AIGC sentinel identities to the legacy display ID', () => {
    expect(formatMaterialId('-2', 'AIGC动态创意素材集合')).toBe('-')
    expect(formatMaterialId('-2', 'AIGC动态创意视频素材集合')).toBe('-')
    expect(formatMaterialId('-2', '普通素材')).toBe('-2')
    expect(formatMaterialId('-2', null)).toBe('-2')
    expect(formatMaterialId('-2', 'AIGC动态创意素材集合 ')).toBe('-2')
    expect(formatMaterialId('-2', 'aigc动态创意素材集合')).toBe('-2')
    expect(formatMaterialId('99999999999999990001', 'AIGC动态创意素材集合')).toBe('99999999999999990001')
    expect(formatMaterialId('-20', 'AIGC动态创意视频素材集合')).toBe('-20')
  })

  it('never changes unknown and zero denominators into zero; percentages differ from ROI', () => {
    expect(formatMetric({ status: 'UNKNOWN_INPUT', value: null })).toContain('数据未知')
    expect(formatMetric({ status: 'ZERO_DENOMINATOR', value: null })).toContain('分母为 0')
    expect(formatMetric({ status: 'VALID', value: 0 })).toBe('0.00')
    expect(formatMetric({ status: 'VALID', value: 1.2 }, 'percent')).toBe('120.00%')
    expect(formatMetric({ status: 'VALID', value: 1.2 })).toBe('1.20')
    expect(formatMetric({ status: 'VALID', value: 10 }, 'integer')).toBe('10')
    expect(formatFirstSpend({ status: 'PENDING', date: null, earliestObservedDate: '2026-11-05' })).not.toContain('2026-11-05')
  })
})
