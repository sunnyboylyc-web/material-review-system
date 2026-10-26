<script setup lang="ts">
import App from '../App.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { validateMaterialTypeResponse, validateReviewResponse, validateReviewMaterials } from '../adapters/httpDashboardAdapter'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'
import type { PeriodPreferences } from '../adapters/periodPreferences'
import type { ProjectModule, ReviewQuery } from '../domain/dashboard'

// Mount the production shell and styles. Only the data boundary is synthetic and loopback-only.
const project: ProjectModule = {id:'synthetic-type-preview',name:'素材分析演示项目',category:'SMALL_APPLIANCE',enabled:true}
const synthetic = new SyntheticMockAdapter([project])
const adapter: DashboardDataAdapter = {
  evidenceKind:'SYNTHETIC',
  listProjects:() => synthetic.listProjects(),
  fetchTop:(...args) => synthetic.fetchTop(...args),
  async fetchFreshness(projectId) {
    if (projectId !== project.id) throw new Error('合成预览项目不存在')
    return {schemaVersion:'freshness-1.0.0',projectId,latestMcpStatDate:'2026-01-15'}
  },
  async fetchReview(projectId,query,limit) {
    if (projectId !== project.id) throw new Error('合成预览项目不存在')
    const params = new URLSearchParams({currentStart:query.current.start,currentEnd:query.current.end,
      previousStart:query.previous.start,previousEnd:query.previous.end,limit:String(limit)})
    const response = await fetch(`http://127.0.0.1:18082/review?${params}`,{signal:AbortSignal.timeout(15000)})
    const raw = await response.json()
    if (!response.ok) throw new Error(raw.error ?? '本地计算服务暂不可用')
    if (raw.evidenceKind !== 'SYNTHETIC' || raw.projectId !== projectId) throw new Error('验收页面仅允许当前项目的合成数据')
    return {...validateReviewResponse({...raw,evidenceKind:'CURRENT_FACTS'}),evidenceKind:'SYNTHETIC'}
  },
  async fetchMaterialTypes(projectId,query) {
    if (projectId !== project.id) throw new Error('合成预览项目不存在')
    const params = new URLSearchParams({currentStart:query.current.start,currentEnd:query.current.end,
      currentTestStart:query.currentTest.start,currentTestEnd:query.currentTest.end,
      previousStart:query.previous.start,previousEnd:query.previous.end,
      previousTestStart:query.previousTest.start,previousTestEnd:query.previousTest.end})
    const response = await fetch(`http://127.0.0.1:18082/material-types?${params}`,{signal:AbortSignal.timeout(15000)})
    const raw = await response.json()
    if (!response.ok || raw.evidenceKind !== 'SYNTHETIC') throw new Error('本地素材类型查询未完成，请检查日期。')
    return validateMaterialTypeResponse({...raw,evidenceKind:'CURRENT_FACTS'},projectId,query)
  },
  async fetchReviewMaterials(projectId,query) {
    if(projectId!==project.id)throw new Error('合成预览项目不存在')
    const params=new URLSearchParams({currentStart:query.current.start,currentEnd:query.current.end,previousStart:query.previous.start,previousEnd:query.previous.end})
    const response=await fetch(`http://127.0.0.1:18082/review-materials?${params}`,{signal:AbortSignal.timeout(15000)})
    const raw=await response.json()
    if(!response.ok||raw.evidenceKind!=='SYNTHETIC')throw new Error('完整合成素材名录暂不可用')
    return {...validateReviewMaterials({...raw,evidenceKind:'CURRENT_FACTS'},projectId,query),evidenceKind:'SYNTHETIC'}
  },
}
const defaults:ReviewQuery = {current:{start:'2026-01-01',end:'2026-01-10'},previous:{start:'2025-12-22',end:'2025-12-31'}}
const selections = new Map<string,ReviewQuery>()
const periodPreferences:PeriodPreferences = {
  load:async id => structuredClone(selections.get(id) ?? defaults),
  save:async (id,query) => {selections.set(id,structuredClone(query))},
}
</script>
<template>
  <App :adapter="adapter" :overview-adapter="adapter" :period-preferences="periodPreferences" history-read-only local-read-only-preview/>
</template>
