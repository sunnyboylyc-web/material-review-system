import type { DashboardDataAdapter } from './dashboardAdapter'
import type {
  Coverage,
  MetricGroup,
  NumericResult,
  ProjectModule,
  ReviewQuery,
  ReviewResult,
  TopDayResult,
  TopResult,
} from '../domain/dashboard'
import { DASHBOARD_SCHEMA_VERSION, MAX_DISPLAY_ROWS } from '../domain/dashboard'
import { enumerateIsoDates, shiftIsoDate } from '../time/shanghai'

const INITIAL_PROJECTS: ProjectModule[] = [
  {
    id: 'sample-project',
    accountId: 'SYNTHETIC_ACCOUNT_DEMO',
    name: '示例项目',
    enabled: true,
  },
]

function cloneProjects(projects: ProjectModule[]): ProjectModule[] {
  return projects.map((project) => ({ ...project }))
}

function coverageFor(dates: string[], missingDates: string[]): Coverage {
  const missing = new Set(missingDates)
  const actualMissing = dates.filter((date) => missing.has(date))
  return {
    state: actualMissing.length === 0 ? 'COMPLETE' : 'PARTIAL',
    expectedDays: dates.length,
    coveredDays: dates.length - actualMissing.length,
    missingDates: actualMissing,
  }
}

const valid = (value: number): NumericResult => ({ status: 'VALID', value, partial: false, partialReasons: [] })
const unknown = (): NumericResult => ({ status: 'UNKNOWN_INPUT', value: null, partial: false, partialReasons: [] })
function syntheticGroups(): MetricGroup[] {
  return (['overall', 'new', 'historical', 'aigc'] as const).map(key => ({ key, metrics: {
    spend: valid(key === 'aigc' ? 0 : 120), materialCount: valid(key === 'aigc' ? 0 : 1), ctr: valid(0.125), cvr: unknown(),
    spendShare: valid(1), roi: valid(2.5), cpc: valid(1.2), cpm: { status: 'ZERO_DENOMINATOR', value: null, partial: false, partialReasons: [] },
  } }))
}

export class SyntheticMockAdapter implements DashboardDataAdapter {
  readonly evidenceKind = 'SYNTHETIC' as const
  private projects: ProjectModule[]
  private readonly projectListeners = new Set<() => void>()

  constructor(projects: ProjectModule[] = INITIAL_PROJECTS) {
    this.projects = cloneProjects(projects)
  }

  async listProjects(): Promise<ProjectModule[]> {
    return cloneProjects(this.projects.filter((project) => project.enabled))
  }
  async fetchFreshness(projectId: string) {
    this.requireProject(projectId)
    return {schemaVersion: 'freshness-1.0.0' as const, projectId, latestMcpStatDate: null}
  }

  async fetchReview(
    projectId: string,
    query: ReviewQuery,
    topComparisonDisplayLimit: number,
  ): Promise<ReviewResult> {
    this.requireProject(projectId)
    if (!Number.isInteger(topComparisonDisplayLimit) || topComparisonDisplayLimit < 1 || topComparisonDisplayLimit > MAX_DISPLAY_ROWS) {
      throw new Error('显示数量须为 1～200 的整数。')
    }
    const currentDates = enumerateIsoDates(query.current.start, query.current.end)
    const previousDates = enumerateIsoDates(query.previous.start, query.previous.end)
    const previousMissing = previousDates.length > 0 ? [previousDates.at(-1)!] : []

    return {
      schemaVersion: DASHBOARD_SCHEMA_VERSION,
      projectId,
      evidenceKind: 'SYNTHETIC',
      requestedAt: new Date().toISOString(),
      topComparisonDisplayLimit,
      metricsState: 'PARTIAL',
      snapshotFingerprint: 'SYNTHETIC_NOT_REAL_EVIDENCE',
      comparisons: syntheticGroups().map(group => ({ ...group, metrics: Object.fromEntries(
        Object.keys(group.metrics).map(key => [key, valid(1.2)]),
      ) as MetricGroup['metrics'] })),
      newMaterials: [],
      cohortMaterials: { new: [], historical: [], aigc: [] },
      topMaterials: [{ rank: 1, materialId: '99999999999999990001', materialName: '合成素材（非业务数据）',
        ctr: valid(0.125), cvr: unknown(), cpm: valid(20),
        dailySpend: currentDates.map(date => ({ date, spend: valid(date === query.current.start ? 120 : 0) })),
        creationDate: { status: 'PENDING', date: null, reason: 'MISSING_CREATION_DATE' },
        currentSpend: valid(120), previousSpend: valid(100), periodChange: valid(0.2),
        spendShare: valid(1), roi: valid(2.5),
        firstSpend: { status: 'PENDING', date: null, earliestObservedDate: query.current.start } }],
      periods: [
        {
          key: 'CURRENT',
          label: '本期',
          range: { ...query.current },
          coverage: coverageFor(currentDates, []),
          groups: syntheticGroups(),
          pendingMaterialCount: 1,
        },
        {
          key: 'PREVIOUS',
          label: '上期',
          range: { ...query.previous },
          coverage: coverageFor(previousDates, previousMissing),
          groups: syntheticGroups(),
          pendingMaterialCount: 0,
        },
      ],
    }
  }

