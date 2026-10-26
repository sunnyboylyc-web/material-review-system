<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'
import type { NumericResult, ReviewQuery, ReviewResult } from '../domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from '../domain/formatting'
import { shanghaiIsoDate, shiftIsoDate } from '../time/shanghai'
import MetricValue from './MetricValue.vue'
import SpendCurve from './SpendCurve.vue'
import UiIcon from './UiIcon.vue'

const props = withDefaults(defineProps<{
  projectId: string; projectName: string; active?: boolean
  revision?: number
  adapter?: Pick<DashboardDataAdapter, 'fetchReview'>
}>(), { active: true })
const yesterday = ref(shanghaiIsoDate(new Date(), -1))
const query = computed<ReviewQuery>(() => ({
  current: { start: shiftIsoDate(yesterday.value, -6), end: yesterday.value },
  previous: { start: shiftIsoDate(yesterday.value, -13), end: shiftIsoDate(yesterday.value, -7) },
}))
const result = ref<ReviewResult | null>(null), loading = ref(false), error = ref(''), selectedId = ref('')
const cache = new Map<string, ReviewResult>()
let generation = 0, timer: ReturnType<typeof setInterval> | undefined
const current = computed(() => result.value?.periods.find(period => period.key === 'CURRENT'))
const overall = computed(() => current.value?.groups.find(group => group.key === 'overall')?.metrics)
const cards = [
  { key: 'spend', label: '消耗', format: 'decimal', unit: '元' },
  { key: 'gmv', label: '成交金额', format: 'decimal', unit: '元' },
  { key: 'impressions', label: '曝光次数', format: 'integer', unit: '次' },
  { key: 'clicks', label: '点击次数', format: 'integer', unit: '次' },
] as const
// Additive server fields are optional during rollout. Never derive totals from ranked rows or ratios.
function metric(key: string): NumericResult | undefined {
  const value = (overall.value as unknown as Record<string, unknown> | undefined)?.[key]
  if (!value || typeof value !== 'object') return undefined
  const item = value as NumericResult
  if (typeof item.partial !== 'boolean' || !Array.isArray(item.partialReasons) || !item.partialReasons.every(reason => typeof reason === 'string')) return undefined
  if (item.status === 'VALID' ? typeof item.value !== 'number' || !Number.isFinite(item.value)
    : !['UNKNOWN_INPUT', 'ZERO_DENOMINATOR'].includes(item.status) || item.value !== null) return undefined
  return item
}
const topThree = computed(() => result.value?.topMaterials.slice(0, 3) ?? [])
const selected = computed(() => topThree.value.find(row => row.materialId === selectedId.value) ?? topThree.value[0])
const signal = computed(() => loading.value ? 'loading' : error.value ? 'red' : !result.value ? 'idle'
  : current.value?.coverage.state === 'COMPLETE'
    && cards.every(card => metric(card.key)?.status === 'VALID' && !metric(card.key)?.partial) ? 'green' : 'red')
const signalLabel = computed(() => error.value ? '近7天数据读取失败' : signal.value === 'green' ? '近7天数据完整' : signal.value === 'red' ? '近7天数据有缺失或指标不可用'
  : signal.value === 'loading' ? '近7天数据读取中' : error.value ? '近7天数据读取失败' : '近7天数据尚未读取')
function matches(data: ReviewResult, id: string, requested: ReviewQuery): boolean {
  const now = data.periods.find(period => period.key === 'CURRENT'), before = data.periods.find(period => period.key === 'PREVIOUS')
  return data.projectId === id && data.topComparisonDisplayLimit === 3
    && now?.range.start === requested.current.start && now.range.end === requested.current.end
    && before?.range.start === requested.previous.start && before.range.end === requested.previous.end
}
async function load(force = false): Promise<void> {
  const token = ++generation
  loading.value = false; error.value = ''; result.value = null; selectedId.value = ''
  if (!props.active || !props.projectId || !props.adapter) return
  const id = props.projectId, requested = structuredClone(query.value), key = `${id}:${requested.current.end}`
  if (!force && cache.has(key)) { result.value = cache.get(key)!; selectedId.value = topThree.value[0]?.materialId ?? ''; return }
  loading.value = true
  try {
    const value = await props.adapter.fetchReview(id, requested, 3)
    if (token !== generation || props.projectId !== id || !props.active) return
    if (!matches(value, id, requested)) throw new Error('项目或日期不一致，未展示本次结果，请重新刷新。')
    cache.set(key, value); result.value = value; selectedId.value = value.topMaterials[0]?.materialId ?? ''
  } catch (failure) {
    if (token === generation) { cache.delete(key); error.value = failure instanceof Error ? failure.message : '近7天数据读取失败，请重新刷新。' }
  } finally { if (token === generation) loading.value = false }
}
watch(() => [props.adapter, props.revision], () => cache.clear())
watch(() => [props.projectId, props.active, props.adapter, props.revision, yesterday.value], () => { void load() }, { immediate: true })
onMounted(() => { timer = setInterval(() => { yesterday.value = shanghaiIsoDate(new Date(), -1) }, 30_000) })
onBeforeUnmount(() => { generation++; if (timer) clearInterval(timer) })
</script>

