<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { AiMaterialKey, MaterialTypeKey, MaterialTypeQuery, MaterialTypeReview, MaterialTypeSelection, MetricKey } from '../domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from '../domain/formatting'
import { comparisonClass } from '../domain/comparison'
import MetricValue from './MetricValue.vue'
import SpendCurve from './SpendCurve.vue'
import DateRangePicker from './DateRangePicker.vue'
import UiIcon from './UiIcon.vue'
import { validRange } from '../time/ranges'

const props = defineProps<{ result: MaterialTypeReview; projectName: string; saved?: MaterialTypeSelection; load?: (query: MaterialTypeQuery) => Promise<MaterialTypeReview> }>()
const emit = defineEmits<{ updated: [selection: MaterialTypeSelection] }>()
const displayed = ref(props.saved?.result ?? props.result)
function initialQuery(): MaterialTypeQuery {
  if (props.saved) return JSON.parse(JSON.stringify(props.saved.query)) as MaterialTypeQuery
  const current = props.result.periods.find(p => p.key === 'CURRENT')!, previous = props.result.periods.find(p => p.key === 'PREVIOUS')!
  return { current: { ...current.range }, currentTest: { ...(current.testRange ?? current.range) },
    previous: { ...previous.range }, previousTest: { ...(previous.testRange ?? previous.range) } }
}
const query = ref<MaterialTypeQuery>(initialQuery())
const committed = ref<MaterialTypeQuery>(initialQuery())
const loading = ref(false), error = ref('')
const pending = computed(() => JSON.stringify(query.value) !== JSON.stringify(committed.value))
const queryFields = [
  { key: 'current', label: '本周期' }, { key: 'currentTest', label: '本周期素材测试周期' },
  { key: 'previous', label: '上周期' }, { key: 'previousTest', label: '上周期素材测试周期' },
] as const
let queryRequest = 0
async function refresh() {
  error.value = ''
  if (queryFields.some(field => !validRange(query.value[field.key]))) { error.value = '请检查四组日期：开始日不能晚于结束日，每组不超过3660天。'; return }
  if (!props.load) { error.value = '当前入口未提供独立素材类型查询，请联系维护人员接入后再刷新。'; return }
  const request = ++queryRequest, selectedQuery = JSON.parse(JSON.stringify(query.value)) as MaterialTypeQuery
  loading.value = true
  try {
    const value = await props.load(selectedQuery)
    if (request !== queryRequest) return
    for (const key of ['CURRENT','PREVIOUS'] as const) {
      const actual = value.periods.find(period => period.key === key)
      const stat = key === 'CURRENT' ? selectedQuery.current : selectedQuery.previous
      const test = key === 'CURRENT' ? selectedQuery.currentTest : selectedQuery.previousTest
      const actualTest = actual?.testRange ?? actual?.range
      if (!actual || actual.range.start !== stat.start || actual.range.end !== stat.end
        || actualTest?.start !== test.start || actualTest.end !== test.end) throw new Error('返回的统计周期或素材测试周期不一致，已保留上次结果。')
    }
    displayed.value = value; committed.value = selectedQuery
    emit('updated', { query: selectedQuery, result: value, refreshedAt: new Date().toISOString() })
    closeAll()
  } catch (failure) { if (request === queryRequest) error.value = failure instanceof Error ? failure.message : '素材类型查询失败，已保留上次结果。' }
  finally { if (request === queryRequest) loading.value = false }
}
const types = ['original', 'mixed', 'other'] as const
const names = { original: '原创', mixed: '混剪', other: '其他', business: '含 AI · 商单', department: '含 AI · 部门' }
type DetailKey = keyof typeof names
const columns = [
  { key: 'spend', label: '消耗', format: 'decimal' },
  { key: 'roi', label: 'ROI', format: 'decimal' },
  { key: 'materialCount', label: '素材投放数量', format: 'integer' },
  { key: 'ctr', label: '点击率', format: 'percent' },
  { key: 'cvr', label: '转化率', format: 'percent' },
  { key: 'cpm', label: 'CPM', format: 'decimal' },
  { key: 'cpc', label: 'CPC', format: 'decimal' },
] as const
const primary = columns.slice(0, 3)
const secondary = [columns[5], columns[6], columns[3], columns[4]]
const period = (key: 'CURRENT' | 'PREVIOUS') => displayed.value.periods.find(p => p.key === key)!
const metric = (key: 'CURRENT' | 'PREVIOUS', type: MaterialTypeKey, field: MetricKey) => period(key).groups.find(g => g.key === type)!.metrics[field]
const compare = (type: MaterialTypeKey, field: MetricKey) => displayed.value.comparisons.find(g => g.key === type)!.metrics[field]
const current = computed(() => period('CURRENT'))
const partial = computed(() => displayed.value.periods.some(p => p.coverage.state !== 'COMPLETE' || p.unresolvedDateMaterials > 0
  || p.groups.some(g => columns.some(c => g.metrics[c.key].partial || g.metrics[c.key].status === 'UNKNOWN_INPUT'))))
