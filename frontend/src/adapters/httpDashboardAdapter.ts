import type { DashboardDataAdapter } from './dashboardAdapter'
import { sessionFetch } from '../domain/session'
import { DASHBOARD_SCHEMA_VERSION, MAX_DISPLAY_ROWS } from '../domain/dashboard'
import type { MaterialTypeQuery, MaterialTypeReview, ProjectModule, ReviewMaterials, ReviewQuery, ReviewResult, TopResult } from '../domain/dashboard'
import { isProjectCategory } from '../domain/projectCategory'
import { enumerateIsoDates, shiftIsoDate } from '../time/shanghai'

const INVALID_RESPONSE = '接口返回的数据结构或状态与当前契约不一致，未展示结果。'
type JsonObject = Record<string, unknown>
function requireValue(condition: unknown): asserts condition {
  if (!condition) throw new Error(INVALID_RESPONSE)
}
function object(value: unknown): JsonObject {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value))
  return value as JsonObject
}
function array(value: unknown): unknown[] {
  requireValue(Array.isArray(value))
  return value
}
function string(value: unknown): value is string { return typeof value === 'string' && value.length > 0 }
function isoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
}
function integer(value: unknown): value is number { return Number.isInteger(value) && Number(value) >= 0 }
function numeric(value: unknown): void {
  const item = object(value)
  requireValue(typeof item.partial === 'boolean' && Array.isArray(item.partialReasons) && item.partialReasons.every(string))
  if (item.status === 'VALID') requireValue(typeof item.value === 'number' && Number.isFinite(item.value))
  else requireValue(['UNKNOWN_INPUT', 'ZERO_DENOMINATOR'].includes(String(item.status)) && item.value === null)
}
function coverage(value: unknown): void {
  const item = object(value)
  requireValue(['COMPLETE', 'PARTIAL'].includes(String(item.state)))
  requireValue(integer(item.expectedDays) && item.expectedDays > 0 && integer(item.coveredDays))
  const missing = array(item.missingDates)
  requireValue(missing.every(isoDate) && new Set(missing).size === missing.length)
  requireValue(item.coveredDays + missing.length === item.expectedDays)
  requireValue((item.state === 'COMPLETE') === (missing.length === 0))
}
function firstSpend(value: unknown): void {
  const item = object(value)
  requireValue(item.earliestObservedDate === null || isoDate(item.earliestObservedDate))
  if (item.status === 'VERIFIED') requireValue(isoDate(item.date))
  else requireValue(item.status === 'PENDING' && item.date === null)
}
function creationDate(value: unknown): void {
  const item = object(value)
  if (item.status === 'VERIFIED') requireValue(isoDate(item.date) && item.reason === null)
  else if (item.status === 'NOT_APPLICABLE') requireValue(item.date === null && item.reason === null)
  else requireValue(item.status === 'PENDING' && item.date === null && ['MISSING_CREATION_DATE','CONFLICTING_CREATION_DATE','CREATION_AFTER_OBSERVATION'].includes(String(item.reason)))
}
function groups(value: unknown): void {
  const entries = array(value).map(object)
  requireValue(entries.length === 4 && ['overall', 'new', 'historical', 'aigc'].every(key => entries.some(item => item.key === key)))
  entries.forEach(entry => {
    const metrics = object(entry.metrics)
    for (const key of ['spend', 'materialCount', 'ctr', 'cvr', 'spendShare', 'roi', 'cpm', 'cpc']) numeric(metrics[key])
  })
}
function rankedRows(value: unknown, limit: number): JsonObject[] {
  const entries = array(value).map(object)
  requireValue(entries.length <= limit)
  requireValue(new Set(entries.map(item => item.materialId)).size === entries.length)
  entries.forEach((entry, index) => {
    requireValue(entry.rank === index + 1 && string(entry.materialId))
    requireValue(entry.materialName === null || typeof entry.materialName === 'string')
    firstSpend(entry.firstSpend)
    creationDate(entry.creationDate)
    requireValue((object(entry.creationDate).status === 'NOT_APPLICABLE') === (entry.materialId === '-2'))
  })
  return entries
}
function header(value: unknown): JsonObject {
  const item = object(value)
  requireValue(item.schemaVersion === DASHBOARD_SCHEMA_VERSION && item.evidenceKind === 'CURRENT_FACTS')
  requireValue(string(item.projectId) && string(item.requestedAt) && string(item.snapshotFingerprint))
  return item
}
export function validateReviewResponse(value: unknown): ReviewResult {
  const item = header(value)
  requireValue(integer(item.topComparisonDisplayLimit) && item.topComparisonDisplayLimit >= 1 && item.topComparisonDisplayLimit <= MAX_DISPLAY_ROWS)
  requireValue(['READY', 'PARTIAL', 'UNAVAILABLE'].includes(String(item.metricsState)))
  const periods = array(item.periods).map(object)
  requireValue(periods.length === 2 && ['CURRENT', 'PREVIOUS'].every(key => periods.some(period => period.key === key)))
  periods.forEach(period => {
    const range = object(period.range)
    requireValue(isoDate(range.start) && isoDate(range.end) && range.start <= range.end)
    requireValue(string(period.label) && integer(period.pendingMaterialCount))
    coverage(period.coverage)
    groups(period.groups)
  })
  groups(item.comparisons)
  const cohorts = object(item.cohortMaterials)
  const current = object(periods.find(period => period.key === 'CURRENT')!.range)
  const dates = enumerateIsoDates(current.start as string, current.end as string)
  for (const key of ['new', 'historical', 'aigc']) {
    rankedRows(cohorts[key], Number.MAX_SAFE_INTEGER).forEach(row => {
      for (const metric of ['currentSpend', 'previousSpend', 'periodChange', 'spendShare', 'roi', 'ctr', 'cvr', 'cpm']) numeric(row[metric])
      const daily = array(row.dailySpend).map(object)
      requireValue(daily.length === dates.length)
      daily.forEach((day, index) => { requireValue(day.date === dates[index]); numeric(day.spend) })
    })
  }
  for (const [key, limit] of [['newMaterials', 10], ['topMaterials', item.topComparisonDisplayLimit]] as const) {
    rankedRows(item[key], limit).forEach(row => {
      for (const metric of ['currentSpend', 'previousSpend', 'periodChange', 'spendShare', 'roi']) numeric(row[metric])
      if (key === 'topMaterials') {
        for (const metric of ['ctr','cvr','cpm']) numeric(row[metric])
        const daily = array(row.dailySpend).map(object)
        requireValue(daily.length === dates.length)
        daily.forEach((day,index) => { requireValue(day.date === dates[index]); numeric(day.spend) })
      }
    })
  }
  if (item.materialTypes !== undefined && item.materialTypes !== null) validateMaterialTypes(item.materialTypes, periods)
  return value as ReviewResult
}

