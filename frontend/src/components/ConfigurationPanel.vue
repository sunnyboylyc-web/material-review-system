<script setup lang="ts">
import { nextTick, computed, onBeforeUnmount, ref, watch } from 'vue'
import { ConfigurationModeUnavailableError, httpConfigurationAdapter, validateConfigurationApply, validateConfigurationPreflight, validateConfigurationSnapshot } from '../adapters/httpConfigurationAdapter'
import { configurationMetricsMatch, configurationProjectsMatch, validateEnabledMetrics, validateConfigurationProjects } from '../domain/configuration'
import type { ConfigurationAdapter, ConfigurationPreflight, ConfigurationProject, ConfigurationSnapshot } from '../domain/configuration'
import { PROJECT_CATEGORIES, projectCategoryLabel } from '../domain/projectCategory'
import { inCollectionWindow, themeLabel } from '../domain/collectionTheme'
import type { CollectionTheme } from '../domain/collectionTheme'

const props = withDefaults(defineProps<{ adapter?: ConfigurationAdapter; active?: boolean; showPreviewAuxiliary?: boolean
  recoveryReadOnly?: boolean; localReadOnlyPreview?: boolean; scope?: 'projects' | 'metrics'; knownRevision?: string }>(), { active: true, scope: 'projects' })
const metricsOnly = computed(() => props.scope === 'metrics')
const titleId = computed(() => metricsOnly.value ? 'metrics-configuration-title' : 'configuration-title')
const emit = defineEmits<{ changed: [revision: string]; refreshed: [revision: string]; saving: [] }>()
const adapter = props.adapter ?? httpConfigurationAdapter
// Match the frozen configuration contract; visual preset count is independent.
const PROJECT_LIMIT = 32
interface ProjectDraft extends ConfigurationProject { publicDouyinId: string }
const snapshot = ref<ConfigurationSnapshot | null>(null)
const draft = ref<ProjectDraft[]>([])
const selectedMetrics = ref<string[]>([])
const metricsExpanded = ref(false)
const preflight = ref<ConfigurationPreflight | null>(null)
const busy = ref<'' | 'loading' | 'checking' | 'saving'>('')
const error = ref('')
const notice = ref('')
const modeUnavailable = ref(false)
const requiresReload = ref(true)
let requestVersion = 0
let draftVersion = 0
let applyingResolution = false
let disposed = false
const canEdit = computed(() => props.active && !props.recoveryReadOnly && Boolean(snapshot.value?.writeEnabled) && !requiresReload.value && busy.value !== 'loading' && busy.value !== 'saving')
const themeEditing = ref('')
const themeChoice = ref<CollectionTheme>('QUANYU')
const themeClock = ref(new Date())
const themeTimer = setInterval(() => { themeClock.value = new Date() }, 1000)
const themeWindow = computed(() => inCollectionWindow(themeClock.value))
function themeLocked(project: ConfigurationProject): boolean {
  return !canEdit.value || !!busy.value || !snapshot.value?.themeChangesEnabled || themeWindow.value
    || !!snapshot.value?.collectingProjectIds?.includes(project.id)
}
function editTheme(project: ConfigurationProject) {
  if (themeLocked(project)) return
  themeEditing.value = project.id
  themeChoice.value = project.collectionTheme ?? 'QUANYU'
}
async function saveTheme(project: ConfigurationProject) {
  themeClock.value = new Date()
  if (themeLocked(project) || !snapshot.value || themeEditing.value !== project.id) return
  const before = snapshot.value
  const chosen = themeChoice.value
  const projects = before.projects.map(item => ({ ...item, douyinIds: [...item.douyinIds],
    ...(item.id === project.id ? { collectionTheme: chosen } : {}) }))
  const candidate = { expectedRevision: before.revision, projects,
    ...(before.metricChangesEnabled ? { enabledMetrics: [...(before.enabledMetrics ?? [])] } : {}) }
  const version = ++requestVersion
  busy.value = 'saving'; preflight.value = null; error.value = ''; notice.value = ''
  emit('saving')
  try {
    const checked = validateConfigurationPreflight(await adapter.preflight(candidate), before.revision)
    if (disposed) return
    themeClock.value = new Date()
    if (inCollectionWindow(themeClock.value)) throw new Error('已进入自动采集时段，请重新读取配置。')
    const applied = validateConfigurationApply(await adapter.apply({ ...candidate, candidateHash: checked.candidateHash }), before.revision)
    const current = validateConfigurationSnapshot(await adapter.read())
    if (!isCurrent(version)) return
    if (current.revision !== applied.revision || !configurationProjectsMatch(current.projects, projects)
      || current.metricChangesEnabled !== before.metricChangesEnabled || !configurationMetricsMatch(current.enabledMetrics, before.enabledMetrics)) throw new Error('保存后回读不一致。')
    snapshot.value = current
    const row = draft.value.find(item => item.id === project.id)
    if (row) row.collectionTheme = chosen
    themeEditing.value = ''
    notice.value = `已保存${project.name}的采集主题：${themeLabel(chosen)}。其他项目草稿保留且未提交；未触发采集，历史数据未改动。`
    emit('changed', current.revision)
  } catch {
    if (!disposed) { requiresReload.value = true; error.value = '主题保存未确认，请重新读取配置核对，勿重复提交。' }
  } finally { if (version === requestVersion) busy.value = '' }
}

