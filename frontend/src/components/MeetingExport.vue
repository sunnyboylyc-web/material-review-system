<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'

const props = withDefaults(defineProps<{ title: string; context: string; synthetic?: boolean; materialDetail?: boolean; disabled?: boolean; candidateComplete?: boolean; captureTarget?: () => HTMLElement | null }>(), {candidateComplete:true})
const trigger=ref<HTMLButtonElement>(),dialog=ref<HTMLDialogElement>(),sheet=ref<HTMLElement>(),content=ref<HTMLElement>()
const opened=ref(false),busy=ref(false),error=ref(''),message=ref(''),page=ref(1),pageSize=ref(10),pages=ref(1)
const scale=2
const exportCount=ref(10),availableRows=ref(0),sourceComplete=ref(true)
const heading=ref(''),caption=ref(''),demo=ref(false)
let source:HTMLElement|null=null,previousFocus:HTMLElement|null=null,closed=false
function renderPage(){
  if(!source||!content.value)return
  const clone=source.cloneNode(true) as HTMLElement
  clone.querySelectorAll('.pr-section-title').forEach(n=>n.remove())
  clone.querySelectorAll('.meeting-export-trigger,dialog,.pr-detail-button,.pr-aigc-button,.expand-button,.material-toolbar,.material-filter-toolbar,.detail-date-filter').forEach(n=>n.remove())
  clone.querySelectorAll('[data-export-only]').forEach(n=>{n.removeAttribute('hidden');n.removeAttribute('aria-hidden')})
  if(props.materialDetail)clone.querySelectorAll('.expanded-material,.pr-expanded').forEach(n=>n.remove())
  const tables=clone.querySelectorAll('table')
  const cards=()=>Array.from(clone.querySelectorAll('.material-gallery>.material-card'))
  availableRows.value=Math.max(cards().length,0,...Array.from(tables,t=>t.tBodies[0]?.rows.length??0))
  if(props.materialDetail)tables.forEach(t=>Array.from(t.tBodies[0]?.rows??[]).slice(exportCount.value).forEach(r=>r.remove()))
  if(props.materialDetail)cards().slice(exportCount.value).forEach(n=>n.remove())
  const max=Math.max(cards().length,0,...Array.from(tables,t=>t.tBodies[0]?.rows.length??0))
  pages.value=Math.max(1,Math.ceil(max/pageSize.value));page.value=Math.max(1,Math.min(page.value,pages.value))
  tables.forEach(t=>Array.from(t.tBodies[0]?.rows??[]).forEach((r,i)=>{if(i<(page.value-1)*pageSize.value||i>=page.value*pageSize.value)r.remove()}))
  cards().forEach((n,i)=>{if(i<(page.value-1)*pageSize.value||i>=page.value*pageSize.value)n.remove()})
  const ids=new Map<string,string>();clone.querySelectorAll('[id]').forEach((n,i)=>{ids.set(n.id,`meeting-${i}`);n.id=`meeting-${i}`})
  clone.querySelectorAll('*').forEach(n=>{for(const attr of Array.from(n.attributes)){
    let value=attr.value;ids.forEach((replacement,id)=>{value=value.replaceAll(`url(#${id})`,`url(#${replacement})`);if(value===`#${id}`)value=`#${replacement}`})
    if(value!==attr.value)n.setAttribute(attr.name,value)
  }})
  clone.setAttribute('inert','');content.value.replaceChildren(clone)
}
async function open(){
  const target=props.captureTarget?props.captureTarget():trigger.value?.closest<HTMLElement>('[data-meeting-section]');if(!target||props.disabled)return
  previousFocus=document.activeElement as HTMLElement;source=target.cloneNode(true) as HTMLElement
  heading.value=props.title;caption.value=props.context;demo.value=!!props.synthetic
  opened.value=true;closed=false;page.value=1;exportCount.value=10;pageSize.value=10;sourceComplete.value=props.candidateComplete!==false;error.value='';message.value=''
  await nextTick();renderPage();dialog.value?.showModal();dialog.value?.querySelector<HTMLButtonElement>('[data-close]')?.focus()
}
function close(){if(busy.value)return;closed=true;dialog.value?.close();opened.value=false;source=null;previousFocus?.focus()}
async function download(){
  if(!sheet.value||busy.value)return
  busy.value=true;error.value='';message.value=''
  try{
    await document.fonts?.ready
    const node=sheet.value,width=Math.ceil(node.getBoundingClientRect().width),height=Math.ceil(node.scrollHeight)
    if(width*height*scale**2>32000000)throw new Error('该板块图片过大，请减少每页条数。')
    const {toPng}=await import('html-to-image')
    const url=await toPng(node,{pixelRatio:scale,width,height,skipAutoScale:true,backgroundColor:getComputedStyle(node).backgroundColor})
    if(closed)return
    const a=document.createElement('a');a.download=`${heading.value}-${caption.value}`.replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').slice(0,140)+`-浅底文档-第${page.value}页-${scale}x.png`;a.href=url;a.click()
    message.value=`已生成第 ${page.value} / ${pages.value} 页 PNG（${width*scale} × ${height*scale} 像素），请在浏览器下载列表查看。`
  }catch(e){error.value=e instanceof Error?e.message:'导出未完成，请重试或减少每页条数。'}
  finally{busy.value=false}
}
onBeforeUnmount(()=>{closed=true;dialog.value?.close()})
</script>
<template>
  <button ref="trigger" class="meeting-export-trigger" type="button" :disabled="disabled" :aria-label="`${title}放大与高清导出`" @click="open">导出 ↗</button>
  <dialog v-if="opened" ref="dialog" class="meeting-dialog" aria-label="板块放大与高清导出" @cancel.prevent="close">
    <header class="meeting-toolbar"><div><strong>会议展示</strong><span>浅底文档版 · 高清 PNG · 打开时的数据</span></div><label v-if="materialDetail">导出数量 <select v-model.number="exportCount" aria-label="导出数量" :disabled="busy" @change="page=1;renderPage()"><option v-for="n in [10,20,30,50,100,200].filter(n=>sourceComplete||n<=Math.max(10,availableRows))" :key="n" :value="n">{{n}} 条</option></select><small>实际 {{Math.min(exportCount,availableRows)}} 条</small></label><label v-if="pages>1 || (source?.querySelector('table'))">每页 <select v-model.number="pageSize" aria-label="每页条数" :disabled="busy" @change="page=1;renderPage()"><option v-for="n in [10,20,30,40,50,100]" :key="n" :value="n">{{n}} 条</option></select></label><div v-if="pages>1" class="meeting-pages"><button :disabled="busy||page<=1" @click="page--;renderPage()">上一页</button><span>{{page}} / {{pages}}</span><button :disabled="busy||page>=pages" @click="page++;renderPage()">下一页</button></div><button :disabled="busy" class="meeting-download" @click="download">{{busy?'正在生成…':pages>1?'下载本页高清 PNG':'下载高清 PNG'}}</button><button data-close :disabled="busy" aria-label="关闭会议展示" @click="close">关闭</button></header>
    <p v-if="error" role="alert" class="meeting-feedback">{{error}}</p><p v-if="message" role="status" class="meeting-feedback">{{message}}</p>
    <div class="meeting-viewport"><article ref="sheet" class="meeting-sheet meeting-paper"><header class="meeting-sheet-heading"><p>素材视界 · 会议复盘</p><h2>{{heading}}</h2><p>{{caption}}</p><p v-if="demo" class="synthetic-coverage-warning">纯虚构演示数据，不用于业务判断</p></header><p v-if="materialDetail&&!sourceComplete" class="module-note">本次仅包含当前已获取的TOP素材；完整名录尚未就绪，不能据此判断其余素材是否存在。</p><div ref="content" class="meeting-content"/><footer><span v-if="materialDetail">当前筛选结果 · 导出 {{Math.min(exportCount,availableRows)}} 条 · 本页 {{page}} / {{pages}} · </span>按页面当前口径展示 · 数据未知、缺失及分母为 0 保留原标记<span v-if="pages>1"> · 第 {{page}} / {{pages}} 页</span></footer></article></div>
  </dialog>