<template>
  <section id="workspace-snapshot" class="home-overview home-snapshot" aria-labelledby="home-overview-title" :aria-busy="loading">
    <header class="home-overview-heading">
      <div><h2 id="home-overview-title" tabindex="-1">{{ projectName }}</h2><p>项目近7天数据<span>{{ query.current.start }} — {{ query.current.end }}</span></p></div>
      <div class="home-overview-actions">
        <span class="home-data-signal" :class="`home-data-signal--${signal}`" role="status" :aria-label="signalLabel" />
        <button class="primary-button home-refresh" type="button" :disabled="loading || !adapter || !projectId" aria-label="刷新项目近7天数据" @click="load(true)"><UiIcon name="refresh" :size="16"/>{{ loading ? '正在刷新…' : '刷新数据' }}</button>
      </div>
    </header>
    <p v-if="error" class="home-read-error" role="alert">{{ error }}</p>
    <p v-if="result?.evidenceKind === 'SYNTHETIC'" class="synthetic-coverage-warning">合成预览数据</p>
    <div class="home-metric-strip">
      <article v-for="card in cards" :key="card.key" :data-home-metric="card.key">
        <span>{{ card.label }}</span><strong><MetricValue v-if="metric(card.key)" :result="metric(card.key)" :format="card.format" hide-partial/><span v-else :aria-label="loading ? '正在读取' : '数据暂不可用'">—</span><small>{{ card.unit }}</small></strong>
      </article>
    </div>
    <div class="home-material-grid">
      <section class="home-ranking" aria-labelledby="home-ranking-title">
        <h3 id="home-ranking-title">近7天消耗 TOP3</h3>
        <div class="home-ranking-list">
          <button v-for="row in topThree" :key="row.materialId" type="button" :class="{ selected: selected?.materialId === row.materialId }" :aria-pressed="selected?.materialId === row.materialId" @click="selectedId = row.materialId">{{ row.materialName ?? '名称未知' }}</button>
          <p v-if="!topThree.length" class="home-empty">{{ loading ? '正在读取…' : result ? '暂无可展示素材' : '请选择项目查看' }}</p>
        </div>
      </section>
      <section class="home-material-detail" aria-label="近7天选中素材详情" aria-live="polite">
        <template v-if="selected">
          <div class="home-detail-meta"><span>{{ formatMaterialLabel(selected.materialId, selected.materialName) }}</span><span>首次投放时间 {{ formatCreationDate(selected.creationDate) }}</span></div>
          <h3>{{ selected.materialName ?? '名称未知' }}</h3>
          <div class="home-detail-metrics">
            <div><span>近7天消耗</span><strong><MetricValue :result="selected.currentSpend"/></strong></div>
            <div><span>近7天 ROI</span><strong><MetricValue :result="selected.roi"/></strong></div>
            <div><span>近7天 CPM</span><strong><MetricValue :result="selected.cpm"/></strong></div>
            <div><span>近7天点击率</span><strong><MetricValue :result="selected.ctr" format="percent"/></strong></div>
            <div><span>近7天转化率</span><strong><MetricValue :result="selected.cvr" format="percent"/></strong></div>
            <div><span>近7天消耗占比</span><strong><MetricValue :result="selected.spendShare" format="percent"/></strong></div>
          </div>
          <div class="home-detail-curve"><h4>近7天消耗曲线</h4><SpendCurve :days="selected.dailySpend" expanded/></div>
        </template>
        <div v-else class="home-empty">{{ loading ? '正在读取…' : '选择左侧素材查看详情' }}</div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.home-overview { max-width:1440px; margin-top:28px; scroll-margin-top: 130px; }
