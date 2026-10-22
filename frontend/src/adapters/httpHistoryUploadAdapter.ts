import { applicationPath } from '../domain/applicationPath'
import { HistoryRequestError, historyStatusLabels, type HistoryUploadAdapter, type HistoryUploadContext, type HistoryUploadView } from '../domain/historyUpload'
import { csrfHeaders, sessionFetch } from '../domain/session'

const BASE = '/api/operations/history'
function checkedView(value: HistoryUploadView, projectId: string, uploadId?: string): HistoryUploadView {
  if (!value || value.projectId !== projectId || (uploadId && value.uploadId !== uploadId)
    || !value.uploadId || !Object.hasOwn(historyStatusLabels, value.status) || !Array.isArray(value.days)
    || !['RETAINED', 'DELETED'].includes(value.sourceFileState)) throw new Error('历史上传回包与当前项目或记录不一致，请刷新记录。')
  return value
}
export class HttpHistoryUploadAdapter implements HistoryUploadAdapter {
  readonly templateUrl = applicationPath(`${BASE}/template`)
  constructor(private readonly fetcher: typeof fetch = sessionFetch, private readonly readOnly = false) {}
  private async request(path: string, body?: File | object): Promise<unknown> {
    if (body !== undefined && this.readOnly) throw new Error('预览页面只读，不允许上传、导入或删除副本。')
    let response: Response
    try {
      response = await this.fetcher(`${BASE}${path}`, {
        method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: body === undefined ? { Accept: 'application/json' } : {
          Accept: 'application/json', 'Content-Type': body instanceof File ? 'application/octet-stream' : 'application/json', 'X-SCSJ-History-Write': '1', ...csrfHeaders(),
        }, body: body === undefined ? undefined : body instanceof File ? body : JSON.stringify(body),
      })
    } catch { throw new Error('历史上传接口连接失败；操作结果未知，请刷新上传记录，不要重复提交。') }
    if (!response.ok) {
      let code = ''
      try { const error = await response.json(); if (typeof error.code === 'string' && /^[A-Z0-9_]+$/.test(error.code)) code = error.code } catch { /* No trusted JSON error. */ }
      const refused = response.status >= 400 && response.status < 500 && ['LOGIN_REQUIRED', 'ACCESS_DENIED', 'LOCAL_REQUEST_REQUIRED', 'REQUEST_INVALID', 'PROJECT_UNAVAILABLE', 'HISTORY_WRITE_DISABLED', 'CONFIRMATION_REQUIRED', 'PREVIEW_CHANGED', 'UPLOAD_NOT_READY', 'CONFIGURATION_CHANGED', 'MCP_CONTEXT_CHANGED', 'UPLOAD_SLOT_OCCUPIED', 'UPLOAD_RECORD_UNAVAILABLE', 'UPLOAD_DISCARD_UNSAFE', 'UPLOAD_BUSY'].includes(code)
      throw new HistoryRequestError(`历史上传请求未完成（HTTP ${response.status}${code ? ` · ${code}` : ''}）。${[404,503].includes(response.status) ? '服务尚未就绪或暂不可用。' : ''}${refused ? '本次请求未执行。请刷新上传记录，核对配置和预览后再确认。' : '请刷新记录核对结果。'}`, refused ? 'NOT_EXECUTED' : 'UNKNOWN')
    }
    return response.json()
  }
  private path(projectId: string, uploadId?: string): string {
    return `/projects/${encodeURIComponent(projectId)}${uploadId ? `/uploads/${encodeURIComponent(uploadId)}` : ''}`
  }
  async context(projectId: string): Promise<HistoryUploadContext> {
    const value = await this.request(`${this.path(projectId)}/context`) as HistoryUploadContext
    if (!value || value.schemaVersion !== 'history-upload-api-v1' || value.projectId !== projectId || typeof value.writeEnabled !== 'boolean' || !Array.isArray(value.uploads)) throw new Error('历史上传上下文不一致，请刷新记录。')
    value.uploads.forEach(view => checkedView(view, projectId))
    // Never render arbitrary diagnostic text from a damaged record or another project.
    value.warnings = Array.isArray(value.warnings) && value.warnings.some(w => w?.code === 'UPLOAD_RECORD_UNAVAILABLE')
      ? [{ code: 'UPLOAD_RECORD_UNAVAILABLE', message: '有上传记录暂时无法读取，不能确认文件占位，已暂停新上传；请联系维护人员检查。' }] : []
    return value
  }
  async preview(projectId: string, file: File, onProgress?: (loaded: number, total: number) => void, mode: 'HISTORY' | 'GAP_FILL' = 'HISTORY'): Promise<HistoryUploadView> {
    if (!onProgress) return checkedView(await this.request(`${this.path(projectId)}/preview${mode === 'GAP_FILL' ? '?mode=GAP_FILL' : ''}`, file) as HistoryUploadView, projectId)
    if (this.readOnly) throw new Error('预览页面只读，不允许上传、导入或删除副本。')
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', applicationPath(`${BASE}${this.path(projectId)}/preview${mode === 'GAP_FILL' ? '?mode=GAP_FILL' : ''}`))
      xhr.setRequestHeader('Accept', 'application/json')
      xhr.setRequestHeader('Content-Type', 'application/octet-stream')
      xhr.setRequestHeader('X-SCSJ-History-Write', '1')
      Object.entries(csrfHeaders()).forEach(([key, value]) => xhr.setRequestHeader(key, value))
      xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(event.loaded, event.total) }
      xhr.upload.onload = () => onProgress(file.size, file.size)
      xhr.onerror = xhr.onabort = xhr.ontimeout = () => reject(new Error('上传连接中断，结果未知；请先刷新上传记录核对，不要重复上传。'))
      xhr.onload = () => {
        try {
          if (xhr.status === 401) { window.location.assign(applicationPath('/login')); throw new HistoryRequestError('登录已过期，请重新登录；本次上传请求未执行。', 'NOT_EXECUTED') }
          if (xhr.status === 403) throw new HistoryRequestError('上传权限或登录验证已失效，请重新登录；本次请求未执行。', 'NOT_EXECUTED')
          if (xhr.status < 200 || xhr.status >= 300) {
            let code = ''
            try { const body = JSON.parse(xhr.responseText); if (['UPLOAD_SLOT_OCCUPIED', 'UPLOAD_RECORD_UNAVAILABLE'].includes(body.code)) code = body.code } catch { /* Unknown outcomes remain guarded. */ }
            if (xhr.status >= 400 && xhr.status < 500 && code) throw new HistoryRequestError(`本次新上传未执行（${code}），请刷新上传记录，先处理现有文件。`, 'NOT_EXECUTED')
            throw new Error(`上传请求未完成（HTTP ${xhr.status}），请先刷新记录核对。`)
          }
          resolve(checkedView(JSON.parse(xhr.responseText) as HistoryUploadView, projectId))
        } catch (error) { reject(error) }
      }
      xhr.send(file)
    })
  }
  async read(projectId: string, uploadId: string): Promise<HistoryUploadView> { return checkedView(await this.request(this.path(projectId, uploadId)) as HistoryUploadView, projectId, uploadId) }
  async apply(projectId: string, uploadId: string, previewHash: string): Promise<HistoryUploadView> {
    return checkedView(await this.request(`${this.path(projectId, uploadId)}/apply`, { previewHash, confirmAccount: true, confirmCompleteDays: true }) as HistoryUploadView, projectId, uploadId)
  }
  async discard(projectId: string, uploadId: string, previewHash: string): Promise<HistoryUploadView> {
    return checkedView(await this.request(`${this.path(projectId, uploadId)}/discard`, { previewHash }) as HistoryUploadView, projectId, uploadId)
  }
}
