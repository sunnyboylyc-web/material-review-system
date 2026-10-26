import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpPeriodPreferences } from '../adapters/periodPreferences'
import { initializeSession } from '../domain/session'
afterEach(() => vi.unstubAllGlobals())
const body = { currentStart: '2026-10-01', currentEnd: '2026-10-07', previousStart: '2026-09-24', previousEnd: '2026-09-30' }
describe('account period preference client', () => {
  it('loads unset and stored dates without business requests', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(Response.json(body))
    vi.stubGlobal('fetch', fetcher)
    expect(await httpPeriodPreferences.load('one')).toBeNull()
    expect(await httpPeriodPreferences.load('one')).toEqual({ current: { start: body.currentStart, end: body.currentEnd }, previous: { start: body.previousStart, end: body.previousEnd } })
    expect(fetcher.mock.calls[0][0]).toBe('/api/preferences/projects/one/period')
    expect(fetcher.mock.calls[0][1].cache).toBe('no-store')
  })
  it('rejects malformed saved dates and failed requests', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json({ ...body, currentStart: '2026-02-30' }))
      .mockResolvedValueOnce(new Response(null, { status: 500 })))
    await expect(httpPeriodPreferences.load('one')).rejects.toThrow('格式异常')
    await expect(httpPeriodPreferences.load('one')).rejects.toThrow('读取失败')
  })
  it('uses session CSRF and excludes username from payload', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ username: 'ordinary', operations: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'token' }))
      .mockResolvedValueOnce(Response.json(body))
    vi.stubGlobal('fetch', fetcher)
    await initializeSession()
    await httpPeriodPreferences.save('one', { current: { start: body.currentStart, end: body.currentEnd }, previous: { start: body.previousStart, end: body.previousEnd } })
    const request = fetcher.mock.calls[1][1]
    expect(request.method).toBe('PUT')
    expect(request.headers['X-CSRF-TOKEN']).toBe('token')
    expect(JSON.parse(request.body)).toEqual(body)
  })
})