watch(() => props.knownRevision, revision => {
  if (revision && snapshot.value && revision !== snapshot.value.revision) {
    requiresReload.value = true
    preflight.value = null
    notice.value = '另一配置分区已更新。当前草稿保留，但不能继续保存；请记下需要的修改，重新读取配置后再编辑。'
  }
})

function candidateProjects(): ConfigurationProject[] {
  if (metricsOnly.value && snapshot.value) return snapshot.value.projects.map(project => ({ ...project, douyinIds: [...project.douyinIds] }))
  return draft.value.map(project => ({ id: project.id, name: project.name, advertiserId: project.advertiserId,
    ...(project.storageAccountId !== undefined ? { storageAccountId: project.storageAccountId } : {}),
    ...(project.collectionTheme !== undefined ? { collectionTheme: project.collectionTheme } : {}),
    douyinIds: [...project.douyinIds], enabled: project.enabled, category: project.category ?? 'OTHER', ...(project.deleted ? { deleted: true } : {}),
    ...(project.publicDouyinId ? { publicDouyinId: project.publicDouyinId, awemeName: project.awemeName } : {}) }))
}
function acceptSnapshot(value: ConfigurationSnapshot): void {
  snapshot.value = value
  selectedMetrics.value = [...(value.enabledMetrics ?? [])]
  draft.value = value.projects.map(project => ({ id: project.id, name: project.name, advertiserId: project.advertiserId,
    ...(project.storageAccountId !== undefined ? { storageAccountId: project.storageAccountId } : {}),
    ...(project.collectionTheme !== undefined ? { collectionTheme: project.collectionTheme } : {}),
    douyinIds: [...project.douyinIds], enabled: project.enabled, publicDouyinId: project.publicDouyinId ?? '', awemeName: project.awemeName,
    deleted: project.deleted ?? false, category: project.category ?? 'OTHER' }))
  requiresReload.value = false
  modeUnavailable.value = false
  themeEditing.value = ''
}
function isCurrent(version: number): boolean { return !disposed && version === requestVersion }

watch([draft, selectedMetrics], () => {
  draftVersion += 1
  preflight.value = null
  notice.value = ''
  if (busy.value === 'checking' && !applyingResolution) { requestVersion += 1; busy.value = '' }
}, { deep: true, flush: 'sync' })

async function load(manual = false): Promise<void> {
  if (busy.value === 'saving') return
  const version = ++requestVersion
  preflight.value = null
  requiresReload.value = true
  busy.value = 'loading'
  error.value = ''
  notice.value = ''
  try {
    const value = validateConfigurationSnapshot(await adapter.read())
    if (!isCurrent(version)) return
    acceptSnapshot(value)
    notice.value = '已重新读取当前配置；这不是采集执行或保存任务的历史回执。'
    if (manual) emit('refreshed', value.revision)
  } catch (failure) {
    if (!isCurrent(version)) return
    modeUnavailable.value = failure instanceof ConfigurationModeUnavailableError
    error.value = failure instanceof Error ? failure.message : '配置读取失败，请重新读取。'
    // Preserve the last snapshot/draft visibly as stale; never replace a failed read with [].
  } finally { if (version === requestVersion) busy.value = '' }
}

