import { sessionFetch } from '../domain/session'

export interface CollectionProjectStatus {
  projectId: string; projectName: string; statDate: string; status: string; updatedAt: string
  errorCode?: string; retryNotBefore?: string; rows?: number; pendingCount?: number
}
export interface CollectionStatus {
  schemaVersion: 'collection-scheduler-v1'; observation: 'NOT_OBSERVED' | 'OBSERVED' | 'STALE' | 'UNAVAILABLE'
  pendingSummary?: {projectId:string;projectName:string;pendingCount:number;manualCount:number;oldestStatDate:string}[]
  mode?: 'GAP_BACKFILL'; updatedAt?: string; statDate?: string; status?: string; projects: CollectionProjectStatus[]
}
const projectStates = ['PENDING', 'COLLECTING', 'RETRY_WAIT', 'SOURCE_READY', 'IMPORTING', 'VERIFYING', 'VERIFIED', 'ALREADY_PRESENT', 'FAILED', 'REVIEW_REQUIRED']
const timestamp = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)
export function validateCollectionStatus(value: unknown): CollectionStatus {
  const fail = () => { throw new Error('采集状态格式无法确认，请联系维护人员检查；不会自动重试采集。') }
  if (!value || typeof value !== 'object') return fail()
  const r = value as CollectionStatus
  if (r.schemaVersion !== 'collection-scheduler-v1' || !['NOT_OBSERVED', 'OBSERVED', 'STALE', 'UNAVAILABLE'].includes(r.observation)
    || !Array.isArray(r.projects) || r.projects.length > (r.mode === 'GAP_BACKFILL' ? 224 : 32)) return fail()
  if (r.observation === 'NOT_OBSERVED' || r.observation === 'UNAVAILABLE') {
    if (r.projects.length) return fail()
    return { schemaVersion: r.schemaVersion, observation: r.observation, projects: [] }
  }
  if ((r.mode !== undefined && r.mode !== 'GAP_BACKFILL') || !timestamp(r.updatedAt) || !date(r.statDate) || !['RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED', 'REVIEW_REQUIRED'].includes(r.status ?? '')) return fail()
  if(r.pendingSummary !== undefined) {
    if(!Array.isArray(r.pendingSummary) || r.pendingSummary.length>32) return fail()
    const seen=new Set<string>()
    for(const p of r.pendingSummary) {
      if(!p || typeof p.projectId!=='string' || seen.has(p.projectId) || typeof p.projectName!=='string'
        || !Number.isSafeInteger(p.pendingCount) || p.pendingCount<0 || !Number.isSafeInteger(p.manualCount)
        || p.manualCount<0 || p.manualCount>p.pendingCount || !date(p.oldestStatDate)) return fail()
      seen.add(p.projectId)
    }
  }
  const ids = new Set<string>()
  for (const p of r.projects) {
    if (!p || typeof p.projectId !== 'string' || ids.has(p.projectId + ':' + p.statDate) || typeof p.projectName !== 'string'
      || !projectStates.includes(p.status) || !date(p.statDate) || (r.mode === 'GAP_BACKFILL' ? !(Date.parse(r.statDate!) - Date.parse(p.statDate) >= 86400000 && Date.parse(r.statDate!) - Date.parse(p.statDate) <= 7 * 86400000) : p.statDate !== r.statDate) || !timestamp(p.updatedAt)
      || (p.retryNotBefore !== undefined && !timestamp(p.retryNotBefore))
      || (p.rows !== undefined && (!Number.isSafeInteger(p.rows) || p.rows < 0))
      || (p.pendingCount !== undefined && (!Number.isSafeInteger(p.pendingCount) || p.pendingCount<0))
      || (p.errorCode !== undefined && (typeof p.errorCode !== 'string' || !/^[A-Z0-9_]{1,64}$/.test(p.errorCode)))) return fail()
    ids.add(p.projectId + ':' + p.statDate)
  }
  return r
}
export async function readCollectionStatus(): Promise<CollectionStatus> {
  const response = await sessionFetch('/api/operations/collection-status', { cache: 'no-store' })
  if (!response.ok) throw new Error(`采集状态读取失败（HTTP ${response.status}），请重新读取或联系维护人员。`)
  return validateCollectionStatus(await response.json())
}