</template>
<style>
.meeting-export-trigger{display:inline-flex;align-items:center;flex-shrink:0;position:relative;z-index:1;margin:0;padding:7px 12px;min-height:36px;border:1px solid var(--sc-gold,#cbb88b);border-radius:7px;background:transparent;color:var(--sc-gold,#cbb88b);font-size:12px;font-family:inherit;cursor:pointer}
.meeting-dialog{width:min(1480px,calc(100vw - 32px));max-width:none;max-height:94dvh;box-sizing:border-box;padding:0;border:1px solid #34394d;border-radius:14px;background:var(--sc-bg,#06080f);color:var(--sc-text,#e6e8f1);overflow:auto}.meeting-dialog::backdrop{background:#000b}.meeting-toolbar{position:sticky;top:0;z-index:3;display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:16px 22px;background:var(--sc-panel,#0d1120);border-bottom:1px solid #34394d}.meeting-toolbar>div:first-child{flex:1;min-width:180px}.meeting-toolbar strong{display:block;font-size:18px}.meeting-toolbar>div>span{font-size:12px;color:var(--sc-muted,#939db7)}.meeting-toolbar label,.meeting-pages{display:flex;align-items:center;gap:8px;font-size:13px}.meeting-toolbar button,.meeting-toolbar select{padding:9px 12px;background:#141827;color:var(--sc-text,#e6e8f1);border:1px solid #34394d;border-radius:6px;cursor:pointer}.meeting-toolbar .meeting-download{border-color:var(--sc-gold,#cbb88b);color:var(--sc-gold,#cbb88b)}.meeting-toolbar button:disabled{opacity:.5;cursor:wait}.meeting-viewport{overflow:auto;padding:24px}.meeting-sheet{width:1200px;box-sizing:border-box;padding:36px;background:var(--sc-bg,#06080f);color:var(--sc-text,#e6e8f1);isolation:isolate}.meeting-sheet-heading{border-bottom:1px solid #34394d;margin-bottom:26px;padding-bottom:18px}.meeting-sheet-heading h2{font-size:30px;margin:10px 0;letter-spacing:1px}.meeting-sheet-heading p{font-size:16px;line-height:1.7;margin:6px 0;color:var(--sc-muted,#939db7)}.meeting-sheet footer{margin-top:26px;border-top:1px solid #34394d;padding-top:16px;font-size:14px;color:var(--sc-muted,#939db7)}.meeting-content [data-meeting-section]{display:flow-root}.meeting-content .pr-section-title{font-size:26px!important;margin-top:0!important}.meeting-content :is(.pr-kpi>span,.pr-kpi-footer,.pr-secondary-kpis article>span,.pr-secondary-kpis article>small,.pr-ratio-labels,.pr-ratio-row,.pr-legend,.module-note,.pr-kpi-footer span,.business-table th,.business-table td,.business-table small){font-size:16px!important;line-height:1.65}.meeting-content .pr-kpi-value{font-size:36px!important}.meeting-content .pr-kpi{padding:24px}.meeting-content .pr-secondary-kpis article>strong{font-size:26px!important}.meeting-content .pr-performance{grid-template-columns:1fr!important}.meeting-content .pr-group{margin-bottom:18px}.meeting-content .table-shell{overflow:visible!important;max-height:none!important}.meeting-content table{min-width:0!important;width:100%;table-layout:auto}.meeting-content table td,.meeting-content table th{padding:14px 12px!important;overflow-wrap:anywhere}.meeting-content .chart-card{background:var(--sc-panel,#0d1120)}.meeting-content :is(button,input,select){pointer-events:none}.meeting-content .scatter-locator{display:none}.meeting-content :is(.selected-metrics-grid span,.selected-material-id,.selected-material-date){font-size:16px!important}.meeting-content .selected-metrics-grid strong{font-size:28px!important}.meeting-content svg{max-width:100%}
</style>