function validateMaterialTypes(value: unknown, expectedPeriods: JsonObject[], query?: MaterialTypeQuery): void {
  const result = object(value), keys = ['original','mixed','other']
  requireValue(query ? result.schemaVersion === 'material-type-review-2.0.0'
    : ['material-type-review-1.0.0','material-type-review-2.0.0'].includes(String(result.schemaVersion)))
  function typeGroups(value: unknown): void {
    const items = array(value).map(object)
    requireValue(items.length === 3 && keys.every(key => items.some(group => group.key === key)))
    for (const group of items) for (const key of ['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare']) numeric(object(group.metrics)[key])
  }
  const periods = array(result.periods).map(object)
  requireValue(periods.length === 2 && ['CURRENT','PREVIOUS'].every(key => periods.some(p => p.key === key)))
  periods.forEach(p => {
    const expected = object(expectedPeriods.find(e => e.key === p.key)!.range), range = object(p.range)
    requireValue(range.start === expected.start && range.end === expected.end)
    const test = p.testRange == null ? range : object(p.testRange)
    const expectedTest = query ? p.key === 'CURRENT' ? query.currentTest : query.previousTest : expected
    requireValue(test.start === expectedTest.start && test.end === expectedTest.end)
    coverage(p.coverage); typeGroups(p.groups)
    requireValue(integer(p.excludedUndatedMaterials) && integer(p.unresolvedDateMaterials))
  })
  typeGroups(result.comparisons)
  const current = object(periods.find(p => p.key === 'CURRENT')!.range)
  const currentPeriod = periods.find(p => p.key === 'CURRENT')!
  const currentTest = currentPeriod.testRange == null ? current : object(currentPeriod.testRange)
  const dates = enumerateIsoDates(current.start as string,current.end as string)
  const seen = new Set<string>(), materials = object(result.materials)
  for (const type of keys) array(materials[type]).map(object).forEach((row,index) => {
    requireValue(row.rank === index+1 && string(row.materialId) && !seen.has(row.materialId))
    seen.add(row.materialId as string)
    requireValue(row.materialName === null || typeof row.materialName === 'string')
    const name = String(row.materialName ?? '')
    requireValue(type === (name.includes('原创') ? 'original' : name.includes('混剪') ? 'mixed' : 'other'))
    creationDate(row.creationDate)
    const creation = object(row.creationDate)
    requireValue(creation.status === 'VERIFIED' && String(creation.date) >= String(currentTest.start) && String(creation.date) <= String(currentTest.end) && row.materialId !== '-2')
    for (const key of ['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare']) numeric(object(row.metrics)[key])
    numeric(row.previousSpend)
    const days = array(row.dailySpend).map(object)
    requireValue(days.length === dates.length)
    days.forEach((day,i) => { requireValue(day.date === dates[i]); numeric(day.spend) })
  })
  if (result.schemaVersion === 'material-type-review-2.0.0') {
    const ai = object(result.ai)
    const aiKeys = ['ai','business','department']
    const aiGroups = (value: unknown) => {
      const entries = array(value).map(object)
      requireValue(entries.length === 3 && aiKeys.every(key => entries.some(group => group.key === key)))
      for (const group of entries) for (const metric of ['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare']) numeric(object(group.metrics)[metric])
    }
    const aiPeriods = array(ai.periods).map(object)
    requireValue(aiPeriods.length === 2 && ['CURRENT','PREVIOUS'].every(key => aiPeriods.some(period => period.key === key)))
    for (const period of aiPeriods) {
      const expected = periods.find(p => p.key === period.key)!
      const actualRange = object(period.range), expectedRange = object(expected.range)
      const actualTest = object(period.testRange ?? period.range), expectedTest = object(expected.testRange ?? expected.range)
      requireValue(actualRange.start === expectedRange.start && actualRange.end === expectedRange.end
        && actualTest.start === expectedTest.start && actualTest.end === expectedTest.end)
      coverage(period.coverage); aiGroups(period.groups)
    }
    aiGroups(ai.comparisons)
    if (ai.materials !== undefined && ai.materials !== null) {
      const details = object(ai.materials)
      requireValue(Object.keys(details).length === 2)
      for (const key of ['business','department']) {
        const ids = new Set<string>()
        array(details[key]).map(object).forEach((row,index) => {
          requireValue(row.rank === index+1 && string(row.materialId) && !ids.has(row.materialId) && row.materialId !== '-2')
          ids.add(row.materialId as string)
          requireValue(typeof row.materialName === 'string' && /AI(?!R)/i.test(row.materialName)
            && row.materialName.includes(key === 'business' ? '商单' : '部门'))
          creationDate(row.creationDate)
          const creation = object(row.creationDate)
          requireValue(creation.status === 'VERIFIED' && String(creation.date) >= String(currentTest.start) && String(creation.date) <= String(currentTest.end))
          for (const field of ['spend','roi','materialCount','ctr','cvr','cpm','cpc','spendShare']) numeric(object(row.metrics)[field])
          numeric(row.previousSpend)
          const days = array(row.dailySpend).map(object)
          requireValue(days.length === dates.length)
          days.forEach((day,index) => { requireValue(day.date === dates[index]); numeric(day.spend) })
        })
      }
    }
  }
}
export function validateMaterialTypeResponse(value: unknown, projectId: string, query: MaterialTypeQuery): MaterialTypeReview {
  const item = object(value)
  requireValue(item.schemaVersion === 'material-type-analysis-1.0.0' && item.projectId === projectId
    && item.evidenceKind === 'CURRENT_FACTS' && string(item.requestedAt) && string(item.snapshotFingerprint))
  validateMaterialTypes(item.analysis, [{ key: 'CURRENT', range: query.current }, { key: 'PREVIOUS', range: query.previous }], query)
  return item.analysis as MaterialTypeReview
}

