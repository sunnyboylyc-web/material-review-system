<script setup lang="ts">
import { computed } from 'vue'
import type { NumericResult } from '../domain/dashboard'
const props = defineProps<{ days: { date: string; spend: NumericResult }[]; expanded?: boolean; label?: string }>()
const days = computed(() => [...props.days].sort((a, b) => a.date.localeCompare(b.date)))
const max = computed(() => Math.max(1, ...days.value.map(d => d.spend.status === 'VALID' ? d.spend.value! : 0)))
const x = (i: number) => 48 + i * 504 / Math.max(1, days.value.length - 1)
const y = (v: number) => 142 - v / max.value * 116
// Each unknown is a real break, never a zero or a line connecting across missing evidence.
const segments = computed(() => {
  const result: string[] = []; let points: string[] = []
  days.value.forEach((d, i) => {
    if (d.spend.status === 'VALID') points.push(`${x(i)},${y(d.spend.value!)}`)
    else { if (points.length) result.push(points.join(' ')); points = [] }
  })
  if (points.length) result.push(points.join(' '))
  return result
})
</script>
<template>
  <svg :class="['spend-curve', { 'spend-curve--expanded': expanded }]" viewBox="0 0 600 180" role="img" :aria-label="label ?? '七日消耗曲线，缺失日期断线'">
    <title>{{ days.map(d => `${d.date}: ${d.spend.status === 'VALID' ? d.spend.value : '未知'}`).join('；') }}</title>
    <template v-if="expanded">
      <path d="M48 18 V142 H552" class="chart-axis" />
      <text x="40" y="30" text-anchor="end">{{ max.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</text>
      <text x="40" y="146" text-anchor="end">0</text>
      <text x="48" y="12">消耗（元）</text>
      <template v-for="(d, i) in days" :key="d.date"><text v-if="i === 0 || i === days.length - 1 || i % Math.ceil(days.length / 7) === 0" :x="x(i)" y="165" text-anchor="middle">{{ d.date.slice(5).replace('-', '/') }}</text></template>
    </template>
    <polyline v-for="(segment, i) in segments" :key="i" :points="segment" fill="none" stroke="currentColor" :stroke-width="expanded ? 2.5 : 5" stroke-linecap="round" stroke-linejoin="round" />
    <template v-for="(d, i) in days" :key="d.date">
      <circle v-if="d.spend.status === 'VALID'" :cx="x(i)" :cy="y(d.spend.value!)" :r="expanded ? 4 : 6" fill="currentColor"><title>{{ d.date }} · {{ d.spend.value }} 元</title></circle>
      <text v-else-if="expanded" :x="x(i)" y="130" text-anchor="middle">缺数</text>
    </template>
  </svg>
</template>
