export interface CollectedExportPreview {
  projectId: string; projectName: string; accountId: string; startDate: string; endDate: string; rowCount: number
  days: { statDate: string; rowCount: number; sourceType?: 'MCP_AUTO' | 'MANUAL_HISTORY' }[]
  excludedDates: { statDate: string; reason: string }[]
}
export interface CollectedExportAdapter {
  preview(projectId: string, startDate: string, endDate: string): Promise<CollectedExportPreview>
  download(projectId: string, startDate: string, endDate: string): Promise<Blob>
}
