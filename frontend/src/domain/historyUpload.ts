export type HistoryStatus = 'PREVIEW_READY' | 'VALIDATION_FAILED' | 'IMPORT_FAILED' | 'IMPORTED_CLEANUP_PENDING' | 'COMPLETED' | 'CANCELLED' | 'IMPORTING' | 'VERIFYING' | 'CLEANING' | 'INTERRUPTED'
export interface HistoryUploadView {
  createdAt?: string | null; completedAt?: string | null
  uploadId: string; projectId: string; accountId: string; configurationRevision: string
  sourceSha256: string; sourceSizeBytes: number; previewHash: string
  uploadMode?: 'HISTORY' | 'GAP_FILL'; status: HistoryStatus; firstMcpDate: string | null; start: string | null; end: string | null
  rowCount: number; totalSpend: string
  duplicateRowsSkipped?: number; duplicatePolicy?: string
  days: { statDate: string; rowCount: number; totalSpend: string; declaredCompleteness: string; state: 'PENDING' | 'VERIFYING' | 'IMPORTED' | 'FAILED' }[]
  diagnostic?: { code: string; message: string; sheet?: string; row?: number; column?: string | number } | null
  sourceFileState: 'RETAINED' | 'DELETED'; message?: string | null
  active?: boolean
  progress?: { stage: string; completedDays: number; totalDays: number; currentDate: string | null; startedAt: string; updatedAt: string } | null
}
export interface HistoryUploadContext {
  schemaVersion: 'history-upload-api-v1'; projectId: string; projectName: string; accountId: string
  configurationRevision: string; firstMcpDate: string | null; writeEnabled: boolean
  blockedReason: string | null; uploads: HistoryUploadView[]
  /** Missing in older APIs means unknown, never an empty imported range. */
  manualHistoryDates?: string[]; existingDates?: string[]
  warnings?: { code: 'UPLOAD_RECORD_UNAVAILABLE'; message: string }[]
}
export function historyDateSummary(dates?: string[]): string {
  if (!Array.isArray(dates) || dates.some(d => typeof d !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(d) || !Number.isFinite(Date.parse(`${d}T00:00:00Z`)) || new Date(`${d}T00:00:00Z`).toISOString().slice(0, 10) !== d)) return '范围待核，请重新读取或联系维护人员'
  const sorted = [...new Set(dates)].sort()
  if (!sorted.length) return '0 天（尚无已入库日期）'
  const ranges: string[] = []
  let start = sorted[0]!, end = start
  for (const day of sorted.slice(1)) {
    if (Date.parse(`${day}T00:00:00Z`) - Date.parse(`${end}T00:00:00Z`) === 86400000) end = day
    else { ranges.push(start === end ? start : `${start} 至 ${end}`); start = end = day }
  }
  ranges.push(start === end ? start : `${start} 至 ${end}`)
  return `${sorted.length} 天：${ranges.join('；')}`
}
/** Only a recognized pre-execution refusal may release the duplicate-submit guard. */
export class HistoryRequestError extends Error {
  constructor(message: string, readonly outcome: 'NOT_EXECUTED' | 'UNKNOWN') { super(message) }
}
export interface HistoryUploadAdapter {
  templateUrl: string
  context(projectId: string): Promise<HistoryUploadContext>
  preview(projectId: string, file: File, onProgress?: (loaded: number, total: number) => void, mode?: 'HISTORY' | 'GAP_FILL'): Promise<HistoryUploadView>
  read(projectId: string, uploadId: string): Promise<HistoryUploadView>
  apply(projectId: string, uploadId: string, previewHash: string): Promise<HistoryUploadView>
  discard(projectId: string, uploadId: string, previewHash: string): Promise<HistoryUploadView>
}
export const historyStatusLabels: Record<HistoryStatus, string> = {
  PREVIEW_READY: '预览通过 · 尚未导入', VALIDATION_FAILED: '文件校验失败 · 未导入',
  IMPORT_FAILED: '导入未全部完成 · 请核对逐日状态', IMPORTED_CLEANUP_PENDING: '已导入 · 上传副本清理待处理',
  COMPLETED: '导入及副本清理完成', CANCELLED: '已取消 · 上传副本已删除',
  IMPORTING: '正在逐日入库', VERIFYING: '正在最终核验', CLEANING: '正在清理上传副本', INTERRUPTED: '任务已中断 · 可确认后重试',
}
