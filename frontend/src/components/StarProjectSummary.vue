<script setup lang="ts">
import { ref } from 'vue'
import MetricValue from './MetricValue.vue'
import type { GroupKey, MetricKey, NumericResult, ReviewPeriodResult, StarSummary } from '../domain/dashboard'

defineProps<{ synthetic: boolean }>()
const dialog = ref<HTMLDialogElement>()
const result = ref<StarSummary>()
const projectName = ref('')
const loading = ref(false)
const error = ref('')
let request = 0

async function open(name: string, load: () => Promise<StarSummary>) {
  const id = ++request
  projectName.value = name
  result.value = undefined
  error.value = ''
  loading.value = true
  dialog.value?.showModal()
  try {
    const summary = await load()
    if (id === request) result.value = summary
  } catch (failure) {
    if (id === request) error.value = failure instanceof Error ? failure.message : '示例项目统计读取失败。'
  } finally {
    if (id === request) loading.value = false
  }
}

defineExpose({ open })
const metrics = [
  { key: 'spend', label: '总消耗（元）', format: 'decimal' },
  { key: 'roi', label: '整体 ROI', format: 'decimal' },
  { key: 'materialCount', label: '素材数量', format: 'integer' },
  { key: 'cpm', label: 'CPM', format: 'decimal' },
  { key: 'cpc', label: 'CPC', format: 'decimal' },
  { key: 'ctr', label: '点击率', format: 'percent' },
  { key: 'cvr', label: '转化率', format: 'percent' },
] as const
const cohorts = [{ key: 'new', label: '新素材' }, { key: 'historical', label: '历史素材' }, { key: 'aigc', label: 'AIGC' }] as const
const sections = [{ key: 'overall', label: '项目整体表现' }, ...cohorts.slice(0, 2)] as const

function metric(period: ReviewPeriodResult, group: GroupKey, key: MetricKey) {
  return period.groups.find(item => item.key === group)?.metrics[key]
}

function countShare(period: ReviewPeriodResult, group: GroupKey): NumericResult {
  const numerator = metric(period, group, 'materialCount')
  const denominator = metric(period, 'overall', 'materialCount')
  const partial = Boolean(numerator?.partial || denominator?.partial)
  const partialReasons = [...new Set([...(numerator?.partialReasons ?? []), ...(denominator?.partialReasons ?? [])])]
  if (numerator?.value == null || denominator?.value == null) return { status: 'UNKNOWN_INPUT', value: null, partial, partialReasons }
  if (denominator.value === 0) return { status: 'ZERO_DENOMINATOR', value: null, partial, partialReasons }
  return { status: 'VALID', value: numerator.value / denominator.value, partial, partialReasons }
}
</script>

<template>
  <dialog ref="dialog" class="star-summary-dialog" aria-labelledby="star-summary-title" @close="request++">
    <div class="star-summary-heading">
      <h3 id="star-summary-title">示例项目统计</h3>
      <button class="primary-button" type="button" @click="dialog?.close()">关闭</button>
    </div>
    <p>{{ projectName }} · 名称含「示例」的素材与 AIGC 汇总</p>
    <p v-if="synthetic" class="synthetic-coverage-warning">本地演示数据</p>
    <p v-if="loading" role="status">正在计算示例项目统计…</p>
    <p v-else-if="error" class="form-error" role="alert">{{ error }}</p>
    <template v-else-if="result">
      <p v-for="period in result.periods" :key="period.key" class="module-note">
        {{ period.label }} {{ period.range.start }} — {{ period.range.end }} · 已入库 {{ period.coverage.coveredDays }}/{{ period.coverage.expectedDays }} 天
      </p>
      <template v-for="section in sections" :key="section.key">
      <section v-if="section.key === 'new'" class="star-summary-table" aria-labelledby="star-composition-title">
        <h4 id="star-composition-title">02 素材构成</h4>
        <table>
          <thead>
            <tr><th rowspan="2" scope="col">素材</th><th v-for="period in result.periods" :key="period.key" colspan="2" scope="colgroup">{{ period.label }}</th></tr>
            <tr><template v-for="period in result.periods" :key="period.key"><th scope="col">消耗占比</th><th scope="col">数量占比</th></template></tr>
          </thead>
          <tbody><tr v-for="cohort in cohorts" :key="cohort.key">
            <th scope="row">{{ cohort.label }}</th>
            <template v-for="period in result.periods" :key="period.key">
              <td><MetricValue :result="metric(period, cohort.key, 'spendShare')" format="percent" /></td>
              <td><MetricValue :result="countShare(period, cohort.key)" format="percent" /></td>
            </template>
          </tr></tbody>
        </table>
      </section>
      <h4 v-if="section.key === 'overall'">01 项目整体表现</h4>
      <h4 v-if="section.key === 'new'">03 新老素材表现</h4>
      <div class="star-summary-table">
        <table>
          <caption v-if="section.key !== 'overall'">{{ section.label }}</caption>
          <thead><tr><th scope="col">指标</th><th scope="col">本周期</th><th scope="col">对比期</th><th scope="col">对比指数</th></tr></thead>
          <tbody><tr v-for="item in metrics" :key="item.key">
            <th scope="row">{{ item.label }}</th>
            <td v-for="period in result.periods" :key="period.key"><MetricValue :result="metric(period, section.key, item.key)" :format="item.format" /></td>
            <td><MetricValue :result="result.comparisons.find(group => group.key === section.key)?.metrics[item.key]" format="percent" /></td>
          </tr></tbody>
        </table>
      </div>
      </template>
    </template>
  </dialog>
</template>

<style scoped>
.star-summary-dialog { width: min(720px, 94vw); max-height: 90vh; border: 1px solid #41475b; border-radius: 16px; padding: 24px; color: #eef2ff; background: #171c2b; }
.star-summary-dialog::backdrop { background: rgb(0 0 0 / 65%); }
.star-summary-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.star-summary-heading h3 { margin: 0; }
.star-summary-table { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 10px 8px; text-align: right; border-bottom: 1px solid #41475b; }
th:first-child { text-align: left; }
caption { text-align: left; font-weight: 600; padding: 12px 8px 0; }
</style>
