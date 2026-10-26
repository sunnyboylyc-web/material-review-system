import type { CollectedExportAdapter, CollectedExportPreview } from '../domain/collectedExport'
import { sessionFetch } from '../domain/session'
const MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const exportErrors: Record<string, string> = {
  EXPORT_ROW_LIMIT: '这段时间的数据超过 10 万行，请缩短日期范围，分几次下载。',
  EXPORT_RANGE_INVALID: '日期范围无效：请填写正确的开始和结束日期，一次最多选择 366 天。',
  EXPORT_NO_DATA: '这段时间没有可下载的已保存数据，请更换日期后重新查看范围。',
  EXPORT_BUILD_FAILED: 'Excel 文件生成失败，系统中的数据没有改变。请联系维护人员检查下载服务。',
  EXPORT_TIMEOUT: '生成 Excel 等待超时，系统中的数据没有改变。请缩短日期范围后重试；仍失败请联系维护人员。',
  EXPORT_READ_FAILED: '暂时无法读取已保存的数据，未生成文件。请稍后重新查看范围；仍失败请联系维护人员。',
  EXPORT_HASH_MISMATCH: '数据核对未通过，为避免下载错误内容，已停止生成文件。请联系维护人员检查。',
  EXPORT_PROJECT_UNAVAILABLE: '当前项目不可下载，请重新选择项目；仍无法下载请联系维护人员检查项目配置。',
}
export class HttpCollectedExportAdapter implements CollectedExportAdapter {
  constructor(private readonly fetcher: typeof fetch = sessionFetch) {}
  private async request(project: string, start: string, end: string, action: string): Promise<Response> {
    let response: Response
    try { response = await this.fetcher(`/api/operations/exports/projects/${encodeURIComponent(project)}/${action}?${new URLSearchParams({ startDate: start, endDate: end })}`, { credentials: 'same-origin', cache: 'no-store' }) }
    catch { throw new Error('无法连接下载服务，请检查网络后重试；已有数据不会改变。') }
    if (!response.ok) {
      let code = ''
      try { const body = await response.json(); if (/^[A-Z0-9_]+$/.test(body.code)) code = body.code } catch { /* Untrusted body not displayed. */ }
      throw new Error(`${exportErrors[code] ?? '下载请求未完成，请核对日期范围，或联系维护人员检查服务。'}（HTTP ${response.status}${code ? ` · ${code}` : ''}）`)
    }
    return response
  }
  async preview(project: string, start: string, end: string): Promise<CollectedExportPreview> {
    const value = await (await this.request(project, start, end, 'preview')).json() as CollectedExportPreview
    if (value.projectId !== project || value.startDate !== start || value.endDate !== end || !Array.isArray(value.days) || !Array.isArray(value.excludedDates) || !Number.isSafeInteger(value.rowCount) || value.rowCount < 0) throw new Error('下载范围返回不一致，请重新查看范围。')
    return value
  }
  async download(project: string, start: string, end: string): Promise<Blob> {
    const response = await this.request(project, start, end, 'download')
    if (!response.headers.get('content-type')?.toLowerCase().startsWith(MIME)) throw new Error('服务未返回 Excel 文件，未保存下载。请联系维护人员。')
    const blob = await response.blob()
    if (blob.size === 0) throw new Error('下载文件为空，未保存。请重新查看范围。')
    return blob
  }
}
