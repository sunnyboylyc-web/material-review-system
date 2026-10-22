<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import { COLLECTION_THEMES, inCollectionWindow, themeLabel } from '../domain/collectionTheme'
import type { CollectionTheme, CollectionThemeAdapter, CollectionAccount, CollectionAccountSnapshot } from '../domain/collectionTheme'

const props = defineProps<{ adapter: CollectionThemeAdapter; now?: () => Date }>()
const snapshot = ref<CollectionAccountSnapshot | null>(null)
const editing = ref('')
const selected = ref<CollectionTheme>('QUANYU')
const busy = ref(false)
const error = ref('')
const message = ref('')
const stale = ref(false)
const clock = ref((props.now ?? (() => new Date()))())
const restricted = computed(() => inCollectionWindow(clock.value))
let disposed = false
let timer: ReturnType<typeof setInterval> | undefined
function blocked(account: CollectionAccount) { return restricted.value || account.collecting || stale.value || busy.value }
async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const value = await props.adapter.read()
    if (disposed) return
    snapshot.value = value
    stale.value = false
    editing.value = ''
    message.value = ''
  } catch { error.value = '读取失败，请重新读取配置。'; stale.value = true }
  finally { if (!disposed) busy.value = false }
}
function edit(account: CollectionAccount) {
  if (blocked(account)) return
  editing.value = account.projectId
  selected.value = account.theme
  error.value = ''; message.value = ''
}
async function save(account: CollectionAccount) {
  clock.value = (props.now ?? (() => new Date()))()
  if (blocked(account) || !snapshot.value || editing.value !== account.projectId) return
  const requested = selected.value
  const before = snapshot.value
  busy.value = true
  error.value = ''; message.value = ''
  try {
    const saved = await props.adapter.save({ expectedRevision: before.revision, projectId: account.projectId, theme: requested })
    const verified = await props.adapter.read()
    if (disposed) return
    const current = verified.accounts.find(item => item.projectId === account.projectId)
    if (saved.revision !== verified.revision || !current || current.theme !== requested
      || current.advertiserId !== account.advertiserId || current.publicDouyinId !== account.publicDouyinId
      || verified.accounts.length !== before.accounts.length
      || before.accounts.some(old => old.projectId !== account.projectId && !verified.accounts.some(next =>
        next.projectId === old.projectId && next.advertiserId === old.advertiserId
        && next.publicDouyinId === old.publicDouyinId && next.theme === old.theme))) throw new Error('READBACK_MISMATCH')
    snapshot.value = verified
    editing.value = ''
    message.value = `${account.projectName}已保存为${themeLabel(requested)}。后续采集使用新主题，本次未触发采集，历史数据保持不变。`
  } catch {
    if (!disposed) {
      stale.value = true
      error.value = '保存未确认，可能已生效。请重新读取配置核对，不要重复提交。'
    }
  } finally { if (!disposed) busy.value = false }
}
onMounted(() => {
  void load()
  timer = setInterval(() => { clock.value = (props.now ?? (() => new Date()))() }, 1000)
})
onBeforeUnmount(() => { disposed = true; if (timer) clearInterval(timer) })
</script>

