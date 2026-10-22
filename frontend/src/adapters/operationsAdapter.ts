import type { DateRange } from '../domain/dashboard'
import type { OperationsResult } from '../domain/operations'

/** No mutating or collector methods exist in this maintenance unit. */
export interface OperationsDataAdapter {
  readonly evidenceKind?: 'CURRENT_FACTS' | 'SYNTHETIC'
  fetchDays(projectId: string, range: DateRange): Promise<OperationsResult>
}
