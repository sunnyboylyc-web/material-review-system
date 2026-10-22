<script setup lang="ts">
import { applicationPath } from '../domain/applicationPath'
import { ref, onBeforeUnmount, watch } from 'vue'
import { csrfHeaders, sessionFetch } from '../domain/session'
const props = withDefaults(defineProps<{ active?: boolean; preview?: boolean }>(), { active: true, preview: false })
interface StoredFile { id: string; name: string; bytes: number; received: number; status: string; sha256: string; createdAt: string }
const expanded = ref(false), files = ref<StoredFile[]>([]), selected = ref<File | null>(null)
const busy = ref(false), writable = ref(false), notice = ref(''), error = ref(''), progress = ref(0)
const deleting = ref(false)
let chunkBytes = 4 * 1024 * 1024
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
async function request(path: string, init?: RequestInit) {
  if (props.preview) throw new Error('演示环境不连接真实文件存储。')
  const response = await sessionFetch(`/api/operations/files${path}`, { cache: 'no-store', ...init,
    headers: { ...csrfHeaders(), ...init?.headers } })
  if (!response.ok) {
    let reason = ''; try { reason = (await response.json()).error } catch { /* non-JSON proxy error */ }
    throw new Error(reason === 'DISK_RESERVE_REQUIRED' ? '服务器剩余空间不足，已停止上传并保留数据库所需空间。请联系运维扩容。' : `文件操作失败（HTTP ${response.status}）。刷新文件列表核对状态；未完成文件不会作为备份提供下载。`)
  }
  return response.json()
}
async function load(clearError = true) {
  if (props.preview || disposed || (!props.active && !busy.value)) return
  if (clearError) error.value = ''
  try { const result = await request(''); files.value = result.files; writable.value = result.writeEnabled; chunkBytes = result.chunkBytes
    clearTimeout(timer)
    if (!disposed && props.active && files.value.some(f => f.status === 'VERIFYING')) timer = setTimeout(() => load(false), 2000)
  }
  catch (e) { error.value = (e as Error).message }
}
async function toggle(event: Event) { expanded.value = (event.target as HTMLDetailsElement).open; if (expanded.value) await load() }
watch(() => props.active, active => {
  if (!active) clearTimeout(timer)
  else if (expanded.value && !busy.value) void load(false)
})
function choose(event: Event) { selected.value = (event.target as HTMLInputElement).files?.[0] ?? null; notice.value = ''; progress.value = 0 }
async function upload() {
  const file = selected.value; if (!file || busy.value) return
  busy.value = true; error.value = ''; notice.value = '正在上传，请保持当前页面打开；关闭或刷新窗口可能中断上传。'; progress.value = 0
  try {
    const entry: StoredFile = await request('', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: file.name, bytes: file.size }) })
    for (let offset = 0; offset < file.size; offset += chunkBytes) {
      if (disposed) throw new Error('页面已离开，上传未完成。返回文件列表后可取消该未完成记录并重新上传。')
      const result: StoredFile = await request(`/${entry.id}/chunks?offset=${offset}`, { method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: file.slice(offset, offset + chunkBytes) })
      progress.value = Math.floor(result.received * 100 / file.size)
    }
    notice.value = '上传字节已接收，服务器正在核验并保存；尚未完成。'
    await request(`/${entry.id}/complete`, { method: 'POST' })
    notice.value = '服务器正在后台核验。列表显示“已保存”后才算完成，可安全切换页面，稍后刷新文件列表。'; selected.value = null
  } catch (e) { error.value = (e as Error).message; notice.value = '上传未确认完成。请核对下方列表；未完成记录可取消后重新上传。' }
  finally { busy.value = false; await load(false) }
}
async function cancel(id: string) {
  if (!window.confirm('仅清除这一份未完成上传的临时文件？已保存的文件不受影响。')) return
  busy.value = true
  try { await request(`/${id}/pending`, { method: 'DELETE' }); await load() }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function complete(id: string) {
  busy.value = true
  try { await request(`/${id}/complete`, { method: 'POST' }); await load() }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
function size(bytes: number) { return `${(bytes / 1024 / 1024).toFixed(2)} MB` }
async function deleteSaved(file: StoredFile) {
  if (busy.value || !writable.value) return
  if (!window.confirm(`确定永久删除“${file.name}”吗？\n保存时间：${new Date(file.createdAt).toLocaleString('zh-CN')}\n仅删除这一份服务器文件，同名的其他文件不受影响。删除后无法恢复，请确认电脑或其他位置已有备份。`)) return
  busy.value = true; deleting.value = true; error.value = ''; notice.value = '正在删除，请稍候。'
  try {
    await request(`/${file.id}`, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ confirmed: true }) })
    notice.value = `已删除“${file.name}”，同名的其他文件不受影响。`
  } catch (e) { error.value = (e as Error).message; notice.value = '删除未确认完成，请刷新列表核对；如显示删除未完成，可再次确认后重试。' }
  finally { busy.value = false; deleting.value = false; await load(false) }
}
</script>