<template>
  <section class="theme-panel" aria-labelledby="theme-title">
    <header><div><span class="eyebrow">采集账户配置</span><h2 id="theme-title">账户与采集主题</h2></div>
      <button :disabled="busy" @click="load">重新读取</button></header>
    <div class="theme-warning" role="note">
      <span class="warning-symbol" aria-hidden="true">⚠</span>
      <div><strong>北京时间每日 08:00—12:00，禁止更改采集主题</strong>
        <p>保存后立即生效；采集任务尚未结束时，需等待任务结束后再修改。</p></div>
    </div>
    <p class="theme-explanation">数据按抖音号连续累计。全域与乘方只决定采集来源，不拆分看板统计；切换不会重采或改写已成功的历史数据。</p>
    <p v-if="restricted" class="locked-message">当前处于自动采集时段，暂不可编辑。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="message" role="status" class="success">{{ message }}</p>
    <div class="table-wrap"><table>
      <thead><tr><th>项目</th><th>千川账户 ID</th><th>公开抖音号</th><th>采集主题</th><th>采集状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="account in snapshot?.accounts" :key="account.projectId">
        <td class="project-name">{{ account.projectName }}</td><td>{{ account.advertiserId }}</td><td>{{ account.publicDouyinId }}</td>
        <td><select v-if="editing === account.projectId" v-model="selected" :aria-label="`${account.projectName}采集主题`" :disabled="blocked(account)">
          <option v-for="theme in COLLECTION_THEMES" :key="theme.value" :value="theme.value">{{ theme.label }}</option>
        </select><span v-else class="theme-badge" :class="{ alternate: account.theme === 'CHENGFANG' }">{{ themeLabel(account.theme) }}</span></td>
        <td><span :class="account.collecting ? 'running' : 'idle'">{{ account.collecting ? '采集中 · 禁止修改' : '未在采集' }}</span></td>
        <td class="actions"><template v-if="editing === account.projectId">
          <button class="save" :disabled="blocked(account) || selected === account.theme" @click="save(account)">{{ busy ? '保存核对中…' : '保存' }}</button>
          <button :disabled="busy" @click="editing = ''">取消</button>
        </template><button v-else :disabled="blocked(account) || !!editing" @click="edit(account)">编辑</button></td>
      </tr><tr v-if="!snapshot"><td colspan="6">{{ busy ? '正在读取账户…' : '尚未读取账户配置' }}</td></tr></tbody>
    </table></div>
    <p class="footnote">只执行所选主题，不自动切换。保存不会立即发起采集。</p>
  </section>
</template>

<style scoped>
.theme-panel { padding: 28px; border: 1px solid #303347; border-radius: 18px; background: #10121f; color: #e7e7ef; }
header { display:flex; justify-content:space-between; align-items:center; gap:20px; margin-bottom:24px; }
h2 { margin:8px 0 0; font-size:24px; }.eyebrow { color:#b9aa76; font-size:13px; }
.theme-warning { display:flex; align-items:center; gap:16px; padding:18px 20px; border:1px solid #b88534; border-left:5px solid #f0b950; border-radius:10px; background:#322718; color:#ffdb92; }
.warning-symbol { font-size:32px; }.theme-warning p { margin:8px 0 0; font-size:14px; }
.theme-explanation,.footnote { color:#a8adbf; line-height:1.8; font-size:14px; }.theme-explanation { margin:20px 0; }.footnote { margin:18px 0 0; }
.table-wrap { overflow-x:auto; }table { width:100%; border-collapse:collapse; min-width:850px; }th,td { text-align:center; padding:20px 14px; border-bottom:1px solid #303347; font-size:14px; }th { color:#9ea5bb; font-weight:500; background:#191c2c; }td { height:35px; }.project-name { font-size:16px; font-weight:600; }
.theme-badge { display:inline-block; padding:6px 18px; background:#243b39; color:#a6e0d4; border-radius:7px; }.theme-badge.alternate { background:#342e4c; color:#d4b8fa; }
button,select { padding:9px 16px; border:1px solid #535363; border-radius:7px; background:#202333; color:#e7e7ef; font:inherit; cursor:pointer; }button:disabled,select:disabled { opacity:.4; cursor:not-allowed; }button:focus-visible,select:focus-visible { outline:2px solid #e6c87e; outline-offset:3px; }.actions { white-space:nowrap; }.actions button+button { margin-left:8px; }.save { background:#d0bc7e; border-color:#d0bc7e; color:#141722; }.idle { color:#a3b3b3; }.running,.locked-message { color:#ffcf7d; }.error { color:#ffaaaa; }.success { color:#a6e0d4; }
@media(max-width:700px) { .theme-panel { padding:16px; }header { align-items:flex-start; }h2 { font-size:20px; }.theme-warning { align-items:flex-start; } }
@media(max-width:1000px) {
  table { min-width:0; }thead { display:none; }tbody { display:grid; gap:14px; }
  tr { display:grid; grid-template-columns:1fr 1fr; border:1px solid #303347; border-radius:10px; padding:10px; }
  td { height:auto; padding:8px; border:0; text-align:left; min-width:0; overflow-wrap:anywhere; }
  td:nth-child(1) { grid-column:1; grid-row:1; }td:nth-child(5) { grid-column:2; grid-row:1; text-align:right; }
  td:nth-child(2),td:nth-child(3) { grid-column:1 / -1; font-size:13px; color:#a8adbf; }
  td:nth-child(2)::before { content:'千川账户：'; }td:nth-child(3)::before { content:'公开抖音号：'; }
  td:nth-child(6) { text-align:right; }
}
</style>
