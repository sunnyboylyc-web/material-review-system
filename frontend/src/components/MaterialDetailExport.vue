<script setup lang="ts">
import type { ReviewMaterialRow,MaterialTypeRow,NumericResult } from '../domain/dashboard'
import { formatCreationDate,formatMaterialLabel,type NumberFormat } from '../domain/formatting'
import MeetingExport from './MeetingExport.vue'
import MetricValue from './MetricValue.vue'
const props=withDefaults(defineProps<{rows:ReadonlyArray<ReviewMaterialRow|MaterialTypeRow>;title:string;context:string;typeMetrics?:boolean;synthetic?:boolean;disabled?:boolean;candidateComplete?:boolean}>(),{candidateComplete:true})
function values(row:ReviewMaterialRow|MaterialTypeRow):{result:NumericResult;format:NumberFormat}[]{
 const m='metrics' in row?row.metrics:null
 return [{result:m?m.spend:(row as ReviewMaterialRow).currentSpend,format:'decimal'},
  {result:row.previousSpend,format:'decimal'},{result:m?m.roi:(row as ReviewMaterialRow).roi,format:'decimal'},
  {result:m?m.ctr:(row as ReviewMaterialRow).ctr,format:'percent'},{result:m?m.cvr:(row as ReviewMaterialRow).cvr,format:'percent'},
  {result:m?m.cpm:(row as ReviewMaterialRow).cpm,format:'decimal'},...(props.typeMetrics&&m?[{result:m.cpc,format:'decimal' as const}]:[])]
}
</script>
<template><div class="material-detail-export" data-meeting-section>
 <MeetingExport :title="title" :context="context" :synthetic="synthetic" :disabled="disabled" :candidate-complete="candidateComplete" material-detail/>
 <div hidden aria-hidden="true" data-export-only>
  <p class="module-note">按打开导出面板时的筛选结果及原排序展示；只导出素材明细，不含展开的逐日曲线。</p>
  <table class="business-table meeting-detail-table"><thead><tr><th>素材名称 / ID / 首次投放时间</th><th>本期消耗</th><th>同素材上期消耗</th><th>本期 ROI</th><th>点击率</th><th>转化率</th><th>CPM</th><th v-if="typeMetrics">CPC</th></tr></thead><tbody>
   <tr v-for="row in rows.slice(0,200)" :key="row.materialId"><td><strong>{{row.rank}}. {{row.materialName??'名称未知'}}</strong><small>{{formatMaterialLabel(row.materialId,row.materialName)}}</small><small>首次投放时间 {{formatCreationDate(row.creationDate)}}</small></td><td v-for="(cell,i) in values(row)" :key="i"><MetricValue :result="cell.result" :format="cell.format"/></td></tr>
  </tbody></table>
  <p v-if="!rows.length" class="module-note">没有符合当前筛选条件的素材。</p>
  <p v-if="rows.length>200" class="module-note">当前匹配 {{rows.length}} 条；单次最多导出原排序前200条，可调整筛选后再导出。</p>
 </div>
</div></template>
<style>.material-detail-export{display:inline-flex;flex-shrink:0}.meeting-content .meeting-detail-table td:first-child strong,.meeting-content .meeting-detail-table td:first-child small{display:block;line-height:1.6}.meeting-content .meeting-detail-table td{white-space:normal}.meeting-content .meeting-detail-table{table-layout:fixed}.meeting-content .meeting-detail-table th:first-child{width:28%}</style>
