import type { ReviewQuery } from '../domain/dashboard'
import { csrfHeaders, sessionFetch } from '../domain/session'

export interface PeriodPreferences {
  load(projectId: string): Promise<ReviewQuery | null>
  save(projectId: string, query: ReviewQuery): Promise<void>
}
const url = (id: string) => `/api/preferences/projects/${encodeURIComponent(id)}/period`
export const httpPeriodPreferences: PeriodPreferences = {
  async load(id) {
    const response = await sessionFetch(url(id), { credentials: 'same-origin', cache: 'no-store' })
    if (response.status === 204) return null
    if (!response.ok) throw new Error('周期日期偏好读取失败，暂用默认日期；请重试。')
    const value = await response.json()
    const dates = [value.currentStart, value.currentEnd, value.previousStart, value.previousEnd]
    if (!dates.every(date => typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)
      && Number.isFinite(Date.parse(`${date}T00:00:00Z`)) && new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date)
      || value.currentStart > value.currentEnd || value.previousStart > value.previousEnd)
      throw new Error('周期日期偏好格式异常，暂用默认日期。')
    return { current: { start: value.currentStart, end: value.currentEnd }, previous: { start: value.previousStart, end: value.previousEnd } }
  },
  async save(id, query) {
    const response = await sessionFetch(url(id), { method: 'PUT', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify({ currentStart: query.current.start, currentEnd: query.current.end,
        previousStart: query.previous.start, previousEnd: query.previous.end }) })
    if (!response.ok) throw new Error('周期日期偏好未保存；本次仍可查询，下次登录可能恢复旧日期。')
  },
}
