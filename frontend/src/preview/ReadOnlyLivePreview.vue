<script setup lang="ts">
import App from '../App.vue'
import { HttpDashboardAdapter } from '../adapters/httpDashboardAdapter'
import type { PeriodPreferences } from '../adapters/periodPreferences'
import type { ReviewQuery } from '../domain/dashboard'

// No session bootstrap or production writes; the local server also rejects every mutation.
const adapter=new HttpDashboardAdapter(async(input,init)=>{
  const path=String(input)
  if(!path.startsWith('/api/projects')||(init?.method??'GET')!=='GET')throw new Error('本地预览只允许看板数据查询')
  return fetch(path,{...init,credentials:'omit',signal:AbortSignal.timeout(120000)})
})
const selections=new Map<string,ReviewQuery>()
const preferences:PeriodPreferences={
  load:async id=>structuredClone(selections.get(id)??null),
  save:async(id,query)=>{selections.set(id,structuredClone(query))},
}
</script>
<template><App :adapter="adapter" :overview-adapter="adapter" :period-preferences="preferences" local-read-only-preview history-read-only preview-data-description="当前通过本机只读代理查询云端已有业务数据，可切换项目、周期和素材明细；不连接本地数据库，不触发采集、不保存复盘或日期、不修改配置。图片只在浏览器生成下载。"/></template>
