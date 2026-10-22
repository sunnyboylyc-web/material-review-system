// Isolated synthetic-only visual acceptance entry. No session bootstrap or real API adapters.
import { createApp } from 'vue'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { ConfigurationAdapter, ConfigurationSnapshot } from '../domain/configuration'
import type { HistoryUploadAdapter } from '../domain/historyUpload'
import type { OperationsDataAdapter } from '../adapters/operationsAdapter'
import { OPERATIONS_SCHEMA_VERSION } from '../domain/operations'
import type { DateRange } from '../domain/dashboard'
import { enumerateIsoDates } from '../time/shanghai'
import '../styles.css'
import '../dashboard-theme.css'

const projects = ['合成项目 A', '合成项目 B', '合成项目 C', '合成项目 D'].map((name, i) => ({ id: `demo-${i}`, name, enabled: true }))
const adapter = new SyntheticMockAdapter(projects)
const noWrite = async (): Promise<never> => { throw new Error('仅用于本地合成界面验收，不执行写入。') }
const configuration: ConfigurationSnapshot = { schemaVersion: 'project-configuration-v1', revision: 'a'.repeat(64), writeEnabled: false, collectorExecution: 'DISABLED', metricProfile: 'material-33-v1', metricChangesEnabled: false,
  projects: projects.map((project, index) => ({ ...project, category: 'SMALL_APPLIANCE', advertiserId: `9999999999999999010${index}`, douyinIds: [`9999999999999999020${index}`], publicDouyinId: `synthetic-${index}` })) }
const configurationAdapter: ConfigurationAdapter = { evidenceKind: 'SYNTHETIC', read: async () => structuredClone(configuration), resolveAnchor: noWrite, preflight: noWrite, apply: noWrite }
const historyUploadAdapter: HistoryUploadAdapter = { templateUrl: '#synthetic-no-download', context: async projectId => {
  const project = configuration.projects.find(item => item.id === projectId)
  if (!project) throw new Error('合成预览项目不存在。')
  return { schemaVersion: 'history-upload-api-v1', projectId, projectName: project.name, accountId: project.advertiserId, configurationRevision: configuration.revision, firstMcpDate: null, writeEnabled: false, blockedReason: null, uploads: [], manualHistoryDates: [], existingDates: [] }
}, read: noWrite, preview: noWrite, apply: noWrite, discard: noWrite }
const operationsAdapter: OperationsDataAdapter = { evidenceKind: 'SYNTHETIC', fetchDays: async (projectId: string, range: DateRange) => {
  const days = enumerateIsoDates(range.start, range.end).map(date => ({ date, state: 'MISSING' as const, currentVersion: null, previousVersionId: null, isolatedVersionCount: 0 }))
  return { schemaVersion: OPERATIONS_SCHEMA_VERSION, projectId, range, requestedAt: new Date().toISOString(), validationScope: 'CURRENT_DB_FACT_HASHES', sourceFilesRechecked: false, collectionStatus: 'NOT_OBSERVED', observedRange: { first: null, last: null },
    summary: { queriedDays: days.length, availableDays: 0, emptyDays: 0, missingDays: days.length, isolatedDays: 0 }, days, missingDetails: [], missingDetailTotal: 0, missingDetailsTruncated: false }
} }
createApp(App, {
  session: { username: '本地合成管理员', administrator: true, operations: true, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'SYNTHETIC_NOT_A_CREDENTIAL' },
  adapter, overviewAdapter: adapter, configurationAdapter, historyUploadAdapter, historyReadOnly: true, localReadOnlyPreview: true,
  operationsAdapter,
}).mount('#app')
