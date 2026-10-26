<script setup lang="ts">
import { formatMaterialLabel } from '../domain/formatting'
import { computed } from 'vue'
import type { TopRow } from '../domain/dashboard'
const props = defineProps<{ rows: TopRow[]; selectedDate: string; selectedId: string }>()
const emit = defineEmits<{ select: [id: string] }>()
const points = computed(() => props.rows.flatMap(row => {
  const spend = row.dailySpend.find(d => d.date === props.selectedDate)?.spend
  return spend?.status === 'VALID' && row.dayRoi.status === 'VALID'
    ? [{ row, spend: spend.value!, roi: row.dayRoi.value! }] : []
}))
const maxSpend = computed(() => Math.max(1, ...points.value.map(p => p.spend)) * 1.08)
const maxRoi = computed(() => Math.max(1, ...points.value.map(p => p.roi)) * 1.08)
// Paint the selected point last without changing coordinates or ranking.
const paintedPoints = computed(() => [...points.value.filter(p => p.row.materialId !== props.selectedId),
  ...points.value.filter(p => p.row.materialId === props.selectedId)])
const selectedRow = computed(() => props.rows.find(row => row.materialId === props.selectedId))
</script>
<template>
  <div class="scatter-wrap">
    <div class="scatter-titlebar">
    <h4>消耗 &amp; ROI</h4>
    <label class="scatter-locator">榜单素材
      <select :value="selectedRow?.materialId ?? ''" @change="emit('select', ($event.target as HTMLSelectElement).value)">
        <option disabled value="">点重叠时，可按排名选择</option>
        <option v-for="row in rows" :key="row.materialId" :value="row.materialId">{{ row.rank }} · {{ row.materialName ?? formatMaterialLabel(row.materialId, row.materialName) }}</option>
      </select>
    </label>
    </div>
    <svg viewBox="0 0 560 390" role="group" aria-label="当天消耗与 ROI 散点图，点击排名联动素材">
      <path d="M58 25 V338 H530" class="chart-axis" />
      <text x="18" y="24">ROI</text><text x="475" y="379">消耗（元）</text>
      <template v-for="i in 4" :key="i">
        <text x="50" :y="342 - i * 75" text-anchor="end">{{ (maxRoi * i / 4).toFixed(1) }}</text>
        <text :x="58 + i * 118" y="357" text-anchor="middle">{{ (maxSpend * i / 4).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</text>
      </template>
      <text x="48" y="353">0</text>
      <g v-for="p in paintedPoints" :key="p.row.materialId" class="scatter-point" :class="{ 'is-selected': selectedId === p.row.materialId }"
        role="button" tabindex="0" :aria-label="`选择第 ${p.row.rank} 名：${p.row.materialName ?? formatMaterialLabel(p.row.materialId, p.row.materialName)}`"
        :transform="`translate(${58 + p.spend / maxSpend * 472}, ${338 - p.roi / maxRoi * 300})`"
        @click="emit('select', p.row.materialId)" @keydown.enter="emit('select', p.row.materialId)" @keydown.space.prevent="emit('select', p.row.materialId)">
        <title>{{ p.row.materialName ?? formatMaterialLabel(p.row.materialId, p.row.materialName) }} · 消耗 {{ p.spend.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }} · ROI {{ p.roi.toFixed(2) }}</title>
        <circle r="12" /><text y="4" text-anchor="middle">{{ p.row.rank }}</text>
      </g>
    </svg>
    <p v-if="rows.some(row => row.dayRoi.partial || row.dailySpend.find(d => d.date === selectedDate)?.spend.partial)" class="metric-unknown">部分点位指标缺失，按已有输入展示。</p>
    <p v-if="points.length !== rows.length" class="form-error">{{ rows.length - points.length }} 条素材的坐标指标未知，仍保留在榜单中，未补零绘点。</p>
  </div>
</template>
