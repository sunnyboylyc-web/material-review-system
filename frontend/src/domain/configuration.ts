import { isProjectCategory, type ProjectCategory } from './projectCategory'
import type { CollectionTheme } from './collectionTheme'
export const CONFIGURATION_SCHEMA_VERSION = 'project-configuration-v1'
export const METRIC_PROFILE = 'material-33-v1'
export const REQUIRED_METRICS = ['stat_cost_for_roi2', 'total_pay_order_gmv_for_roi2', 'live_show_count_for_roi2_v2', 'live_watch_count_for_roi2_v2', 'total_pay_order_count_for_roi2']
export interface ConfigurationMetric { id: string; label: string; required: boolean }

export interface ConfigurationProject {
  collectionTheme?: CollectionTheme
  storageAccountId?: string
  category?: ProjectCategory
  id: string
  name: string
  advertiserId: string
  douyinIds: string[]
  enabled: boolean
  deleted?: boolean | null
  publicDouyinId?: string | null
  awemeName?: string | null
}
export interface ConfigurationSnapshot {
  themeChangesEnabled?: boolean
  collectingProjectIds?: string[]
  schemaVersion: 'project-configuration-v1'
  revision: string
  writeEnabled: boolean
  collectorExecution: 'DISABLED'
  metricProfile: 'material-33-v1'
  metricChangesEnabled: boolean
  enabledMetrics?: string[]
  metricCatalog?: ConfigurationMetric[]
  projects: ConfigurationProject[]
}
export interface ConfigurationCandidate {
  expectedRevision: string
  projects: ConfigurationProject[]
  enabledMetrics?: string[]
}

export function validateEnabledMetrics(selected: string[], catalog: ConfigurationMetric[]): string {
  if (new Set(selected).size !== selected.length || selected.some(id => !catalog.some(metric => metric.id === id))) return '指标选择重复或不在已知目录中。'
  if (catalog.some(metric => metric.required && !selected.includes(metric.id))) return '看板必需指标不能停用。'
  return ''
}
export function configurationMetricsMatch(left?: string[], right?: string[]): boolean {
  return JSON.stringify(left && [...left].sort()) === JSON.stringify(right && [...right].sort())
}
export interface ConfigurationPreflight {
  status: 'READY'
  expectedRevision: string
  candidateHash: string
}
export interface ConfigurationApply {
  status: 'CURRENT_VERIFIED'
  revision: string
  previousRevision: string
  collectorExecution: 'DISABLED'
}
export interface ConfigurationAdapter {
  readonly evidenceKind?: 'SYNTHETIC' | 'CURRENT_FACTS'
  read(): Promise<ConfigurationSnapshot>
  resolveAnchor(input: AnchorRequest): Promise<AnchorResolution>
  preflight(candidate: ConfigurationCandidate): Promise<ConfigurationPreflight>
  apply(candidate: ConfigurationCandidate & { candidateHash: string }): Promise<ConfigurationApply>
}
export interface AnchorRequest { advertiserId: string; publicDouyinId: string }
export interface AnchorResolution extends AnchorRequest { awemeId: string; awemeName: string }

export function validateConfigurationProjects(projects: ConfigurationProject[], previous: ConfigurationProject[] = []): string {
  if (projects.length > 32) return '最多配置 32 个项目，包含已删除但保留供恢复的项目。'
  const ids = new Set<string>()
  const accounts = new Set<string>()
  for (const project of projects) {
    if (project.collectionTheme !== undefined && !['QUANYU', 'CHENGFANG'].includes(project.collectionTheme)) return '采集主题须选择全域或乘方。'
    if (project.category !== undefined && !isProjectCategory(project.category)) return '所属分类须选择大家电、小家电或其他。'
    if (!/^[a-z][a-z0-9-]{0,39}$/.test(project.id)) return '项目 ID 须以小写字母开头，只含小写字母、数字或连字符，最多 40 字符。'
    if (!project.name.trim() || project.name.length > 80) return '项目名称须为 1～80 字符。'
    if (!/^[1-9]\d{0,29}$/.test(project.advertiserId)) return '账户 ID 须为 1～30 位正整数文本，不允许 0 或前导 0。'
    const storage = project.storageAccountId ?? project.publicDouyinId ?? project.advertiserId
    if (ids.has(project.id) || accounts.has(storage)) return '项目 ID 和抖音数据身份不能重复。'
    ids.add(project.id)
    accounts.add(storage)
    if (!project.douyinIds.length && !project.publicDouyinId) return '请在采集账户配置中填写公开抖音号（抖音个人主页显示的抖音号，不是账号昵称）。'
    if (project.douyinIds.length > 20
      || project.douyinIds.some(id => !/^[1-9]\d{0,29}$/.test(id))
      || new Set(project.douyinIds).size !== project.douyinIds.length) return '每个项目须有 1～20 个不重复的抖音锚点，每个为 1～30 位正整数文本，不允许 0 或前导 0。'
    if (typeof project.enabled !== 'boolean') return '项目启用状态无效。'
    if (project.deleted != null && typeof project.deleted !== 'boolean') return '项目删除状态无效。'
    if (project.deleted && project.enabled) return '已删除项目不能启用，请先恢复。'
    if (project.publicDouyinId != null && (typeof project.publicDouyinId !== 'string' || !/^[A-Za-z0-9_.-]{1,64}$/.test(project.publicDouyinId))) return '公开抖音号格式无效。'
    if (project.awemeName != null && (typeof project.awemeName !== 'string' || !project.awemeName.trim() || project.awemeName.length > 200)) return '抖音账号名称无效。'
  }
  for (const existing of previous) {
    const current = projects.find(project => project.id === existing.id)
    if (!current) return '已有项目不能直接移除；请使用可恢复删除。'
  }
  return ''
}

/** Stable semantic comparison only; candidate/revision SHA-256 is owned by the server. */
export function configurationProjectsMatch(left: ConfigurationProject[], right: ConfigurationProject[]): boolean {
  const canonical = (projects: ConfigurationProject[]) => JSON.stringify(projects.map(project => ({
    id: project.id, name: project.name, advertiserId: project.advertiserId,
    collectionTheme: project.collectionTheme ?? 'QUANYU',
    storageAccountId: project.storageAccountId ?? project.advertiserId,
    douyinIds: [...project.douyinIds].sort(), enabled: project.enabled,
    publicDouyinId: project.publicDouyinId ?? null, awemeName: project.awemeName ?? null,
    deleted: project.deleted ?? false,
    category: project.category ?? 'OTHER',
  })).sort((a, b) => a.id.localeCompare(b.id)))
  return canonical(left) === canonical(right)
}
