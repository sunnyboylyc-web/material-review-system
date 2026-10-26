<script setup lang="ts">
import { computed, ref } from 'vue'
import { operationsFixture } from '../test/operationsFixture'
import type { HistoryUploadAdapter } from '../domain/historyUpload'
import type { NumericResult, MetricGroup } from '../domain/dashboard'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { REQUIRED_METRICS, type ConfigurationAdapter, type ConfigurationSnapshot } from '../domain/configuration'

// Local visual acceptance only: synthetic data, no auth/bootstrap, real APIs or persistence.
const role = ref('operator')
const session = computed(() => ({ username: role.value === 'user' ? '合成使用者' : role.value === 'admin' ? '合成管理员' : '合成运维',
  publicAccess: true, operations: role.value !== 'user', administrator: role.value === 'admin', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'SYNTHETIC_NOT_A_CREDENTIAL' }))
const projects = ['晨光演示', '星河演示', '清风演示', '云朵演示'].map((name, i) => ({ id: `synthetic-gallery-${i}`, name, enabled: true, category: 'SMALL_APPLIANCE' as const }))
const adapter = new SyntheticMockAdapter(projects)
const valid = (value: number): NumericResult => ({ status: 'VALID', value, partial: false, partialReasons: [] })
const originalReview = adapter.fetchReview.bind(adapter)
adapter.fetchReview = async (...args) => {
  const result = await originalReview(...args)
  result.metricsState = 'READY'
  for (const period of result.periods) {
    period.coverage = { ...period.coverage, state: 'COMPLETE', coveredDays: period.coverage.expectedDays, missingDates: [] }
    period.pendingMaterialCount = 0
    period.groups = period.groups.map((group, i) => {
      const spend = [24000, 12000, 9600, 2400][i] * (period.key === 'PREVIOUS' ? 0.8 : 1)
      return { ...group, metrics: { spend: valid(spend), gmv: valid(spend*3.2), impressions: valid(spend/80*1000), clicks: valid(spend/2), materialCount: valid([40,20,16,4][i]), roi: valid(3.2), ctr: valid(0.04), cvr: valid(0.05), cpm: valid(80), cpc: valid(2), spendShare: valid([1,0.5,0.4,0.1][i]) } }
    })
  }
  result.comparisons = result.periods[0].groups.map(g => ({ ...g, metrics: Object.fromEntries(Object.keys(g.metrics).map(k => [k, valid(['spend','gmv','impressions','clicks'].includes(k)?1.25:1)])) as MetricGroup['metrics'] }))
  result.topMaterials = [1,2,3].map((rank) => ({ ...structuredClone(result.topMaterials[0]), rank,
    materialId: `9999999999999999010${rank}`, materialName: ['原创·产品场景展示','混剪·功能卖点合集','新品体验·演示素材'][rank-1],
    periodChange: valid(1.25), currentSpend: valid(6000/rank), previousSpend: valid(4800/rank), roi: valid(3.2), ctr: valid(0.04), cvr: valid(0.05),
    spendShare: valid(0.25/rank), cpm: valid(80),
    creationDate: { status: 'VERIFIED', date: args[1].current.start, reason: null },
    firstSpend: { status: 'VERIFIED', date: args[1].current.start, earliestObservedDate: args[1].current.start },
    dailySpend: result.topMaterials[0].dailySpend.map((d,i,all) => ({date:d.date,spend:valid((6000/rank/all.length)*(0.7+0.6*i/Math.max(1,all.length-1)))})) }))
  result.cohortMaterials = {new: result.topMaterials, historical: result.topMaterials, aigc: []}
  result.newMaterials = result.topMaterials
  result.materialTypes = {schemaVersion:'material-type-review-1.0.0', periods: result.periods.map(p => ({key:p.key,range:p.range,coverage:p.coverage, excludedUndatedMaterials:0,unresolvedDateMaterials:0,
    groups: (['original','mixed','other'] as const).map((key,i) => ({key,metrics:{...p.groups[0].metrics,spend:valid([12000,8000,4000][i]*(p.key==='PREVIOUS'?0.8:1)),materialCount:valid([20,12,8][i])}}))})),
    comparisons: (['original','mixed','other'] as const).map(key=>({key,metrics:result.comparisons[0].metrics})), materials:{original:[],mixed:[],other:[]}}
  return result
}
const originalTop = adapter.fetchTop.bind(adapter)
adapter.fetchTop = async (...args) => {
  const result = await originalTop(...args)
  result.rows = [1, 2, 3, 4, 5].slice(0, args[2]).map((rank) => {
    const row = structuredClone(result.rows[0])
    const roi = [4.2, 2.7, 5.8, 3.3, 1.8][rank - 1]
    return { ...row, rank, materialId: `9999999999999999020${rank}`,
      materialName: ['原创·产品场景展示', '混剪·功能卖点合集', '新品体验·素材演示', '原创·使用步骤', '混剪·场景对比'][rank - 1],
      dayRoi: { status: 'VALID' as const, value: roi },
      recent3Spend: { status: 'VALID' as const, value: 3000 / rank }, recent3Roi: { status: 'VALID' as const, value: roi },
      windowSpend: { status: 'VALID' as const, value: 5400 / rank }, windowRoi: { status: 'VALID' as const, value: roi },
      recent3Cpc: { status: 'VALID' as const, value: 30 / rank }, recent3Cpm: { status: 'VALID' as const, value: 3000 / rank },
      dailySpend: row.dailySpend.map(day => ({ ...day, spend: { status: 'VALID' as const, value: day.date === args[1] ? 1800 / rank : 600 / rank } })) }
  })
  return result
}
const metricLabels = ['消耗', '成交金额', '展现次数', '点击次数', '成交订单数']
const metricCatalog = [...REQUIRED_METRICS.map((id, i) => ({ id, label: metricLabels[i], required: true })),
  ...Array.from({ length: 23 }, (_, i) => ({ id: `synthetic_optional_${i}`, label: `合成可选指标 ${i + 1}`, required: false }))]
