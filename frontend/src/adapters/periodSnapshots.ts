import type { ReviewQuery, ReviewResult } from '../domain/dashboard'
import { csrfHeaders, sessionFetch } from '../domain/session'
import { validateReviewResponse } from './httpDashboardAdapter'

export interface PeriodSnapshot { result: ReviewResult; savedAt: string }
export interface RefreshedPeriodSnapshot { result: ReviewResult; savedAt: string | null; persisted: boolean; warning: string | null }
export interface PeriodSnapshots {
  load(projectId: string): Promise<PeriodSnapshot | null>
  refresh(projectId: string, query: ReviewQuery, limit: number): Promise<RefreshedPeriodSnapshot>
}
const url = (id: string) => `/api/preferences/projects/${encodeURIComponent(id)}/review-snapshot`
function resultFor(value: unknown, id: string): ReviewResult {
  const result = validateReviewResponse(value)
  if (result.projectId !== id) throw new Error('保存结果的项目不一致，未展示结果。')
  return result
}
function savedTime(value: unknown): string {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw new Error('保存结果的时间无效，请刷新数据。')
  return value
}
export const httpPeriodSnapshots: PeriodSnapshots = {
  async load(id) {
    const response = await sessionFetch(url(id), { credentials: 'same-origin', cache: 'no-store' })
    if (response.status === 204) return null
    if (!response.ok) throw new Error('上次保存的周期结果暂不可用，请点击“刷新数据”重新获取。')
    const value = await response.json()
    return { result: resultFor(value.result, id), savedAt: savedTime(value.savedAt) }
  },
  async refresh(id, query, limit) {
    const response = await sessionFetch(`${url(id)}/refresh`, { method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify({ currentStart: query.current.start, currentEnd: query.current.end,
        previousStart: query.previous.start, previousEnd: query.previous.end, limit }) })
    if (!response.ok) throw new Error(`周期数据刷新失败（HTTP ${response.status}），请重试或联系运维。`)
    const value = await response.json()
    if (typeof value.persisted !== 'boolean' || (!value.persisted && value.savedAt !== null))
      throw new Error('周期结果保存状态异常，请重试。')
    return { result: resultFor(value.result, id), persisted: value.persisted,
      savedAt: value.persisted ? savedTime(value.savedAt) : null,
      warning: value.persisted ? null : value.warning === 'REVIEW_SNAPSHOT_TOO_LARGE'
        ? '本次结果较大，超过保存容量，未覆盖上次保存的结果。当前计算结果仍可查看；请缩短周期后再刷新保存。'
        : '本次计算已完成，但结果未能保存；重新登录后仍可能看到上次保存的结果。请稍后刷新重试。' }
  },
}
