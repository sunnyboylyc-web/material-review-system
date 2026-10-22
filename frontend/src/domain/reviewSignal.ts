import type { ReviewResult } from './dashboard'

function incomplete(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  if (record.status === 'UNKNOWN_INPUT' || record.partial === true) return true
  return Object.values(record).some(incomplete)
}

export function reviewSignal(result: ReviewResult | null, loading = false): 'loading' | 'red' | 'green' {
  if (loading || !result) return 'loading'
  const rows = [...result.topMaterials, ...Object.values(result.cohortMaterials).flat()]
  // An incomparable change (for example a zero prior denominator) is not missing evidence.
  const rowIncomplete = rows.some(({ periodChange, ...inputs }) => incomplete(inputs))
  const periodIncomplete = result.periods.some(period =>
    period.coverage.state !== 'COMPLETE' || period.pendingMaterialCount > 0 || incomplete(period.groups),
  )
  return result.metricsState !== 'READY' || periodIncomplete || rowIncomplete ? 'red' : 'green'
}