const snapshot: ConfigurationSnapshot = { schemaVersion: 'project-configuration-v1', revision: 'a'.repeat(64),
  writeEnabled: false, collectorExecution: 'DISABLED', metricProfile: 'material-33-v1', metricChangesEnabled: true,
  metricCatalog, enabledMetrics: metricCatalog.map(metric => metric.id),
  projects: projects.map((project,i) => ({ ...project, collectionTheme: 'QUANYU', advertiserId: '99999999999999990001', douyinIds: ['99999999999999990002'], publicDouyinId: `synthetic-preview-${i}` })) }
const noWrite = async (): Promise<never> => { throw new Error('本地合成预览，不保存配置、不发起采集。') }
const configurationAdapter: ConfigurationAdapter = { evidenceKind: 'SYNTHETIC', read: async () => structuredClone(snapshot),
  preflight: noWrite, apply: noWrite, resolveAnchor: noWrite }
const operationsAdapter = { evidenceKind: 'SYNTHETIC' as const, fetchDays: async (projectId: string, range: {start: string; end: string}) => operationsFixture(projectId, range) }
const historyAdapter: HistoryUploadAdapter = { templateUrl: '', context: async projectId => ({
  schemaVersion: 'history-upload-api-v1', projectId, projectName: projects.find(p=>p.id===projectId)!.name,
  accountId: '99999999999999990001', configurationRevision: 'a'.repeat(64), firstMcpDate: '2026-10-01',
  writeEnabled: false, blockedReason: '合成预览：上传与写入已关闭', uploads: [], manualHistoryDates: [], existingDates: []
}), preview: noWrite, read: noWrite, apply: noWrite, discard: noWrite }
</script>
<template>
  <div class="d217-preview-bar"><span>Material Review System · 产品展示 · 全部为虚构数据</span></div>
  <App :key="role" :session="session" :adapter="adapter" :overview-adapter="adapter" :configuration-adapter="configurationAdapter" :operations-adapter="operationsAdapter" :history-upload-adapter="historyAdapter" local-read-only-preview history-read-only />
</template>
<style scoped>
.d217-preview-bar { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; padding:8px 22px; background:#232034; color:#e1ceaa; font-size:12px; }
.d217-preview-bar label { display:flex; align-items:center; gap:8px; }
.d217-preview-bar select { background:#111726; border:1px solid #645579; color:#dfdbea; padding:4px 10px; border-radius:5px; }
</style>


