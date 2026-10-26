<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { HttpCollectedExportAdapter } from '../adapters/httpCollectedExportAdapter'
import type { CollectedExportAdapter, CollectedExportPreview } from '../domain/collectedExport'
import { shanghaiIsoDate, shiftIsoDate } from '../time/shanghai'
const props = withDefaults(defineProps<{ projectId: string; projects?: { id: string; name: string }[]; active?: boolean; readOnly?: boolean; downloadDisabled?: boolean; downloadDisabledMessage?: string; adapter?: CollectedExportAdapter }>(), { active: true, readOnly: false, downloadDisabled: false })
const emit = defineEmits<{ selectProject: [projectId: string]; busy: [value: boolean] }>()
const api = props.adapter ?? new HttpCollectedExportAdapter()
const end = ref(shanghaiIsoDate(new Date(), -1)); const start = ref(shiftIsoDate(end.value, -29))
const preview = ref<CollectedExportPreview | null>(null); const busy = ref(''); const error = ref(''); const notice = ref(''); const elapsed = ref(0)
// Synchronous notification closes the same-event gap before the dialog can dismiss.
watch(busy, value => emit('busy', !!value), { flush: 'sync' })
let generation = 0; let timer: ReturnType<typeof setInterval> | undefined
function invalidate(): void { generation++; preview.value = null; busy.value = ''; error.value = ''; notice.value = ''; if (timer) clearInterval(timer) }
watch(() => [props.projectId, props.active, start.value, end.value], invalidate)
onBeforeUnmount(invalidate)
const canDownload = computed(() => preview.value && preview.value.days.length > 0 && !busy.value && !props.readOnly && !props.downloadDisabled)
function datePicker(event: Event): void { const input = event.currentTarget as HTMLInputElement; try { input.showPicker?.() } catch { input.focus() } }
function selectProject(event: Event): void { const id = (event.target as HTMLSelectElement).value; if (props.projects?.some(p => p.id === id)) emit('selectProject', id) }
async function run(download = false): Promise<void> {
  if (busy.value || props.readOnly || !props.active || !props.projectId || (download && !canDownload.value)) return
  error.value = ''; notice.value = ''
  if (!start.value || !end.value || start.value > end.value) { error.value = '请选择开始和结束日期，结束日期不能早于开始日期。'; return }
  if ((Date.parse(end.value) - Date.parse(start.value)) / 86400000 >= 366) { error.value = '一次最多选择 366 天，请缩短日期范围或分几次下载。'; return }
  const token = ++generation; const project = props.projectId; const from = start.value; const to = end.value
  busy.value = download ? '正在转换并生成 Excel' : '正在检查可下载日期'; elapsed.value = 0
  const begun = Date.now(); timer = setInterval(() => { elapsed.value = Math.floor((Date.now() - begun) / 1000) }, 1000)
  try {
    if (download) {
      const blob = await api.download(project, from, to)
      if (token !== generation) return
      const url = URL.createObjectURL(blob); const link = document.createElement('a')
      link.href = url; link.download = `项目数据_${project.replace(/[^\w-]/g, '_')}_${from}_${to}.xlsx`; link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      notice.value = 'Excel 已生成并交给浏览器下载，请到浏览器下载列表查看。系统中的数据未改变。'
    } else {
      preview.value = null
      const result = await api.preview(project, from, to)
      if (token !== generation) return
      if (result.projectId !== project || result.startDate !== from || result.endDate !== to) throw new Error('返回的项目或日期不一致，请重新查看范围。')
      preview.value = result
    }
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : '下载未完成，请重试。' }
  finally { if (token === generation) { busy.value = ''; if (timer) clearInterval(timer) } }
}
function reason(value: string): string { return ({ MISSING: '系统中没有已成功保存的数据', NO_CURRENT_VERSION: '没有已入库数据' }[value] ?? '没有符合条件的已保存数据') }
</script>
<template>
  <section class="panel collected-export" aria-labelledby="collected-export-title" :aria-busy="!!busy">
    <h3 id="collected-export-title">下载项目数据</h3>
    <p>用来补齐您电脑里的人工表格：选择项目和日期 → 查看可下载范围 → 下载 Excel。开始日和结束日都包含在内，格式与历史上传模板一致。</p>
    <p>包含机器采集和已确认导入的人工历史数据，可下载留存，或在本地文档丢失后取回数据。不会启动采集，也不会修改系统数据。</p>
    <p class="export-attention">下载的是系统保存的字段，会重新生成标准模板；不会恢复原文件的排版、备注或公式，也不能代替整个系统的备份。已有日期不能再次上传覆盖。</p>
    <p class="export-attention">一次最多选择 366 天、下载 10 万行；超出时请分几次下载。下载时会重新核对最新已保存数据，预览不会锁定数据版本。</p>
    <p v-if="readOnly" class="export-attention">当前为只读预览页面，请到正式系统的每日素材页面下载。</p>
    <p v-else-if="downloadDisabled" class="download-preview-note">{{ downloadDisabledMessage ?? '本地虚构演示：可以选择项目和日期、查看示例范围；不生成真实业务 Excel 文件。' }}</p>
    <form @submit.prevent="run()">
      <label v-if="projects?.length">下载目标项目<select :value="projectId" :disabled="!!busy || readOnly" @change="selectProject"><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
      <label>下载开始日期<input v-model="start" type="date" min="1900-01-01" max="2200-12-31" :disabled="!!busy || readOnly" @click="datePicker"></label>
      <label>下载结束日期<input v-model="end" type="date" min="1900-01-01" max="2200-12-31" :disabled="!!busy || readOnly" @click="datePicker"></label>
      <button type="submit" :disabled="!!busy || readOnly">查看可下载范围</button>
      <button type="button" class="primary-button" :disabled="!canDownload" @click="run(true)">下载 Excel</button>
    </form>
    <p v-if="busy" class="export-attention" role="status">{{ busy }} · 已用时 {{ elapsed }} 秒，请勿重复点击或关闭面板。</p>
    <progress v-if="busy" aria-label="下载处理进度"></progress>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="preview" class="export-preview" aria-live="polite">
      <p>项目：{{ preview.projectName }} · 账户 ID：{{ preview.accountId }} · {{ preview.startDate }} — {{ preview.endDate }}</p>
      <p>可下载 {{ preview.days.length }} 天，共 {{ preview.rowCount }} 行。{{ preview.days.length ? downloadDisabled ? downloadDisabledMessage ?? '以上为虚构范围演示。' : '请核对范围后点击“下载 Excel”。' : '这段时间没有可下载的已保存数据，请更换日期。' }}</p>
      <p v-if="preview.days.some(day => day.sourceType)">机器采集 {{ preview.days.filter(day => day.sourceType === 'MCP_AUTO').length }} 天，人工历史 {{ preview.days.filter(day => day.sourceType === 'MANUAL_HISTORY').length }} 天。同一天只下载当前有效版本，不重复累计。</p>
      <details v-if="preview.excludedDates.length"><summary>未包含 {{ preview.excludedDates.length }} 天（点击查看，不补零）</summary><ul><li v-for="day in preview.excludedDates" :key="day.statDate">{{ day.statDate }}：{{ reason(day.reason) }}</li></ul></details>
    </div>
  </section>
</template>
<style scoped>
.collected-export { margin:20px 0; color:var(--ink); } p { line-height:1.6; overflow-wrap:anywhere; }
.export-attention, .form-error { color:var(--danger, #b42318); }
form { display:flex; flex-wrap:wrap; align-items:end; gap:12px; } label { display:grid; gap:6px; } input,select,button { min-height:44px; max-width:100%; border:1px solid var(--line); border-radius:8px; padding:8px 12px; color:var(--ink); background:var(--surface); }
button,summary { cursor:pointer; } button:disabled { opacity:.55; cursor:not-allowed; } :is(input,select,button,summary):focus-visible { outline:2px solid var(--accent); outline-offset:3px; }
progress { width:100%; accent-color:var(--accent); } .export-preview { margin-top:16px; border-top:1px solid var(--line); } summary { padding:10px 0; } ul { max-height:220px; overflow:auto; padding-left:24px; }
@media(max-width:600px) { form { display:grid; grid-template-columns:1fr; } }
</style>