function addDraft(): void {
  if (!canEdit.value || draft.value.length >= PROJECT_LIMIT) return
  draft.value.push({ id: `project-${crypto.randomUUID().slice(0, 8)}`, name: '', advertiserId: '', douyinIds: [], enabled: false,
    ...(snapshot.value?.themeChangesEnabled ? { collectionTheme: 'QUANYU' as const } : {}),
    publicDouyinId: '', awemeName: undefined, category: 'OTHER' })
}

function changeIdentity(project: ProjectDraft, field: 'advertiserId' | 'publicDouyinId', event: Event): void {
  project[field] = (event.target as HTMLInputElement).value.trim()
  project.douyinIds = []
  project.awemeName = undefined
}
function isSaved(project: ProjectDraft): boolean { return Boolean(snapshot.value?.projects.some(saved => saved.id === project.id)) }
function removeProject(project: ProjectDraft): void {
  if (!canEdit.value) return
  if (isSaved(project)) { project.deleted = true; project.enabled = false }
  else draft.value.splice(draft.value.indexOf(project), 1)
}
function restoreProject(project: ProjectDraft): void {
  if (!canEdit.value) return
  project.deleted = false
  project.enabled = false
}

async function checkDraft(): Promise<void> {
  if (!canEdit.value || !snapshot.value) return
  preflight.value = null
  error.value = ''
  notice.value = ''
  const projects = candidateProjects()
  const changedThemes = projects.filter(project => (project.collectionTheme ?? 'QUANYU') !== (snapshot.value?.projects.find(old => old.id === project.id)?.collectionTheme ?? 'QUANYU'))
  themeClock.value = new Date()
  if (changedThemes.some(project => themeLocked(project))) { error.value = '当前禁止修改采集主题，请在采集时段和运行任务结束后再保存。'; return }
  const validation = validateConfigurationProjects(projects, snapshot.value.projects)
  if (validation) { error.value = validation; return }
  const metricValidation = snapshot.value.metricChangesEnabled ? validateEnabledMetrics(selectedMetrics.value, snapshot.value.metricCatalog ?? []) : ''
  if (metricValidation) { error.value = metricValidation; return }
  const metrics = snapshot.value.metricChangesEnabled ? { enabledMetrics: [...selectedMetrics.value] } : {}
  const expectedRevision = snapshot.value.revision
  const version = ++requestVersion
  let checkedDraftVersion = draftVersion
  busy.value = 'checking'
  try {
    for (const project of projects) {
      const old = snapshot.value?.projects.find(p => p.id === project.id)
      if (snapshot.value?.themeChangesEnabled && ((!old && !!project.publicDouyinId) || (old && (old.advertiserId !== project.advertiserId || (old.publicDouyinId ?? null) !== (project.publicDouyinId ?? null))))) {
        if (inCollectionWindow(new Date()) || snapshot.value?.collectingProjectIds?.includes(project.id)) throw new Error('采集期间禁止修改账户或抖音号。')
        const resolved = await adapter.resolveAnchor({ advertiserId: project.advertiserId, publicDouyinId: project.publicDouyinId ?? '' })
        if (!isCurrent(version) || checkedDraftVersion !== draftVersion) return
        project.douyinIds = [resolved.awemeId]; project.awemeName = resolved.awemeName
        project.storageAccountId = old?.storageAccountId ?? old?.advertiserId ?? resolved.awemeId
        const row = draft.value.find(p => p.id === project.id)!
        applyingResolution = true
        try { row.douyinIds = [...project.douyinIds]; row.awemeName = project.awemeName; row.storageAccountId = project.storageAccountId } finally { applyingResolution = false }
        await nextTick(); checkedDraftVersion = draftVersion
      }
    }
    const value = validateConfigurationPreflight(await adapter.preflight({ expectedRevision, projects, ...metrics }), expectedRevision)
    if (!isCurrent(version) || checkedDraftVersion !== draftVersion) return
    preflight.value = value
    notice.value = metricsOnly.value ? '指标草稿预检通过，请明确保存。本次只修改采集指标，不提交项目配置页的草稿，也不执行采集。'
      : '草稿结构预检通过，请明确保存。未执行采集；变更已有账户时会核验抖音号身份，历史保留。'
  } catch (failure) {
    if (!isCurrent(version)) return
    error.value = failure instanceof Error ? failure.message : '配置预检失败。'
    requiresReload.value = true
  } finally { if (version === requestVersion) busy.value = '' }
}

