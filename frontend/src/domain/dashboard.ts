import type { ProjectCategory } from './projectCategory'
/** JSON wire model: contracts/dashboard-query-v1.json and DashboardModels.java. */
export const DASHBOARD_SCHEMA_VERSION = 'dashboard-query-1.7.0'
export const MAX_DISPLAY_ROWS = 200
export type EvidenceKind = 'SYNTHETIC' | 'CURRENT_FACTS'
export type DayAvailability = 'AVAILABLE' | 'MISSING'
export type CoverageState = 'COMPLETE' | 'PARTIAL'

export interface ProjectModule {
  category?: ProjectCategory
  id: string
  accountId?: string
  name: string
  enabled: boolean
}

export interface DateRange {
  start: string
  end: string
}

export interface ReviewQuery {
  current: DateRange
  previous: DateRange
}

export interface Coverage {
  state: CoverageState
  expectedDays: number
  coveredDays: number
  missingDates: string[]
}

export interface ReviewPeriodResult {
  key: 'CURRENT' | 'PREVIOUS'
  label: string
  range: DateRange
  coverage: Coverage
  groups: MetricGroup[]
  pendingMaterialCount: number
}

export type NumericStatus = 'VALID' | 'UNKNOWN_INPUT' | 'ZERO_DENOMINATOR'
export interface NumericResult {
  status: NumericStatus
  value: number | null
  partial?: boolean
  partialReasons?: string[]
}
export type MetricKey = 'spend' | 'materialCount' | 'ctr' | 'cvr' | 'spendShare' | 'roi' | 'cpm' | 'cpc'
export type GroupKey = 'overall' | 'new' | 'historical' | 'aigc'
export interface MetricGroup {
  key: GroupKey
  metrics: Record<MetricKey, NumericResult>
}
export interface FirstSpend {
  status: 'VERIFIED' | 'PENDING'
  date: string | null
  earliestObservedDate: string | null
}
export interface MaterialRow {
  rank: number
  materialId: string
  materialName: string | null
  currentSpend: NumericResult
  previousSpend: NumericResult
  periodChange: NumericResult
  firstSpend: FirstSpend
  creationDate: CreationDate
  spendShare: NumericResult
  roi: NumericResult
}

export interface CreationDate {
  status: 'VERIFIED' | 'PENDING' | 'NOT_APPLICABLE'
  date: string | null
  reason: null | 'MISSING_CREATION_DATE' | 'CONFLICTING_CREATION_DATE' | 'CREATION_AFTER_OBSERVATION'
}

export interface ReviewResult {
  materialTypes?: MaterialTypeReview | null
  schemaVersion: string
  projectId: string
  evidenceKind: EvidenceKind
  requestedAt: string
  topComparisonDisplayLimit: number
  periods: ReviewPeriodResult[]
  metricsState: 'READY' | 'PARTIAL' | 'UNAVAILABLE'
  comparisons: MetricGroup[]
  newMaterials: MaterialRow[]
  topMaterials: ReviewMaterialRow[]
  cohortMaterials: { new: ReviewMaterialRow[]; historical: ReviewMaterialRow[]; aigc: ReviewMaterialRow[] }
  snapshotFingerprint: string
}

export type MaterialTypeKey = 'original' | 'mixed' | 'other'
export interface MaterialTypeGroup {
  key: MaterialTypeKey
  metrics: Record<MetricKey, NumericResult>
}
export interface MaterialTypePeriod {
  key: 'CURRENT' | 'PREVIOUS'
  range: DateRange
  coverage: Coverage
  groups: MaterialTypeGroup[]
  excludedUndatedMaterials: number
  unresolvedDateMaterials: number
}
export interface MaterialTypeRow {
  rank: number
  materialId: string
  materialName: string | null
  creationDate: CreationDate
  metrics: Record<MetricKey, NumericResult>
  previousSpend: NumericResult
  dailySpend: { date: string; spend: NumericResult }[]
}
export interface MaterialTypeReview {
  schemaVersion: 'material-type-review-1.0.0'
  periods: MaterialTypePeriod[]
  comparisons: MaterialTypeGroup[]
  materials: Record<MaterialTypeKey, MaterialTypeRow[]>
}

export interface ReviewMaterialRow extends MaterialRow {
  ctr: NumericResult
  cvr: NumericResult
  cpm: NumericResult
  dailySpend: { date: string; spend: NumericResult }[]
}

export interface TopDayResult {
  date: string
  availability: DayAvailability
}

export interface TopResult {
  schemaVersion: string
  projectId: string
  evidenceKind: EvidenceKind
  requestedAt: string
  selectedDate: string
  displayLimit: number
  coverage: Coverage
  days: TopDayResult[]
  rankingState: 'READY' | 'PARTIAL' | 'UNAVAILABLE' | 'UNKNOWN_INPUT'
  recent3Coverage: Coverage
  annualCoverage: Coverage
  rows: TopRow[]
  snapshotFingerprint: string
}

export interface TopRow {
  rank: number
  materialId: string
  materialName: string | null
  dailySpend: { date: string; spend: NumericResult }[]
  trend: {
    status: 'VALID' | 'UNKNOWN_INPUT'
    label: string | null
    lastGap: number | null
    previousGap: number | null
  }
  annualRoi: NumericResult
  windowRoi: NumericResult
  windowSpend: NumericResult
  dayRoi: NumericResult
  firstSpend: FirstSpend
  creationDate: CreationDate
  recent3Orders: NumericResult
  recent3Spend: NumericResult
  recent3Roi: NumericResult
  lifetimeSpend: NumericResult
  lifetimeRoi: NumericResult
  lifetimeCoverage: Coverage | null
  recent3Cpc: NumericResult
  recent3Cpm: NumericResult
  recent3Impressions: NumericResult
  recent3Clicks: NumericResult
  recent3Ctr: NumericResult
  recent3Cvr: NumericResult
}
