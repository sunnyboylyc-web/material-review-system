import { applicationPath } from '../domain/applicationPath'
import { csrfHeaders, sessionFetch } from '../domain/session'

export interface DatabaseBackupStatus {
  available: boolean
  latest: null | { generation: string; createdAt: string; bytes: number; sha256: string; validation: 'DUMP_EXIT_AND_HASH_READBACK'; restoreTested: false; compression?: 'gzip' | 'none'; sqlBytes?: number; sqlSha256?: string; scope?: 'FULL_DATABASE' | 'STAT_DATE_RANGE'; startDate?: string | null; endDate?: string | null }
  lastAttempt: null | { status: 'RUNNING' | 'SUCCESS' | 'FAILED'; startedAt: string; completedAt: string | null; errorCode: string | null }
  schedule: string
  retention: 1
}
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value))
export function validateDatabaseBackupStatus(value: unknown): DatabaseBackupStatus {
  const fail = () => { throw new Error('备份状态格式异常，未展示下载；请联系维护人员核对。') }
  if (!object(value) || typeof value.available !== 'boolean' || value.retention !== 1
    || value.schedule !== '每周日23:00（上海时间）') return fail()
  const latest = value.latest, attempt = value.lastAttempt
  if (latest !== null && (!object(latest) || typeof latest.generation !== 'string' || !/^[0-9a-f]{32}$/.test(latest.generation)
    || !timestamp(latest.createdAt) || !Number.isSafeInteger(latest.bytes) || Number(latest.bytes) <= 0
    || typeof latest.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(latest.sha256)
    || latest.validation !== 'DUMP_EXIT_AND_HASH_READBACK' || latest.restoreTested !== false)) return fail()
  if (value.available !== (latest !== null)) return fail()
  if (object(latest) && latest.scope === 'STAT_DATE_RANGE' && (typeof latest.startDate !== 'string' || typeof latest.endDate !== 'string'
    || !/^\d{4}-\d{2}-\d{2}$/.test(latest.startDate) || !/^\d{4}-\d{2}-\d{2}$/.test(latest.endDate) || latest.startDate > latest.endDate)) return fail()
  if (object(latest) && latest.compression !== undefined && (!['gzip','none'].includes(String(latest.compression))
    || !Number.isSafeInteger(latest.sqlBytes) || Number(latest.sqlBytes) <= 0
    || typeof latest.sqlSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(latest.sqlSha256))) return fail()
  if (attempt !== null && (!object(attempt) || !['RUNNING', 'SUCCESS', 'FAILED'].includes(String(attempt.status))
    || !timestamp(attempt.startedAt) || !(attempt.completedAt === null || timestamp(attempt.completedAt))
    || !(attempt.errorCode === null || typeof attempt.errorCode === 'string' && /^[A-Z0-9_]{1,100}$/.test(attempt.errorCode)))) return fail()
  return value as unknown as DatabaseBackupStatus
}
export interface CustomBackupStatus { backup: DatabaseBackupStatus; job: { running: boolean; errorCode: string | null } }
export async function readCustomBackup(): Promise<CustomBackupStatus> {
  const response = await sessionFetch('/api/operations/database-backup/custom', { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw new Error('无法读取自定义备份状态')
  const value = await response.json()
  if (!object(value) || !object(value.job) || typeof value.job.running !== 'boolean'
    || !(value.job.errorCode === null || typeof value.job.errorCode === 'string' && /^[A-Z_]{1,80}$/.test(value.job.errorCode))) throw new Error('备份状态异常')
  return { backup: validateDatabaseBackupStatus(value.backup), job: value.job as CustomBackupStatus['job'] }
}
export async function createCustomBackup(startDate: string, endDate: string) {
  const response = await sessionFetch('/api/operations/database-backup/custom', { method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ startDate, endDate }) })
  if (!response.ok) throw new Error(response.status === 409 ? '备份任务正在处理或刚刚提交，请稍后刷新。' : '未能提交备份，请核对日期并刷新页面后再试。')
}
export function customBackupDownloadUrl(generation: string) {
  if (!/^[0-9a-f]{32}$/.test(generation)) throw new Error('备份版本异常')
  return applicationPath(`/api/operations/database-backup/custom/download?generation=${generation}`)
}
export async function readDatabaseBackup(): Promise<DatabaseBackupStatus> {
  const response = await sessionFetch('/api/operations/database-backup', { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? '无法读取备份状态，请核对登录及运维权限。'
    : '备份状态暂不可读取，不能据此判断备份成功或失败；请稍后刷新状态。')
  return validateDatabaseBackupStatus(await response.json())
}
export function databaseBackupDownloadUrl(generation: string): string {
  if (!/^[0-9a-f]{32}$/.test(generation)) throw new Error('备份版本异常，不能下载。')
  return applicationPath(`/api/operations/database-backup/download?generation=${generation}`)
}