async function save(): Promise<void> {
  if (!canEdit.value || busy.value || !snapshot.value || !preflight.value) return
  const expectedRevision = snapshot.value.revision
  themeClock.value = new Date()
  if (candidateProjects().some(project => (project.collectionTheme ?? 'QUANYU') !== (snapshot.value?.projects.find(old => old.id === project.id)?.collectionTheme ?? 'QUANYU') && themeLocked(project))) { error.value = '当前禁止修改采集主题，请在采集时段和运行任务结束后再保存。'; preflight.value = null; return }
  if (preflight.value.expectedRevision !== expectedRevision) { preflight.value = null; return }
  const candidate = { expectedRevision, projects: candidateProjects(), candidateHash: preflight.value.candidateHash,
    ...(snapshot.value.metricChangesEnabled ? { enabledMetrics: [...selectedMetrics.value] } : {}) }
  const version = ++requestVersion
  preflight.value = null
  busy.value = 'saving'
  requiresReload.value = true
  error.value = ''
  notice.value = ''
  emit('saving')
  try {
    const applied = validateConfigurationApply(await adapter.apply(candidate), expectedRevision)
    if (!isCurrent(version)) return
    const current = validateConfigurationSnapshot(await adapter.read())
    if (!isCurrent(version)) return
    if (current.revision !== applied.revision || !configurationProjectsMatch(current.projects, candidate.projects)
      || current.metricChangesEnabled !== snapshot.value.metricChangesEnabled
      || !configurationMetricsMatch(current.enabledMetrics, candidate.enabledMetrics)) {
      throw new Error('保存后独立读取的版本或配置与本次提交不一致；结果未确认，请重新读取，禁止自动重试。')
    }
    acceptSnapshot(current)
    notice.value = '配置已保存并独立读取核对当前版本；正在刷新工作台项目。采集未执行，历史数据未删除。'
    emit('changed', current.revision)
  } catch (failure) {
    if (!isCurrent(version)) return
    error.value = `${failure instanceof Error ? failure.message : '保存结果无法确认。'} 必须重新读取当前配置后再判断，不自动重试保存。`
  } finally { if (version === requestVersion) busy.value = '' }
}

watch(() => props.active, active => {
  // Each scope retains its own draft. Full-snapshot writes remain revision guarded.
  // Re-entry does not silently reload over edits; the explicit reload remains available.
  if (active && !snapshot.value && !busy.value) void load()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; requestVersion += 1; preflight.value = null; clearInterval(themeTimer) })
</script>