<template>
  <details class="file-storage" @toggle="toggle">
    <summary>人工文件存储 · 上传 / 下载资料</summary>
    <div v-if="expanded" class="file-storage__body">
      <p class="file-storage__attention">仅运维账号可以上传、下载和删除。可存放程序开发资料压缩包；这里只保存原文件，不解压、不执行、不导入历史数据。同名文件分别保存，不覆盖。删除须再次确认，且无法恢复，请自行保留备份。</p>
      <p class="file-storage__attention">不设业务总容量配额，但受服务器实际磁盘空间限制，至少保留 1 GB 安全空间。这里与系统在同一台服务器，不能代替电脑或异地备份。</p>
      <p v-if="preview" class="file-storage__attention">本地演示不读取、上传或下载正式文件。系统每周备份请使用上方固定入口，不必重复上传到本列表。</p>
      <div class="file-storage__actions">
        <label>选择备份文件 <input type="file" :disabled="busy || !writable" @change="choose"></label>
        <button type="button" class="primary-button" :disabled="busy || !selected || !writable" @click="upload">上传文件</button>
        <button type="button" :disabled="busy || preview" @click="load()">刷新文件列表</button>
      </div>
      <div v-if="busy && !deleting" role="status"><progress :value="progress" max="100" aria-label="已收到的上传字节进度" /> {{ progress }}%</div>
      <p v-if="busy" class="file-storage__attention" role="status">{{ deleting ? '正在删除，请勿重复操作；请等待明确结果。' : '正在处理，请勿重复提交；上传字节达到100%不代表已核验保存，请等待“已保存”结果。' }}</p>
      <p v-if="notice" role="status">{{ notice }}</p><p v-if="error" role="alert" class="file-storage__error">{{ error }}</p>
      <p v-if="!files.length && !error">暂无文件。</p>
      <ul class="file-storage__list">
        <li v-for="file in files" :key="file.id">
          <div><strong>{{ file.name }}</strong><small>{{ size(file.bytes) }} · {{ new Date(file.createdAt).toLocaleString('zh-CN') }}</small>
            <small v-if="file.status === 'READY'">已保存 · SHA-256 {{ file.sha256 }}</small>
            <small v-else-if="file.status === 'DELETING'">删除未完成，不能下载；请再次确认删除以完成清理</small>
            <small v-else-if="file.status === 'VERIFYING'">服务器正在核验并保存，尚未完成；稍后自动更新状态</small>
            <small v-else>未完成{{ file.status === 'FAILED' || file.status === 'INTERRUPTED' ? '（核验失败或中断，可点击完成保存重试）' : '' }} · 已接收 {{ size(file.received) }}，不能作为有效备份</small></div>
          <a v-if="file.status === 'READY'" :href="applicationPath(`/api/operations/files/${file.id}/download`)" download>下载</a>
          <button v-if="file.status === 'READY' || file.status === 'DELETING'" type="button" class="file-storage__delete" :disabled="busy || !writable" @click="deleteSaved(file)">{{ file.status === 'DELETING' ? '重试删除' : '删除' }}</button>
          <template v-else-if="file.status !== 'VERIFYING'"><button v-if="file.received === file.bytes" type="button" :disabled="busy" @click="complete(file.id)">完成保存</button><button v-if="file.status === 'UPLOADING'" type="button" :disabled="busy" @click="cancel(file.id)">取消未完成上传</button></template>
        </li>
      </ul>
    </div>
  </details>
</template>

<style scoped>
.file-storage { margin: 0 0 16px auto; padding: 12px 16px; border: 1px solid var(--border-color, #dbe5ea); border-radius: 10px; }
summary { cursor: pointer; min-height: 24px; text-align: right; font-weight: 600; }
.file-storage__body { line-height: 1.6; }
.file-storage__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
button, a { min-height: 44px; display: inline-flex; align-items: center; padding: 8px 12px; }
button:not(.primary-button), a { border: 1px solid #dbe5ea; border-radius: 8px; background: #fff; color: #216185; text-decoration: none; cursor: pointer; font: inherit; }
button:not(.primary-button):hover, a:hover { background: #edf5f8; }
button:disabled { opacity: .55; cursor: not-allowed; }
button:focus-visible, a:focus-visible, summary:focus-visible { outline: 2px solid #146883; outline-offset: 3px; }
input { max-width: 100%; }
.file-storage__list { list-style: none; padding: 0; max-height: 300px; overflow: auto; }
li { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; border-top: 1px solid var(--border-color, #dbe5ea); padding: 12px 0; }
li > div { flex: 1; min-width: 180px; overflow-wrap: anywhere; }
small { display: block; }
.file-storage__error { color: #b42318; }
button.file-storage__delete { color: #b42318; border-color: #e8b8b3; }
progress { width: min(100%, 320px); }
</style>
