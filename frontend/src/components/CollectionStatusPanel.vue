<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { readCollectionStatus, type CollectionStatus } from '../adapters/httpCollectionStatusAdapter'
const props = withDefaults(defineProps<{ active?: boolean; read?: () => Promise<CollectionStatus> }>(), { active: true })
const result = ref<CollectionStatus | null>(null)
const busy = ref(false)
const error = ref('')
let version = 0
const labels: Record<string, string> = {
  RUNNING: '执行中', COMPLETE: '本轮执行结束', PARTIAL: '部分项目未完成', FAILED: '本轮失败', REVIEW_REQUIRED: '结果待核，请联系维护人员',
  PENDING: '等待本轮处理', COLLECTING: '采集中', RETRY_WAIT: '等待重试', SOURCE_READY: '来源已准备，尚未确认入库',
  IMPORTING: '入库中', VERIFYING: '正在独立回读核对', VERIFIED: '已入库且回读一致', ALREADY_PRESENT: '已有数据，本轮未覆盖',
}
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
function errorLabel(code: string) {
  if (code === 'BACKFILL_THEME_CONFLICT') return '全域和乘方均返回数据，本日未入库，请人工核对处理。两边来源已保留，自动补采不会自行选择或合并。'
  const detail = /^PLATFORM_51010_(BASE|VIDEO|ASSIST|RATIO1|RATIO2)_PAGE_([1-9][0-9]{0,4}|100000)_RETRIES_([0-9]|1[0-2])$/.exec(code)
  if (!detail) return `错误代码 ${code}`
  return `平台返回 51010 · ${detail[1].toLowerCase()} 组第 ${detail[2]} 页 · 本项目本轮已延迟重试 ${detail[3]}/12 次。后续自动时点将尝试未完成项目；若当天时点已结束，请联系维护人员。`
}
async function refresh() {
  const id = ++version
  busy.value = true; error.value = ''; result.value = null
  try { const value = await (props.read ?? readCollectionStatus)(); if (id === version && props.active) result.value = value }
  catch (e) { if (id === version && props.active) error.value = e instanceof Error ? e.message : '采集状态读取失败。' }
  finally { if (id === version) busy.value = false }
}
watch(() => props.active, active => { if (active) void refresh(); else { version++; busy.value = false; result.value = null } }, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>

<template>
  <section class="collection-status" aria-labelledby="collection-status-title" :aria-busy="busy">
    <div class="collection-status__heading"><strong id="collection-status-title">最近采集执行状态</strong><button type="button" :disabled="busy" @click="refresh">{{ busy ? '读取状态中…' : '刷新采集状态' }}</button></div>
    <p>这里只读取最近一次执行记录，不触发采集、入库或看板计算。是否已启用定时任务，以服务器部署回执为准；时间均为北京时间。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <div v-else-if="result" role="status" aria-live="polite">
      <p v-if="result.observation === 'NOT_OBSERVED'">尚无采集执行记录；这不代表采集成功，也不能据此判断定时任务是否启用。</p>
      <p v-else-if="result.observation === 'UNAVAILABLE'" class="form-error">执行记录暂不可读取，请联系维护人员检查；未将缺失记录当成成功。</p>
      <template v-else>
        <p v-if="result.observation === 'STALE'" class="form-error">状态过期，待核：超过 150 秒没有新的运行心跳，不能确认任务仍在执行。请联系维护人员，不要反复发起采集。</p>
        <p><strong>{{ result.statDate }}</strong> {{ result.mode === 'GAP_BACKFILL' ? '之前7天的缺口补采（T−2至T−8）' : '的采集' }} · {{ result.observation === 'STALE' ? '状态待核（运行记录已过期）' : labels[result.status ?? ''] }} · 最近更新 {{ formatTime(result.updatedAt) }}</p>
        <ul class="collection-status__projects">
          <li v-for="project in result.projects" :key="project.projectId + project.statDate">
            <strong>{{ project.projectName }}</strong><span>{{ project.statDate }}</span><span :class="{ 'form-error': ['FAILED', 'REVIEW_REQUIRED'].includes(project.status) }">{{ result.observation === 'STALE' ? '上次记录：' : '' }}{{ labels[project.status] }}</span>
            <span v-if="project.rows !== undefined">{{ project.rows }} 行</span>
            <span v-if="project.status === 'RETRY_WAIT' && project.retryNotBefore">最早重试时间 {{ formatTime(project.retryNotBefore) }}；期间先处理其他项目，若其他项目尚未结束则顺延。请勿重复启动。</span>
            <span v-if="project.errorCode" :class="{ 'form-error': ['FAILED', 'REVIEW_REQUIRED'].includes(project.status) }">{{ errorLabel(project.errorCode) }}</span>
          </li>
        </ul>
        <p v-if="!result.projects.length">该记录没有当前启用项目的条目。</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.collection-status { margin: 1rem 0; padding: 1rem; border: 1px solid var(--border-color, #dce5e9); border-radius: 12px; }
.collection-status__heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
.collection-status button { min-height: 44px; padding: .5rem .8rem; cursor: pointer; }
.collection-status button:disabled { cursor: wait; }
.collection-status p { font-size: .875rem; line-height: 1.7; }
.collection-status__projects { margin: 0; padding-left: 1.2rem; }
.collection-status__projects li { padding: .5rem 0; overflow-wrap: anywhere; line-height: 1.7; }
.collection-status__projects span { display: inline-block; margin-left: .75rem; }
</style>