<template>
  <section class="panel configuration-panel" :class="{ 'metrics-configuration-panel': metricsOnly }" :aria-labelledby="titleId">
    <div class="panel__header">
      <div><p class="panel__index">{{ metricsOnly ? 'COLLECTION METRICS' : 'CONFIGURATION' }}</p><h3 :id="titleId">{{ metricsOnly ? '采集指标配置' : '项目配置' }}</h3>
        <p v-if="metricsOnly">选择以后采集的指标，预检后明确保存。仅保存本页指标选择，不提交“项目配置”页的未保存草稿。</p>
        <p v-else>设置项目及账户绑定，预检后明确保存。采集指标配置已移至“采集状态”末尾；采集时序和执行记录请看“采集状态”。</p>
      </div>
      <button type="button" class="primary-button configuration-reload" :disabled="busy === 'saving'" @click="load(true)">重新读取配置</button>
    </div>
    <p v-if="props.localReadOnlyPreview" class="configuration-attention">本地只读预览不连接云端文件存储或账号管理，不代表云端没有文件；请在正式环境查看。</p>
    <p v-if="adapter.evidenceKind === 'SYNTHETIC'" class="synthetic-coverage-warning">配置为合成测试样本，不是真实账户。</p>
    <details v-if="!metricsOnly" class="operations-boundary configuration-guide">
      <summary>第一次使用：如何新增项目、保存和恢复</summary>
      <p>新增项目：点下方“新增草稿项目” → 在 01 选择所属分类，填项目名称、账户 ID、公开抖音号 → 在 02 勾选启用 → 预检草稿 → 明确保存配置。一个项目对应一个账户；所属分类只负责把项目放到左侧对应的组里。</p>
      <p>“草稿”指尚未保存的修改。“预检”只检查填写格式；账户 ID 和公开抖音号按填写内容保存，不要求人工查询核对，请自行确保没有填错。程序不会在预检时查询抖音账号或采集数据。</p>
      <p>保存不会立即采集，也不会补齐历史数据。启用并保存后项目会出现在左侧；没有数据时仍需采集或导入历史数据。定时任务是否启用以服务器部署回执为准。</p>
      <p>停用只隐藏项目并停止把它列入后续采集计划，不删除已有数据。已保存的项目 ID 和账户 ID 不能直接修改，避免把不同账户的数据混在一起。</p>
      <p class="configuration-attention">切换运维分区保留本页未保存草稿；重新读取配置、刷新或关闭浏览器会丢失未保存的修改。保存过程中请等待结果；如果提示结果不确定，先重新读取核对，不要反复点击保存。</p>
      <p>技术信息（一般不用操作）：指标目录 <strong>material-33-v1</strong>。云端业务配置、历史上传和文件存储仅供运维账号使用；普通账号可查看大屏和下载项目数据。本地开发模式仍只供本机使用。</p>
    </details>
    <p v-if="busy" class="configuration-attention" role="status">{{ busy === 'loading' ? '正在读取配置，期间不可保存。' : '正在检查或保存配置，请等待明确结果，请勿重复提交。' }}</p>
    <p v-if="error" :class="modeUnavailable ? 'module-note' : 'form-error'" role="alert">{{ error }}</p>
    <p v-if="notice" class="query-summary" role="status">{{ notice }}</p>
    <div v-if="!snapshot && !busy" class="configuration-unavailable-modules">
      <section v-for="(label, index) in (metricsOnly ? ['采集指标配置'] : ['采集账户配置', '大屏项目账户配置'])" :key="label" class="configuration-module">
        <h4><span>0{{ index + 1 }}</span>{{ label }}</h4>
        <p>当前未读取到配置，不能展示或修改。请由维护人员检查本地配置模式后重新读取；不影响已入库数据的查看。</p>
      </section>
    </div>
    <template v-if="snapshot">
      <p class="module-note">{{ requiresReload ? '上次已读版本（当前未核验，禁止保存）' : '当前已读版本' }}：<code class="configuration-revision">{{ snapshot.revision }}</code></p>
      <p v-if="!snapshot.writeEnabled" class="module-note">当前只允许查看配置，输入框和保存按钮不可操作。需要修改时请联系维护人员开启本机配置编辑；已有数据仍可查看。</p>
      <section v-if="metricsOnly" class="configuration-module" aria-labelledby="collection-metrics-title">
        <div class="configuration-metrics-header">
          <h4 id="collection-metrics-title">采集指标选择</h4>
          <button type="button" class="configuration-metrics-toggle" :aria-expanded="metricsExpanded" aria-controls="collection-metrics-content" @click="metricsExpanded = !metricsExpanded">{{ metricsExpanded ? '收起指标' : '展开指标' }}</button>
        </div>
        <p class="configuration-metrics-summary">{{ snapshot.metricChangesEnabled ? `已选 ${selectedMetrics.length} / ${snapshot.metricCatalog?.length ?? 0} 项` : '当前使用固定指标' }} · 所有启用账户共用。一般保持默认即可；收起不会丢失尚未保存的选择。</p>
        <div id="collection-metrics-content" v-show="metricsExpanded">
        <p>这里选择以后采集哪些指标，不会改变已有数据。重新启用不会补齐历史，之前没有采到的值仍显示“数据未知”，不会按 0 计算。</p>
      <fieldset v-if="snapshot.metricChangesEnabled" class="configuration-metrics" :disabled="!canEdit">
        <legend>已选 {{ selectedMetrics.length }} / {{ snapshot.metricCatalog?.length }} 项</legend>
        <p>仅可选择已知指标，不支持自定义未知指标或编辑公式。看板计算必需的指标保持启用。</p>
        <label v-for="metric in snapshot.metricCatalog" :key="metric.id" class="configuration-metric">
          <input v-model="selectedMetrics" type="checkbox" :value="metric.id" :aria-label="`采集指标 ${metric.label}`" :data-metric-id="metric.id" :disabled="!canEdit || metric.required" />
          {{ metric.label }}{{ metric.required ? '（看板必需）' : '' }} <code>{{ metric.id }}</code>
        </label>
      </fieldset>
      <p v-else class="module-note">当前配置使用固定指标目录，指标增减未开启。</p>
        </div>
      </section>
      <section v-if="!metricsOnly" class="configuration-module" aria-labelledby="collection-accounts-title">
        <h4 id="collection-accounts-title"><span>01</span>采集账户配置</h4>
        <div class="collection-theme-warning" role="note"><span aria-hidden="true">⚠</span><div><strong>北京时间每日 08:00—12:00，禁止更改采集主题</strong><p>保存后立即生效；采集任务尚未结束时，需等待任务结束后再修改。只执行所选主题，不自动切换，保存不触发采集。</p></div></div>
        <p v-if="!snapshot.themeChangesEnabled" class="module-note">当前服务尚未启用主题编辑，现有账户继续使用全域。</p>
        <p>先选所属分类，再填写账户。分类只在这里设置，02 和左侧列表自动跟随；新项目默认“其他”。修改分类并保存后仅移动列表位置，不改变账户绑定、已有数据或采集规则。所有分类使用同一套采集与看板模板，不做分类合计。</p>
        <p>填写项目名称、千川账户 ID 和公开抖音号即可。项目名称可以用“吹风机”这样的业务名称；账户 ID 是千川后台的账户编号；公开抖音号是抖音主页显示的“抖音号”，不是昵称，也不是主页链接。无需填写内部编号或抖音昵称。</p>
        <div class="table-shell" tabindex="0" aria-label="采集账户配置草稿表">
          <table class="business-table configuration-table">
            <thead><tr><th>所属分类</th><th>项目名称</th><th>账户 ID</th><th>公开抖音号</th><th>采集主题</th><th>主题操作</th><th>联动状态</th></tr></thead>
            <tbody>
              <tr v-for="(project, index) in draft" v-show="!project.deleted" :key="index">
                <td><select v-model="project.category" :aria-label="`项目 ${index + 1} 所属分类`" :disabled="!canEdit"><option v-for="category in PROJECT_CATEGORIES" :key="category.id" :value="category.id">{{ category.label }}</option></select></td>
                <td><input v-model="project.name" :aria-label="`采集项目 ${index + 1} 名称`" :disabled="!canEdit" maxlength="80" autocomplete="off" /></td>
                <td><input :value="project.advertiserId" @input="changeIdentity(project, 'advertiserId', $event)" :aria-label="`项目 ${index + 1} 账户 ID`" :disabled="!canEdit || (isSaved(project) && (!snapshot.themeChangesEnabled || themeLocked(project)))" maxlength="30" inputmode="numeric" autocomplete="off" /></td>
                <td><input :value="project.publicDouyinId" @input="changeIdentity(project, 'publicDouyinId', $event)" :aria-label="`项目 ${index + 1} 公开抖音号`" :disabled="!canEdit" maxlength="64" autocomplete="off" placeholder="填写公开抖音号" /><small v-if="!project.publicDouyinId && index < snapshot.projects.length">公开抖音号待补充；保留原绑定</small></td>
                <td><select v-if="themeEditing === project.id || !isSaved(project)" :value="isSaved(project) ? themeChoice : (project.collectionTheme ?? 'QUANYU')" :aria-label="`项目 ${index + 1} 采集主题`" :disabled="themeLocked(project)" @change="isSaved(project) ? themeChoice = ($event.target as HTMLSelectElement).value as CollectionTheme : project.collectionTheme = ($event.target as HTMLSelectElement).value as CollectionTheme"><option value="QUANYU">全域</option><option value="CHENGFANG">乘方</option></select><span v-else class="collection-theme-label">{{ themeLabel(project.collectionTheme ?? 'QUANYU') }}</span><small v-if="snapshot.collectingProjectIds?.includes(project.id)">采集中，禁止修改</small></td>
                <td class="collection-theme-actions"><template v-if="isSaved(project)"><template v-if="themeEditing === project.id"><button type="button" :disabled="themeLocked(project) || themeChoice === (project.collectionTheme ?? 'QUANYU')" @click="saveTheme(project)">保存</button><button type="button" :disabled="!!busy" @click="themeEditing = ''">取消</button></template><button v-else type="button" :disabled="themeLocked(project) || !!themeEditing" @click="editTheme(project)">编辑</button></template><small v-else>随新项目一起保存</small></td>
                <td>{{ project.enabled ? '启用草稿' : '停用草稿' }}<small>在下方项目配置统一修改</small></td>
              </tr>
              <tr v-if="!draft.some(project => !project.deleted)"><td colspan="7">暂无账户；使用下方“新增草稿项目”建立账户与项目绑定。</td></tr>
            </tbody>
          </table>
        </div>
      </section>
      <section v-if="!metricsOnly" class="configuration-module" aria-labelledby="dashboard-accounts-title">
        <h4 id="dashboard-accounts-title"><span>02</span>大屏项目账户配置</h4>
        <p>显示名称就是左侧列表里的名称，与 01 的项目名称同步。当前启用开关同时控制大屏展示和后续采集计划；勾选并保存后出现在左侧，取消勾选并保存后隐藏。启用不代表已经开始采集，停用不删除历史数据。</p>
      <div class="table-shell" tabindex="0" aria-label="项目配置草稿表">
        <table class="business-table configuration-table">
          <thead><tr><th>项目 ID（稳定标识）</th><th>显示名称</th><th>所属分类（跟随 01）</th><th>绑定账户 ID</th><th>采集与展示一起启用</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(project, index) in draft" v-show="!project.deleted" :key="index" :data-config-row="index">
              <td><input v-model="project.id" :aria-label="`项目 ${index + 1} ID`" :disabled="!canEdit || index < snapshot.projects.length" maxlength="40" autocomplete="off" spellcheck="false" /></td>
              <td><input v-model="project.name" :aria-label="`项目 ${index + 1} 名称`" :disabled="!canEdit" maxlength="80" autocomplete="off" /></td>
              <td class="configuration-category-label">{{ projectCategoryLabel(project.category) }}</td>
              <td class="configuration-account-binding">{{ project.advertiserId || '请在采集账户配置填写' }}</td>
              <td><input v-model="project.enabled" type="checkbox" :aria-label="`项目 ${index + 1} 启用`" :disabled="!canEdit" /></td>
              <td><button type="button" class="configuration-delete" :aria-label="`删除项目 ${index + 1}`" :disabled="!canEdit" @click="removeProject(project)">{{ isSaved(project) ? '删除项目' : '移除草稿' }}</button></td>
            </tr>
            <tr v-if="!draft.some(project => !project.deleted)"><td colspan="6">当前没有已配置项目，可新增草稿；不会自动启用账户。</td></tr>
          </tbody>
        </table>
      </div>
      <p class="module-note">项目 ID 是程序用来区分项目的编号，已自动生成，一般不用改。新项目默认停用，勾选启用并保存后自动显示在工作台卫星和项目列表中。删除已保存项目需再次预检并保存才生效，只移出使用列表，不删除历史数据；未保存的新草稿会直接移除。</p>
      <div v-if="draft.some(project => project.deleted)" class="configuration-deleted">
        <strong>已删除项目（可恢复）</strong>
        <p v-for="project in draft.filter(item => item.deleted)" :key="project.id">{{ project.name }} <button type="button" :aria-label="`恢复项目 ${project.name}`" :disabled="!canEdit" @click="restoreProject(project)">恢复为停用</button></p>
      </div>
      </section>
      <div class="configuration-actions">
        <button v-if="!metricsOnly" type="button" class="primary-button configuration-add" :disabled="!canEdit || draft.length >= PROJECT_LIMIT" @click="addDraft">新增草稿项目</button>
        <button type="button" class="primary-button configuration-check" :disabled="!canEdit || busy === 'checking'" @click="checkDraft">{{ busy === 'checking' ? '正在预检…' : '预检草稿' }}</button>
        <button type="button" class="primary-button configuration-save" :disabled="!canEdit || !!busy || !preflight" @click="save">{{ busy === 'saving' ? '保存并核对中…' : '明确保存配置' }}</button>
      </div>
      <p v-if="metricsOnly" class="module-note">预检后再次修改指标，需要重新预检。切换运维分区会保留未保存的选择；重新读取、刷新或关闭页面会丢失草稿。其他分区保存后，请重新读取最新配置再编辑。</p>
      <p v-else class="module-note">以上两个模块一起检查、一起保存。预检通过后如果又改了内容，需要重新预检，保存按钮才会可用。最多 32 个项目；16 款自然卫星按项目顺序循环分配，不限制项目容量。项目名额包含已删除但保留供恢复的项目；删除不会腾出新增名额。恢复已删除项目后默认停用，如需重新显示，请勾选启用后预检并保存。</p>
    </template>
  </section>
