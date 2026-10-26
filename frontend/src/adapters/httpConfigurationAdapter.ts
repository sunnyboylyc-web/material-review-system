import { CONFIGURATION_SCHEMA_VERSION, METRIC_PROFILE, REQUIRED_METRICS, validateEnabledMetrics, validateConfigurationProjects } from '../domain/configuration'
import { csrfHeaders, sessionFetch } from '../domain/session'
import type { AnchorRequest, AnchorResolution, ConfigurationAdapter, ConfigurationApply, ConfigurationCandidate, ConfigurationMetric, ConfigurationPreflight, ConfigurationProject, ConfigurationSnapshot } from '../domain/configuration'

const CONTRACT_ERROR = '配置接口回包与契约或当前请求不一致；请重新读取配置，不要自动重试保存。'
const SHA = /^[a-f0-9]{64}$/
type JsonObject = Record<string, unknown>
function requireValue(condition: unknown): asserts condition { if (!condition) throw new Error(CONTRACT_ERROR) }
function object(value: unknown): JsonObject {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value))
  return value as JsonObject
}
function hash(value: unknown): value is string { return typeof value === 'string' && SHA.test(value) }

export class ConfigurationModeUnavailableError extends Error {
  constructor() { super('配置模式未开启；原有入库状态查询仍可使用。') }
}
export function validateAnchorResolution(value: unknown, input: AnchorRequest): AnchorResolution {
  const result = object(value)
  requireValue(result.advertiserId === input.advertiserId && result.publicDouyinId === input.publicDouyinId
    && typeof result.awemeId === 'string' && /^[1-9]\d{0,29}$/.test(result.awemeId)
    && typeof result.awemeName === 'string' && !!result.awemeName.trim() && result.awemeName.length <= 200)
  return value as AnchorResolution
}
export function validateConfigurationSnapshot(value: unknown): ConfigurationSnapshot {
  const result = object(value)
  requireValue(result.schemaVersion === CONFIGURATION_SCHEMA_VERSION && hash(result.revision))
  requireValue(result.themeChangesEnabled === undefined || typeof result.themeChangesEnabled === 'boolean')
  requireValue(result.collectingProjectIds === undefined || (Array.isArray(result.collectingProjectIds)
    && result.collectingProjectIds.every(id => typeof id === 'string')))
  requireValue(typeof result.writeEnabled === 'boolean' && result.collectorExecution === 'DISABLED'
    && result.metricProfile === METRIC_PROFILE && typeof result.metricChangesEnabled === 'boolean')
  if (result.metricChangesEnabled) {
    requireValue(Array.isArray(result.metricCatalog) && result.metricCatalog.length === 28)
    const ids = new Set<string>()
    result.metricCatalog.forEach(value => {
      const metric = object(value)
      requireValue(typeof metric.id === 'string' && /^[a-z][a-z0-9_]*$/.test(metric.id)
        && typeof metric.label === 'string' && metric.label.trim().length > 0 && typeof metric.required === 'boolean')
      requireValue(!ids.has(metric.id) && metric.required === REQUIRED_METRICS.includes(metric.id))
      ids.add(metric.id)
    })
    requireValue(REQUIRED_METRICS.every(id => ids.has(id)))
    requireValue(Array.isArray(result.enabledMetrics) && result.enabledMetrics.every(id => typeof id === 'string'))
    requireValue(validateEnabledMetrics(result.enabledMetrics as string[], result.metricCatalog as ConfigurationMetric[]) === '')
  }
  requireValue(Array.isArray(result.projects))
  result.projects.forEach(value => {
    const project = object(value)
    requireValue(typeof project.id === 'string' && typeof project.name === 'string' && typeof project.advertiserId === 'string'
      && typeof project.enabled === 'boolean' && Array.isArray(project.douyinIds) && project.douyinIds.every(id => typeof id === 'string'))
  })
  requireValue(validateConfigurationProjects(result.projects as ConfigurationProject[]) === '')
  return value as ConfigurationSnapshot
}
export function validateConfigurationPreflight(value: unknown, expectedRevision: string): ConfigurationPreflight {
  const result = object(value)
  requireValue(result.status === 'READY' && result.expectedRevision === expectedRevision && hash(result.candidateHash))
  return value as ConfigurationPreflight
}
export function validateConfigurationApply(value: unknown, expectedRevision: string): ConfigurationApply {
  const result = object(value)
  requireValue(result.status === 'CURRENT_VERIFIED' && hash(result.revision)
    && result.previousRevision === expectedRevision && result.collectorExecution === 'DISABLED')
  return value as ConfigurationApply
}

