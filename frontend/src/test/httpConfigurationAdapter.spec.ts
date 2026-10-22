import { describe, expect, it, vi } from 'vitest'
import { ConfigurationModeUnavailableError, HttpConfigurationAdapter, validateConfigurationApply, validateConfigurationPreflight, validateConfigurationSnapshot } from '../adapters/httpConfigurationAdapter'
import { configurationProjectsMatch, validateConfigurationProjects } from '../domain/configuration'
import { configurationFixture, metricConfigurationFixture, TEST_PROJECT } from './configurationFixture'

const response = (data: unknown) => ({ ok: true, status: 200, json: async () => data }) as Response
const expectedRevision = 'a'.repeat(64)
const candidateHash = 'b'.repeat(64)
const revision = 'c'.repeat(64)
const candidate = { expectedRevision, projects: [TEST_PROJECT] }

describe('local project configuration HTTP adapter', () => {
  it('accepts absent legacy category as Other for comparison but rejects null and unknown categories', () => {
    expect(configurationProjectsMatch([{ ...TEST_PROJECT, category: undefined }], [TEST_PROJECT])).toBe(true)
    expect(configurationProjectsMatch([TEST_PROJECT], [{ ...TEST_PROJECT, category: 'SMALL_APPLIANCE' }])).toBe(false)
    for (const category of [null, 'unknown', '小家电']) {
      expect(() => validateConfigurationSnapshot({ ...configurationFixture(), projects: [{ ...TEST_PROJECT, category }] })).toThrow('不一致')
    }
  })
  it('resolves precise public handles using protected POST and rejects mismatched returned identities', async () => {
    const input = { advertiserId: TEST_PROJECT.advertiserId, publicDouyinId: 'synthetic.public' }
    const result = { ...input, awemeId: '99999999', awemeName: '合成账号' }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(result))
      .mockResolvedValueOnce(response({ ...result, publicDouyinId: 'other' }))
    const adapter = new HttpConfigurationAdapter(fetcher)
    expect(await adapter.resolveAnchor(input)).toEqual(result)
    expect(fetcher.mock.calls[0][0]).toBe('/api/operations/configuration/resolve-anchor')
    expect(fetcher.mock.calls[0][1]?.headers).toMatchObject({ 'X-SCSJ-Config-Write': '1' })
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual(input)
    await expect(adapter.resolveAnchor(input)).rejects.toThrow('不一致')
    const enriched = { ...TEST_PROJECT, publicDouyinId: input.publicDouyinId, awemeName: result.awemeName }
    expect(validateConfigurationSnapshot(configurationFixture([enriched])).projects[0]).toEqual(enriched)
    expect(configurationProjectsMatch([enriched], [TEST_PROJECT])).toBe(false)
    expect(configurationProjectsMatch([TEST_PROJECT], [{ ...TEST_PROJECT, publicDouyinId: null, awemeName: null }])).toBe(true)
    expect(configurationProjectsMatch([TEST_PROJECT], [{ ...TEST_PROJECT, deleted: false }])).toBe(true)
    expect(configurationProjectsMatch([TEST_PROJECT], [{ ...TEST_PROJECT, deleted: true }])).toBe(false)
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, deleted: true }])).toContain('不能启用')
    expect(validateConfigurationSnapshot(configurationFixture([{ ...TEST_PROJECT, publicDouyinId: 'synthetic', douyinIds: [], deleted: false }])).projects[0].douyinIds).toEqual([])
  })
  it('validates the 28-metric catalog, selection and immutable required dependency flags', () => {
    const valid = metricConfigurationFixture()
    expect(validateConfigurationSnapshot(valid)).toEqual(valid)
    for (const override of [
      { enabledMetrics: [...valid.enabledMetrics!, valid.enabledMetrics![0]] },
      { enabledMetrics: [...valid.enabledMetrics!, 'unknown'] },
      { enabledMetrics: valid.enabledMetrics!.slice(1) },
      { metricCatalog: valid.metricCatalog!.slice(1) },
      { metricCatalog: valid.metricCatalog!.map((metric, index) => index === 0 ? { ...metric, required: false } : metric) },
      { metricCatalog: valid.metricCatalog!.map((metric, index) => index === 27 ? valid.metricCatalog![0] : metric) },
      { metricCatalog: valid.metricCatalog!.map((metric, index) => index === 0 ? { ...metric, label: '' } : metric) },
    ]) expect(() => validateConfigurationSnapshot({ ...valid, ...override })).toThrow('不一致')
  })

  it('sends the selected metrics on both preflight and apply without replacing candidate hash', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response({ status: 'READY', expectedRevision, candidateHash }))
      .mockResolvedValueOnce(response({ status: 'CURRENT_VERIFIED', previousRevision: expectedRevision, revision, collectorExecution: 'DISABLED' }))
    const adapter = new HttpConfigurationAdapter(fetcher)
    const selected = { ...candidate, enabledMetrics: metricConfigurationFixture().enabledMetrics!.slice(0, 5) }
    await adapter.preflight(selected)
    await adapter.apply({ ...selected, candidateHash })
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual(selected)
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toEqual({ ...selected, candidateHash })
  })
  it('uses no-store reads and guarded JSON POSTs without manually overriding Origin', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(configurationFixture()))
      .mockResolvedValueOnce(response({ status: 'READY', expectedRevision, candidateHash }))
      .mockResolvedValueOnce(response({ status: 'CURRENT_VERIFIED', previousRevision: expectedRevision, revision, collectorExecution: 'DISABLED' }))
    const adapter = new HttpConfigurationAdapter(fetcher)
    expect(await adapter.read()).toEqual(configurationFixture())
    expect(await adapter.preflight(candidate)).toEqual({ status: 'READY', expectedRevision, candidateHash })
    expect((await adapter.apply({ ...candidate, candidateHash })).revision).toBe(revision)
    expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/operations/configuration', '/api/operations/configuration/preflight', '/api/operations/configuration/apply'])
    expect(fetcher.mock.calls[0][1]?.cache).toBe('no-store')
    for (const [, options] of fetcher.mock.calls.slice(1)) {
      expect(options?.method).toBe('POST')
      expect(options?.headers).toEqual({ Accept: 'application/json', 'Content-Type': 'application/json', 'X-SCSJ-Config-Write': '1' })
      expect(JSON.parse(String(options?.body)).projects[0].advertiserId).toBe('99999999999999990001')
    }
  })

  it('rejects unsupported schemas, changed execution state, duplicate bindings and malformed arrays', () => {
    const valid = configurationFixture()
    for (const override of [{ schemaVersion: 'old' }, { collectorExecution: 'ENABLED' }, { metricChangesEnabled: true },
      { projects: [TEST_PROJECT, TEST_PROJECT] }, { projects: [{ ...TEST_PROJECT, advertiserId: 123 }] },
      { projects: [{ ...TEST_PROJECT, douyinIds: [] }] }, { revision: 'wrong' }]) {
      expect(() => validateConfigurationSnapshot({ ...valid, ...override })).toThrow('不一致')
    }
    expect(validateConfigurationSnapshot({ ...valid, projects: [], writeEnabled: false }).projects).toEqual([])
  })

  it('rejects preflight and apply revisions/status mismatches and does not equate candidate hash with revision', () => {
    expect(() => validateConfigurationPreflight({ status: 'READY', expectedRevision: revision, candidateHash }, expectedRevision)).toThrow('不一致')
    expect(() => validateConfigurationApply({ status: 'CURRENT_VERIFIED', previousRevision: revision, revision, collectorExecution: 'DISABLED' }, expectedRevision)).toThrow('不一致')
    expect(() => validateConfigurationApply({ status: 'UNKNOWN', previousRevision: expectedRevision, revision, collectorExecution: 'DISABLED' }, expectedRevision)).toThrow('不一致')
    expect(validateConfigurationApply({ status: 'CURRENT_VERIFIED', previousRevision: expectedRevision, revision, collectorExecution: 'DISABLED' }, expectedRevision).revision).not.toBe(candidateHash)
  })

  it('treats read-only launch 404 as unavailable mode, and hides raw write failure diagnostics', async () => {
    const body = vi.fn().mockResolvedValue({ detail: 'PRIVATE_PATH_OR_ID' })
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce({ ok: false, status: 404, json: body } as unknown as Response)
      .mockResolvedValueOnce({ ok: false, status: 503, json: body } as unknown as Response)
    const adapter = new HttpConfigurationAdapter(fetcher)
    await expect(adapter.read()).rejects.toBeInstanceOf(ConfigurationModeUnavailableError)
    await expect(adapter.apply({ ...candidate, candidateHash })).rejects.toThrow('保存是否生效不能据此推断')
    expect(body).toHaveBeenCalledTimes(1)
    fetcher.mockRejectedValueOnce(new Error('PRIVATE_NETWORK_DETAIL'))
    await expect(adapter.apply({ ...candidate, candidateHash })).rejects.toThrow('结果可能不确定')
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('enforces fixed identity and bounded structural fields without validating online ownership', () => {
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, enabled: false }], [TEST_PROJECT])).toBe('')
    expect(validateConfigurationProjects([], [TEST_PROJECT])).toContain('不能直接移除')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, advertiserId: '123' }], [TEST_PROJECT])).toBe('')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, id: 'UPPER' }])).toContain('小写')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, douyinIds: ['123', '123'] }])).toContain('不重复')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, advertiserId: '0' }])).toContain('不允许 0')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, advertiserId: '0123' }])).toContain('前导 0')
    expect(validateConfigurationProjects([{ ...TEST_PROJECT, douyinIds: ['0123'] }])).toContain('前导 0')
    expect(configurationProjectsMatch([TEST_PROJECT], [{ ...TEST_PROJECT, enabled: false }])).toBe(false)
  })
})