export function validateReviewMaterials(value: unknown, projectId: string, query: ReviewQuery): ReviewMaterials {
  const item=object(value)
  requireValue(item.schemaVersion==='review-materials-1.0.0' && item.projectId===projectId && item.evidenceKind==='CURRENT_FACTS' && string(item.snapshotFingerprint))
  for(const key of ['current','previous'] as const){const range=object(item[key]);requireValue(range.start===query[key].start && range.end===query[key].end)}
  const dates=enumerateIsoDates(query.current.start,query.current.end)
  rankedRows(item.rows,Number.MAX_SAFE_INTEGER).forEach(row=>{
    for(const key of ['currentSpend','previousSpend','periodChange','spendShare','roi','ctr','cvr','cpm'])numeric(row[key])
    const days=array(row.dailySpend).map(object);requireValue(days.length===dates.length)
    days.forEach((day,index)=>{requireValue(day.date===dates[index]);numeric(day.spend)})
  })
  return value as ReviewMaterials
}

export function validateTopResponse(value: unknown): TopResult {
  const item = header(value)
  requireValue(isoDate(item.selectedDate))
  requireValue(integer(item.displayLimit) && item.displayLimit >= 1 && item.displayLimit <= MAX_DISPLAY_ROWS)
  requireValue(['READY', 'PARTIAL', 'UNAVAILABLE', 'UNKNOWN_INPUT'].includes(String(item.rankingState)))
  coverage(item.coverage)
  coverage(item.recent3Coverage)
  coverage(item.annualCoverage)
  const expectedDates = Array.from({ length: 7 }, (_, index) => shiftIsoDate(item.selectedDate as string, -index))
  const days = array(item.days).map(object)
  requireValue(days.length === 7)
  days.forEach((day, index) => requireValue(day.date === expectedDates[index] && ['AVAILABLE', 'MISSING'].includes(String(day.availability))))
  const rows = rankedRows(item.rows, item.displayLimit)
  requireValue(['READY', 'PARTIAL'].includes(String(item.rankingState)) || rows.length === 0)
  rows.forEach(row => {
    const daily = array(row.dailySpend).map(object)
    requireValue(daily.length === 7)
    daily.forEach((day, index) => { requireValue(day.date === expectedDates[index]); numeric(day.spend) })
    for (const metric of ['annualRoi', 'windowRoi', 'windowSpend', 'dayRoi', 'recent3Orders', 'recent3Spend', 'recent3Roi', 'lifetimeSpend', 'lifetimeRoi', 'recent3Cpc', 'recent3Cpm', 'recent3Impressions', 'recent3Clicks', 'recent3Ctr', 'recent3Cvr']) numeric(row[metric])
    if (row.lifetimeCoverage !== null) coverage(row.lifetimeCoverage)
    const trend = object(row.trend)
    if (trend.status === 'VALID') {
      requireValue(['明显上升', '阶梯下降', '消耗波动', '阶梯上升'].includes(String(trend.label)))
      requireValue(typeof trend.lastGap === 'number' && Number.isFinite(trend.lastGap) && typeof trend.previousGap === 'number' && Number.isFinite(trend.previousGap))
    } else requireValue(trend.status === 'UNKNOWN_INPUT' && trend.label === null && trend.lastGap === null && trend.previousGap === null)
  })
  return value as TopResult
}

