import type { DateRange } from '../domain/dashboard'
import { sessionFetch } from '../domain/session'
import { OPERATIONS_SCHEMA_VERSION, isOperationsDate, operationsRequestMatches, validateOperationsRange } from '../domain/operations'
import type { OperationsResult } from '../domain/operations'
import { enumerateIsoDates } from '../time/shanghai'
import type { OperationsDataAdapter } from './operationsAdapter'

const CONTRACT_ERROR = '运维接口返回的数据结构、状态或查询参数不一致，未展示结果。'
type JsonObject = Record<string, unknown>
function requireValue(condition: unknown): asserts condition {
  if (!condition) throw new Error(CONTRACT_ERROR)
}
function object(value: unknown): JsonObject {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value))
  return value as JsonObject
}
function nonnegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0
}
function positiveInteger(value: unknown): value is number {
  return nonnegativeInteger(value) && value > 0
}
function instant(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value))
}

export function validateOperationsResponse(value: unknown, projectId: string, range: DateRange): OperationsResult {
  requireValue(validateOperationsRange(range) === '')
  const result = object(value)
  requireValue(result.schemaVersion === OPERATIONS_SCHEMA_VERSION && result.projectId === projectId && instant(result.requestedAt))
  const responseRange = object(result.range)
  requireValue(responseRange.start === range.start && responseRange.end === range.end)
  requireValue(result.validationScope === 'CURRENT_DB_FACT_HASHES' && result.sourceFilesRechecked === false && result.collectionStatus === 'NOT_OBSERVED')
  const observed = object(result.observedRange)
  requireValue((observed.first === null && observed.last === null)
    || (isOperationsDate(observed.first) && isOperationsDate(observed.last) && observed.first <= observed.last))
  const dates = enumerateIsoDates(range.start, range.end)
  requireValue(Array.isArray(result.days) && result.days.length === dates.length)
  let available = 0
  let empty = 0
  let isolated = 0
  result.days.forEach((value, index) => {
    const day = object(value)
    requireValue(day.date === dates[index] && ['AVAILABLE', 'EMPTY', 'MISSING'].includes(String(day.state)))
    requireValue(day.previousVersionId === null || positiveInteger(day.previousVersionId))
    requireValue(nonnegativeInteger(day.isolatedVersionCount))
    if (day.isolatedVersionCount > 0) isolated += 1
    if (day.state === 'MISSING') requireValue(day.currentVersion === null && day.previousVersionId === null)
    else {
      requireValue(isOperationsDate(observed.first) && isOperationsDate(observed.last)
        && String(day.date) >= observed.first && String(day.date) <= observed.last)
      const version = object(day.currentVersion)
      requireValue(positiveInteger(version.versionId) && day.previousVersionId !== version.versionId)
      requireValue(['MCP_AUTO', 'MANUAL_HISTORY'].includes(String(version.sourceType)))
      requireValue(typeof version.contentHash === 'string' && /^[a-f0-9]{64}$/.test(version.contentHash))
      requireValue(nonnegativeInteger(version.rowCount) && instant(version.updatedAt))
      requireValue((day.state === 'EMPTY') === (version.rowCount === 0))
      available += 1
      if (day.state === 'EMPTY') empty += 1
    }
  })
  const summary = object(result.summary)
  requireValue(Array.isArray(result.missingDetails))
  requireValue(nonnegativeInteger(result.missingDetailTotal) && result.missingDetailTotal >= result.missingDetails.length)
  requireValue(result.missingDetailsTruncated === (result.missingDetailTotal > result.missingDetails.length))
  result.missingDetails.forEach(value => {
    const entry = object(value)
    requireValue(isOperationsDate(entry.date) && dates.includes(entry.date))
    requireValue(entry.materialId === null || typeof entry.materialId === 'string')
    requireValue(typeof entry.field === 'string' && typeof entry.reason === 'string')
    requireValue(Array.isArray(entry.affectedMetrics) && entry.affectedMetrics.every(metric => typeof metric === 'string'))
    requireValue(entry.sourceVersionId === null || positiveInteger(entry.sourceVersionId))
  })
  requireValue(summary.queriedDays === dates.length && summary.availableDays === available
    && summary.emptyDays === empty && summary.missingDays === dates.length - available && summary.isolatedDays === isolated)
  const typed = value as OperationsResult
  requireValue(operationsRequestMatches(typed, projectId, range))
  return typed
}

export class HttpOperationsAdapter implements OperationsDataAdapter {
  readonly evidenceKind = 'CURRENT_FACTS' as const
  constructor(private readonly fetcher: typeof fetch = sessionFetch) { }

  async fetchDays(projectId: string, range: DateRange): Promise<OperationsResult> {
    const error = validateOperationsRange(range)
    if (error) throw new Error(error)
    if (!projectId) throw new Error('请选择已启用项目。')
    const query = new URLSearchParams({ start: range.start, end: range.end })
    let response: Response
    try {
      response = await this.fetcher(`/api/operations/projects/${encodeURIComponent(projectId)}/days?${query}`, {
        cache: 'no-store', headers: { Accept: 'application/json' },
      })
    } catch { throw new Error('本地运维接口连接失败，未展示旧数据；请检查本地服务。') }
    if (!response.ok) {
      const reasons: Record<number, string> = {
        400: '查询日期或参数无效', 404: '项目未启用', 503: '当前数据状态暂不可验证',
      }
      throw new Error(`运维查询失败（HTTP ${response.status}：${reasons[response.status] ?? '接口异常'}），未展示旧数据。`)
    }
    let value: unknown
    try { value = await response.json() } catch { throw new Error(CONTRACT_ERROR) }
    return validateOperationsResponse(value, projectId, range)
  }
}

export const httpOperationsAdapter = new HttpOperationsAdapter()