const selected = ref<DetailKey | null>(null)
const aiOpened = ref(false)
const aiDialog = ref<HTMLDialogElement>()
const aiCloseButton = ref<HTMLButtonElement>()
const aiNames = { business: '商单', department: '部门' }
const aiKeys = ['business','department'] as const
const aiMetric = (key: 'CURRENT' | 'PREVIOUS', type: AiMaterialKey, field: MetricKey) => displayed.value.ai!.periods.find(p => p.key === key)!.groups.find(g => g.key === type)!.metrics[field]
const aiCompare = (type: AiMaterialKey, field: MetricKey) => displayed.value.ai!.comparisons.find(g => g.key === type)!.metrics[field]
let aiReturnFocus: HTMLElement | null = null
async function openAi() {
  if (!displayed.value.ai) return
  aiReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  aiOpened.value = true; await nextTick(); aiDialog.value?.showModal?.(); aiCloseButton.value?.focus()
}
function closeAi() { const wasOpen = aiOpened.value; aiDialog.value?.close?.(); aiOpened.value = false; if (wasOpen) aiReturnFocus?.focus() }
const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()
const expanded = ref('')
const limits = ref({ original: 10, mixed: 10, other: 10, business: 10, department: 10 })
const options = computed(() => [...new Set([10,20,30,40,50,...Object.values(limits.value)])].sort((a,b) => a-b))
const detailMetric = (key: 'CURRENT' | 'PREVIOUS', type: DetailKey, field: MetricKey) => type === 'business' || type === 'department' ? aiMetric(key,type,field) : metric(key,type,field)
const rows = computed(() => {
  const type = selected.value
  if (!type) return []
  const source = type === 'business' || type === 'department' ? displayed.value.ai?.materials?.[type] : displayed.value.materials[type]
  return (source ?? []).slice(0, limits.value[type])
})
const emptyMessage = computed(() => !selected.value || detailMetric('CURRENT',selected.value,'spend').status !== 'VALID'
  ? '当前数据不可用，无法确定素材明细。' : partial.value ? '已到数据中暂无符合条件的素材，仍有缺失或待核。' : '本周期无符合双日期条件的素材。')
let returnFocus: HTMLElement | null = null
function setLimit(value: number) { if (selected.value && Number.isFinite(value)) limits.value[selected.value] = Math.max(1, Math.min(200, Math.trunc(value))) }
async function open(type: DetailKey) {
  if ((type === 'business' || type === 'department') && !displayed.value.ai?.materials) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  selected.value = type; expanded.value = ''
  await nextTick(); dialog.value?.showModal?.(); closeButton.value?.focus()
}
function close() { const wasOpen = selected.value !== null; dialog.value?.close?.(); selected.value = null; expanded.value = ''; if (wasOpen) returnFocus?.focus() }
function closeAll() { close(); closeAi() }
watch(() => props.result, () => { closeAll(); if (!props.saved) { displayed.value = props.result; query.value = initialQuery(); committed.value = initialQuery() } })
onBeforeUnmount(() => { queryRequest++; dialog.value?.close?.(); aiDialog.value?.close?.() })
</script>

