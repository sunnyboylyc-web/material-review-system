<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { GroupKey, MetricKey, NumericResult, ReviewResult } from '../domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from '../domain/formatting'
import MetricValue from './MetricValue.vue'
import { comparisonClass } from '../domain/comparison'
import SpendCurve from './SpendCurve.vue'
import PeriodMaterialChart from './PeriodMaterialChart.vue'
import MaterialTypeReview from './MaterialTypeReview.vue'
const props = defineProps<{ result: ReviewResult; projectName: string }>()
type Cohort = 'new' | 'historical' | 'aigc'
const cohort = ref<GroupKey | null>(null)
const expanded = ref('')
const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()
let returnFocus: HTMLElement | null = null
const names = { new: '新素材', historical: '历史素材', overall: '整体 TOP10', aigc: 'AIGC' }
const detailLimits = ref({ new: 10, historical: 10, aigc: 10 })
const smallMetrics = [{key:'cpm',label:'CPM'},{key:'cpc',label:'CPC'},{key:'ctr',label:'点击率'},{key:'cvr',label:'转化率'}] as const
function setDetailLimit(value: number) {
  if (cohort.value && cohort.value !== 'overall') detailLimits.value[cohort.value] = Math.max(1, Math.min(200, value))
}
const detailOptions = computed(() => [...new Set([10,20,30,40,50,...(cohort.value && cohort.value !== 'overall' ? [detailLimits.value[cohort.value]] : [])])].sort((a,b)=>a-b))
const metrics = [{ key: 'spend', label: '消耗' }, { key: 'roi', label: 'ROI' }, { key: 'materialCount', label: '素材数' }] as const
const metric = (period: string, group: GroupKey, key: MetricKey) => props.result.periods.find(p => p.key === period)?.groups.find(g => g.key === group)?.metrics[key]
const compare = (group: GroupKey, key: MetricKey) => props.result.comparisons.find(g => g.key === group)?.metrics[key]
const currentPeriod = computed(() => props.result.periods.find(period => period.key === 'CURRENT'))
const rankingAvailable = (group: GroupKey) => metric('CURRENT','overall','spend')?.status === 'VALID' && metric('CURRENT',group,'spend')?.status === 'VALID' && (currentPeriod.value?.coverage.coveredDays ?? 0) > 0
const incompleteGroup = (group: GroupKey) => currentPeriod.value?.coverage.state === 'PARTIAL' || Boolean(metric('CURRENT',group,'spend')?.partial) || group !== 'overall' && (currentPeriod.value?.pendingMaterialCount ?? 0) > 0
const emptyMessage = computed(() => !cohort.value || !rankingAvailable(cohort.value) ? '本期数据或素材分类待核，无法确定榜单。' : incompleteGroup(cohort.value) ? '已到且已分类数据中无符合条件素材，仍有缺失或待核。' : '本期无符合条件的素材。')
const ratio = (period: string, group: Cohort, key: 'spend' | 'materialCount'): NumericResult => {
  const numerator = metric(period, group, key), denominator = metric(period, 'overall', key)
  const partial = Boolean(numerator?.partial || denominator?.partial)
  const partialReasons = [...new Set([...(numerator?.partialReasons ?? []), ...(denominator?.partialReasons ?? [])])]
  if (numerator?.status !== 'VALID' || denominator?.status !== 'VALID') return { status: 'UNKNOWN_INPUT', value: null, partial, partialReasons }
  return denominator.value === 0 ? { status: 'ZERO_DENOMINATOR', value: null, partial, partialReasons } : { status: 'VALID', value: numerator.value! / denominator.value!, partial, partialReasons }
}
const validRatio = (period: string, key: 'spend' | 'materialCount') => ['new', 'historical', 'aigc'].every(g => ratio(period, g as Cohort, key).status === 'VALID')
const width = (period: string, group: Cohort, key: 'spend' | 'materialCount') => `${Math.max(0, Math.min(100, (ratio(period, group, key).value ?? 0) * 100))}%`
const pendingRatio = (period: string, key: 'spend' | 'materialCount') => Math.max(0, 1 - (ratio(period, 'new', key).value ?? 0) - (ratio(period, 'historical', key).value ?? 0) - (ratio(period, 'aigc', key).value ?? 0))
const rows = computed(() => cohort.value === 'overall' ? props.result.topMaterials.slice(0,10) : cohort.value ? props.result.cohortMaterials[cohort.value].slice(0,detailLimits.value[cohort.value]) : [])
async function open(group: GroupKey, id = '') {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  cohort.value = group; expanded.value = id
  await nextTick()
  dialog.value?.showModal?.(); closeButton.value?.focus()
}
function close() { dialog.value?.close?.(); cohort.value = null; expanded.value = ''; returnFocus?.focus() }
watch(() => props.result, close)
onBeforeUnmount(() => dialog.value?.close?.())
</script>
<template>
  <div class="period-review-v2">
    <p v-if="result.evidenceKind === 'SYNTHETIC'" class="synthetic-coverage-warning">纯虚构演示数据，不用于业务判断。</p>
    <h4 class="pr-section-title"><small>01</small>项目整体表现</h4>
    <div class="pr-kpis">
      <article v-for="item in metrics" :key="item.key" class="pr-kpi">
        <span>{{ item.key === 'spend' ? '总消耗' : item.key === 'roi' ? '整体 ROI' : '素材数量' }}</span>
        <strong class="pr-kpi-value"><MetricValue :result="metric('CURRENT','overall',item.key)" :format="item.key === 'materialCount' ? 'integer' : 'decimal'" /><small v-if="item.key !== 'roi'">{{ item.key === 'spend' ? '元' : '条' }}</small></strong>
        <div class="pr-kpi-footer"><span>上期 <MetricValue :result="metric('PREVIOUS','overall',item.key)" :format="item.key === 'materialCount' ? 'integer' : 'decimal'" /></span><b :class="comparisonClass(compare('overall',item.key))">对比上期 <MetricValue :result="compare('overall',item.key)" format="percent" /></b></div>
      </article>
      <div class="pr-secondary-kpis"><article v-for="item in smallMetrics" :key="item.key"><span>{{ item.label }}</span><strong><MetricValue :result="metric('CURRENT','overall',item.key)" :format="item.key === 'ctr' || item.key === 'cvr' ? 'percent' : 'decimal'" /></strong><small>上期 <MetricValue :result="metric('PREVIOUS','overall',item.key)" :format="item.key === 'ctr' || item.key === 'cvr' ? 'percent' : 'decimal'" /></small><small :class="comparisonClass(compare('overall',item.key))">对比上期 <MetricValue :result="compare('overall',item.key)" format="percent" /></small></article></div>
    </div>
    <div class="pr-section-head"><h4 class="pr-section-title"><small>02</small>素材构成</h4><div class="pr-legend"><span>● 新素材</span><span>● 历史素材</span><span class="aigc-label">● AIGC</span><span>● 待核</span></div></div>
    <div class="pr-composition"><section v-for="key in (['spend', 'materialCount'] as const)" :key="key"><h4>{{ key === 'spend' ? '消耗占比' : '投放素材数量占比' }}</h4><div v-for="period in result.periods" :key="period.key" class="pr-ratio-row" :class="{ previous: period.key === 'PREVIOUS' }"><span>{{ period.label }}</span><div><div v-if="validRatio(period.key,key)" class="pr-ratio-track"><i class="new" :style="{width:width(period.key,'new',key)}"></i><i class="historical" :style="{width:width(period.key,'historical',key)}"></i><i class="aigc" :style="{width:width(period.key,'aigc',key)}"></i><i class="pending" :style="{width:`${pendingRatio(period.key,key)*100}%`}"></i></div><div v-else class="metric-unknown">占比不可计算</div><div class="pr-ratio-labels"><span>新素材 <MetricValue :result="ratio(period.key,'new',key)" format="percent" /></span><span>历史素材 <MetricValue :result="ratio(period.key,'historical',key)" format="percent" /></span><span class="aigc-label">AIGC <MetricValue :result="ratio(period.key,'aigc',key)" format="percent" /></span><span v-if="validRatio(period.key,key) && pendingRatio(period.key,key) > 0.000001">待核 {{ (pendingRatio(period.key,key)*100).toFixed(1) }}%</span></div></div></div></section></div>
    <div class="pr-section-head"><h4 class="pr-section-title"><small>03</small>新老素材表现</h4><button type="button" class="pr-aigc-button" @click="open('aigc')">查看 AIGC 素材 ↗</button></div>
    <div class="pr-performance">
      <article v-for="group in (['new','historical'] as const)" :key="group" :class="['pr-group',group]">
        <div class="pr-section-head"><h4><span class="pr-icon">{{ group === 'new' ? 'N' : 'H' }}</span>{{ names[group] }} <small class="group-share">本期消耗占比 <MetricValue :result="ratio('CURRENT',group,'spend')" format="percent" /></small></h4><button class="pr-detail-button" type="button" @click="open(group)">查看{{ names[group] }} ↗</button></div>
        <div class="pr-group-layout">
          <div class="pr-group-metrics"><article v-for="item in metrics" :key="item.key" class="pr-kpi"><span>{{ item.label }}</span><strong class="pr-kpi-value"><MetricValue :result="metric('CURRENT',group,item.key)" :format="item.key === 'materialCount' ? 'integer' : 'decimal'" /><small v-if="item.key !== 'roi'">{{ item.key === 'spend' ? '元' : '条' }}</small></strong><div class="pr-kpi-footer"><span>上期 <MetricValue :result="metric('PREVIOUS',group,item.key)" :format="item.key === 'materialCount' ? 'integer' : 'decimal'" /></span><b :class="comparisonClass(compare(group,item.key))">对比上期 <MetricValue :result="compare(group,item.key)" format="percent" /></b></div></article></div>
          <div class="pr-secondary-kpis"><article v-for="item in smallMetrics" :key="item.key"><span>{{ item.label }}</span><strong><MetricValue :result="metric('CURRENT',group,item.key)" :format="item.key === 'ctr' || item.key === 'cvr' ? 'percent' : 'decimal'" /></strong><small>上期 <MetricValue :result="metric('PREVIOUS',group,item.key)" :format="item.key === 'ctr' || item.key === 'cvr' ? 'percent' : 'decimal'" /></small><small :class="comparisonClass(compare(group,item.key))">对比上期 <MetricValue :result="compare(group,item.key)" format="percent" /></small></article></div>
        </div>
      </article>
    </div>
    <h4 class="pr-section-title"><small>04</small>TOP10素材消耗对比</h4>
    <div class="pr-material-charts"><PeriodMaterialChart title="本周期整体素材消耗对比" :rows="result.topMaterials" :ranking-available="rankingAvailable('overall')" :partial="incompleteGroup('overall')" @select="id => open('overall',id)" /></div>
    <MaterialTypeReview v-if="result.materialTypes" :result="result.materialTypes" :project-name="projectName" />
    <p v-else class="module-note">此份结果尚无素材类型统计，刷新数据后可查看。</p>
    <dialog v-if="cohort" ref="dialog" class="pr-dialog" aria-labelledby="pr-dialog-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }"><header class="pr-dialog-head"><div><h3 id="pr-dialog-title">{{ names[cohort] }} · 素材明细</h3><p>{{ projectName }} · <span v-for="period in result.periods" :key="period.key">{{ period.label }} {{ period.range.start }} — {{ period.range.end }}　</span></p></div><div v-if="cohort !== 'overall'" class="pr-detail-limit" role="group" aria-label="明细显示数量"><span>显示数量</span><button type="button" aria-label="减少明细显示数量" :disabled="detailLimits[cohort] <= 1" @click="setDetailLimit(detailLimits[cohort]-1)">−</button><select aria-label="快速选择明细显示数量" :value="detailLimits[cohort]" @change="setDetailLimit(Number(($event.target as HTMLSelectElement).value))"><option v-for="limit in detailOptions" :key="limit" :value="limit">{{ limit }} 条</option></select><button type="button" aria-label="增加明细显示数量" :disabled="detailLimits[cohort] >= 200" @click="setDetailLimit(detailLimits[cohort]+1)">+</button></div><button ref="closeButton" type="button" aria-label="关闭素材明细" @click="close">×</button></header><div class="pr-dialog-body"><div v-if="cohort !== 'overall'" class="pr-dialog-summary"><span v-for="item in metrics" :key="item.key">本期{{ item.label }} <b><MetricValue :result="metric('CURRENT',cohort,item.key)" :format="item.key === 'materialCount' ? 'integer' : 'decimal'" /></b></span></div><div class="table-shell"><table class="business-table pr-detail-table"><thead><tr><th>{{ cohort === 'overall' ? '本期素材' : '本期组内素材' }}</th><th>本期消耗</th><th>同素材上期消耗</th><th>本期 ROI</th><th>点击率</th><th>转化率</th><th>CPM</th><th>展开</th></tr></thead><tbody><template v-for="row in rows" :key="row.materialId"><tr><td><strong>{{ row.rank }}. {{ row.materialName ?? '名称未知' }}</strong><small>{{ formatMaterialLabel(row.materialId,row.materialName) }} · 首次投放时间 {{ formatCreationDate(row.creationDate) }}</small></td><td><MetricValue :result="row.currentSpend" /></td><td><MetricValue :result="row.previousSpend" /></td><td><MetricValue :result="row.roi" /></td><td><MetricValue :result="row.ctr" format="percent" /></td><td><MetricValue :result="row.cvr" format="percent" /></td><td><MetricValue :result="row.cpm" /></td><td><button type="button" class="expand-button" :aria-expanded="expanded === row.materialId" :aria-label="`${expanded === row.materialId ? '收起' : '展开'}第 ${row.rank} 名本周期消耗曲线`" @click="expanded = expanded === row.materialId ? '' : row.materialId"><svg viewBox="0 0 16 16" :class="{'is-expanded':expanded === row.materialId}" aria-hidden="true"><path d="m3 5 5 5 5-5" /></svg><span>{{ expanded === row.materialId ? '收起' : '展开' }}</span></button></td></tr><tr v-if="expanded === row.materialId" class="pr-expanded"><td colspan="8"><h4>本周期逐日消耗</h4><SpendCurve :days="row.dailySpend" expanded label="本周期消耗曲线，缺失日期断线" /><div class="daily-value-list"><span v-for="day in row.dailySpend" :key="day.date">{{ day.date }} <MetricValue :result="day.spend" /></span></div></td></tr></template><tr v-if="!rows.length"><td colspan="8">{{ emptyMessage }}</td></tr></tbody></table></div></div></dialog>
  </div>
</template>
