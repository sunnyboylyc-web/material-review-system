import type { DateRange } from '../domain/dashboard'
import type { OperationsDay, OperationsResult } from '../domain/operations'
import { OPERATIONS_SCHEMA_VERSION } from '../domain/operations'
import { enumerateIsoDates } from '../time/shanghai'

/** Fabricated metadata only: never reads business facts, accounts, or private files. */
export function operationsFixture(projectId: string, range: DateRange): OperationsResult {
  const days: OperationsDay[] = enumerateIsoDates(range.start, range.end).map((date, index) => ({
    date,
    state: index === 2 ? 'MISSING' : index === 1 ? 'EMPTY' : 'AVAILABLE',
    currentVersion: index === 2 ? null : {
      versionId: index + 10,
      sourceType: index === 1 ? 'MANUAL_HISTORY' : 'MCP_AUTO',
      contentHash: 'a'.repeat(64),
      rowCount: index === 1 ? 0 : 2,
      updatedAt: '2026-10-13T00:00:00Z',
    },
    previousVersionId: index === 3 ? 1 : null,
    isolatedVersionCount: index === 3 ? 2 : 0,
  }))
  return {
    schemaVersion: OPERATIONS_SCHEMA_VERSION,
    projectId,
    requestedAt: '2026-10-13T00:01:00Z',
    range: { ...range },
    validationScope: 'CURRENT_DB_FACT_HASHES',
    sourceFilesRechecked: false,
    collectionStatus: 'NOT_OBSERVED',
    observedRange: { first: range.start, last: range.end },
    summary: {
      queriedDays: days.length,
      availableDays: days.filter(day => day.state !== 'MISSING').length,
      emptyDays: days.filter(day => day.state === 'EMPTY').length,
      missingDays: days.filter(day => day.state === 'MISSING').length,
      isolatedDays: days.filter(day => day.isolatedVersionCount > 0).length,
    },
    days,
    missingDetails: [],
    missingDetailTotal: 0,
    missingDetailsTruncated: false,
  }
}
