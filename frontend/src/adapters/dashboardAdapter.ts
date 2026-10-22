import type { ProjectModule, ReviewQuery, ReviewResult, TopResult } from '../domain/dashboard'

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
  subscribeProjects?(listener: () => void): () => void
}