.home-overview-heading { display:flex; align-items:center; justify-content:space-between; gap:20px; margin-bottom:22px; }
.home-overview-heading h2 { margin:0; color:var(--sc-text); font:500 25px/1.4 Georgia,"SimSun",serif; letter-spacing:1px; }
.home-overview-heading p { display:flex; flex-wrap:wrap; gap:18px; margin:10px 0 0; color:var(--sc-text); font-size:13px; }
.home-overview-heading p span { color:var(--sc-muted); font-size:12px; }
.home-overview-actions { display:flex; align-items:center; gap:18px; }
.home-refresh { display:inline-flex; align-items:center; justify-content:center; gap:9px; min-height:42px; padding:10px 18px; font-size:12px; cursor:pointer; }
.home-refresh:disabled { opacity:.45; cursor:default; }
.home-data-signal { display:block; width:9px; height:9px; border-radius:50%; background:#78839b; box-shadow:0 0 10px #78839b33; }
.home-data-signal--green { background:#91d4b3; box-shadow:0 0 12px #91d4b350; }
.home-data-signal--red { background:#ff8d99; box-shadow:0 0 12px #ff8d9950; }
.home-data-signal--loading { background:#e8c884; }
.home-read-error { color:var(--danger,#ff9b9b); font-size:13px; }
.home-metric-strip { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); border:1px solid var(--sc-line); border-radius:16px; background:linear-gradient(110deg,#13172a,#0b0f1a); padding:28px 0; }
.home-metric-strip article { padding:0 28px; min-width:0; }
.home-metric-strip article+article { border-left:1px solid #2b3043; }
.home-metric-strip article>span { display:block; margin-bottom:18px; font-size:12px; color:var(--sc-muted); }
.home-metric-strip strong { display:block; color:var(--sc-text); font:500 clamp(22px,2.1vw,32px)/1.4 Consolas,monospace; overflow-wrap:anywhere; }
.home-metric-strip strong small { font:12px sans-serif; color:var(--sc-muted); margin-left:8px; }
.home-material-grid { display:grid; grid-template-columns:minmax(0,1.1fr) minmax(0,1fr); gap:20px; margin-top:22px; }
.home-ranking,.home-material-detail { border:1px solid var(--sc-line); border-radius:16px; padding:26px; background:linear-gradient(135deg,#101525,#0a0e18); min-width:0; }
.home-ranking { display:flex; flex-direction:column; }
.home-ranking h3 { margin:0 0 20px; font:600 18px/1.5 "Microsoft YaHei","PingFang SC",sans-serif; color:var(--sc-text); }
.home-ranking-list { display:grid; grid-auto-rows:minmax(102px,1fr); flex:1; }
.home-ranking-list button { min-height:102px; display:flex; align-items:center; border:0; border-top:1px solid #292f41; padding:20px 22px 20px 18px; text-align:left; background:none; color:#b9c1d5; font:14px/1.8 "Microsoft YaHei","PingFang SC",sans-serif; overflow-wrap:anywhere; cursor:pointer; position:relative; }
.home-ranking-list button.selected { background:linear-gradient(90deg,#d9b57213,transparent); color:#f1ddae; }
.home-ranking-list button.selected::before { content:''; position:absolute; left:0; top:23px; bottom:23px; width:2px; background:var(--sc-gold); }
.home-ranking-list button:hover { color:var(--sc-text); background:#ffffff05; }
.home-ranking-list button:focus-visible,.home-refresh:focus-visible { outline:2px solid var(--sc-gold); outline-offset:4px; }
.home-detail-meta { display:flex; flex-wrap:wrap; justify-content:space-between; gap:8px 16px; color:var(--sc-muted); font-size:10px; line-height:1.7; overflow-wrap:anywhere; }
.home-material-detail h3 { margin:15px 0 24px; color:var(--sc-text); font:500 17px/1.7 Georgia,"SimSun",serif; overflow-wrap:anywhere; }
.home-detail-metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:22px 16px; }
.home-detail-metrics > div > span { color:var(--sc-muted); font-size:12px; }
.home-detail-metrics strong { display:block; margin-top:8px; color:var(--sc-text); font:18px/1.5 Consolas,monospace; overflow-wrap:anywhere; }
.home-detail-curve { margin-top:26px; color:var(--sc-gold); }
.home-detail-curve h4 { color:var(--sc-muted); margin:0 0 8px; font-size:11px; font-weight:400; }
.home-detail-curve :deep(.spend-curve) { width:100%; height:150px; }
.home-empty { margin:0; color:var(--sc-muted); font-size:13px; padding:36px 0; text-align:center; }
@media(max-width:900px) { .home-material-grid { grid-template-columns:1fr; } .home-metric-strip article { padding:0 18px; } }
@media(max-width:540px) { .home-overview-heading { align-items:flex-start; gap:12px; } .home-overview-heading h2 { font-size:21px; } .home-overview-heading p { display:grid; gap:5px; } .home-overview-actions { gap:12px; } .home-metric-strip { grid-template-columns:repeat(2,minmax(0,1fr)); padding:0; } .home-metric-strip article { padding:22px 18px; } .home-metric-strip article:nth-child(3) { border-left:0; } .home-metric-strip article:nth-child(n+3) { border-top:1px solid #2b3043; } .home-metric-strip article>span { margin-bottom:12px; } .home-ranking,.home-material-detail { padding:20px; } .home-detail-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); } }
</style>
