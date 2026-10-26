<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { HttpHistoryUploadAdapter } from '../adapters/httpHistoryUploadAdapter'
import { HistoryRequestError, historyDateSummary, historyStatusLabels, type HistoryUploadAdapter, type HistoryUploadContext, type HistoryUploadView } from '../domain/historyUpload'

const props = withDefaults(defineProps<{ projectId: string; projects?: { id: string; name: string }[]; active?: boolean; readOnly?: boolean; adapter?: HistoryUploadAdapter }>(), { active: true, readOnly: false })
const emit = defineEmits<{ selectProject: [projectId: string] }>()
const adapter = props.adapter ?? new HttpHistoryUploadAdapter(undefined, props.readOnly)
const completenessLabel = (value: string) => ({ COMPLETE: '当天数据完整', EMPTY: '当天无投放', EXPLICIT_EMPTY: '当天无投放' }[value] ?? value)
const uploadMode = ref<'HISTORY' | 'GAP_FILL'>('HISTORY')
const context = ref<HistoryUploadContext | null>(null)
const view = ref<HistoryUploadView | null>(null)
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const requestBusy = ref(false)
const loadingContext = ref(false)
const uncertain = ref(false)
const previewUnknown = ref(false)
const contextNeedsRefresh = ref(false)
const previewStage = ref<'idle' | 'uploading' | 'converting' | 'ready' | 'failed' | 'rejected' | 'unknown'>('idle')
const uploadPercent = ref<number | undefined>()
const previewStarted = ref(0)
const previewEnded = ref(0)
const clock = ref(Date.now())
const activeTask = computed(() => view.value?.active === true || ['IMPORTING', 'VERIFYING', 'CLEANING'].includes(view.value?.status ?? '') && view.value?.active !== false)
const busy = computed(() => requestBusy.value || activeTask.value || uncertain.value || previewUnknown.value)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let tickTimer: ReturnType<typeof setInterval> | undefined
let pollEpoch = 0
let polledUploadId: string | null = null
function stopPolling(): void { pollEpoch++; if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function stopClock(): void { if (tickTimer) clearInterval(tickTimer); tickTimer = undefined }
function startClock(): void { if (!tickTimer) tickTimer = setInterval(() => { clock.value = Date.now() }, 1000) }
const importElapsed = computed(() => {
  const p = view.value?.progress
  if (!p?.startedAt) return ''
  const seconds = Math.max(0, Math.floor(((activeTask.value || uncertain.value ? clock.value : Date.parse(p.updatedAt)) - Date.parse(p.startedAt)) / 1000))
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
})
const previewElapsed = computed(() => { const seconds = Math.max(0, Math.floor(((previewEnded.value || clock.value) - previewStarted.value) / 1000)); return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` })
const stageLabel = computed(() => ({ IMPORTING: '逐日入库', VERIFYING: '最终核验', CLEANING: '副本清理' }[view.value?.progress?.stage ?? ''] ?? '等待后台状态'))
const stepPercent = computed(() => {
  const p = view.value?.progress
  return p && p.totalDays > 0 && ['IMPORTING', 'VERIFYING'].includes(p.stage) ? Math.min(100, Math.floor(p.completedDays / p.totalDays * 100)) : undefined
})
function accept(result: HistoryUploadView): void {
  if (view.value?.uploadId === result.uploadId && view.value.status === 'COMPLETED' && result.status !== 'COMPLETED') return
  if (view.value?.uploadId === result.uploadId && view.value.progress && result.progress && result.progress.updatedAt < view.value.progress.updatedAt) return
  view.value = result
  if (context.value) context.value.uploads = [result, ...context.value.uploads.filter(item => item.uploadId !== result.uploadId)]
}
function startPolling(id: string): void {
  polledUploadId = id
  stopPolling(); startClock()
  const epoch = pollEpoch; const token = generation; const project = props.projectId
  const poll = async (): Promise<void> => {
    if (epoch !== pollEpoch || token !== generation) return
    try {
      const result = await adapter.read(project, id)
      if (epoch !== pollEpoch || token !== generation) return
      if (result.projectId !== project || result.uploadId !== id) throw new Error('任务回包身份不一致')
      accept(result)
      if (!activeTask.value && result.status !== 'PREVIEW_READY') {
        if (result.status === 'COMPLETED') contextNeedsRefresh.value = true
        uncertain.value = false; requestBusy.value = false; error.value = ''; stopPolling(); stopClock(); return
      }
    } catch { if (epoch === pollEpoch && token === generation) { uncertain.value = true; error.value = '暂时无法读取进度，操作结果未知。正在自动核对，请勿重复提交。' } }
    if (epoch === pollEpoch && token === generation) pollTimer = setTimeout(() => { void poll() }, 2000)
  }
  pollTimer = setTimeout(() => { void poll() }, 2000)
}
const accountConfirmed = ref(false)
const daysConfirmed = ref(false)
const discardConfirmed = ref(false)
const showDiscard = ref(false)
const notice = ref('')
let generation = 0
const writable = computed(() => !props.readOnly && context.value?.writeEnabled === true)
const writeAllowed = computed(() => writable.value && (!context.value?.blockedReason || context.value.blockedReason === 'UPLOAD_SLOT_OCCUPIED') && !contextNeedsRefresh.value)
const retainedUploads = computed(() => (context.value?.uploads ?? []).filter(record => record.sourceFileState === 'RETAINED'))
const uploadAllowed = computed(() => writeAllowed.value && !context.value?.blockedReason && !retainedUploads.value.length && !context.value?.warnings?.length)
const discardAllowed = computed(() => view.value?.sourceFileState === 'RETAINED' && view.value.active !== true && !view.value.progress && ['PREVIEW_READY', 'VALIDATION_FAILED'].includes(view.value.status) && view.value.days.every(day => day.state === 'PENDING'))
const blocked = computed(() => props.readOnly ? '视觉预览只读，不能上传、导入或删除副本。'
  : !context.value ? ''
  : context.value.blockedReason === 'UPLOAD_RECORD_UNAVAILABLE' ? '上传记录暂不可核验，已暂停新上传；请联系维护人员检查，不要重复提交。'
  : context.value.blockedReason === 'UPLOAD_SLOT_OCCUPIED' ? '项目上传位置已占用，请先处理现有文件，再重新读取状态。'
  : context.value?.blockedReason === 'NO_MCP' ? '当前服务仍限制无机器采集的项目上传，请联系维护人员更新服务；请勿重复提交。'
    : !context.value?.writeEnabled ? `历史导入功能尚未开启，请联系维护人员${context.value?.blockedReason ? `（原因代码：${context.value.blockedReason}）` : ''}。` : '')
const applyAllowed = computed(() => view.value && ['PREVIEW_READY', 'IMPORT_FAILED', 'IMPORTED_CLEANUP_PENDING', 'INTERRUPTED'].includes(view.value.status))
const canApply = computed(() => writeAllowed.value && applyAllowed.value && accountConfirmed.value && daysConfirmed.value && !busy.value)
const recordGroups = computed(() => {
  let latestSuccessKept = false
  const current: HistoryUploadView[] = [], archived: HistoryUploadView[] = []
  const time = (r: HistoryUploadView) => {
    const parsed = Date.parse(r.completedAt ?? r.createdAt ?? '')
    return Number.isFinite(parsed) ? parsed : 0
  }
  for (const record of [...(context.value?.uploads ?? [])].sort((a, b) => time(b) - time(a))) {
    const cleaned = record.sourceFileState === 'DELETED'
    if (cleaned && ['VALIDATION_FAILED', 'CANCELLED'].includes(record.status)) archived.push(record)
    else if (cleaned && record.status === 'COMPLETED') {
      if (latestSuccessKept) archived.push(record)
      else { current.push(record); latestSuccessKept = true }
    } else current.push(record)
  }
  return { current, archived }
})
function resetConfirmations(): void { accountConfirmed.value = false; daysConfirmed.value = false; discardConfirmed.value = false; showDiscard.value = false }
function clearFile(): void {
  file.value = null; error.value = ''
  if (fileInput.value) fileInput.value.value = ''
  notice.value = '已清除本次文件选择，电脑原文件未删除，可重新选择。'
}
function changeProject(event: Event): void {
  const id = (event.target as HTMLSelectElement).value
  if (busy.value || id === props.projectId || !props.projects?.some(p => p.id === id)) return
  generation++; context.value = null; view.value = null; clearFile(); resetConfirmations()
  emit('selectProject', id)
}
async function load(): Promise<void> {
  const token = ++generation; const project = props.projectId
  const unresolvedId = uncertain.value ? polledUploadId : null
  const unknownUpload = unresolvedId && view.value?.uploadId === unresolvedId ? view.value : null
  stopPolling(); requestBusy.value = true; loadingContext.value = true; error.value = ''; resetConfirmations()
  try {
    const result = await adapter.context(project)
    if (token !== generation) return
    if (result.projectId !== project) throw new Error('项目上下文不一致，请重新读取。')
    context.value = result
    contextNeedsRefresh.value = false
    const unresolved = unknownUpload ? result.uploads.find(item => item.uploadId === unknownUpload.uploadId) : undefined
    view.value = unknownUpload ? unresolved ?? unknownUpload
      : result.uploads.find(item => item.active || ['IMPORTING', 'VERIFYING', 'CLEANING'].includes(item.status)) ?? recordGroups.value.current.find(item => item.uploadId === view.value?.uploadId) ?? recordGroups.value.current[0] ?? result.uploads.find(item => item.status === 'VALIDATION_FAILED') ?? null
    uncertain.value = !!unknownUpload && (!unresolved || activeTask.value || view.value?.status === 'PREVIEW_READY')
    if (previewUnknown.value) { previewStage.value = 'idle'; file.value = null; if (fileInput.value) fileInput.value.value = '' }
    previewUnknown.value = false
    if ((activeTask.value || uncertain.value) && view.value) startPolling(view.value.uploadId)
    else stopClock()
  } catch (reason) { if (token === generation) {
    context.value = null; contextNeedsRefresh.value = true; view.value = unknownUpload
    if (unresolvedId) { uncertain.value = true; startPolling(unresolvedId) } else stopClock()
    error.value = reason instanceof Error ? reason.message : '读取失败，请刷新记录。'
  } }
  finally { if (token === generation) { requestBusy.value = false; loadingContext.value = false } }
}
watch(() => props.projectId, project => {
  loadingContext.value = false
  generation++; stopPolling(); stopClock(); context.value = null; view.value = null; file.value = null; error.value = ''; notice.value = ''; requestBusy.value = false; uncertain.value = false; previewUnknown.value = false; previewStage.value = 'idle'; resetConfirmations()
  if (fileInput.value) fileInput.value.value = ''
  if (project && props.active) void load()
}, { immediate: true })
// Section navigation must not discard a selected file or invalidate an in-flight upload.
// Only an unfinished upload/import continues its existing bounded read-only progress polling.
watch(() => props.active, active => { if (active && props.projectId && !context.value && !busy.value) void load() })
onBeforeUnmount(() => { generation++; stopPolling(); stopClock() })
function chooseFile(event: Event): void {
  if (busy.value || !uploadAllowed.value) return
  file.value = null; error.value = ''; notice.value = ''
  const candidate = (event.target as HTMLInputElement).files?.[0]
  if (!candidate) return
  if (!candidate.name.toLowerCase().endsWith('.xlsx') || candidate.size === 0 || candidate.size > 20 * 1024 * 1024) { error.value = '请选择非空 .xlsx 文件，大小不超过 20 MiB。'; return }
  // A new local file is not the previously selected upload or its confirmations.
  view.value = null; resetConfirmations(); previewStage.value = 'idle'; previewStarted.value = 0; previewEnded.value = 0
  file.value = candidate
}
async function run(action: 'preview' | 'read' | 'apply' | 'discard', upload?: HistoryUploadView): Promise<void> {
  if (busy.value || !props.active || (action !== 'read' && !writable.value)) return
  if (action === 'apply' && !canApply.value) return
  if (action === 'discard' && (!discardConfirmed.value || !discardAllowed.value || contextNeedsRefresh.value)) return
  if (action === 'preview' && (!file.value || !uploadAllowed.value)) return
  const current = upload ?? view.value
  if (action !== 'preview' && !current) return
  const token = ++generation; const project = props.projectId
  requestBusy.value = true; error.value = ''
  if (action === 'preview') { previewStage.value = 'uploading'; uploadPercent.value = undefined; previewStarted.value = Date.now(); previewEnded.value = 0; clock.value = Date.now(); startClock() }
  if (action === 'apply') { uncertain.value = true; startPolling(current!.uploadId) }
  try {
    const result = action === 'preview' ? await adapter.preview(project, file.value!, (loaded, total) => {
      if (token !== generation || total <= 0) return
      uploadPercent.value = Math.min(100, Math.floor(loaded / total * 100)); previewStage.value = loaded >= total ? 'converting' : 'uploading'
    }, uploadMode.value)
      : action === 'read' ? await adapter.read(project, current!.uploadId)
        : action === 'apply' ? await adapter.apply(project, current!.uploadId, current!.previewHash)
          : await adapter.discard(project, current!.uploadId, current!.previewHash)
    if (token !== generation) return
    if (result.projectId !== project || (action !== 'preview' && result.uploadId !== current!.uploadId)) throw new Error('回包与当前项目或上传记录不一致，请刷新记录。')
    accept(result); resetConfirmations()
    if (action === 'read' && activeTask.value) startPolling(result.uploadId)
    if (action === 'apply') { uncertain.value = false; if (!activeTask.value) { stopPolling(); stopClock() } }
    if (action === 'discard' || result.status === 'COMPLETED') {
      contextNeedsRefresh.value = true
      const refreshed = await adapter.context(project)
      if (token !== generation) return
      if (refreshed.projectId !== project) throw new Error('项目上下文不一致，请重新读取。')
      context.value = refreshed; contextNeedsRefresh.value = false
      if (action === 'discard') notice.value = '服务器上传副本已删除，已重新读取项目状态。若无其他待处理副本，可重新选择上传；已入库日期不会撤销。'
    }
    if (action === 'preview') { previewStage.value = result.status === 'PREVIEW_READY' ? 'ready' : 'failed'; previewEnded.value = Date.now(); stopClock(); file.value = null; if (fileInput.value) fileInput.value.value = '' }
  } catch (reason) { if (token === generation && !(action === 'apply' && view.value?.status === 'COMPLETED')) {
    error.value = reason instanceof Error ? reason.message : '请求未完成，请刷新记录核对，不要重复提交。'; resetConfirmations()
    if (action === 'preview') {
      const refused = reason instanceof HistoryRequestError && reason.outcome === 'NOT_EXECUTED'
      previewStage.value = refused ? 'rejected' : 'unknown'; previewEnded.value = Date.now(); previewUnknown.value = true; stopClock()
      error.value += refused ? ' 新上传已被拒绝，请先刷新上传记录；不会自动重试。' : ' 结果未知，请先刷新上传记录核对，不要重复上传。'
    }
    if (action === 'discard') { contextNeedsRefresh.value = true; error.value += ' 删除结果或最新项目状态待核，请刷新上传记录；不要重复删除或上传。' }
    if (action === 'apply') {
      if (reason instanceof HistoryRequestError && reason.outcome === 'NOT_EXECUTED' && !activeTask.value) {
        uncertain.value = false; stopPolling(); stopClock()
      } else { uncertain.value = true; error.value += ' 正在自动查询后台进度，请勿重复确认。' }
    }
  } }
  finally { if (token === generation) requestBusy.value = false }
}
</script>

<template>
  <section class="panel history-upload" aria-labelledby="history-upload-title" :aria-busy="busy">
    <header><h3 id="history-upload-title">人工历史数据</h3><button type="button" :disabled="requestBusy" @click="load">刷新上传记录</button><a :href="adapter.templateUrl" download>下载标准模板</a></header>
    <label v-if="props.projects?.length">上传目标项目<select :value="projectId" :disabled="busy" @change="changeProject"><option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
    <p>先选目标项目，再选文件。选择文件仅在电脑上待上传；点击“上传并预览”才保存到后台，<strong class="upload-important">最后“确认导入”才写入数据。切换项目会清除已选文件和确认勾选，请重新选择。</strong></p>
    <p>第一次使用：先下载标准模板，按“填写说明”把旧表格数据填入“素材数据”工作表；第三张“日期声明”可留空。统计日期会从素材行自动识别，没有填写的日期直接跳过，不当作零消耗。上传时程序自动转换，不需要 AI 参与。</p>
    <p v-if="busy" class="upload-important" role="status">{{ loadingContext ? '正在读取上传记录，请稍候…' : '正在处理，请勿重复提交。校验通过不代表已入库，请等待明确结果。' }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" id="history-upload-error" class="upload-important" role="alert">{{ error }}</p>
    <p v-if="context?.warnings?.length" class="upload-important" role="alert">有上传记录暂时无法读取，不能确认文件占位，已暂停新上传；请联系维护人员检查。此提示不代表已入库数据丢失。</p>
    <p v-if="context">项目：{{ context.projectName }} · 账户 ID：{{ context.accountId }}</p>
    <p v-if="view">当前记录用途：{{ view.uploadMode === 'GAP_FILL' ? '缺日补录' : '历史导入' }}</p>
    <label>上传用途<select v-model="uploadMode" :disabled="busy"><option value="HISTORY">历史导入</option><option value="GAP_FILL">缺日补录</option></select></label>
    <p v-if="uploadMode === 'GAP_FILL'" class="upload-important">缺日补录：可补北京时间昨天及更早的完整日期，不受自动补采窗口限制。已有日期禁止覆盖；成功入库后自动补采会跳过。全域和乘方同日数据不要直接相加。</p>
    <p v-if="context && uploadMode === 'HISTORY'" class="upload-important">注意：{{ context.firstMcpDate ? `历史日期须早于 ${context.firstMcpDate}（不含当天）` : '该项目尚无机器采集，也可上传人工历史；日期必须早于北京时间今天，不含今天及未来日期' }}。系统已有日期不能重复上传；缺失不是零，请核对账户与整日完整性。</p>
    <section v-if="context" aria-label="已入库日期范围" aria-live="polite" class="date-ranges">
      <p>人工历史已入库：{{ historyDateSummary(context.manualHistoryDates) }}</p>
      <details><summary>已有输入记录、不可重复上传的日期（人工及机器）</summary><p>{{ historyDateSummary(context.existingDates) }}</p></details>
      <p class="upload-important">人工历史范围来自数据库当前人工版本，不是文件提交时间；不可重复上传范围还包含非当前及隔离输入记录，不代表这些日期全部已成功入库。已有日期不能再次上传或覆盖。分段之间可能有空缺，缺失不等于零消耗。</p>
    </section>
    <p v-if="contextNeedsRefresh" class="upload-important" role="status">最新项目状态待核，请点击“刷新上传记录”；核对完成前不能上传或提交。</p>
    <section v-if="retainedUploads.length" aria-label="待处理上传文件" aria-live="polite">
      <p class="upload-important">当前项目已有 {{ retainedUploads.length }} 个保留副本，占用上传位置，不能再上传第二个文件。请完成正常入库，或对尚未入库的失败/预览记录明确删除后重新上传。处理中、部分入库或中断的任务不能删除覆盖。</p>
      <p v-if="retainedUploads.length > 1" class="upload-important">检测到旧版保留的多个副本：不会自动删除，请在下方逐个核对并处理。</p>
      <ul><li v-for="record in retainedUploads" :key="record.uploadId">{{ historyStatusLabels[record.status] }} · {{ record.sourceSizeBytes }} 字节 · 提交时间 {{ record.createdAt ?? '待核' }} · 待处理日期：{{ record.days.length ? record.days.some(day => day.state !== 'IMPORTED') ? historyDateSummary(record.days.filter(day => day.state !== 'IMPORTED').map(day => day.statDate)) : '无待入库日期（请核对核验及副本清理状态）' : '待核（文件尚未成功核验）' }}</li></ul>
    </section>
    <p v-if="blocked" class="upload-important" role="status">{{ blocked }}</p>
    <div class="progress-steps">
      <section class="progress-card" aria-label="上传并预览进度">
        <h4>1 · 上传并预览（不入库）</h4>
        <p :class="{ 'upload-important': ['uploading', 'converting', 'failed', 'rejected', 'unknown'].includes(previewStage) || view?.status === 'VALIDATION_FAILED' }" role="status">{{ previewStage === 'idle' && view && !file ? (view.status === 'VALIDATION_FAILED' ? '当前记录校验失败，请查看下方原因。' : '当前记录已上传处理，可在下方查看结果；请先完成现有文件的处理。') : { idle: '选择文件后开始；先传文件，再校验与转换。', uploading: '正在上传文件', converting: '文件传输完成，正在校验与转换', ready: '预览已生成，请核对后确认导入', failed: '文件校验失败，请查看下方具体原因', rejected: '本次新上传已拒绝，未执行；请先刷新上传记录', unknown: '连接中断，结果未知；先刷新记录核对' }[previewStage] }}</p>
        <progress v-if="['uploading','converting'].includes(previewStage)" aria-label="文件上传进度" :value="previewStage === 'uploading' ? uploadPercent : undefined" max="100"></progress>
        <p v-if="previewStage === 'uploading' && uploadPercent !== undefined">文件传输 {{ uploadPercent }}%（不代表预览完成）</p>
        <p v-if="previewStarted && previewStage !== 'idle'" class="upload-important">已用时 {{ previewElapsed }} · 校验转换耗时随文件内容变化，请勿重复上传。</p>
      </section>
      <section class="progress-card" aria-label="导入并核验进度">
        <h4>2 · 导入并核验</h4>
        <p :class="{ 'upload-important': activeTask || uncertain || view && ['IMPORT_FAILED', 'INTERRUPTED', 'IMPORTED_CLEANUP_PENDING'].includes(view.status) }" role="status">{{ view?.status === 'COMPLETED' ? '全部完成：入库、最终核验与副本清理均已完成。' : activeTask || uncertain ? `当前步骤：${stageLabel}` : view && ['IMPORT_FAILED','INTERRUPTED','IMPORTED_CLEANUP_PENDING'].includes(view.status) ? `${historyStatusLabels[view.status]} · 停留步骤：${stageLabel}` : '核对预览并确认后，程序自动入库 → 最终核验 → 清理副本。' }}</p>
        <template v-if="view?.progress && view.status !== 'PREVIEW_READY'">
          <progress v-if="view.status !== 'COMPLETED'" aria-label="当前步骤进度" :value="stepPercent" max="100"></progress>
          <p>当前步骤进度：{{ view.progress.completedDays }} / {{ view.progress.totalDays }} 天<span v-if="stepPercent !== undefined">（{{ stepPercent }}%）</span><span v-if="view.progress.currentDate"> · 当前日期 {{ view.progress.currentDate }}</span></p>
          <p v-if="importElapsed">已用时 {{ importElapsed }}<span v-if="view.status !== 'COMPLETED'"> · 当前步骤结束后仍需核验和清理，以“全部完成”为准。</span></p>
        </template>
        <progress v-else-if="activeTask || uncertain" aria-label="等待后台进度"></progress>
        <p v-if="activeTask || uncertain" class="upload-important">每 2 秒自动核对进度。刷新页面后可继续查看已有导入任务，不会重新导入；请勿重复提交。</p>
      </section>
    </div>
    <div class="upload-controls">
      <label>选择历史文件<input ref="fileInput" type="file" accept=".xlsx" :disabled="busy || !uploadAllowed" aria-describedby="history-upload-error" @change="chooseFile"></label>
      <button type="button" :disabled="busy || !uploadAllowed || !file" @click="run('preview')">上传并预览（不入库）</button>
      <button v-if="file" type="button" :disabled="busy" @click="clearFile">清除已选文件</button>
      <button v-if="discardAllowed" type="button" :disabled="busy || !writable || contextNeedsRefresh" :aria-expanded="showDiscard" @click="showDiscard = !showDiscard; discardConfirmed = false">删除已上传文件</button>
    </div>
    <p v-if="file" :class="{ 'upload-important': previewUnknown || requestBusy }" role="status">已选文件：{{ file.name }}，{{ previewUnknown ? '结果待核，请先刷新记录' : requestBusy ? '正在上传或处理，请勿重复操作' : '尚未上传' }}。目标项目：{{ context?.projectName }}。</p>
    <p v-else-if="context && !context.uploads.length && !busy">当前项目暂无后台上传记录。</p>
    <fieldset v-if="showDiscard && discardAllowed" class="upload-important" :disabled="busy || !writable || contextNeedsRefresh"><legend>删除当前选中记录的上传文件</legend><p>仅删除当前项目这次尚未入库的服务器副本，保留处理记录；电脑原文件不变。必须删除成功并重新读取项目状态后，才能选择新文件。</p><label><input v-model="discardConfirmed" type="checkbox">确认取消并删除服务器上传副本；不删除电脑原文件，不撤销已导入日</label><button type="button" :disabled="busy || !writable || !discardConfirmed || contextNeedsRefresh" @click="run('discard')">取消并删除上传副本</button></fieldset>
    <div v-if="recordGroups.current.length" class="upload-records" aria-label="已有上传记录">
      <button v-for="record in recordGroups.current" :key="record.uploadId" type="button" :disabled="busy" :aria-pressed="view?.uploadId === record.uploadId" @click="run('read', record)">{{ record.start ?? '日期待核' }} — {{ record.end ?? '待核' }} · {{ historyStatusLabels[record.status] }}</button>
    </div>
    <details v-if="recordGroups.archived.length" :key="projectId" class="archived-uploads">
      <summary>已收起的历史记录（{{ recordGroups.archived.length }} 条）</summary>
      <p>副本已删除的失败/取消记录及更早的成功记录自动收起。仍保留文件的记录显示在主列表，不会自动删除；已入库数据不受影响。</p>
      <div class="upload-records" aria-label="已收起的上传记录">
        <button v-for="record in recordGroups.archived" :key="record.uploadId" type="button" :disabled="busy" :aria-pressed="view?.uploadId === record.uploadId" @click="run('read', record)">{{ record.start ?? '日期待核' }} — {{ record.end ?? '待核' }} · {{ historyStatusLabels[record.status] }}</button>
      </div>
    </details>
    <article v-if="view" class="upload-preview">
      <h4 :class="{ 'upload-important': view.status !== 'COMPLETED' && view.status !== 'CANCELLED' }">{{ historyStatusLabels[view.status] }}</h4>
      <p v-if="view.status === 'VALIDATION_FAILED'" class="upload-important" role="alert">文件校验或转换失败，本次未进入入库，不是在后台继续处理。上传副本仍保留时，请先查看原因；不要重复提交。</p>
      <p>账户 ID：{{ view.accountId }} · {{ view.start ?? '待核' }} — {{ view.end ?? '待核' }} · {{ view.rowCount }} 行 · 消耗合计 {{ view.totalSpend }} 元</p>
      <p v-if="view.duplicatePolicy === 'KEEP_LAST_FILE_ROW_PER_DAY_MATERIAL'" class="upload-important upload-duplicate-policy">同一项目、同一统计日、同一素材 ID 的重复记录，无论数值是否不同，都按文件顺序保留最后一条整行，不相加；跨日不去重。{{ view.duplicateRowsSkipped == null ? '本次排除数量待核' : `本次已排除 ${view.duplicateRowsSkipped} 条前序重复记录` }}；请核对去重后的行数和消耗再确认。原始文件未改写，系统已有日期仍拒绝覆盖。</p>
      <p>上传副本：{{ view.sourceFileState === 'DELETED' ? '已删除' : '保留' }} · {{ view.sourceSizeBytes }} 字节</p>
      <p v-if="view.status === 'PREVIEW_READY'" class="upload-important">文件已上传并保存预览，尚未入库；选错文件可在上方删除后重新上传。</p>
      <p v-if="view.status === 'COMPLETED'">已确认入库，上传副本已自动清理。若数据有误，需要核对后修正入库数据，不能通过删除文件直接重传覆盖。</p>
      <details><summary>文件校验与版本（供维护人员排查）</summary><p>上传记录 {{ view.uploadId }}</p><p>文件指纹 SHA-256：{{ view.sourceSha256 }}（用于确认是否为同一份文件）</p><p>配置版本 {{ view.configurationRevision }}</p></details>
      <p v-if="view.message" :class="{ 'upload-important': ['VALIDATION_FAILED', 'IMPORT_FAILED', 'INTERRUPTED', 'IMPORTED_CLEANUP_PENDING'].includes(view.status) }">{{ view.message }}</p>
      <p v-if="view.diagnostic" class="upload-important" role="alert">{{ view.diagnostic.message }}（{{ view.diagnostic.code }}）{{ view.diagnostic.sheet ? ` · 工作表 ${view.diagnostic.sheet}` : '' }}{{ view.diagnostic.row ? ` · 第 ${view.diagnostic.row} 行` : '' }}{{ view.diagnostic.column ? ` · 列 ${view.diagnostic.column}` : '' }}</p>
      <div v-if="view.days.length" class="upload-days"><table><thead><tr><th>统计日</th><th>行数</th><th>消耗</th><th>日期声明</th><th>导入状态</th></tr></thead><tbody><tr v-for="day in view.days" :key="day.statDate"><td>{{ day.statDate }}</td><td>{{ day.rowCount }}</td><td>{{ day.totalSpend }}</td><td>{{ completenessLabel(day.declaredCompleteness) }}</td><td>{{ { PENDING: '尚未导入', VERIFYING: '已提交 · 正在核验', IMPORTED: '已导入', FAILED: '失败' }[day.state] }}</td></tr></tbody></table></div>
      <fieldset v-if="applyAllowed" class="upload-important" :disabled="busy || !writable"><legend>核对并勾选后才能导入到系统（不会覆盖已导入日期）</legend><label><input v-model="accountConfirmed" type="checkbox">我确认文件全部属于上述项目与账户 ID</label><label><input v-model="daysConfirmed" type="checkbox">我确认所声明日期为整日完整，明确无投放的日期已如实声明</label><button class="primary-button" type="button" :disabled="!canApply" @click="run('apply')">{{ view.status === 'PREVIEW_READY' ? '确认导入' : '确认重试未完成步骤' }}</button></fieldset>
    </article>
  </section>
</template>

<style scoped>
.history-upload { margin: 20px 0; color: var(--ink); }
.progress-steps { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin:16px 0; }
.progress-card { border:1px solid var(--line); border-radius:10px; padding:16px; min-width:0; background:var(--surface); }
.progress-card h4 { margin:0 0 8px; }
.progress-card p { margin:8px 0; }
progress { width:100%; height:12px; accent-color:var(--accent); }
@media(max-width:700px) { .progress-steps { grid-template-columns:1fr; } }
header,.upload-controls { display:flex; align-items:center; gap:16px; flex-wrap:wrap; }
header h3 { margin-right:auto; }
button,a,input,select { min-height:40px; }
select { border:1px solid var(--line); border-radius:8px; background:var(--surface); color:var(--ink); padding:8px 12px; }
button,a { border:1px solid var(--line); border-radius:8px; padding:10px 14px; background:var(--surface); color:var(--ink); cursor:pointer; }
button:disabled { opacity:.55; cursor:not-allowed; }
button:focus-visible,a:focus-visible,input:focus-visible,summary:focus-visible { outline:2px solid var(--accent); outline-offset:3px; }
label { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
input[type=checkbox] { min-height:auto; width:18px; height:18px; }
.upload-records { display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }
.archived-uploads .upload-records { max-height:220px; overflow-y:auto; }
.upload-important { color:var(--danger, #b42318); font-weight:600; }
.upload-records button[aria-pressed=true] { border-color:var(--accent); }
.upload-preview { border-top:1px solid var(--line); margin-top:20px; padding-top:16px; }
fieldset { border:1px solid var(--line); border-radius:8px; display:grid; gap:12px; margin-top:16px; padding:16px; }
fieldset button { justify-self:start; }
p { overflow-wrap:anywhere; line-height:1.6; }
[role=alert] { color:var(--danger, #b42318); border-left:3px solid currentColor; padding-left:10px; }
fieldset p, fieldset legend, fieldset label { color:var(--danger, #b42318); }
.upload-days { max-height:320px; overflow:auto; margin-top:12px; }
table { width:100%; border-collapse:collapse; }
th,td { padding:10px; text-align:left; border-bottom:1px solid var(--line); }
summary { cursor:pointer; padding:8px 0; }
</style>
