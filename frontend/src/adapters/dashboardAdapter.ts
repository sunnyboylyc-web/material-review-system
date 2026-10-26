import type { MaterialTypeQuery, MaterialTypeReview, ProjectModule, ReviewQuery, ReviewResult, TopResult } from '../domain/dashboard'

export interface DashboardDataAdapter {
  fetchFreshness(projectId: string): Promise<{schemaVersion: 'freshness-1.0.0'; projectId: string; latestMcpStatDate: string | null}>
  readonly evidenceKind?: 'CURRENT_FACTS' | 'SYNTHETIC'
  listProjects(): Promise<ProjectModule[]>
  fetchReview(
    projectId: string,
    query: ReviewQuery,
    topComparisonDisplayLimit: number,
  ): Promise<ReviewResult>
  fetchTop(projectId: string, selectedDate: string, displayLimit: number): Promise<TopResult>
  fetchMaterialTypes?(projectId: string, query: MaterialTypeQuery): Promise<MaterialTypeReview>
  subscribeProjects?(listener: () => void): () => void
}