</template>

<style scoped>
.collection-theme-warning { display:flex; gap:14px; align-items:center; padding:16px; margin:16px 0; background:#fff4d9; border:1px solid #c38b29; border-left:5px solid #c38b29; border-radius:9px; color:#775012; }
.collection-theme-warning > span { font-size:30px; }
.collection-theme-warning p { margin:6px 0 0; color:inherit; }
.collection-theme-actions { min-width:130px; white-space:nowrap; }.collection-theme-actions button { padding:7px 12px; border:1px solid #8c835f; border-radius:6px; background:transparent; color:inherit; cursor:pointer; }.collection-theme-actions button+button { margin-left:7px; }.collection-theme-actions button:disabled { opacity:.4; cursor:not-allowed; }.collection-theme-label { font-weight:600; white-space:nowrap; }
.configuration-module { margin-top: 1.5rem; padding: 1.25rem; border: 1px solid #dce5e9; border-radius: 14px; background: #fff; }
.configuration-module h4 { display: flex; align-items: center; gap: .7rem; margin: 0 0 .7rem; font-size: 1.05rem; }
.configuration-module h4 span { color: #46716e; font-size: .8rem; letter-spacing: .06em; }
.configuration-module p, .configuration-schedule p { color: #596b77; font-size: .85rem; line-height: 1.7; }
.configuration-module small { display: block; font-size: .75rem; color: #687b85; margin-top: .3rem; }
.configuration-guide summary { cursor: pointer; font-weight: 600; }
.configuration-schedule { padding: 1rem 1.25rem; border: 1px solid #dce5e9; background: #f5f8fa; border-radius: 12px; margin: 1rem 0; }
.configuration-schedule strong { font-size: .9rem; }
.configuration-account-binding { overflow-wrap: anywhere; }
.configuration-metrics { border: 0; padding: .5rem 0; }
.configuration-metric { padding: .5rem; }
.configuration-metrics-header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
.configuration-metrics-header h4 { margin: 0; }
.configuration-metrics-toggle { min-height: 44px; padding: .55rem .9rem; border: 1px solid #b8d2dc; border-radius: 8px; color: #206280; background: #f4fafc; cursor: pointer; }
.configuration-metrics-toggle:hover { background: #e9f4f7; }
.configuration-metrics-toggle:focus-visible { outline: 3px solid #167d92; outline-offset: 3px; }
</style>