<template>
  <section class="material-type-review" aria-labelledby="material-type-title">
    <form class="period-toolbar material-type-toolbar" aria-label="素材类型独立周期" @submit.prevent="refresh">
      <h3>素材类型数据</h3>
      <div class="material-type-date-grid"><DateRangePicker v-for="field in queryFields" :key="field.key" v-model="query[field.key]" :label="field.label" :disabled="loading" /></div>
      <button type="submit" class="primary-button" :disabled="loading"><UiIcon name="refresh" :size="16"/>{{ loading ? '正在刷新…' : '刷新数据' }}</button>
    </form>
    <p class="module-note material-type-scope">统计日期按本周期／上周期筛选，首次投放日期按对应素材测试周期筛选；只更新 05、06。</p>
    <p v-if="pending" class="form-error" role="status">条件已修改，请刷新数据；05、06 仍显示上次结果。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <h4 id="material-type-title" class="pr-section-title"><small>05</small>素材类型</h4>
    <p v-if="partial" class="module-note material-type-attention" role="status">部分数据缺失或待核，以下为已有数据结果。</p>
    <div class="table-shell material-type-table-shell">
      <table class="business-table material-type-table" aria-label="本期素材类型七项指标">
        <thead><tr><th>本期素材类型</th><th v-for="column in columns" :key="column.key">{{ column.label }}</th></tr></thead>
        <tbody><tr v-for="type in types" :key="type"><th scope="row"><button v-if="type === 'other'" type="button" class="type-detail-button other-detail-button" aria-haspopup="dialog" aria-label="查看其他素材" @click="open(type)">{{ names[type] }}</button><template v-else>{{ names[type] }}</template></th><td v-for="column in columns" :key="column.key"><MetricValue :result="metric('CURRENT',type,column.key)" :format="column.format" /></td></tr><tr v-if="displayed.ai" class="ai-summary-row" @click="openAi"><th scope="row"><button type="button" class="ai-summary-button" aria-haspopup="dialog" @click.stop="openAi">含 AI <span aria-hidden="true">↗</span></button></th><td v-for="column in columns" :key="column.key"><MetricValue :result="aiMetric('CURRENT','ai',column.key)" :format="column.format" /></td></tr></tbody>
      </table>
    </div>
    <p v-if="displayed.ai" class="module-note ai-overlay-note">含 AI 为独立叠加统计，可与原创／混剪／其他重叠；不区分大小写，AIR 片段不计入。点击含 AI 查看商单与部门。</p>
    <p v-else class="module-note">这份旧结果尚无含 AI 统计，刷新素材类型数据后可查看。</p>
    <h4 class="pr-section-title"><small>06</small>原创与混剪表现</h4>
    <div class="pr-performance material-type-performance">
      <article v-for="type in (['original','mixed'] as const)" :key="type" :class="['pr-group',type === 'original' ? 'new' : 'historical']">
        <div class="pr-section-head"><h4><span class="pr-icon">{{ type === 'original' ? '原' : '混' }}</span>{{ names[type] }}素材</h4><button class="pr-detail-button" type="button" @click="open(type)">查看{{ names[type] }}素材 ↗</button></div>
        <div class="pr-group-layout">
          <div class="pr-group-metrics"><article v-for="item in primary" :key="item.key" class="pr-kpi"><span>{{ item.label }}</span><strong class="pr-kpi-value"><MetricValue :result="metric('CURRENT',type,item.key)" :format="item.format"/><small v-if="item.key !== 'roi'">{{ item.key === 'spend' ? '元' : '个' }}</small></strong><div class="pr-kpi-footer"><span>上期 <MetricValue :result="metric('PREVIOUS',type,item.key)" :format="item.format" /></span><b :class="comparisonClass(compare(type,item.key))">对比上期 <MetricValue :result="compare(type,item.key)" format="percent" /></b></div></article></div>
          <div class="pr-secondary-kpis"><article v-for="item in secondary" :key="item.key"><span>{{ item.label }}</span><strong><MetricValue :result="metric('CURRENT',type,item.key)" :format="item.format" /></strong><small>上期 <MetricValue :result="metric('PREVIOUS',type,item.key)" :format="item.format" /></small><small :class="comparisonClass(compare(type,item.key))">对比上期 <MetricValue :result="compare(type,item.key)" format="percent" /></small></article></div>
        </div>
      </article>
    </div>
    <dialog v-if="aiOpened && displayed.ai" ref="aiDialog" class="pr-dialog ai-breakdown-dialog" aria-labelledby="ai-breakdown-title" @cancel.prevent="closeAi" @click="event => { if (event.target === aiDialog) closeAi() }">
      <header class="pr-dialog-head"><div><h3 id="ai-breakdown-title">含 AI · 商单与部门</h3><p>{{ projectName }} · 本周期 {{ current.range.start }} — {{ current.range.end }}</p><p>素材测试周期 {{ (current.testRange ?? current.range).start }} — {{ (current.testRange ?? current.range).end }}</p></div><button ref="aiCloseButton" type="button" aria-label="关闭含AI细分" @click="closeAi">×</button></header>
      <div class="pr-dialog-body"><p class="ai-breakdown-note">先筛选含 AI 素材，再分别识别名称中的“商单”“部门”。两个词都有时两块均计入；都没有时仅计入含 AI 总体。两块不可相加作为总计。</p>
        <div class="pr-performance"><article v-for="type in aiKeys" :key="type" class="pr-group ai-breakdown-card"><div class="pr-section-head"><h4>{{ aiNames[type] }}</h4><button type="button" class="pr-detail-button" :disabled="!displayed.ai.materials" @click="open(type)">查看{{ aiNames[type] }}素材 ↗</button></div>
          <div class="pr-group-metrics"><article v-for="item in primary" :key="item.key" class="pr-kpi"><span>{{ item.label }}</span><strong class="pr-kpi-value"><MetricValue :result="aiMetric('CURRENT',type,item.key)" :format="item.format"/></strong><div class="pr-kpi-footer"><span>上期 <MetricValue :result="aiMetric('PREVIOUS',type,item.key)" :format="item.format"/></span><b :class="comparisonClass(aiCompare(type,item.key))">对比上期 <MetricValue :result="aiCompare(type,item.key)" format="percent"/></b></div></article></div>
          <div class="pr-secondary-kpis"><article v-for="item in secondary" :key="item.key"><span>{{ item.label }}</span><strong><MetricValue :result="aiMetric('CURRENT',type,item.key)" :format="item.format"/></strong><small>上期 <MetricValue :result="aiMetric('PREVIOUS',type,item.key)" :format="item.format"/></small><small :class="comparisonClass(aiCompare(type,item.key))">对比上期 <MetricValue :result="aiCompare(type,item.key)" format="percent"/></small></article></div>
        </article></div><p v-if="!displayed.ai.materials" class="module-note" role="status">这份旧结果尚无商单／部门素材明细，请刷新素材类型数据后查看。</p>
      </div>
    </dialog>
    <dialog v-if="selected" ref="dialog" class="pr-dialog material-type-dialog" aria-labelledby="material-type-dialog-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
      <header class="pr-dialog-head"><div><h3 id="material-type-dialog-title">{{ names[selected] }}素材 · 明细</h3><p>{{ projectName }} · 本期 {{ current.range.start }} — {{ current.range.end }}</p><p>首次投放时间 {{ (current.testRange ?? current.range).start }} — {{ (current.testRange ?? current.range).end }}</p></div>
        <div class="pr-detail-limit" role="group" aria-label="类型明细显示数量"><span>显示数量</span><button type="button" aria-label="减少类型明细显示数量" :disabled="limits[selected] <= 1" @click="setLimit(limits[selected]-1)">−</button><select aria-label="类型明细显示数量" :value="limits[selected]" @change="setLimit(Number(($event.target as HTMLSelectElement).value))"><option v-for="limit in options" :key="limit" :value="limit">{{ limit }} 条</option></select><button type="button" aria-label="增加类型明细显示数量" :disabled="limits[selected] >= 200" @click="setLimit(limits[selected]+1)">+</button></div>
        <button ref="closeButton" type="button" aria-label="关闭类型素材明细" @click="close">×</button>
      </header>
      <div class="pr-dialog-body"><div class="pr-dialog-summary"><span v-for="item in columns" :key="item.key">本期{{ item.label }} <b><MetricValue :result="detailMetric('CURRENT',selected,item.key)" :format="item.format" /></b></span></div>
        <div class="table-shell"><table class="business-table pr-detail-table"><thead><tr><th>本期素材</th><th>本期消耗</th><th>同素材上期消耗</th><th>本期 ROI</th><th>点击率</th><th>转化率</th><th>CPM</th><th>CPC</th><th>展开</th></tr></thead>
          <tbody><template v-for="row in rows" :key="row.materialId"><tr><td><strong>{{ row.rank }}. {{ row.materialName ?? '名称未知' }}</strong><small>{{ formatMaterialLabel(row.materialId,row.materialName) }} · 首次投放时间 {{ formatCreationDate(row.creationDate) }}</small></td><td><MetricValue :result="row.metrics.spend" /></td><td><MetricValue :result="row.previousSpend" /></td><td><MetricValue :result="row.metrics.roi" /></td><td><MetricValue :result="row.metrics.ctr" format="percent" /></td><td><MetricValue :result="row.metrics.cvr" format="percent" /></td><td><MetricValue :result="row.metrics.cpm" /></td><td><MetricValue :result="row.metrics.cpc" /></td><td><button class="expand-button" type="button" :aria-expanded="expanded === row.materialId" :aria-label="`${expanded === row.materialId ? '收起' : '展开'}第 ${row.rank} 名类型素材消耗曲线`" @click="expanded = expanded === row.materialId ? '' : row.materialId">{{ expanded === row.materialId ? '收起' : '展开' }}</button></td></tr>
          <tr v-if="expanded === row.materialId" class="pr-expanded"><td colspan="9"><h4>本周期逐日消耗</h4><SpendCurve :days="row.dailySpend" expanded label="类型素材本期逐日消耗，缺失日期断线"/><div class="daily-value-list"><span v-for="day in row.dailySpend" :key="day.date">{{ day.date }} <MetricValue :result="day.spend" /></span></div></td></tr></template><tr v-if="!rows.length"><td colspan="9">{{ emptyMessage }}</td></tr></tbody>
        </table></div><p class="module-note">同素材上期消耗按上周期统计日期及上周期素材测试周期共同筛选；两组筛选独立生效。</p>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.material-type-table-shell{border:1px solid #2e3449;border-radius:12px;background:#141827;margin-bottom:30px}
.material-type-toolbar{border:1px solid var(--line,#30364b);border-radius:12px;padding:20px;margin:32px 0 10px;gap:20px}.material-type-toolbar h3{font-size:20px}.material-type-date-grid{display:grid;grid-template-columns:repeat(2,minmax(280px,1fr));gap:14px 22px;flex:1}.material-type-scope{margin-bottom:24px}@media(max-width:900px){.material-type-date-grid{grid-template-columns:1fr;width:100%}.material-type-toolbar{align-items:flex-start}}
.material-type-table{width:100%;min-width:850px}
.material-type-review .material-type-table th,.material-type-review .material-type-table td{text-align:center;vertical-align:middle;padding:20px 16px}
.material-type-table tbody th{font-size:18px;font-weight:600;color:#d6c798}.material-type-table thead th{white-space:nowrap}
.ai-summary-row{cursor:pointer;background:#bba3e00b}.ai-summary-row:hover{background:#bba3e017}.ai-summary-button,.type-detail-button{border:0;background:none;color:inherit;font:inherit;font-size:18px;font-weight:600;cursor:pointer}.other-detail-button::after{content:" ↗";font-size:14px;margin-left:7px}.ai-breakdown-card .pr-detail-button:disabled{opacity:.5;cursor:not-allowed}.ai-summary-button span{font-size:14px;margin-left:7px}.ai-overlay-note{margin:-14px 0 28px}.ai-breakdown-note{border:1px solid #7c719b55;background:#82739915;border-radius:10px;padding:14px 18px;margin-bottom:22px;line-height:1.7;color:#c7bfdc}.ai-breakdown-card .pr-secondary-kpis{margin-top:22px}
.material-type-attention{color:#e2ab88}.material-type-dialog .pr-dialog-head p+p{margin-top:6px}
.material-type-dialog .pr-dialog-summary{gap:12px 24px}.material-type-dialog .pr-detail-table{min-width:1050px}
</style>
