<script setup lang="ts">
import { ref, watch } from 'vue'
import { httpAdminAccounts, type AdminAccounts, type ManagedAccount } from '../adapters/adminAccounts'
const props = withDefaults(defineProps<{ adapter?: AdminAccounts; active?: boolean }>(), { active: true })
const adapter = props.adapter ?? httpAdminAccounts
const accounts = ref<ManagedAccount[]>([]), username = ref(''), password = ref('')
const role = ref<'VIEWER' | 'OPERATIONS'>('VIEWER'), busy = ref(false), error = ref(''), notice = ref('')
let loaded = false
const labels = { ADMIN: '管理员', OPERATIONS: '运维', VIEWER: '普通用户' }
async function load() {
  busy.value = true; error.value = ''
  try { accounts.value = (await adapter.list()).accounts; loaded = true }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function create() {
  if (busy.value) return
  error.value = ''; notice.value = ''
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(username.value)) { error.value = '账号需为 1～40 位英文字母、数字、下划线或短横线，区分大小写。'; return }
  if ([...password.value].length < 6 || [...password.value].length > 30) { error.value = '密码需为 6～30 个字符，支持数字、大小写和符号。'; return }
  busy.value = true
  try {
    accounts.value = (await adapter.create(username.value, password.value, role.value)).accounts
    notice.value = `账号“${username.value}”已创建，请通过安全方式告知使用者。`
    username.value = ''; password.value = ''; role.value = 'VIEWER'
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function remove(account: ManagedAccount) {
  if (busy.value || account.role === 'ADMIN') return
  if (!window.confirm(`确定删除账号“${account.username}”（${labels[account.role]}）吗？\n删除后该账号不能再登录，已登录的会话也将失效。不删除项目、业务数据和已上传文件。\n账号名称删除后不能重新使用。确认后才会删除。`)) return
  busy.value = true; error.value = ''; notice.value = ''
  try { accounts.value = (await adapter.remove(account.username)).accounts; notice.value = `账号“${account.username}”已删除，业务数据未删除。` }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
watch(() => props.active, active => { if (active && !loaded && !busy.value) void load() }, { immediate: true })
</script>

<template>
  <section class="panel admin-accounts" aria-labelledby="admin-accounts-title" :aria-busy="busy">
    <header><div><p class="eyebrow">ADMINISTRATION</p><h3 id="admin-accounts-title">账号管理 · 仅管理员</h3></div>
      <button type="button" :disabled="busy" @click="load">刷新账号列表</button></header>
    <p class="module-note">普通用户可看全部项目和下载项目数据；运维还可配置业务、上传历史数据及管理备份文件。只有管理员可以新增、删除账号。</p>
    <form class="admin-accounts__form" @submit.prevent="create">
      <label>新账号<input v-model="username" name="new-account" autocomplete="off" :disabled="busy" required aria-describedby="account-name-help" /></label>
      <label>初始密码<input v-model="password" type="password" name="new-password" autocomplete="new-password" :disabled="busy" required aria-describedby="account-password-help" /></label>
      <label>账号类型<select v-model="role" :disabled="busy"><option value="VIEWER">普通用户</option><option value="OPERATIONS">运维</option></select></label>
      <button type="submit" class="primary-button" :disabled="busy">{{ busy ? '正在处理…' : '新增账号' }}</button>
    </form>
    <p id="account-name-help" class="module-note">账号：1～40 位英文字母、数字、下划线或短横线，区分大小写。已删除的账号名称不能再次使用。</p>
    <p id="account-password-help" class="module-note">密码：6～30 个字符，支持数字、大小写和符号；建议使用 12 位以上不同密码。创建后不显示或提供密码下载。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="busy" role="status" class="module-note">正在处理账号信息，请稍候…</p>
    <ul class="admin-accounts__list" aria-label="账号列表">
      <li v-for="account in accounts" :key="account.username"><strong>{{ account.username }}</strong><span>{{ labels[account.role] }}</span>
        <small v-if="account.role === 'ADMIN'">固定管理员，不可删除</small>
        <button v-else type="button" class="admin-accounts__delete" :disabled="busy" :aria-label="`删除账号 ${account.username}`" @click="remove(account)">删除账号</button></li>
    </ul>
  </section>
</template>

<style scoped>
.admin-accounts { margin-bottom: 20px; }
header { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.admin-accounts__form { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 12px; margin-top: 16px; }
label { display: grid; gap: 6px; min-width: 160px; flex: 1; }
input, select { min-height: 44px; border: 1px solid var(--border-color, #dbe5ea); border-radius: 8px; padding: 8px 10px; font: inherit; width: 100%; }
button { min-height: 44px; padding: 8px 12px; cursor: pointer; font: inherit; border-radius: 8px; }
button:not(.primary-button) { border: 1px solid var(--border-color, #dbe5ea); background: white; color: #216185; }
button:disabled { opacity: .55; cursor: not-allowed; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid #146883; outline-offset: 3px; }
.admin-accounts__list { list-style: none; padding: 0; max-height: 320px; overflow: auto; }
li { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; border-top: 1px solid var(--border-color, #dbe5ea); padding: 12px 0; }
li strong { flex: 1; min-width: 100px; overflow-wrap: anywhere; }
button.admin-accounts__delete { color: #b42318; border-color: #e8b8b3; }
</style>
