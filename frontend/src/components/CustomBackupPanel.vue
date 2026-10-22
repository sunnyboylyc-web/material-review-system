<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createCustomBackup, readCustomBackup, customBackupDownloadUrl, type CustomBackupStatus } from '../adapters/httpDatabaseBackupAdapter'
const props = defineProps<{ active: boolean; preview: boolean }>()
const start = ref(''), end = ref(''), busy = ref(false), loading = ref(false), error = ref(''), notice = ref('')
const status = ref<CustomBackupStatus | null>(null)
const latest = computed(() => status.value?.backup.latest)
let version = 0, timer: ReturnType<typeof setTimeout> | undefined
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const valid = computed(() => !!start.value && !!end.value && start.value >= '2000-01-01' && start.value <= end.value && end.value < today())
async function refresh() {
  if (!props.active || props.preview || loading.value) return
  const current = version; loading.value = true
  try {
    const value = await readCustomBackup()
    if (current !== version) return
    status.value = value; busy.value = value.job.running; error.value = ''
    if (value.job.errorCode || value.backup.lastAttempt?.status === 'FAILED') error.value = '本次备份未完成，原成功备份保留。请稍后重试或联系维护人员。'
    if (value.job.running) { clearTimeout(timer); timer = setTimeout(() => void refresh(), 3000) }
  } catch { if (current === version) { status.value = null; error.value = '状态读取失败，请刷新核对；不会自动重复提交备份。' } }
  finally { if (current === version) loading.value = false }
}
async function create() {
  if (!valid.value || busy.value || props.preview || !props.active) return
  busy.value = true; error.value = ''; notice.value = ''; const current = version
  try {
    await createCustomBackup(start.value, end.value)
    if (current !== version) return
    notice.value = '已提交，请等待生成完成。下方旧备份在新备份核验成功前继续保留。'
    await refresh()
  } catch (e) { if (current === version) { error.value = e instanceof Error ? e.message : '提交失败'; busy.value = false } }
}
watch([() => props.active, () => props.preview], () => {
  version++; clearTimeout(timer); loading.value = false; busy.value = false; status.value = null; error.value = ''; notice.value = ''
  if (props.active && !props.preview) void refresh()
}, { immediate: true })
onBeforeUnmount(() => { version++; clearTimeout(timer) })
</script>
<template>
  <section class="custom-backup" aria-labelledby="custom-backup-title">
    <h4 id="custom-backup-title">自定义周期备份</h4>
    <p>忘记下载某周备份时，可圈选日期补备份。包含首尾日期、所有项目，以统计日筛选；导出当前库中保留的数据，不还原过去某时刻的状态。最晚可选昨天。</p>
    <div class="custom-backup__form">
      <label>开始日期 <input v-model="start" type="date" min="2000-01-01" :disabled="preview || busy" /></label>
      <label>结束日期 <input v-model="end" type="date" min="2000-01-01" :disabled="preview || busy" /></label>
      <button type="button" :disabled="!valid || preview || busy" @click="create">{{ busy ? '正在生成…' : '生成压缩备份' }}</button>
      <button type="button" :disabled="preview || loading" @click="refresh">刷新自定义备份</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="latest">
      <p>备份范围：{{ latest.startDate }} 至 {{ latest.endDate }}（含首尾） · {{ (latest.bytes / 1024 / 1024).toFixed(2) }} MiB</p>
      <p>生成时间：{{ new Date(latest.createdAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</p>
      <p class="hash">SHA-256：{{ latest.sha256 }}</p>
      <a :href="customBackupDownloadUrl(latest.generation)" download @click="notice = '请在浏览器确认下载完成并核对SHA-256；点击下载不代表已完成异地备份。'">下载此周期压缩备份（.sql.gz）</a>
    </template>
    <p>自定义备份单独保留最近一份成功结果，不替换自动周备份。新结果成功后替换上一份，请及时下载。恢复时先导入独立空库核验，再处理与其他周期的合并。</p>
  </section>
</template>
<style scoped>
.custom-backup{border-top:1px solid #394158;margin-top:24px;padding-top:18px}h4{font-size:20px;margin:0 0 12px}p{font-size:14px;line-height:1.7}.custom-backup__form{display:flex;flex-wrap:wrap;gap:16px;align-items:end}label{display:grid;gap:8px}input,button{font:inherit;color:inherit;background:#141829;border:1px solid #596174;border-radius:8px;padding:10px;min-height:44px}button{cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}a{display:inline-block;color:var(--sc-gold,#d9c181);padding:12px 0}.hash{overflow-wrap:anywhere}
</style>
