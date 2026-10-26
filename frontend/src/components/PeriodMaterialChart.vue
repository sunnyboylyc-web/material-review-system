<script setup lang="ts">
import { computed } from 'vue'
import MetricValue from './MetricValue.vue'
import { formatMaterialLabel } from '../domain/formatting'
import type { ReviewMaterialRow } from '../domain/dashboard'
const props = defineProps<{ rows: ReviewMaterialRow[]; title: string; rankingAvailable?: boolean; partial?: boolean }>()
defineEmits<{ select: [id: string] }>()
const rows = computed(() => props.rows.slice(0, 10))
const maximum = computed(() => Math.max(1, ...rows.value.flatMap(r => [r.currentSpend, r.previousSpend]).filter(v => v.status === 'VALID').map(v => v.value!)))
</script>
<template>
  <section class="period-material-chart">
    <div class="pr-section-head"><div class="pr-chart-legend"><span>● 上期</span><span>● 本期</span></div></div>
    <div v-if="rows.length" class="pr-chart-scroll">
      <div class="pr-column-chart" :style="{ minWidth: `${Math.max(480, rows.length * 92)}px` }">
        <button v-for="row in rows" :key="row.materialId" class="pr-column-item" type="button" :title="`${row.materialName ?? formatMaterialLabel(row.materialId, row.materialName)}（点击查看明细）`" :aria-label="`查看第 ${row.rank} 名 ${row.materialName ?? formatMaterialLabel(row.materialId, row.materialName)}`" @click="$emit('select', row.materialId)">
          <div class="pr-column-values"><span>上期 <MetricValue :result="row.previousSpend" /></span><span>本期 <MetricValue :result="row.currentSpend" /></span></div>
          <div class="pr-column-track"><span v-for="(metric, index) in [row.previousSpend, row.currentSpend]" :key="index" :class="['pr-column', { current: index === 1, unknown: metric.status !== 'VALID' }]" :style="metric.status === 'VALID' ? { height: `${Math.max(0, metric.value! / maximum * 100)}%` } : {}"><span v-if="metric.status !== 'VALID'">未知</span></span></div>
          <strong>{{ row.rank }}. {{ row.materialName ?? '名称未知' }}</strong>
        </button>
      </div>
    </div>
    <p v-else class="module-note">{{ rankingAvailable === false ? '本期数据或素材分类待核，无法确定榜单。' : partial ? '已到且已分类数据中无符合条件素材，仍有缺失或待核。' : '本期无符合条件的素材。' }}</p>
  </section>
</template>
