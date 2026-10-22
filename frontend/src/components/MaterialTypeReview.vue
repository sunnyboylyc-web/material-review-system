<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { MaterialTypeKey, MaterialTypeReview, MetricKey } from '../domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from '../domain/formatting'
import { comparisonClass } from '../domain/comparison'
import MetricValue from './MetricValue.vue'
import SpendCurve from './SpendCurve.vue'

const props = defineProps<{ result: MaterialTypeReview; projectName: string }>()
const types = ['original', 'mixed', 'other'] as const
const names = { original: '原创', mixed: '混剪', other: '其他' }
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
const period = (key: 'CURRENT' | 'PREVIOUS') => props.result.periods.find(p => p.key === key)!
const metric = (key: 'CURRENT' | 'PREVIOUS', type: MaterialTypeKey, field: MetricKey) => period(key).groups.find(g => g.key === type)!.metrics[field]
const compare = (type: MaterialTypeKey, field: MetricKey) => props.result.comparisons.find(g => g.key === type)!.metrics[field]
const current = computed(() => period('CURRENT'))
const partial = computed(() => props.result.periods.some(p => p.coverage.state !== 'COMPLETE' || p.unresolvedDateMaterials > 0
  || p.groups.some(g => columns.some(c => g.metrics[c.key].partial || g.metrics[c.key].status === 'UNKNOWN_INPUT'))))
const selected = ref<'original' | 'mixed' | null>(null)
const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()
const expanded = ref('')
const limits = ref({ original: 10, mixed: 10 })
const options = computed(() => [...new Set([10,20,30,40,50,...Object.values(limits.value)])].sort((a,b) => a-b))
const rows = computed(() => selected.value ? props.result.materials[selected.value].slice(0, limits.value[selected.value]) : [])
const emptyMessage = computed(() => !selected.value || metric('CURRENT',selected.value,'spend').status !== 'VALID'
  ? '当前数据不可用，无法确定素材明细。' : partial.value ? '已到数据中暂无符合条件的素材，仍有缺失或待核。' : '本周期无符合双日期条件的素材。')
let returnFocus: HTMLElement | null = null
function setLimit(value: number) { if (selected.value) limits.value[selected.value] = Math.max(1, Math.min(200, value)) }
async function open(type: 'original' | 'mixed') {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  selected.value = type; expanded.value = ''
  await nextTick(); dialog.value?.showModal?.(); closeButton.value?.focus()
}
function close() { dialog.value?.close?.(); selected.value = null; expanded.value = ''; returnFocus?.focus() }
watch(() => props.result, close)
onBeforeUnmount(() => dialog.value?.close?.())
</script>

<template>
  <section class="material-type-review" aria-labelledby="material-type-title">
    <h4 id="material-type-title" class="pr-section-title"><small>05</small>素材类型</h4>
    <p v-if="partial" class="module-note material-type-attention" role="status">部分数据缺失或待核，以下为已有数据结果。</p>
    <div class="table-shell material-type-table-shell">
      <table class="business-table material-type-table" aria-label="本期素材类型七项指标">
        <thead><tr><th>本期素材类型</th><th v-for="column in columns" :key="column.key">{{ column.label }}</th></tr></thead>
        <tbody><tr v-for="type in types" :key="type"><th scope="row">{{ names[type] }}</th><td v-for="column in columns" :key="column.key"><MetricValue :result="metric('CURRENT',type,column.key)" :format="column.format" /></td></tr></tbody>
      </table>
    </div>
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
    <dialog v-if="selected" ref="dialog" class="pr-dialog material-type-dialog" aria-labelledby="material-type-dialog-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
      <header class="pr-dialog-head"><div><h3 id="material-type-dialog-title">{{ names[selected] }}素材 · 明细</h3><p>{{ projectName }} · 本期 {{ current.range.start }} — {{ current.range.end }}</p><p>统计日期与首次投放时间均在本期内</p></div>
        <div class="pr-detail-limit" role="group" aria-label="类型明细显示数量"><span>显示数量</span><button type="button" aria-label="减少类型明细显示数量" :disabled="limits[selected] <= 1" @click="setLimit(limits[selected]-1)">−</button><select aria-label="类型明细显示数量" :value="limits[selected]" @change="setLimit(Number(($event.target as HTMLSelectElement).value))"><option v-for="limit in options" :key="limit" :value="limit">{{ limit }} 条</option></select><button type="button" aria-label="增加类型明细显示数量" :disabled="limits[selected] >= 200" @click="setLimit(limits[selected]+1)">+</button></div>
        <button ref="closeButton" type="button" aria-label="关闭类型素材明细" @click="close">×</button>
      </header>
      <div class="pr-dialog-body"><div class="pr-dialog-summary"><span v-for="item in columns" :key="item.key">本期{{ item.label }} <b><MetricValue :result="metric('CURRENT',selected,item.key)" :format="item.format" /></b></span></div>
        <div class="table-shell"><table class="business-table pr-detail-table"><thead><tr><th>本期素材</th><th>本期消耗</th><th>同素材上期消耗</th><th>本期 ROI</th><th>点击率</th><th>转化率</th><th>CPM</th><th>CPC</th><th>展开</th></tr></thead>
          <tbody><template v-for="row in rows" :key="row.materialId"><tr><td><strong>{{ row.rank }}. {{ row.materialName ?? '名称未知' }}</strong><small>{{ formatMaterialLabel(row.materialId,row.materialName) }} · 首次投放时间 {{ formatCreationDate(row.creationDate) }}</small></td><td><MetricValue :result="row.metrics.spend" /></td><td><MetricValue :result="row.previousSpend" /></td><td><MetricValue :result="row.metrics.roi" /></td><td><MetricValue :result="row.metrics.ctr" format="percent" /></td><td><MetricValue :result="row.metrics.cvr" format="percent" /></td><td><MetricValue :result="row.metrics.cpm" /></td><td><MetricValue :result="row.metrics.cpc" /></td><td><button class="expand-button" type="button" :aria-expanded="expanded === row.materialId" :aria-label="`${expanded === row.materialId ? '收起' : '展开'}第 ${row.rank} 名类型素材消耗曲线`" @click="expanded = expanded === row.materialId ? '' : row.materialId">{{ expanded === row.materialId ? '收起' : '展开' }}</button></td></tr>
          <tr v-if="expanded === row.materialId" class="pr-expanded"><td colspan="9"><h4>本周期逐日消耗</h4><SpendCurve :days="row.dailySpend" expanded label="类型素材本期逐日消耗，缺失日期断线"/><div class="daily-value-list"><span v-for="day in row.dailySpend" :key="day.date">{{ day.date }} <MetricValue :result="day.spend" /></span></div></td></tr></template><tr v-if="!rows.length"><td colspan="9">{{ emptyMessage }}</td></tr></tbody>
        </table></div><p class="module-note">上期消耗同样要求该素材首次投放时间在上期内；本期新增素材在不重叠的上期通常为 0。</p>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.material-type-table-shell{border:1px solid #2e3449;border-radius:12px;background:#141827;margin-bottom:30px}
.material-type-table{width:100%;min-width:850px}
.material-type-review .material-type-table th,.material-type-review .material-type-table td{text-align:center;vertical-align:middle;padding:20px 16px}
.material-type-table tbody th{font-size:18px;font-weight:600;color:#d6c798}.material-type-table thead th{white-space:nowrap}
.material-type-attention{color:#e2ab88}.material-type-dialog .pr-dialog-head p+p{margin-top:6px}
.material-type-dialog .pr-dialog-summary{gap:12px 24px}.material-type-dialog .pr-detail-table{min-width:1050px}
</style>
