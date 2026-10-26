<script setup lang="ts">
import { computed, ref } from 'vue'
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { REQUIRED_METRICS, type ConfigurationAdapter, type ConfigurationSnapshot } from '../domain/configuration'

// Local visual acceptance only: synthetic data, no auth/bootstrap, real APIs or persistence.
const role = ref('operator')
const session = computed(() => ({ username: role.value === 'user' ? '合成使用者' : role.value === 'admin' ? '合成管理员' : '合成运维',
  operations: role.value !== 'user', administrator: role.value === 'admin', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'SYNTHETIC_NOT_A_CREDENTIAL' }))
const projects = ['示例项目', '吹风机', '剃须刀 CM31', '剃须刀 TM31'].map((name, i) => ({ id: 'synthetic-theme-' + i, name, enabled: true, category: 'SMALL_APPLIANCE' as const }))
const adapter = new SyntheticMockAdapter(projects)
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
let snapshot: ConfigurationSnapshot = { schemaVersion: 'project-configuration-v1', revision: 'a'.repeat(64),
  writeEnabled: true, themeChangesEnabled: true, collectingProjectIds: [], collectorExecution: 'DISABLED', metricProfile: 'material-33-v1', metricChangesEnabled: true,
  metricCatalog, enabledMetrics: metricCatalog.map(metric => metric.id),
  projects: projects.map((project, i) => ({ ...project, advertiserId: '90000000000000' + (i + 1), douyinIds: ['90000000000100' + (i + 1)], publicDouyinId: 'example_douyin_' + (i + 1), collectionTheme: 'QUANYU' as const })) }
const noWrite = async (): Promise<never> => { throw new Error('本地合成预览，不保存配置、不发起采集。') }
const configurationAdapter: ConfigurationAdapter = { evidenceKind: 'SYNTHETIC', read: async () => structuredClone(snapshot),
  preflight: async candidate => {
    if (candidate.expectedRevision !== snapshot.revision) throw new Error('预览版本已变化')
    return { status: 'READY', expectedRevision: candidate.expectedRevision, candidateHash: 'b'.repeat(64) }
  },
  apply: async candidate => {
    if (candidate.expectedRevision !== snapshot.revision) throw new Error('预览版本已变化')
    const previousRevision = snapshot.revision
    const next = (parseInt(snapshot.revision.slice(0, 8), 16) + 1).toString(16).padStart(8, '0').repeat(8)
    snapshot = { ...snapshot, revision: next, projects: structuredClone(candidate.projects), enabledMetrics: candidate.enabledMetrics ?? snapshot.enabledMetrics }
    return { status: 'CURRENT_VERIFIED', revision: next, previousRevision, collectorExecution: 'DISABLED' }
  }, resolveAnchor: noWrite }
</script>
<template>
  <div class="d217-preview-bar"><span>原页面整合预览 · 四个账户为虚构数据，保存仅在内存中生效，刷新重置</span><label>预览身份 <select v-model="role"><option value="user">使用者</option><option value="operator">运维</option><option value="admin">管理员</option></select></label></div>
  <App :key="role" :session="session" :adapter="adapter" :overview-adapter="adapter" :configuration-adapter="configurationAdapter" history-read-only />
</template>
<style scoped>
.d217-preview-bar { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; padding:8px 22px; background:#232034; color:#e1ceaa; font-size:12px; }
.d217-preview-bar label { display:flex; align-items:center; gap:8px; }
.d217-preview-bar select { background:#111726; border:1px solid #645579; color:#dfdbea; padding:4px 10px; border-radius:5px; }
</style>
