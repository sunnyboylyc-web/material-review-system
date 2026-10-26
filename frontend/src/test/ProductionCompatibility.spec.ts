import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import DateRangePicker from '../components/DateRangePicker.vue'
import PeriodReview from '../components/PeriodReview.vue'
import { initializeSession } from '../domain/session'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { ReviewQuery, ReviewResult } from '../domain/dashboard'

// Test-only transport, never imported by either production or handoff entrypoint.
// It speaks the unchanged D155 wire format to the real HTTP adapters.
const projects = Array.from({ length: 17 }, (_, i) => ({ id: `existing-${i}`, name: `已有项目 ${i + 1}`, enabled: true }))
const identity = { username: 'existing-viewer', operations: false, administrator: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'TEST_CSRF_ONLY' }
const originalQuery: ReviewQuery = { current: { start: '2026-10-01', end: '2026-10-07' }, previous: { start: '2026-09-24', end: '2026-09-30' } }
const wire = (query: ReviewQuery) => ({ currentStart: query.current.start, currentEnd: query.current.end, previousStart: query.previous.start, previousEnd: query.previous.end })
const response = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

async function existingApi() {
  const fixture = new SyntheticMockAdapter(projects)
  let preference = structuredClone(originalQuery)
  let stored: ReviewResult = { ...await fixture.fetchReview(projects[0].id, originalQuery, 20), evidenceKind: 'CURRENT_FACTS' }
  const requests: { path: string; method: string; body?: Record<string, unknown>; csrf: string | null }[] = []
  const unexpected: string[] = []
  const fetcher = vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(String(input), 'https://contract.test'), path = url.pathname, method = init?.method ?? 'GET'
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ path: path + url.search, method, body, csrf: new Headers(init?.headers).get('X-CSRF-TOKEN') })
    if (path === '/api/auth/session') return response(identity)
    if (path === '/api/projects') return response(projects)
    const id = projects.find(p => path.includes(`/${p.id}/`))?.id
    if (path.endsWith('/freshness') && id) return response({ schemaVersion: 'freshness-1.0.0', projectId: id, latestMcpStatDate: '2026-10-15' })
    if (path.endsWith('/period') && id) {
      if (method === 'PUT') { preference = { current: { start: body.currentStart, end: body.currentEnd }, previous: { start: body.previousStart, end: body.previousEnd } }; return response(null, 204) }
      return response(wire(preference))
    }
    if (path.endsWith('/review-snapshot') && id) return id === projects[0].id ? response({ result: stored, savedAt: '2026-10-16T01:00:00Z' }) : response(null, 204)
    if (path.endsWith('/review') && id && method === 'GET') {
      const params = url.searchParams
      return response({ ...await fixture.fetchReview(id, {
        current: { start: params.get('currentStart')!, end: params.get('currentEnd')! },
        previous: { start: params.get('previousStart')!, end: params.get('previousEnd')! },
      }, Number(params.get('limit'))), evidenceKind: 'CURRENT_FACTS' })
    }
    if (path.endsWith('/review-snapshot/refresh') && id && method === 'POST') {
      stored = { ...await fixture.fetchReview(id, { current: { start: body.currentStart, end: body.currentEnd }, previous: { start: body.previousStart, end: body.previousEnd } }, body.limit), evidenceKind: 'CURRENT_FACTS' }
      return response({ result: stored, savedAt: '2026-10-17T01:00:00Z', persisted: true })
    }
    if (path.endsWith('/top') && id) return response({ ...await fixture.fetchTop(id, url.searchParams.get('date')!, Number(url.searchParams.get('limit'))), evidenceKind: 'CURRENT_FACTS' })
    if (path.endsWith('/preview') && path.startsWith('/api/operations/exports/projects/') && id) {
      const startDate = url.searchParams.get('startDate')!, endDate = url.searchParams.get('endDate')!
      return response({ projectId: id, projectName: projects.find(p => p.id === id)!.name, accountId: '99999999999999999901', startDate, endDate, rowCount: 1,
        days: [{ statDate: endDate, rowCount: 1, sourceType: 'MCP_AUTO' }], excludedDates: [] })
    }
    unexpected.push(`${method} ${path}`)
    return response({ error: 'UNEXPECTED_TEST_REQUEST' }, 500)
  })
  vi.stubGlobal('fetch', fetcher)
  return { requests, unexpected, stored: () => stored, preference: () => preference }
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('same D155 HTTP compatibility', () => {
  it('uses existing identity, 17 legacy projects and persisted results without configuration changes or browser storage', async () => {
    const api = await existingApi(), storage = vi.spyOn(Storage.prototype, 'setItem')
    const session = await initializeSession()
    let page = mount(App, { props: { session }, global: { stubs: { StarScene: true } } }); await flushPromises()
    expect(page.findAll('.project-button')).toHaveLength(17)
    expect(page.findComponent(PeriodReview).props('result')).toEqual(api.stored())
    expect(page.findAllComponents(DateRangePicker)[0].props('modelValue')).toEqual(originalQuery.current)
    expect(api.requests.every(r => r.method === 'GET')).toBe(true)
    expect(api.requests.filter(r => r.path.includes('/review?'))).toHaveLength(1)
    expect(new URL(api.requests.find(r => r.path.includes('/review?'))!.path, 'https://contract.test').searchParams.get('limit')).toBe('3')
    expect(api.preference()).toEqual(originalQuery)
    expect(api.requests.some(r => r.path.includes('/top?') || r.path.endsWith('/refresh'))).toBe(false)
    expect(page.get('form[action="/logout"] input[name="_csrf"]').attributes('value')).toBe(identity.csrfToken)

    await page.get('.date-form--top').trigger('submit'); await flushPromises()
    expect(page.get('.top-results').text()).toContain('99999999999999990001')
    const picker = page.findAllComponents(DateRangePicker)[0]
    picker.vm.$emit('update:modelValue', { start: '2026-10-08', end: '2026-10-14' }); await flushPromises()
    picker.vm.$emit('confirm'); await flushPromises()
    await page.get('.date-form--review').trigger('submit'); await flushPromises()
    const query = { ...originalQuery, current: { start: '2026-10-08', end: '2026-10-14' } }
    const writes = api.requests.filter(r => r.method !== 'GET')
    expect(writes.map(r => [r.method, r.path])).toEqual([
      ['PUT', '/api/preferences/projects/existing-0/period'],
      ['POST', '/api/preferences/projects/existing-0/review-snapshot/refresh'],
      ['PUT', '/api/preferences/projects/existing-0/period'],
    ])
    expect(writes.every(r => r.csrf === identity.csrfToken)).toBe(true)
    expect(writes.find(r => r.method === 'POST')?.body).toEqual({ ...wire(query), limit: 20 })
    const writeCount = writes.length
    page.unmount()
    page = mount(App, { props: { session: await initializeSession() }, global: { stubs: { StarScene: true } } }); await flushPromises()
    expect(page.findAll('.project-button').map(p => p.text().trim())).toEqual(projects.map(p => p.name))
    expect(page.findComponent(PeriodReview).props('result')).toEqual(api.stored())
    expect(page.findAllComponents(DateRangePicker)[0].props('modelValue')).toEqual(query.current)
    expect(api.requests.filter(r => r.method !== 'GET')).toHaveLength(writeCount)
    expect(storage).not.toHaveBeenCalled()
    expect(api.unexpected).toEqual([])
    page.unmount()
  })
  it('keeps the existing export API usable for an ordinary production user', async () => {
    const api = await existingApi()
    const page = mount(App, { props: { session: await initializeSession() }, global: { stubs: { StarScene: true } } }); await flushPromises()
    // Both the D155 shell and the redesigned shell use the same export component.
    const entry = page.find('.download-data-button')
    await (entry.exists() ? entry : page.get('.operations-toggle')).trigger('click'); await flushPromises()
    await page.get('.collected-export form').trigger('submit'); await flushPromises()
    expect(page.get('.export-preview').text()).toContain('99999999999999999901')
    expect(page.get('.collected-export button.primary-button').attributes('disabled')).toBeUndefined()
    expect(api.requests.some(r => r.path.startsWith('/api/operations/exports/projects/existing-0/preview?'))).toBe(true)
    expect(api.requests.every(r => r.method === 'GET')).toBe(true)
    expect(api.unexpected).toEqual([])
    page.unmount()
  })
})

describe('redesigned workspace notices', () => {
  it('shows a production project failure on the initial workspace without substituting demo data', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({}, 503)))
    const page = mount(App, { props: { session: identity }, global: { stubs: { StarScene: true } } }); await flushPromises()
    expect(page.get('.home-project-status[role="alert"]').isVisible()).toBe(true)
    expect(page.get('.home-project-status[role="alert"]').text()).toContain('503')
    expect(page.findAll('.project-satellite')).toHaveLength(0)
    expect(page.text()).not.toContain('纯虚构数据')
    page.unmount()
  })
})
