import { afterEach, describe, expect, it, vi } from 'vitest'
import type { HistoryUploadAdapter } from '../domain/historyUpload'
import type { ConfigurationAdapter } from '../domain/configuration'
import type { OperationsDataAdapter } from '../adapters/operationsAdapter'
import { validateOperationsResponse } from '../adapters/httpOperationsAdapter'

const preview = vi.hoisted(() => ({ props: {} as { historyUploadAdapter: HistoryUploadAdapter; configurationAdapter: ConfigurationAdapter; operationsAdapter: OperationsDataAdapter } }))
vi.mock('vue', async original => ({
  ...await original<typeof import('vue')>(),
  createApp: (_component: unknown, props: typeof preview.props) => {
    preview.props = props
    return { mount: vi.fn() }
  },
}))
afterEach(() => vi.unstubAllGlobals())

describe('D187 isolated preview fixtures', () => {
  it('binds every history context to its selected synthetic configuration account without network access', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    await import('../preview/d187')
    const configuration = await preview.props.configurationAdapter.read()
    expect(configuration.projects).toHaveLength(4)
    for (const project of configuration.projects) {
      const context = await preview.props.historyUploadAdapter.context(project.id)
      expect(context.projectId).toBe(project.id)
      expect(context.projectName).toBe(project.name)
      expect(context.accountId).toBe(project.advertiserId)
      expect(context.writeEnabled).toBe(false)
    }
    await expect(preview.props.historyUploadAdapter.context('not-a-preview-project')).rejects.toThrow('合成预览项目不存在')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('supplies contract-valid empty fact ranges for all synthetic project history panels', async () => {
    await import('../preview/d187')
    const range = { start: '2026-10-12', end: '2026-10-18' }
    for (const project of (await preview.props.configurationAdapter.read()).projects) {
      const result = await preview.props.operationsAdapter.fetchDays(project.id, range)
      expect(validateOperationsResponse(result, project.id, range)).toBe(result)
      expect(result.observedRange).toEqual({ first: null, last: null })
      expect(result.summary).toEqual({ queriedDays: 7, availableDays: 0, emptyDays: 0, missingDays: 7, isolatedDays: 0 })
    }
  })
})