/** No account configuration is cached or persisted in browser storage. */
export class HttpConfigurationAdapter implements ConfigurationAdapter {
  readonly evidenceKind = 'CURRENT_FACTS' as const
  constructor(private readonly fetcher: typeof fetch = sessionFetch) { }
  private async request(path: string, body?: unknown): Promise<unknown> {
    let response: Response
    try {
      response = await this.fetcher(`/api/operations/configuration${path}`, body === undefined
        ? { cache: 'no-store', headers: { Accept: 'application/json' } }
        : { method: 'POST', cache: 'no-store', headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-SCSJ-Config-Write': '1', ...csrfHeaders() }, body: JSON.stringify(body) })
    } catch { throw new Error('配置接口连接失败；若已发送保存，结果可能不确定。请重新读取配置，不要自动重试保存。') }
    if (response.status === 404 && body === undefined) throw new ConfigurationModeUnavailableError()
    if (!response.ok) {
      let code = ''; try { code = (await response.json()).code ?? '' } catch { /* do not expose raw responses */ }
      const messages: Record<string, string> = { CONFIG_COLLECTION_WINDOW: '08:00—12:00禁止切换采集配置', CONFIG_IDENTITY_MISMATCH: '抖音号身份不一致，不能关联历史', ANCHOR_IDENTITY_MISMATCH: '新旧账户不属于同一抖音号', ANCHOR_ACCOUNT_HAS_UNLINKED_DATA: '目标账户已有未关联数据，请先核查归属' }
      if (messages[code]) throw new Error(messages[code])
      const reason = ({ 400: '配置无效', 403: '本地配置写入未获允许', 409: '配置版本已变化', 503: '配置当前无法核验' } as Record<number, string>)[response.status] ?? '接口异常'
      throw new Error(`配置请求失败（HTTP ${response.status}：${reason}）。保存是否生效不能据此推断；请重新读取，不自动重试。`)
    }
    try { return await response.json() } catch { throw new Error(CONTRACT_ERROR) }
  }
  read(): Promise<ConfigurationSnapshot> {
    return this.request('').then(validateConfigurationSnapshot)
  }
  async resolveAnchor(input: AnchorRequest): Promise<AnchorResolution> {
    requireValue(/^[1-9]\d{0,29}$/.test(input.advertiserId) && /^[A-Za-z0-9_.-]{1,64}$/.test(input.publicDouyinId))
    return validateAnchorResolution(await this.request('/resolve-anchor', input), input)
  }
  async preflight(candidate: ConfigurationCandidate): Promise<ConfigurationPreflight> {
    requireValue(hash(candidate.expectedRevision) && validateConfigurationProjects(candidate.projects) === '')
    return validateConfigurationPreflight(await this.request('/preflight', candidate), candidate.expectedRevision)
  }
  async apply(candidate: ConfigurationCandidate & { candidateHash: string }): Promise<ConfigurationApply> {
    requireValue(hash(candidate.expectedRevision) && hash(candidate.candidateHash) && validateConfigurationProjects(candidate.projects) === '')
    return validateConfigurationApply(await this.request('/apply', candidate), candidate.expectedRevision)
  }
}
export const httpConfigurationAdapter = new HttpConfigurationAdapter()
