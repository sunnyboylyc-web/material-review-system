import type { DateRange } from './dashboard'

export const OPERATIONS_SCHEMA_VERSION = 'operations-status-1.2.0'
export const OPERATIONS_MAX_RANGE_DAYS = 366

export interface OperationsVersion {
  versionId: number
  sourceType: 'MCP_AUTO' | 'MANUAL_HISTORY'
  contentHash: string
  rowCount: number
  updatedAt: string
}

export interface OperationsDay {
  date: string
  state: 'AVAILABLE' | 'EMPTY' | 'MISSING'
  currentVersion: OperationsVersion | null
  previousVersionId: number | null
  isolatedVersionCount: number
}

export interface OperationsResult {
  schemaVersion: 'operations-status-1.2.0'
  projectId: string
  requestedAt: string
  range: DateRange
  validationScope: 'CURRENT_DB_FACT_HASHES'
  sourceFilesRechecked: false
  collectionStatus: 'NOT_OBSERVED'
  observedRange: { first: string | null; last: string | null }
  summary: {
    queriedDays: number
    availableDays: number
    emptyDays: number
    missingDays: number
    isolatedDays: number
  }
  days: OperationsDay[]
  missingDetails: { date: string; materialId: string | null; field: string; reason: string; affectedMetrics: string[]; sourceVersionId: number | null }[]
  missingDetailTotal: number
  missingDetailsTruncated: boolean
}

export function isOperationsDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && value >= '1900-01-01' && value <= '2200-12-31'
    && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
}

export function validateOperationsRange(range: DateRange): string {
  if (!isOperationsDate(range.start) || !isOperationsDate(range.end)) {
    return '请选择有效起止日期（1900～2200 年）。'
  }
  if (range.start > range.end) return '开始日期不能晚于结束日期。'
  const days = (Date.parse(range.end) - Date.parse(range.start)) / 86400000 + 1
  return days > OPERATIONS_MAX_RANGE_DAYS ? '运维单次查询最多 366 天，请缩小日期范围。' : ''
}

export function operationsRequestMatches(result: OperationsResult, projectId: string, range: DateRange): boolean {
  return result.projectId === projectId && result.range.start === range.start && result.range.end === range.end
}