  async fetchTop(projectId: string, selectedDate: string, displayLimit: number): Promise<TopResult> {
    this.requireProject(projectId)
    if (!Number.isInteger(displayLimit) || displayLimit < 1 || displayLimit > MAX_DISPLAY_ROWS) {
      throw new Error('显示数量须为 1～200 的整数。')
    }
    const dates = Array.from({ length: 7 }, (_, index) => shiftIsoDate(selectedDate, -index))
    const missingDate = dates[1]
    const days: TopDayResult[] = dates.map((date) => ({
      date,
      availability: date === missingDate ? 'MISSING' : 'AVAILABLE',
    }))

    return {
      schemaVersion: DASHBOARD_SCHEMA_VERSION,
      projectId,
      evidenceKind: 'SYNTHETIC',
      requestedAt: new Date().toISOString(),
      selectedDate,
      displayLimit,
      coverage: coverageFor(dates, [missingDate]),
      days,
      rankingState: 'READY',
      recent3Coverage: coverageFor(dates.slice(0, 3), [missingDate]),
      annualCoverage: coverageFor(enumerateIsoDates(`${selectedDate.slice(0, 4)}-01-01`, selectedDate), [missingDate]),
      snapshotFingerprint: 'SYNTHETIC_NOT_REAL_EVIDENCE',
      rows: [{ rank: 1, materialId: '99999999999999990001', materialName: '合成素材（非业务数据）',
        dailySpend: dates.map(date => ({ date, spend: date === missingDate ? unknown() : valid(date === selectedDate ? 120 : 0) })),
        trend: { status: 'UNKNOWN_INPUT', label: null, lastGap: null, previousGap: null },
        annualRoi: unknown(), windowRoi: valid(2.5), windowSpend: valid(120), dayRoi: valid(2.5),
        creationDate: { status: 'PENDING', date: null, reason: 'MISSING_CREATION_DATE' },
        firstSpend: { status: 'PENDING', date: null, earliestObservedDate: selectedDate },
        recent3Orders: valid(3), recent3Spend: valid(120), recent3Roi: valid(2.5),
        lifetimeSpend: unknown(), lifetimeRoi: unknown(), lifetimeCoverage: null, recent3Cpc: valid(1.2),
        recent3Impressions: valid(1000), recent3Clicks: valid(100), recent3Ctr: valid(0.1), recent3Cvr: valid(0.03),
        recent3Cpm: { status: 'ZERO_DENOMINATOR', value: null, partial: false, partialReasons: [] } }],
    }
  }

  subscribeProjects(listener: () => void): () => void {
    this.projectListeners.add(listener)
    return () => this.projectListeners.delete(listener)
  }

  replaceProjects(projects: ProjectModule[]): void {
    this.projects = cloneProjects(projects)
    this.projectListeners.forEach((listener) => listener())
  }

  private requireProject(projectId: string): void {
    if (!this.projects.some((project) => project.id === projectId && project.enabled)) {
      throw new Error(`Unknown synthetic project: ${projectId}`)
    }
  }
}

export const syntheticMockAdapter = new SyntheticMockAdapter()