/** Same-origin local API only. A failed request never falls back to synthetic facts. */
export class HttpDashboardAdapter implements DashboardDataAdapter {
  readonly evidenceKind = 'CURRENT_FACTS' as const
  constructor(private readonly fetcher: typeof fetch = sessionFetch) { }

  private async get(path: string): Promise<unknown> {
    let response: Response
    try { response = await this.fetcher(path, { cache: 'no-store', headers: { Accept: 'application/json' } }) }
    catch { throw new Error('本地数据接口连接失败，请联系运维检查服务；没有切换为演示数据。') }
    if (!response.ok) throw new Error(`数据接口请求失败（HTTP ${response.status}），请联系运维；未保留旧结果。`)
    try { return await response.json() }
    catch { throw new Error(INVALID_RESPONSE) }
  }
  async listProjects(): Promise<ProjectModule[]> {
    const projects = array(await this.get('/api/projects')).map(object)
    requireValue(new Set(projects.map(project => project.id)).size === projects.length)
    projects.forEach(project => requireValue(string(project.id) && string(project.name) && project.enabled === true
      && (project.category === undefined || isProjectCategory(project.category))))
    return projects as unknown as ProjectModule[]
  }
  async fetchFreshness(projectId: string) {
    const value = object(await this.get(`/api/projects/${encodeURIComponent(projectId)}/freshness`))
    requireValue(value.schemaVersion === 'freshness-1.0.0' && value.projectId === projectId)
    requireValue(value.latestMcpStatDate === null || isoDate(value.latestMcpStatDate))
    return {schemaVersion: 'freshness-1.0.0' as const, projectId, latestMcpStatDate: value.latestMcpStatDate as string | null}
  }
  async fetchReview(projectId: string, query: ReviewQuery, limit: number): Promise<ReviewResult> {
    const params = new URLSearchParams({ currentStart: query.current.start, currentEnd: query.current.end,
      previousStart: query.previous.start, previousEnd: query.previous.end, limit: String(limit) })
    return validateReviewResponse(await this.get(`/api/projects/${encodeURIComponent(projectId)}/review?${params}`))
  }
  async fetchTop(projectId: string, date: string, limit: number): Promise<TopResult> {
    const params = new URLSearchParams({ date, limit: String(limit) })
    return validateTopResponse(await this.get(`/api/projects/${encodeURIComponent(projectId)}/top?${params}`))
  }
  async fetchMaterialTypes(projectId: string, query: MaterialTypeQuery): Promise<MaterialTypeReview> {
    const params = new URLSearchParams({ currentStart: query.current.start, currentEnd: query.current.end,
      currentTestStart: query.currentTest.start, currentTestEnd: query.currentTest.end,
      previousStart: query.previous.start, previousEnd: query.previous.end,
      previousTestStart: query.previousTest.start, previousTestEnd: query.previousTest.end })
    return validateMaterialTypeResponse(await this.get(`/api/projects/${encodeURIComponent(projectId)}/material-types?${params}`), projectId, query)
  }
  async fetchReviewMaterials(projectId: string, query: ReviewQuery): Promise<ReviewMaterials> {
    const params=new URLSearchParams({currentStart:query.current.start,currentEnd:query.current.end,previousStart:query.previous.start,previousEnd:query.previous.end})
    return validateReviewMaterials(await this.get(`/api/projects/${encodeURIComponent(projectId)}/review-materials?${params}`),projectId,query)
  }
}

export const httpDashboardAdapter = new HttpDashboardAdapter()
