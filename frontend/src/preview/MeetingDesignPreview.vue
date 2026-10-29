<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PeriodReview from '../components/PeriodReview.vue'
import MaterialScatter from '../components/MaterialScatter.vue'
import SpendCurve from '../components/SpendCurve.vue'
import MeetingExport from '../components/MeetingExport.vue'
import MetricValue from '../components/MetricValue.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { ReviewResult,TopResult,NumericResult,MetricKey,MaterialTypeReview } from '../domain/dashboard'
const result=ref<ReviewResult>(),top=ref<TopResult>(),selected=ref(''),ready=ref(false)
const n=(value:number):NumericResult=>({status:'VALID',value,partial:false,partialReasons:[]})
function metrics(spend:number,count:number,roi=3.62){return {spend:n(spend),materialCount:n(count),roi:n(roi),ctr:n(.0386),cvr:n(.0214),cpm:n(128.5),cpc:n(3.33),spendShare:n(spend/120000)} as Record<MetricKey,NumericResult>}
const current={start:'2026-10-01',end:'2026-10-07'},previous={start:'2026-09-24',end:'2026-09-30'}
const coverage={state:'COMPLETE' as const,expectedDays:7,coveredDays:7,missingDates:[]}
onMounted(async()=>{
 const adapter=new SyntheticMockAdapter([{id:'meeting-demo',name:'合成演示项目',enabled:true,category:'SMALL_APPLIANCE'}])
 const r=await adapter.fetchReview('meeting-demo',{current,previous},10)
 r.periods.forEach((p,i)=>{p.coverage={...coverage};p.pendingMaterialCount=0;p.groups.forEach(g=>{const totals={overall:[120000,94],new:[40000,30],historical:[75000,58],aigc:[5000,6]}[g.key];g.metrics=metrics(totals[0]*(i?.8:1),totals[1],i?3.1:3.62)})})
 r.metricsState='READY';r.comparisons=r.periods[0].groups.map(g=>({key:g.key,metrics:Object.fromEntries(Object.keys(g.metrics).map(k=>[k,n(k==='spend'?1.25:1.17)])) as Record<MetricKey,NumericResult>}))
 const names=['原创·产品场景展示','混剪·功能卖点合集','原创AI·商单场景演示','混剪·体验对比','原创·使用步骤拆解','AI·部门创意展示','混剪·日常应用展示','原创·细节体验','产品演示·场景组合','混剪·产品功能解读']
 r.topMaterials=names.map((name,i)=>({...structuredClone(r.topMaterials[0]),rank:i+1,materialId:`DEMO-${String(i+1).padStart(3,'0')}`,materialName:name,currentSpend:n(18500-i*1500),previousSpend:n(12000-i*900),roi:n(3.62+i*.12),ctr:n(.0386),cvr:n(.0214),cpm:n(128.5),creationDate:{status:'VERIFIED',date:'2026-10-01',reason:null},dailySpend:Array.from({length:7},(_,j)=>({date:`2026-10-0${j+1}`,spend:n([2100,2600,1800,3000,3500,2800,2700][j]/(1+i*.2))}))}))
 r.cohortMaterials={new:r.topMaterials.slice(0,5),historical:r.topMaterials.slice(5),aigc:[]}
 const groups=()=>[{key:'original' as const,metrics:metrics(45000,36,3.85)},{key:'mixed' as const,metrics:metrics(55000,42,3.42)},{key:'other' as const,metrics:metrics(20000,16,3.65)}]
 const types:MaterialTypeReview={schemaVersion:'material-type-review-1.0.0',periods:[{key:'CURRENT',range:current,testRange:current,coverage:{...coverage},groups:groups(),excludedUndatedMaterials:0,unresolvedDateMaterials:0},{key:'PREVIOUS',range:previous,testRange:previous,coverage:{...coverage},groups:groups().map(g=>({...g,metrics:metrics(g.metrics.spend.value!*.8,g.metrics.materialCount.value!,3.1)})),excludedUndatedMaterials:0,unresolvedDateMaterials:0}],comparisons:groups().map(g=>({...g,metrics:Object.fromEntries(Object.keys(g.metrics).map(k=>[k,n(1.25)])) as Record<MetricKey,NumericResult>})),materials:{original:[],mixed:[],other:[]}}
 types.ai={periods:types.periods.map(p=>({...p,groups:[{key:'ai',metrics:metrics(30000,24,3.76)},{key:'business',metrics:metrics(18000,14,3.9)},{key:'department',metrics:metrics(12000,10,3.55)}]})),comparisons:[{key:'ai',metrics:metrics(1.2,1.2)},{key:'business',metrics:metrics(1.2,1.2)},{key:'department',metrics:metrics(1.2,1.2)}],materials:{business:[],department:[]}}
 for(const item of types.ai.comparisons)item.metrics=Object.fromEntries(Object.keys(item.metrics).map(k=>[k,n(1)])) as Record<MetricKey,NumericResult>
 r.materialTypes=types;result.value=r
 const t=await adapter.fetchTop('meeting-demo','2026-10-07',10)
 t.rows=names.map((name,i)=>({...structuredClone(t.rows[0]),rank:i+1,materialName:name,materialId:`DEMO-${String(i+1).padStart(3,'0')}`,dayRoi:n(2+i*.45),dailySpend:r.topMaterials[i].dailySpend,windowRoi:n(3.62)}));top.value=t;selected.value=t.rows[0].materialId;ready.value=true
})
</script>
<template>
 <div class="neural-app app-shell meeting-design-preview"><header class="design-preview-head"><div><span>素材视界 / 本地样式预览</span><h1>星系原版 · 文档导出</h1><p>全部为前端合成数据 · 不连接数据库或业务接口 · 未上线</p></div><a href="#daily-preview">查看小模块 ↓</a></header>
 <div class="design-preview-guide"><b>查看方式</b><span>标题右侧的“导出”对应整个模块或当前小卡片。打开后直接查看浅底文档版，点击“下载高清 PNG”即可保存。TOP10已恢复原布局，仅放大文字。</span></div>
 <p v-if="!ready">正在准备虚构样式样本…</p>
 <main v-if="result"><div class="design-period-context"><h2>合成演示项目</h2><p>本周期 2026/10/01 — 10/07　上周期 2026/09/24 — 09/30</p></div><PeriodReview :result="result" project-name="合成演示项目"/>
 <section v-if="top" id="daily-preview" class="design-daily-preview"><h2>每日素材 · 小模块</h2><p>合成演示项目 · 统计日 2026/10/07</p><div class="top-overview">
  <section class="chart-card scatter-card" data-meeting-section><MaterialScatter :rows="top.rows" :selected-date="top.selectedDate" :selected-id="selected" @select="selected=$event"><template #export><MeetingExport title="消耗与ROI" context="合成演示项目 · 2026-10-07" synthetic/></template></MaterialScatter></section>
  <section class="chart-card selected-material-panel" data-meeting-section><header class="selected-material-titlebar"><h4>素材详情</h4><MeetingExport title="素材详情" context="合成演示项目 · 2026-10-07" synthetic/></header><h5>原创 · 产品场景展示</h5><div class="selected-metrics-grid"><div><span>当天消耗</span><strong>2,700.00</strong></div><div><span>当天 ROI</span><strong>3.62</strong></div><div><span>近7天消耗</span><strong>18,500.00</strong></div><div><span>近7天 ROI</span><strong>3.62</strong></div></div><p>近7天消耗走势 · 元</p><SpendCurve :days="top.rows[0].dailySpend" expanded/><p class="module-note">素材编号 DEMO-001 · 所有数据均为虚构样本</p></section>
 </div></section></main></div>
</template>
<style scoped>
.meeting-design-preview{padding:32px max(24px,calc((100vw - 1360px)/2)) 80px}.design-preview-head{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--sc-line);padding-bottom:24px}.design-preview-head span{color:var(--sc-gold);font-size:13px}.design-preview-head h1{font-size:30px;font-weight:500;letter-spacing:2px;margin:14px 0}.design-preview-head p,.design-period-context p,.design-daily-preview>p{color:var(--sc-muted);font-size:14px}.design-preview-head a{color:var(--sc-gold)}.design-preview-guide{display:flex;gap:18px;background:var(--sc-panel);border:1px solid var(--sc-line);border-radius:10px;margin:24px 0 36px;padding:18px 22px;line-height:1.8;color:var(--sc-muted);font-size:14px}.design-preview-guide b{color:var(--sc-gold);white-space:nowrap}.design-period-context h2,.design-daily-preview h2{font-size:22px;font-weight:500}.design-daily-preview{margin-top:48px;padding-top:28px;border-top:1px solid var(--sc-line)}.selected-material-panel>h5{font-size:18px}.design-daily-preview .chart-card{min-width:0;padding:24px}
</style>

