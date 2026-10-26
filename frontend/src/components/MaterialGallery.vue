<script setup lang="ts">
import type { TopRow } from '../domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from '../domain/formatting'
import MetricValue from './MetricValue.vue'
import SpendCurve from './SpendCurve.vue'
defineProps<{ rows: TopRow[]; date: string; selected: string }>()
const emit = defineEmits<{ select: [id: string] }>()
</script>
<template>
  <div class="material-gallery">
    <button v-for="(row, i) in rows" :key="row.materialId" type="button" class="material-card"
      :class="{ 'is-selected': selected === row.materialId }" :style="{ '--card-hue': `${[252,169,38,209][i % 4]}` }"
      :aria-label="`查看${formatMaterialLabel(row.materialId, row.materialName)}，${row.materialName ?? '名称未知'}的详情`"
      :aria-pressed="selected === row.materialId" @click="emit('select', row.materialId)">
      <div class="material-card-art">
        <p class="material-card-id">{{ formatMaterialLabel(row.materialId, row.materialName) }}</p>
        <div class="material-card-name">
          <span class="material-card-label">视频名称</span>
          <h4 class="material-card-title">{{ row.materialName ?? '名称未知' }}</h4>
        </div>
        <p class="material-card-date"><span class="material-card-label">首次投放时间</span><strong>{{ formatCreationDate(row.creationDate) }}</strong></p>
      </div>
      <div class="material-card-data">
        <span class="material-card-curve-label">近 7 天消耗曲线</span>
        <SpendCurve :days="row.dailySpend" label="近 7 天消耗曲线，缺失日期断线" />
        <div class="material-card-metrics">
          <p>当天消耗<strong><MetricValue :result="row.dailySpend.find(day => day.date === date)?.spend" /></strong></p>
          <p>当天 ROI<strong><MetricValue :result="row.dayRoi" /></strong></p>
          <b aria-hidden="true">↗</b>
        </div>
      </div>
    </button>
    <p v-if="!rows.length" class="module-note">当前结果无可展示的素材。</p>
  </div>
</template>

<style scoped>
.material-card { display:flex; flex-direction:column; min-width:0; height:100%; color:var(--ink, #e8e8f0); cursor:pointer; }
.material-card:focus-visible { outline:2px solid var(--sc-gold, #d5b779); outline-offset:4px; }
.material-card-art { display:flex; flex:1; flex-direction:column; gap:16px; height:auto; min-height:215px; padding:20px; }
.material-card-id { margin:0; color:#d5d9e6; font:11px/1.6 Consolas,monospace; overflow-wrap:anywhere; }
.material-card-name { min-width:0; }
.material-card-label { display:block; color:#aeb8d0; font-size:11px; line-height:1.6; }
.material-card-art .material-card-title { position:static; margin:4px 0 0!important; color:#f0f0f6; font-family:inherit; font-size:15px; font-weight:600; line-height:1.7; white-space:normal; overflow-wrap:anywhere; }
.material-card-date { display:grid; gap:3px; margin: auto 0 0; }
.material-card-date strong { color:#d5d9e6; font-size:12px; font-weight:500; line-height:1.6; overflow-wrap:anywhere; }
.material-card-data { padding:16px 20px 18px; }
.material-card-data > .material-card-curve-label { color:#aeb8d0; font-family:inherit; font-size:11px; line-height:1.6; letter-spacing:0; }
.material-card-data > .spend-curve { display:block; margin:7px 0 10px; }
.material-card-data > .material-card-metrics { align-items:flex-start; gap:16px; }
.material-card-metrics > p { flex:1; min-width:0; }
.material-card-metrics strong { overflow-wrap:anywhere; }
.material-card-metrics > b { align-self:center; }
@media(max-width:540px) { .material-card-art { min-height:195px; padding:18px; } .material-card-data { padding:14px 18px 16px; } }
</style>
