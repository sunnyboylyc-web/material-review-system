<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import CustomBackupPanel from './CustomBackupPanel.vue'
import { databaseBackupDownloadUrl, readDatabaseBackup, validateDatabaseBackupStatus, type DatabaseBackupStatus } from '../adapters/httpDatabaseBackupAdapter'
const props = withDefaults(defineProps<{ active?: boolean; preview?: boolean; read?: () => Promise<DatabaseBackupStatus> }>(), { active: true, preview: false })
const result = ref<DatabaseBackupStatus | null>(null), loading = ref(false), error = ref(''), downloadNotice = ref('')
let version = 0
const latest = computed(() => result.value?.latest ?? null)
const downloadUrl = computed(() => latest.value ? databaseBackupDownloadUrl(latest.value.generation) : '')
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(2)} MiB（${bytes.toLocaleString('zh-CN')} 字节）`
async function refresh() {
  if (!props.active || props.preview || loading.value) return
  const current = ++version
  loading.value = true; error.value = ''; downloadNotice.value = ''; result.value = null
  try {
    const value = validateDatabaseBackupStatus(await (props.read ?? readDatabaseBackup)())
    if (current === version && props.active && !props.preview) result.value = value
  } catch { if (current === version) error.value = '备份状态暂不可核验，已暂停下载。请刷新状态或联系维护人员；不会自动启动或重试备份。' }
  finally { if (current === version) loading.value = false }
}
watch([() => props.active, () => props.preview], ([active, preview]) => {
  version++; loading.value = false; result.value = null; error.value = ''; downloadNotice.value = ''
  if (active && !preview) void refresh()
}, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>

<template>
  <section class="database-backup" aria-labelledby="database-backup-title" :aria-busy="loading">
    <header><div><p class="panel__index">DATABASE BACKUP</p><h3 id="database-backup-title">最新业务数据库备份</h3></div>
      <button type="button" :disabled="loading || preview" @click="refresh">{{ loading ? '正在读取状态…' : '刷新备份状态' }}</button></header>
    <p>每周日23:00（上海时间），独立备份当天之前7个完整自然日（T−7至T−1）的数据。新份核验成功后替换上一份，不叠加历史备份。漏下载时可在下方自定义周期补备份。</p>
    <p class="backup-attention">按统计日期包含所有项目的业务数据及对应版本、状态；这是周期数据备份，不是全库或整套系统备份，不含程序、账号配置、密钥及库外来源文件。请妥善保管。</p>
    <p class="backup-attention">同一服务器上的备份不能防止整机丢失。请下载到电脑或其他独立位置；点击下载不代表异地备份已完成，请在浏览器确认文件下载结束并核对 SHA-256。</p>
    <p v-if="preview" class="backup-attention" role="status">当前为本地或只读演示，不连接真实备份、不提供真实文件下载。正式环境的备份状态以服务器记录为准。</p>
    <p v-else-if="loading" role="status">只读取备份状态，不触发备份、采集、入库或清理。</p>
    <p v-else-if="error" role="alert" class="backup-attention">{{ error }}</p>
    <template v-else-if="result">
      <p v-if="result.lastAttempt?.status === 'RUNNING'" role="status" class="backup-attention">上次记录：处理中，尚无完成回执（开始于 {{ time(result.lastAttempt.startedAt) }}）。可能仍在处理或已经中断，请核对；这不是实时进度，刷新不会触发备份。{{ latest ? '下方显示当前已核验、可下载的版本，请按生成时间核对。' : '目前还没有可下载的已核验备份。' }}</p>
      <p v-else-if="result.lastAttempt?.status === 'FAILED'" role="alert" class="backup-attention">本次备份或旧份清理未全部完成，请联系维护人员。{{ latest ? '下方为已核验的可用备份，请按生成时间核对；未完成清理时可能暂时保留多份。' : '目前没有可下载的已核验备份。' }}<span v-if="result.lastAttempt.errorCode"> 原因代码：{{ result.lastAttempt.errorCode }}</span></p>
      <p v-if="!latest" role="status">尚无已核验的周备份。未提供下载，也不代表定时任务已启用；请等待首次备份回执。</p>
      <template v-else>
        <p v-if="latest.scope === 'STAT_DATE_RANGE'">备份范围：{{ latest.startDate }} 至 {{ latest.endDate }}（含首尾）</p>
        <p v-if="latest.compression === 'gzip'">下载格式：SQL压缩包（.sql.gz）。先解压得到SQL文件，再用于数据库恢复。下方大小和SHA-256对应压缩文件；解压后SQL大小为 {{ size(latest.sqlBytes!) }}。</p>
        <dl class="database-backup__facts"><div><dt>备份时间（上海）</dt><dd>{{ time(latest.createdAt) }}</dd></div><div><dt>文件大小</dt><dd>{{ size(latest.bytes) }}</dd></div><div class="database-backup__hash"><dt>SHA-256</dt><dd><code>{{ latest.sha256 }}</code></dd></div></dl>
        <p class="backup-attention">已完成导出退出检查及文件哈希读回；尚未完成此份备份的数据库恢复演练。只保留最新一份，替换后不能通过本入口回到更早一周。</p>
        <a class="primary-button database-backup__download" :href="downloadUrl" download @click="downloadNotice = '已交给浏览器发起下载，本站不能确认下载完成。如提示版本已更新（HTTP 409），请刷新备份状态后下载新版本；不会自动切换为另一份文件。'">下载此份数据库备份</a>
        <p v-if="downloadNotice" class="backup-attention" role="status">{{ downloadNotice }}</p>
      </template>
    </template>
    <p class="database-backup__footnote">刷新仅查询状态；备份计划的实际启用情况以服务器部署回执为准。</p>
    <CustomBackupPanel :active="active" :preview="preview" />
  </section>
</template>

<style scoped>
.database-backup { border: 1px solid var(--border-color, #394158); border-radius: 12px; padding: 22px; margin-bottom: 24px; }
header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 16px; }
h3 { margin: 6px 0; } p { font-size: 14px; line-height: 1.7; }
.backup-attention { color: var(--sc-warning-text, #efb3ad); }
button, .database-backup__download { min-height: 44px; border-radius: 8px; padding: 10px 16px; display: inline-flex; align-items: center; text-decoration: none; font: inherit; }
button { color: inherit; background: transparent; border: 1px solid var(--border-color, #596174); cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--sc-gold, #d9c181); outline-offset: 3px; }
.database-backup__facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin: 22px 0; }
dt { opacity: .8; font-size: 13px; margin-bottom: 8px; } dd { margin: 0; overflow-wrap: anywhere; font-size: 14px; }
.database-backup__hash { grid-column: 1 / -1; } .database-backup__footnote { font-size: 12px; }
@media(max-width:540px) { .database-backup { padding: 16px; } .database-backup__facts { grid-template-columns: 1fr; } }
</style>
