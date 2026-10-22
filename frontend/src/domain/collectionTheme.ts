export type CollectionTheme = 'QUANYU' | 'CHENGFANG'
export const COLLECTION_THEMES = [
  { value: 'QUANYU', label: '全域', topic: 'SITE_PROMOTION_POST_DATA_VIDEO' },
  { value: 'CHENGFANG', label: '乘方', topic: 'OVERALL_ROI_LIVE_MATERIAL_VIDEO' },
] as const

export interface CollectionAccount {
  projectId: string
  projectName: string
  advertiserId: string
  publicDouyinId: string
  theme: CollectionTheme
  collecting: boolean
}
export interface CollectionAccountSnapshot {
  revision: string
  accounts: CollectionAccount[]
}
/** Server must repeat time/active-task checks under the collector/configuration lock. */
export interface CollectionThemeAdapter {
  read(): Promise<CollectionAccountSnapshot>
  save(input: { expectedRevision: string; projectId: string; theme: CollectionTheme }): Promise<CollectionAccountSnapshot>
}

export function inCollectionWindow(now: Date): boolean {
  const hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai', hour: '2-digit', hourCycle: 'h23' }).format(now))
  return hour >= 8 && hour < 12
}
export function themeLabel(theme: CollectionTheme): string {
  return COLLECTION_THEMES.find(item => item.value === theme)?.label ?? '数据未知'
}
