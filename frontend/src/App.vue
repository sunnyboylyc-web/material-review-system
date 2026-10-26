<script setup lang="ts">
import { applicationPath } from './domain/applicationPath'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import MetricValue from './components/MetricValue.vue'
import UiIcon from './components/UiIcon.vue'
import ConstellationGlyph from './components/ConstellationGlyph.vue'
import WorkspaceHome from './components/WorkspaceHome.vue'
import MaterialGallery from './components/MaterialGallery.vue'
import MaterialDrawer from './components/MaterialDrawer.vue'
import type { SessionIdentity } from './domain/session'
import OperationsPanel from './components/OperationsPanel.vue'
import ConfigurationPanel from './components/ConfigurationPanel.vue'
import AdminAccountsPanel from './components/AdminAccountsPanel.vue'
import HistoryUploadPanel from './components/HistoryUploadPanel.vue'
import CollectedExportPanel from './components/CollectedExportPanel.vue'
import CalculationSchemaViewer from './components/CalculationSchemaViewer.vue'
import FileStoragePanel from './components/FileStoragePanel.vue'
import DatabaseBackupPanel from './components/DatabaseBackupPanel.vue'
import CollectionOperationsPanel from './components/CollectionOperationsPanel.vue'
import ReturnToTop from './components/ReturnToTop.vue'
import UsageGuide from './components/UsageGuide.vue'
import type { HistoryUploadAdapter } from './domain/historyUpload'
import type { CollectedExportAdapter } from './domain/collectedExport'
import SpendCurve from './components/SpendCurve.vue'
import MaterialScatter from './components/MaterialScatter.vue'
import { reviewSignal } from './domain/reviewSignal'
import PeriodReview from './components/PeriodReview.vue'
import DateRangePicker from './components/DateRangePicker.vue'
import type { ConfigurationAdapter } from './domain/configuration'
import type { OperationsDataAdapter } from './adapters/operationsAdapter'
import type { DashboardDataAdapter } from './adapters/dashboardAdapter'
import { httpDashboardAdapter } from './adapters/httpDashboardAdapter'
import { MAX_DISPLAY_ROWS } from './domain/dashboard'
import { PROJECT_CATEGORIES, type ProjectCategory } from './domain/projectCategory'
import type { MaterialTypeQuery, MaterialTypeReview, MaterialTypeSelection, ProjectModule, ReviewQuery, ReviewResult, TopResult } from './domain/dashboard'
import { formatCreationDate, formatMaterialLabel } from './domain/formatting'
import { defaultDateSelection, shiftIsoDate, shanghaiIsoDate } from './time/shanghai'
import { httpPeriodPreferences, type PeriodPreferences } from './adapters/periodPreferences'
import { httpPeriodSnapshots, type PeriodSnapshots } from './adapters/periodSnapshots'

const props = defineProps<{
  session?: SessionIdentity
  adapter?: DashboardDataAdapter
  overviewAdapter?: Pick<DashboardDataAdapter, 'fetchReview'>
  operationsAdapter?: OperationsDataAdapter
  configurationAdapter?: ConfigurationAdapter
  historyUploadAdapter?: HistoryUploadAdapter
  historyReadOnly?: boolean
  localReadOnlyPreview?: boolean
  collectedExportAdapter?: CollectedExportAdapter
  periodPreferences?: PeriodPreferences
  periodSnapshots?: PeriodSnapshots
}>()
const canOperate = computed(() => !props.session || props.session.operations)
const recoveryReadOnly = computed(() => props.session?.recoveryReadOnly === true)

const adapter: DashboardDataAdapter = props.adapter ?? httpDashboardAdapter
const isSynthetic = computed(() => adapter.evidenceKind === 'SYNTHETIC'
  || reviewResult.value?.evidenceKind === 'SYNTHETIC' || topResult.value?.evidenceKind === 'SYNTHETIC')
const privateOperationsPreview = computed(() => isSynthetic.value || props.localReadOnlyPreview === true || !props.session)
const defaults = defaultDateSelection()
const projects = ref<ProjectModule[]>([])
const materialTypeSelections = ref<Record<string, MaterialTypeSelection>>({})
const categoryExpanded = ref<Record<ProjectCategory, boolean>>({ LARGE_APPLIANCE: true, SMALL_APPLIANCE: true, OTHER: true })
const categoryGroups = computed(() => PROJECT_CATEGORIES.map(category => ({ ...category,
  projects: projects.value.filter(project => (project.category ?? 'OTHER') === category.id),
})))
const selectedProjectId = ref('')
const activeView = ref<'dashboard' | 'operations'>('dashboard')
const activeTab = ref<'top' | 'review'>('top')
const workspaceHome = ref(true)
const projectDialog = ref<HTMLDialogElement>()
const projectPickerOpen = ref(false)
const operationsSection = ref('configuration')
const knownConfigurationRevision = ref('')
const downloadDialog = ref<HTMLDialogElement>()
const downloadOpen = ref(false)
const downloadBusy = ref(false)
const downloadTrigger = ref<HTMLButtonElement>()
const downloadCloseButton = ref<HTMLButtonElement>()
let downloadReturnFocus: HTMLElement | null = null
const materialView = ref<'table' | 'cards'>('table')
const materialSearch = ref('')
const materialDrawerOpen = ref(false)
function openMaterialDrawer(id:string) { selectMaterial(id); materialDrawerOpen.value=true }
const filteredTopRows = computed(() => (topResult.value?.rows ?? []).filter(row => !materialSearch.value.trim() || `${row.materialName ?? ''} ${row.materialId}`.toLowerCase().includes(materialSearch.value.trim().toLowerCase())))
const operationItems = computed(() => canOperate.value ? [
  { id:'configuration', label:'项目配置', icon:'settings', en:'CONFIGURATION' },
  { id:'status', label:'采集状态', icon:'pulse', en:'COLLECTION STATUS' },
  { id:'history', label:'历史数据', icon:'folder', en:'HISTORY DATA' },
  { id:'files', label:'文件存储', icon:'folder', en:'FILE STORAGE' },
  ...(props.session?.administrator ? [{ id:'accounts', label:'账号管理', icon:'user', en:'ACCOUNTS' }] : []),
] : [])
let projectNavigationVersion = 0
function openWorkspace(page: string): void {
  projectNavigationVersion += 1
  projectDialog.value?.close?.()
  projectPickerOpen.value = false
  if (page === 'downloads') { void openDownloads(); return }
  if (page === 'home') { workspaceHome.value = true; activeView.value = 'dashboard'; void nextTick(() => { document.documentElement.scrollTop = 0 }); return }
  workspaceHome.value = false
  if (page === 'top' || page === 'review') { activeView.value = 'dashboard'; activeTab.value = page }
  else if (canOperate.value) { operationsOpened.value = true; activeView.value = 'operations'; operationsSection.value = operationItems.value.some(item => item.id === page) ? page : operationItems.value[0].id }
  void nextTick(() => { document.documentElement.scrollTop = 0 })
}
async function chooseProject(id: string) {
  materialDrawerOpen.value=false
  await selectProject(id)
  materialSearch.value = ''; projectDialog.value?.close?.(); projectPickerOpen.value = false
}
async function enterProject(id: string, page: 'top' | 'review' = 'top') {
  const navigationVersion = ++projectNavigationVersion
  await chooseProject(id)
  if (navigationVersion === projectNavigationVersion && selectedProjectId.value === id) openWorkspace(page)
}
async function selectHomeProject(id: string) {
  const navigationVersion = ++projectNavigationVersion
  await chooseProject(id)
  await nextTick()
  if (navigationVersion !== projectNavigationVersion || !workspaceHome.value || selectedProjectId.value !== id) return
  const overview = document.getElementById('workspace-snapshot')
  overview?.scrollIntoView?.({ behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' })
  document.getElementById('home-overview-title')?.focus({ preventScroll: true })
}
async function chooseExportProject(id: string) { await chooseProject(id); if(selectedProjectId.value===id) activeTab.value='top' }
async function openDownloads(event?: Event) {
  if(recoveryReadOnly.value || downloadOpen.value) return
  downloadReturnFocus = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement instanceof HTMLElement ? document.activeElement : null
  projectDialog.value?.close?.(); workspaceHome.value=false; activeView.value='dashboard'; activeTab.value='top'
  await nextTick()
  if(!downloadDialog.value) return
  downloadOpen.value=true
  await nextTick()
  downloadDialog.value?.showModal?.()
  downloadCloseButton.value?.focus()
}
function downloadsClosed() {
  if (!downloadOpen.value) return
  downloadOpen.value = false
  const target = downloadReturnFocus?.isConnected ? downloadReturnFocus : downloadTrigger.value
  downloadReturnFocus = null
  target?.focus()
}
function closeDownloads() {
  if (downloadBusy.value) return
  downloadDialog.value?.close?.()
  downloadsClosed()
}
function setDownloadBusy(value: boolean) {
  downloadBusy.value = value
  if (value) void nextTick(() => { if (downloadBusy.value && downloadOpen.value) downloadDialog.value?.focus() })
}
function trapDownloadsFocus(event: KeyboardEvent) {
  const dialog = downloadDialog.value
  if (event.key !== 'Tab' || !dialog) return
  const controls = [...dialog.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href], summary')]
  const first = controls[0], last = controls[controls.length - 1]
  if (!first) { event.preventDefault(); dialog.focus(); return }
  if (document.activeElement === dialog || (event.shiftKey && document.activeElement === first)) {
    event.preventDefault(); (event.shiftKey ? last : first)?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}
function openProjects() { projectPickerOpen.value = true; projectDialog.value?.showModal?.() }

const selectedMaterialId = ref('')
const expandedMaterialId = ref('')
const selectedMaterial = computed(() => ['READY','PARTIAL'].includes(topResult.value?.rankingState ?? '')
  ? topResult.value?.rows.find(row => row.materialId === selectedMaterialId.value) ?? null : null)
function selectMaterial(id: string): void { selectedMaterialId.value = id }
function toggleMaterial(id: string): void {
  selectedMaterialId.value = id
  expandedMaterialId.value = expandedMaterialId.value === id ? '' : id
}
const operationsOpened = ref(false)
const reviewDraft = ref<ReviewQuery>(structuredClone(defaults.review))
const committedReview = ref<ReviewQuery>(structuredClone(defaults.review))
const reviewTopComparisonLimit = ref(20)
const topDateDraft = ref(defaults.topDate)
const committedTopDate = ref(defaults.topDate)
const topDisplayLimit = ref(10)
const topLimitOptions = computed(() => [...new Set([10, 20, 30, 40, 50, topDisplayLimit.value])].sort((a, b) => a - b))
const reviewResult = ref<ReviewResult | null>(null)
const topResult = ref<TopResult | null>(null)
const projectLoading = ref(false)
const projectError = ref('')
const reviewLoading = ref(false)
const topLoading = ref(false)
const reviewError = ref('')
const topError = ref('')
const preferenceError = ref('')
const preferenceLoading = ref(false)
const preferences = recoveryReadOnly.value ? undefined : props.periodPreferences ?? (props.session && adapter.evidenceKind !== 'SYNTHETIC' ? httpPeriodPreferences : undefined)
const periodSnapshots = recoveryReadOnly.value ? undefined : props.periodSnapshots ?? (props.session && adapter.evidenceKind !== 'SYNTHETIC' ? httpPeriodSnapshots : undefined)
const snapshotLoading = ref(false)
const snapshotMessage = ref('')
const snapshotSavedAt = ref<string | null>(null)
const snapshotLoaded = new Set<string>()
let snapshotVersion = 0
const projectStates = new Map<string, ReturnType<typeof snapshotState>>()
const preferenceLoaded = new Set<string>()
let preferenceVersion = 0
const topPending = computed(() => !!topResult.value && (topResult.value.selectedDate !== topDateDraft.value || topResult.value.displayLimit !== topDisplayLimit.value))
const reviewPending = computed(() => !!reviewResult.value && !reviewResultMatches(reviewResult.value, selectedProjectId.value, reviewDraft.value, reviewTopComparisonLimit.value))
function snapshotState() {
  return { reviewDraft: cloneReviewQuery(reviewDraft.value), committedReview: cloneReviewQuery(committedReview.value),
    topDate: topDateDraft.value, committedTopDate: committedTopDate.value, topLimit: topDisplayLimit.value,
    reviewLimit: reviewTopComparisonLimit.value, review: reviewResult.value, top: topResult.value,
    tab: activeTab.value, selected: selectedMaterialId.value, expanded: expandedMaterialId.value,
    freshness: freshnessText.value, reviewError: reviewError.value, topError: topError.value, preferenceError: preferenceError.value,
    snapshotMessage: snapshotMessage.value, snapshotSavedAt: snapshotSavedAt.value }
}
function restoreState(id: string) {
  const saved = projectStates.get(id)
  const initial = defaultDateSelection()
  reviewDraft.value = cloneReviewQuery(saved?.reviewDraft ?? initial.review)
  committedReview.value = cloneReviewQuery(saved?.committedReview ?? initial.review)
  topDateDraft.value = saved?.topDate ?? initial.topDate
  committedTopDate.value = saved?.committedTopDate ?? initial.topDate
  topDisplayLimit.value = saved?.topLimit ?? 10
  reviewTopComparisonLimit.value = saved?.reviewLimit ?? 20
  reviewResult.value = saved?.review ?? null
  topResult.value = saved?.top ?? null
  activeTab.value = saved?.tab ?? 'top'
  selectedMaterialId.value = saved?.selected ?? ''
  expandedMaterialId.value = saved?.expanded ?? ''
  freshnessText.value = saved?.freshness ?? '最新数据未知'
  reviewError.value = saved?.reviewError ?? ''
  topError.value = saved?.topError ?? ''
  preferenceError.value = saved?.preferenceError ?? ''
  snapshotMessage.value = saved?.snapshotMessage ?? ''
  snapshotSavedAt.value = saved?.snapshotSavedAt ?? null
  snapshotLoading.value = false
  reviewLoading.value = false
  topLoading.value = false
}
async function restorePeriodSnapshot(id: string) {
  const version = ++snapshotVersion
  const requestVersion = reviewRequestVersion
  if (!periodSnapshots || snapshotLoaded.has(id)) return
  snapshotLoading.value = true
  try {
    const saved = await periodSnapshots.load(id)
    if (version !== snapshotVersion || selectedProjectId.value !== id || requestVersion !== reviewRequestVersion) return
    snapshotLoaded.add(id)
    if (!saved) return
    if (saved.result.projectId !== id) throw new Error('保存结果的项目不一致，未展示结果。')
    reviewResult.value = saved.result
    reviewTopComparisonLimit.value = saved.result.topComparisonDisplayLimit
    snapshotSavedAt.value = saved.savedAt
    snapshotMessage.value = ''
  } catch (error) {
    if (version === snapshotVersion && selectedProjectId.value === id && requestVersion === reviewRequestVersion)
      snapshotMessage.value = error instanceof Error ? error.message : '上次保存的周期结果暂不可用，请刷新数据。'
  } finally { if (version === snapshotVersion) snapshotLoading.value = false }
}
async function restorePreference(id: string) {
  const version = ++preferenceVersion
  preferenceLoading.value = false
  if (!preferences || preferenceLoaded.has(id)) return
  preferenceLoading.value = true
  try {
    const saved = await preferences.load(id)
    if (version !== preferenceVersion || selectedProjectId.value !== id) return
    if (saved) reviewDraft.value = cloneReviewQuery(saved)
    preferenceLoaded.add(id)
  } catch (error) {
    if (version === preferenceVersion) preferenceError.value = error instanceof Error ? error.message : '周期日期偏好读取失败。'
  } finally { if (version === preferenceVersion) preferenceLoading.value = false }
}
// Serialize preference writes so a slower old selection cannot overwrite the latest one.
let preferenceWrites = Promise.resolve()
function persistReviewDates() {
  if (!preferences || preferenceLoading.value) return
  const id = selectedProjectId.value
  const query = cloneReviewQuery(reviewDraft.value)
  if (validateRange('本期', query.current.start, query.current.end) || validateRange('上期', query.previous.start, query.previous.end)) return
  preferenceWrites = preferenceWrites.then(async () => {
    try { await preferences.save(id, query); if (selectedProjectId.value === id) preferenceError.value = '' }
    catch (error) { if (selectedProjectId.value === id) preferenceError.value = error instanceof Error ? error.message : '周期日期偏好未保存。' }
  })
}
let reviewRequestVersion = 0
let topRequestVersion = 0
let projectRequestVersion = 0
let unsubscribeProjects: (() => void) | undefined
let observedShanghaiDay = shanghaiIsoDate()
let rolloverTimer: ReturnType<typeof setInterval> | undefined
function checkDayRollover(): void {
  const today = shanghaiIsoDate()
  if (today === observedShanghaiDay) return
  observedShanghaiDay = today
  topDateDraft.value = shiftIsoDate(today, -1)
  for (const state of projectStates.values()) state.topDate = topDateDraft.value
}
const periodSignal = computed(() => reviewSignal(reviewResult.value, reviewLoading.value))
const topSignal = computed(() => topLoading.value || topError.value || !topResult.value ? 'loading' : topResult.value.coverage.state === 'COMPLETE' ? 'green' : 'red')
const freshnessText = ref('最新数据未知')
let freshnessRequestVersion = 0
async function loadFreshness(): Promise<void> {
  const projectId = selectedProjectId.value
  const version = ++freshnessRequestVersion
  freshnessText.value = '最新数据未知'
  if (!projectId) return
  try {
    const data = await adapter.fetchFreshness(projectId)
    if (version !== freshnessRequestVersion || selectedProjectId.value !== projectId) return
    if (data.projectId !== projectId) return
    freshnessText.value = adapter.evidenceKind === 'SYNTHETIC' ? '合成演示 · 非真实采集日期'
      : data.latestMcpStatDate ? `最新数据${data.latestMcpStatDate.replaceAll('-', '.')}日` : '暂无MCP数据'
  } catch { /* Retain UNKNOWN, never reuse the prior project's date. */ }
}

const selectedProject = computed(() =>
  projects.value.find((project) => project.id === selectedProjectId.value),
)

function toggleOperations(): void {
  if (!canOperate.value) { void openDownloads(); return }
  workspaceHome.value = false
  projectDialog.value?.close?.()
  operationsSection.value = 'status'
  if (activeView.value === 'dashboard') {
    operationsOpened.value = true
    activeView.value = 'operations'
  } else activeView.value = 'dashboard'
}

const topEmptyMessage = computed(() => topResult.value?.rankingState === 'UNAVAILABLE'
  ? '排名日缺失，无法生成真实名次，请报告运维补齐。'
  : topResult.value?.rankingState === 'UNKNOWN_INPUT' ? '排名所需消耗字段未知，无法确定名次。' : topResult.value?.rankingState === 'PARTIAL' ? '消耗数据不全，无法确定完整榜单。' : '所选完整排名日没有正消耗素材。')

function cloneReviewQuery(query: ReviewQuery): ReviewQuery {
  return {
    current: { ...query.current },
    previous: { ...query.previous },
  }
}

function reviewQueriesMatch(left: ReviewQuery, right: ReviewQuery): boolean {
  return (
    left.current.start === right.current.start &&
    left.current.end === right.current.end &&
    left.previous.start === right.previous.start &&
    left.previous.end === right.previous.end
  )
}

function reviewResultMatches(
  result: ReviewResult,
  projectId: string,
  query: ReviewQuery,
  topComparisonDisplayLimit: number,
): boolean {
  const current = result.periods.find((period) => period.key === 'CURRENT')
  const previous = result.periods.find((period) => period.key === 'PREVIOUS')
  return (
    result.projectId === projectId &&
    result.topComparisonDisplayLimit === topComparisonDisplayLimit &&
    current?.range.start === query.current.start &&
    current.range.end === query.current.end &&
    previous?.range.start === query.previous.start &&
    previous.range.end === query.previous.end
  )
}

function isCurrentReviewRequest(
  requestVersion: number,
  projectId: string,
  query: ReviewQuery,
  topComparisonDisplayLimit: number,
): boolean {
  return (
    requestVersion === reviewRequestVersion &&
    selectedProjectId.value === projectId &&
    reviewQueriesMatch(committedReview.value, query)
  )
}

function isCurrentTopRequest(
  requestVersion: number,
  projectId: string,
  selectedDate: string,
  displayLimit: number,
): boolean {
  return (
    requestVersion === topRequestVersion &&
    selectedProjectId.value === projectId &&
    committedTopDate.value === selectedDate
  )
}

function validateRange(label: string, start: string, end: string): string {
  if (!start || !end) return `${label}起止日期不能为空。`
  if (start > end) return `${label}开始日期不能晚于结束日期。`
  const days = (Date.parse(end) - Date.parse(start)) / 86400000 + 1
  if (!Number.isFinite(days) || days > 3660) return `${label}日期范围须有效且不超过 3660 天。`
  return ''
}

async function loadMaterialTypes(query: MaterialTypeQuery): Promise<MaterialTypeReview> {
  const projectId = selectedProjectId.value
  if (!adapter.fetchMaterialTypes) throw new Error('当前数据入口暂不支持独立素材类型查询。')
  const result = await adapter.fetchMaterialTypes(projectId, query)
  if (projectId !== selectedProjectId.value) throw new Error('项目已切换，请重新刷新素材类型数据。')
  return result
}

async function loadReviewOverallMaterials(limit: number): Promise<ReviewResult['topMaterials']> {
  const source = reviewResult.value, projectId = selectedProjectId.value
  if (!source || !Number.isInteger(limit) || limit < 1 || limit > MAX_DISPLAY_ROWS) throw new Error('请选择有效的明细显示数量。')
  const query = {
    current: { ...source.periods.find(period => period.key === 'CURRENT')!.range },
    previous: { ...source.periods.find(period => period.key === 'PREVIOUS')!.range },
  }
  const result = await adapter.fetchReview(projectId, query, limit)
  if (selectedProjectId.value !== projectId || reviewResult.value !== source) throw new Error('项目或周期已变化，请重新打开素材明细。')
  if (!reviewResultMatches(result, projectId, query, limit) || result.snapshotFingerprint !== source.snapshotFingerprint) {
    throw new Error('数据版本或查询条件已变化，请先刷新周期数据后再查看更多素材。')
  }
  return result.topMaterials
}

async function loadReview(): Promise<void> {
  void loadFreshness()
  const projectId = selectedProjectId.value
  if (!projectId) {
    reviewResult.value = null
    return
  }
  const query = cloneReviewQuery(committedReview.value)
  const topComparisonDisplayLimit = reviewTopComparisonLimit.value
  const requestVersion = ++reviewRequestVersion
  reviewResult.value = null
  snapshotVersion += 1
  snapshotLoading.value = false
  snapshotSavedAt.value = null
  snapshotMessage.value = ''
  reviewLoading.value = true
  reviewError.value = ''
  try {
    const saved = periodSnapshots ? await periodSnapshots.refresh(projectId, query, topComparisonDisplayLimit) : null
    const result = saved ? saved.result : await adapter.fetchReview(projectId, query, topComparisonDisplayLimit)
    if (!isCurrentReviewRequest(requestVersion, projectId, query, topComparisonDisplayLimit)) return
    if (!reviewResultMatches(result, projectId, query, topComparisonDisplayLimit)) {
      reviewError.value = '周期复盘返回的项目、日期范围或榜单数量与当前查询不一致。'
      return
    }
    reviewResult.value = result
    if (saved) {
      snapshotLoaded.add(projectId)
      snapshotSavedAt.value = saved.persisted ? saved.savedAt : null
      snapshotMessage.value = saved.persisted ? '' : saved.warning ?? '本次计算已完成，但结果未保存，请稍后重试。'
    }
  } catch (error) {
    if (isCurrentReviewRequest(requestVersion, projectId, query, topComparisonDisplayLimit)) {
      reviewError.value = error instanceof Error ? error.message : '周期复盘请求失败。'
    }
  } finally {
    if (requestVersion === reviewRequestVersion) reviewLoading.value = false
  }
}

async function loadTop(): Promise<void> {
  materialDrawerOpen.value = false
  void loadFreshness()
  selectedMaterialId.value = ''
  expandedMaterialId.value = ''
  const projectId = selectedProjectId.value
  if (!projectId) {
    topResult.value = null
    return
  }
  const selectedDate = committedTopDate.value
  const displayLimit = topDisplayLimit.value
  const requestVersion = ++topRequestVersion
  topResult.value = null
  topLoading.value = true
  topError.value = ''
  try {
    const result = await adapter.fetchTop(projectId, selectedDate, displayLimit)
    if (!isCurrentTopRequest(requestVersion, projectId, selectedDate, displayLimit)) return
    if (
      result.projectId !== projectId ||
      result.selectedDate !== selectedDate ||
      result.displayLimit !== displayLimit
    ) {
      topError.value = 'TOP 返回的项目、统计日或显示数量与当前查询不一致。'
      return
    }
    topResult.value = result
    selectedMaterialId.value = ['READY','PARTIAL'].includes(result.rankingState) ? result.rows[0]?.materialId ?? '' : ''
  } catch (error) {
    if (isCurrentTopRequest(requestVersion, projectId, selectedDate, displayLimit)) {
      topError.value = error instanceof Error ? error.message : 'TOP 请求失败。'
    }
  } finally {
    if (requestVersion === topRequestVersion) topLoading.value = false
  }
}

async function refreshProjects(_forceResults = false): Promise<void> {
  const requestVersion = ++projectRequestVersion
  projectLoading.value = true
  projectError.value = ''
  try {
    const nextProjects = await adapter.listProjects()
    if (requestVersion !== projectRequestVersion) return
    const previousSelection = selectedProjectId.value
    projects.value = nextProjects
    if (!nextProjects.some((project) => project.id === previousSelection)) {
      await selectProject(nextProjects[0]?.id ?? '')
    }
    for (const id of projectStates.keys()) if (!nextProjects.some(project => project.id === id)) {
      projectStates.delete(id)
      preferenceLoaded.delete(id)
      snapshotLoaded.delete(id)
    }
    if (!selectedProjectId.value) {
      reviewRequestVersion += 1
      topRequestVersion += 1
      reviewResult.value = null
      topResult.value = null
      reviewLoading.value = false
      topLoading.value = false
    }
  } catch (error) {
    if (requestVersion !== projectRequestVersion) return
    projectError.value = error instanceof Error ? error.message : '项目列表读取失败。'
    projects.value = []
    selectedProjectId.value = ''
    reviewRequestVersion += 1
    topRequestVersion += 1
    reviewResult.value = null
    topResult.value = null
    reviewLoading.value = false
    topLoading.value = false
  } finally {
    if (requestVersion === projectRequestVersion) projectLoading.value = false
  }
}

const overviewRevision = ref(0)
function invalidateConfigurationResults(): void {
  overviewRevision.value += 1
  projectStates.clear()
  preferenceLoaded.clear()
  snapshotLoaded.clear()
  snapshotVersion += 1
  snapshotLoading.value = false
  snapshotSavedAt.value = null
  snapshotMessage.value = ''
  preferenceVersion += 1
  preferenceLoading.value = false
  projectRequestVersion += 1
  projectLoading.value = false
  reviewRequestVersion += 1
  topRequestVersion += 1
  reviewResult.value = null
  topResult.value = null
  reviewLoading.value = false
  topLoading.value = false
}

async function configurationChanged(revision: string): Promise<void> {
  knownConfigurationRevision.value = revision
  invalidateConfigurationResults()
  await refreshProjects(true)
}

async function selectProject(projectId: string): Promise<void> {
  if (projectId === selectedProjectId.value) return
  if (selectedProjectId.value) projectStates.set(selectedProjectId.value, snapshotState())
  reviewRequestVersion += 1
  topRequestVersion += 1
  freshnessRequestVersion += 1
  snapshotVersion += 1
  selectedProjectId.value = projectId
  restoreState(projectId)
  if (projectId) {
    const selectionVersion = reviewRequestVersion
    await restorePreference(projectId)
    if (selectedProjectId.value === projectId && selectionVersion === reviewRequestVersion) await restorePeriodSnapshot(projectId)
  }
}

async function confirmReviewDates(): Promise<void> {
  workspaceHome.value = false
  reviewError.value =
    validateRange('本期', reviewDraft.value.current.start, reviewDraft.value.current.end) ||
    validateRange('上期', reviewDraft.value.previous.start, reviewDraft.value.previous.end)
  if (reviewError.value) {
    reviewRequestVersion += 1
    reviewResult.value = null
    reviewLoading.value = false
    return
  }
  committedReview.value = cloneReviewQuery(reviewDraft.value)
  persistReviewDates()
  await loadReview()
}

async function adjustReviewTopComparisonLimit(delta: number): Promise<void> {
  const nextLimit = reviewTopComparisonLimit.value + delta
  if (nextLimit < 1 || nextLimit > MAX_DISPLAY_ROWS) {
    reviewError.value = '显示数量须为 1～200 的整数，未执行超出范围的查询。'
    return
  }
  reviewTopComparisonLimit.value = nextLimit
}

function openTopDatePicker(event: MouseEvent): void {
  const input = event.currentTarget as HTMLInputElement
  try { input.showPicker?.() } catch { input.focus() }
}

async function confirmTopDate(): Promise<void> {
  workspaceHome.value = false
  if (!topDateDraft.value) {
    topError.value = 'TOP 统计日不能为空。'
    topRequestVersion += 1
    topResult.value = null
    topLoading.value = false
    return
  }
  committedTopDate.value = topDateDraft.value
  await loadTop()
}

async function adjustTopDisplayLimit(delta: number): Promise<void> {
  await setTopDisplayLimit(topDisplayLimit.value + delta)
}

async function setTopDisplayLimit(nextLimit: number): Promise<void> {
  if (!Number.isInteger(nextLimit) || nextLimit < 1 || nextLimit > MAX_DISPLAY_ROWS) {
    topError.value = '显示数量须为 1～200 的整数，未执行超出范围的查询。'
    return
  }
  topDisplayLimit.value = nextLimit
}

onMounted(async () => {
  rolloverTimer=setInterval(checkDayRollover,30000)
  window.addEventListener('focus',checkDayRollover)
  window.addEventListener('pageshow',checkDayRollover)
  document.addEventListener('visibilitychange',checkDayRollover)
  unsubscribeProjects = adapter.subscribeProjects?.(() => void refreshProjects())
  await refreshProjects()
})

onBeforeUnmount(() => {
  preferenceVersion += 1
  freshnessRequestVersion += 1
  clearInterval(rolloverTimer)
  window.removeEventListener('focus',checkDayRollover)
  window.removeEventListener('pageshow',checkDayRollover)
  document.removeEventListener('visibilitychange',checkDayRollover)
  projectRequestVersion += 1
  reviewRequestVersion += 1
  topRequestVersion += 1
  unsubscribeProjects?.()
})
</script>

<template>
  <div class="app-shell neural-app" :class="{'is-home': workspaceHome}">
    <header class="global-header" tabindex="-1">
      <button class="global-brand" @click="openWorkspace('home')"><span class="brand-star"><UiIcon name="star" :size="24"/></span><span><strong>素材视界</strong></span></button>
      <nav class="global-nav" aria-label="主导航"><button :class="{active:workspaceHome}" @click="openWorkspace('home')"><span>工作台</span><small>WORKSPACE</small></button><button :class="{active:!workspaceHome && activeView==='dashboard' && activeTab==='top'}" @click="openWorkspace('top')"><span>每日TOP素材</span><small>MATERIALS</small></button><button :class="{active:!workspaceHome && activeView==='dashboard' && activeTab==='review'}" @click="openWorkspace('review')"><span>周期数据</span><small>INSIGHTS</small></button><button v-if="canOperate" :class="{active:!workspaceHome && activeView==='operations'}" @click="openWorkspace(canOperate ? 'configuration':'downloads')"><span>{{ canOperate ? '数据运维' : '数据下载' }}</span><small>{{ canOperate ? 'OPERATIONS' : 'EXPORT' }}</small></button></nav>
      <div class="global-actions"><span v-if="props.session && !props.session.publicAccess" class="global-username">{{ props.session.username }}</span><UsageGuide :operations="canOperate" :administrator="props.session?.administrator === true" :recovery-read-only="recoveryReadOnly"/><button class="user-avatar" @click="openProjects" :aria-label="props.session?.publicAccess ? '打开项目面板' : `当前用户 ${props.session?.username ?? '本地用户'}，打开项目与账号面板`"><UiIcon name="user" :size="17"/></button></div>
    </header>
    <dialog ref="projectDialog" class="project-dialog" aria-label="项目与账号" @close="projectPickerOpen = false" @click="event => { if(event.target === projectDialog) projectDialog?.close() }">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand__mark" aria-hidden="true"><UiIcon name="star"/></span>
        <div>
          <h1>素材数据</h1><small>选择你的观察对象</small>
        </div>
      </div>

      <button class="project-close icon-button" aria-label="关闭项目面板" @click="projectDialog?.close()"><UiIcon name="close"/></button>
      <div v-if="isSynthetic" class="synthetic-notice">
        <strong>{{ isSynthetic ? '合成演示数据' : '本地事实查询模式' }}</strong>
        <span>{{ isSynthetic ? '纯虚构数据，仅验证交互；不是业务结果' : '只读当前事实版本；不触发采集、写表或云服务' }}</span>
      </div>

      <nav aria-label="项目列表" class="project-nav">
        <section v-for="category in categoryGroups" :key="category.id" class="project-category"
          :data-category="category.id" :style="{ flexGrow: categoryExpanded[category.id] ? category.weight : 0 }"
          :class="{ 'project-category--collapsed': !categoryExpanded[category.id] }">
          <button type="button" class="project-category-toggle" :aria-expanded="categoryExpanded[category.id]"
            :aria-controls="`category-${category.id}`" @click="categoryExpanded[category.id] = !categoryExpanded[category.id]">
            <span>{{ category.label }} <small>({{ category.projects.length }})</small></span>
            <span aria-hidden="true">{{ categoryExpanded[category.id] ? '−' : '+' }}</span>
          </button>
          <div v-show="categoryExpanded[category.id]" :id="`category-${category.id}`" class="project-category-list" :aria-label="`${category.label}项目`" tabindex="0">
        <button
          v-for="project in category.projects"
          :key="project.id"
          type="button"
          class="project-button"
          :class="{ 'project-button--active': project.id === selectedProjectId }"
          :aria-current="project.id === selectedProjectId ? 'page' : undefined"
          @click="chooseProject(project.id)"
        >
          <span class="project-button__dot" aria-hidden="true"></span>
          <span>
            <strong>{{ project.name }}</strong>
          </span>
        </button>
          <p v-if="!category.projects.length" class="project-category-empty">暂无已启用项目</p>
          </div>
        </section>
        <p v-if="projectError" class="form-error" role="alert">{{ projectError }}</p>
        <p v-else-if="!projects.length && !projectLoading" class="empty-projects">暂无已启用项目。</p>
      </nav>

      <div class="sidebar__footnote">
        <button class="operations-toggle" type="button" :aria-pressed="activeView === 'operations'" @click="toggleOperations">
          {{ activeView === 'operations' ? '返回大屏' : canOperate ? '数据运维' : '下载数据' }}
        </button>
        <form v-if="props.session && !props.session.publicAccess" :action="applicationPath('/logout')" method="post">
          <input type="hidden" name="_csrf" :value="props.session.csrfToken" />
          <button type="submit" class="operations-toggle">退出登录</button>
        </form>
      </div>
    </aside>
    </dialog>
    <main class="workspace" :class="{'workspace--home':workspaceHome}">
      <section v-if="props.localReadOnlyPreview" class="local-preview-notice" role="status" :aria-label="isSynthetic ? '本地合成数据只读说明' : '本地真实数据只读说明'" :data-evidence-kind="isSynthetic ? 'SYNTHETIC' : 'CURRENT_FACTS'">
        <strong>{{ isSynthetic ? '本地合成数据 · 隔离只读预览' : '本地真实数据 · 只读预览' }}</strong>
        <p v-if="isSynthetic">当前仅使用虚构样本验证界面，不读取本机业务数据或云端数据。上传、配置保存、删除和真实下载已关闭；展示结果不是业务结果。</p>
        <p v-else>当前读取本机已有数据，不是云端实时副本。可切换项目、日期并刷新查看；上传、配置保存、删除和文件生成已关闭。日期与结果仅在本次打开期间保留。</p>
      </section>
      <section v-if="recoveryReadOnly" class="panel recovery-notice" role="status" aria-label="恢复验证说明">
        <strong>管理员只读恢复版 · 尚未恢复正式运行</strong>
        <p>当前查询隔离恢复库；项目配置来自旧迁移基线，需核对。自动采集、账号管理、上传、下载和配置修改均关闭。</p>
        <p>历史上传留存、个人日期偏好与保存结果尚未完整恢复。本页日期和结果仅在本次打开期间保留，不能据此认定这些文件已找回。</p>
      </section>
      <p v-if="workspaceHome && projectLoading" class="home-project-status" role="status">正在读取已有项目配置…</p>
      <p v-if="workspaceHome && projectError" class="home-project-status form-error" role="alert">{{ projectError }}</p>
      <div v-show="workspaceHome" class="workspace-home"><WorkspaceHome :active="workspaceHome" :overview-revision="overviewRevision" :overview-adapter="props.overviewAdapter ?? (props.adapter ? undefined : adapter)" :project-name="selectedProject?.name ?? '正在载入项目'" :projects="projects" :selected-project-id="selectedProjectId" :can-operate="canOperate" :top="topResult" :review="reviewResult" @navigate="openWorkspace" @select-project="enterProject" @select-overview="selectHomeProject" @projects="openProjects"/></div>
      <div v-show="!workspaceHome" class="workspace-content" :data-operation="operationsSection">

      <div class="workspace-breadcrumb"><button @click="openWorkspace('home')">工作台</button><span>/</span><span>{{ activeView === 'operations' ? '数据运维' : activeTab === 'top' ? '每日TOP素材' : '周期数据' }}</span><span class="breadcrumb-end"><i class="status-spark"/>{{ isSynthetic ? '本地演示 · 虚构数据' : '当前项目空间' }}</span></div>
      <header class="workspace-header">
        <div>
          <p v-if="activeView === 'operations' || activeTab === 'review'" class="overline">{{ activeView === 'operations' ? 'OPERATIONS CENTER' : activeTab === 'top' ? 'MATERIAL OBSERVATORY' : 'PERIOD INSIGHTS' }}</p><h2>{{ selectedProject?.name ?? '未选择项目' }}</h2><small v-if="selectedProject" class="freshness-label">{{ freshnessText }}</small>
        </div>
        <span v-if="!props.session" class="mode-pill">{{ isSynthetic ? '本地 · 合成' : '本地 · 只读接口' }}</span>
      </header>

      <div class="workspace-intro"><p v-if="activeView === 'operations' || activeTab === 'review'">{{ activeView === 'operations' ? '让数据各归其位，让每一步操作清晰可见。' : activeTab === 'top' ? '看见每一份素材的表现，找到值得关注的下一步。' : '沿着时间回看，让整体变化与素材贡献一起呈现。' }}</p><button class="text-link" @click="openProjects">切换项目 <UiIcon name="arrow" :size="15"/></button></div>
      <nav v-show="activeView === 'operations'" class="operations-nav" aria-label="运维分区"><button v-for="item in operationItems" :key="item.id" :class="{active:operationsSection === item.id}" @click="operationsSection = item.id"><UiIcon :name="item.icon"/><span>{{ item.label }}<small>{{ item.en }}</small></span></button></nav>
      <p v-if="activeView === 'operations' && isSynthetic" class="preview-operation-note"><UiIcon name="shield" :size="16"/>{{ props.localReadOnlyPreview ? '当前为隔离只读的虚构数据演示。配置保存、账号写入、文件上传与真实下载均不执行。' : '当前为虚构数据演示。配置保存仅在本页内生效；账号写入、文件上传与真实下载不执行。' }}</p>
      <AdminAccountsPanel v-if="operationsOpened && props.session?.administrator === true && !recoveryReadOnly && !privateOperationsPreview" v-show="activeView === 'operations' && operationsSection === 'accounts'" :active="activeView === 'operations' && operationsSection === 'accounts'" />
      <p v-if="operationsOpened && operationsSection === 'accounts' && privateOperationsPreview && activeView === 'operations'" class="configuration-attention">演示环境不连接真实账号管理，也不执行账号修改。</p>
      <ConfigurationPanel v-if="operationsOpened && canOperate && (!isSynthetic || props.configurationAdapter)" v-show="activeView === 'operations' && operationsSection === 'configuration'"
        :active="activeView === 'operations' && operationsSection === 'configuration'" :adapter="props.configurationAdapter" :recovery-read-only="recoveryReadOnly" :local-read-only-preview="props.localReadOnlyPreview" :known-revision="knownConfigurationRevision"
        @saving="invalidateConfigurationResults" @changed="configurationChanged" @refreshed="configurationChanged" />
      <p v-if="operationsOpened && isSynthetic && !props.configurationAdapter && activeView === 'operations' && operationsSection === 'configuration'" class="configuration-attention">此演示未提供项目配置样本，不会读取或修改正式配置。</p>
      <CollectionOperationsPanel v-if="operationsOpened && canOperate && !recoveryReadOnly" v-show="activeView === 'operations' && operationsSection === 'status'"
        :active="activeView === 'operations' && operationsSection === 'status'" :preview="privateOperationsPreview">
        <ConfigurationPanel v-if="!isSynthetic || props.configurationAdapter" scope="metrics"
          :active="activeView === 'operations' && operationsSection === 'status'" :adapter="props.configurationAdapter" :recovery-read-only="recoveryReadOnly" :local-read-only-preview="props.localReadOnlyPreview" :known-revision="knownConfigurationRevision"
          @saving="invalidateConfigurationResults" @changed="configurationChanged" @refreshed="configurationChanged" />
        <p v-else class="configuration-attention">此演示未提供采集指标配置样本，不会读取或修改正式配置。</p>
      </CollectionOperationsPanel>
      <section v-if="operationsOpened && canOperate && !recoveryReadOnly" v-show="activeView === 'operations' && operationsSection === 'files'" class="panel operations-files" aria-labelledby="operations-files-title">
        <div class="panel__header"><div><p class="panel__index">FILE STORAGE</p><h3 id="operations-files-title">文件存储</h3><p>系统数据库备份与人工上传资料分开管理。这里不解压、不执行文件，也不导入业务数据。</p></div></div>
        <DatabaseBackupPanel :active="activeView === 'operations' && operationsSection === 'files'" :preview="privateOperationsPreview" />
        <FileStoragePanel :active="activeView === 'operations' && operationsSection === 'files'" :preview="privateOperationsPreview" />
      </section>

      <div v-if="!selectedProject" class="empty-state">
        <strong>没有可展示的项目</strong>
          <span>{{ projectError || !canOperate ? '请联系运维检查项目配置或数据。' : '可进入数据运维查看项目配置；零启用项目不关闭配置入口。' }}</span>
      </div>

      <template v-else>
        <p v-if="operationsOpened && privateOperationsPreview && !props.historyUploadAdapter && activeView === 'operations' && operationsSection === 'history'" class="configuration-attention">本地演示未连接正式历史数据；不会读取真实上传记录、上传文件或入库。</p>
        <HistoryUploadPanel v-if="operationsOpened && canOperate && !recoveryReadOnly && (!privateOperationsPreview || props.historyUploadAdapter)" v-show="activeView === 'operations' && operationsSection === 'history'"
          :project-id="selectedProjectId" :active="activeView === 'operations' && operationsSection === 'history'"
          :projects="projects" @select-project="selectProject"
          :adapter="props.historyUploadAdapter" :read-only="props.historyReadOnly || privateOperationsPreview" />
        <OperationsPanel v-if="operationsOpened && canOperate && !recoveryReadOnly" v-show="activeView === 'operations' && operationsSection === 'history'"
          :project-id="selectedProjectId" :project-name="selectedProject.name"
          :history-top-result="topResult" :active="activeView === 'operations' && operationsSection === 'history' && (!privateOperationsPreview || !!props.operationsAdapter)" :adapter="props.operationsAdapter" />
        <section v-show="activeView === 'dashboard' && activeTab === 'review'" class="panel review-panel" aria-labelledby="review-title">
          <form class="period-toolbar date-form--review" @submit.prevent="confirmReviewDates">
            <h3 id="review-title">周期素材数据</h3>
            <DateRangePicker v-model="reviewDraft.current" label="本周期" :disabled="preferenceLoading" @confirm="persistReviewDates" />
            <DateRangePicker v-model="reviewDraft.previous" label="上周期" :disabled="preferenceLoading" @confirm="persistReviewDates" />
            <span v-if="reviewResult" class="period-query-time">查询时间 {{ new Date(reviewResult.requestedAt).toLocaleString('zh-CN') }}</span>
            <span class="period-status-dot" :class="`period-status-dot--${periodSignal}`" role="status" :aria-label="periodSignal === 'green' ? '周期数据完整' : periodSignal === 'red' ? '周期数据缺失或待核' : '周期数据查询中或未就绪'" />
            <button class="primary-button" type="submit" :disabled="reviewLoading || preferenceLoading"><UiIcon name="refresh" :size="16"/>{{ reviewLoading ? '正在刷新…' : '刷新数据' }}</button>
          </form>

          <p v-if="reviewError" class="form-error" role="alert">{{ reviewError }}</p>
          <p v-if="preferenceLoading" class="module-note" role="status">正在恢复此项目的周期日期偏好…</p>
          <p v-if="preferenceError" class="form-error" role="alert">{{ preferenceError }}</p>
          <p v-if="snapshotLoading" class="module-note" role="status">正在读取上次保存的周期结果，不会重新计算…</p>
          <p v-if="snapshotMessage" class="form-error" role="alert">{{ snapshotMessage }}</p>
          <p v-if="reviewResult && snapshotSavedAt" class="module-note" role="status">上次保存的结果 · 保存时间 {{ new Date(snapshotSavedAt).toLocaleString('zh-CN') }}。可能不是最新数据，点击“刷新数据”重新计算并保存。</p>
          <p v-if="reviewPending" class="form-error" role="status">条件已修改，请刷新数据。下方仍是上次查询结果。</p>
          <p v-if="reviewResult" class="module-note">当前结果：本周期 {{ reviewResult.periods.find(p => p.key === 'CURRENT')?.range.start }} — {{ reviewResult.periods.find(p => p.key === 'CURRENT')?.range.end }}；上周期 {{ reviewResult.periods.find(p => p.key === 'PREVIOUS')?.range.start }} — {{ reviewResult.periods.find(p => p.key === 'PREVIOUS')?.range.end }}</p>
          <p v-else-if="!reviewLoading" class="module-note" role="status">点击“刷新数据”查看已入库数据，不会启动采集。</p>

          <div v-if="!reviewResult" class="analysis-empty" :aria-busy="reviewLoading"><ConstellationGlyph :kind="1"/><p class="overline">PERIOD COMPARISON</p><h4>{{ reviewLoading ? '正在整理周期表现…' : '把两个周期，放在一起看。' }}</h4><p>选择本期与上期日期，点击“刷新数据”。<br>整体表现、素材构成与 TOP 对比将在这里展开。</p><div class="empty-stage-labels"><span>01 整体表现</span><span>02 素材构成</span><span>03 分类对比</span></div></div>
          <PeriodReview v-if="reviewResult" :key="selectedProjectId" :result="reviewResult" :project-name="selectedProject.name" :load-overall-materials="loadReviewOverallMaterials" :material-type-selection="materialTypeSelections[selectedProjectId]" :load-material-types="loadMaterialTypes" @material-type-updated="selection => materialTypeSelections[selectedProjectId] = selection" />
        </section>

        <section v-show="activeView === 'dashboard' && activeTab === 'top'" class="panel daily-panel" aria-labelledby="top-title">
          <div class="top-controls-layout">
          <h3 id="top-title">每日TOP素材数据</h3>
          <form class="date-form date-form--top" @submit.prevent="confirmTopDate">
            <label for="top-date">
              <span>统计日</span>
              <input id="top-date" v-model="topDateDraft" type="date" @click="openTopDatePicker" />
            </label>
            <div class="top-limit" role="group" aria-label="TOP 显示数量">
              <span class="top-limit__label">显示数量</span>
              <div class="top-limit__control">
                <button
                  type="button"
                  class="step-button"
                  aria-label="减少 TOP 显示数量"
                  :disabled="topDisplayLimit <= 1"
                  @click="adjustTopDisplayLimit(-1)"
                >
                  −
                </button>
                <select class="standalone-top-limit__value top-limit__value" aria-label="快速选择 TOP 显示数量" :value="topDisplayLimit" @change="setTopDisplayLimit(Number(($event.target as HTMLSelectElement).value))">
                  <option v-for="limit in topLimitOptions" :key="limit" :value="limit">{{ limit }} 条</option>
                </select>
                <button
                  type="button"
                  class="step-button"
                  aria-label="增加 TOP 显示数量"
                  :disabled="topDisplayLimit >= MAX_DISPLAY_ROWS"
                  @click="adjustTopDisplayLimit(1)"
                >
                  +
                </button>
              </div>
            </div>
            <div class="daily-actions">
              <span class="period-status-dot daily-status" :class="`period-status-dot--${topSignal}`" role="status" :aria-label="topError ? '近7天数据读取失败' : topSignal === 'green' ? '近7天数据正常' : topSignal === 'red' ? '近7天数据缺失' : '近7天数据查询中或未就绪'" />
              <button v-if="!recoveryReadOnly" ref="downloadTrigger" class="primary-button download-data-button" type="button" aria-haspopup="dialog" @click="openDownloads"><UiIcon name="download" :size="16"/>下载数据</button>
              <button class="primary-button" type="submit" :disabled="topLoading"><UiIcon name="refresh" :size="16"/>{{ topLoading ? '正在刷新…' : '刷新数据' }}</button>
            </div>
          </form>
          </div>

          <p v-if="topError" class="form-error" role="alert">{{ topError }}</p>
          <p v-if="topPending" class="form-error" role="status">条件已修改，请刷新数据。下方仍是上次查询结果。</p>
          <p v-if="topResult" class="module-note">当前结果：统计日 {{ topResult.selectedDate }} · 显示 {{ topResult.displayLimit }} 条 · 查询时间 {{ new Date(topResult.requestedAt).toLocaleString('zh-CN') }}</p>
          <p v-else-if="!topLoading" class="module-note" role="status">点击“刷新数据”查看已入库数据，不会启动采集。</p>

          <div v-if="!topResult" class="analysis-empty" :aria-busy="topLoading"><ConstellationGlyph/><p class="overline">YOUR DAILY OBSERVATORY</p><h4>{{ topLoading ? '正在整理素材表现…' : '你的下一次观察，从这里开始。' }}</h4><p>选择统计日，点击“刷新数据”。<br>素材排名、消耗趋势和 ROI 将在这里呈现。</p><div class="empty-stage-labels"><span>01 查看榜单</span><span>02 定位素材</span><span>03 观察趋势</span></div></div>
          <div v-if="topResult" class="top-results" aria-live="polite">
            <p v-if="topResult.evidenceKind === 'SYNTHETIC'" class="synthetic-coverage-warning">
              覆盖灯和缺失日期为纯虚构交互演示，不对应这些日期的真实采集或验收状态。
            </p>
            <div class="top-overview">
              <section class="chart-card scatter-card">
                <MaterialScatter v-if="['READY','PARTIAL'].includes(topResult.rankingState)" :rows="topResult.rows" :selected-date="topResult.selectedDate" :selected-id="selectedMaterialId" @select="selectMaterial" />
                <template v-else><h4>消耗 &amp; ROI</h4><p class="form-error scatter-unavailable">{{ topEmptyMessage }} 散点图暂不可用，不将缺失数据绘为零。</p></template>
              </section>
              <section class="chart-card selected-material-panel" aria-label="选中素材详情" aria-live="polite">
                <div class="selected-material-titlebar"><h4>素材详情</h4><span v-if="selectedMaterial" class="selected-material-id">{{ formatMaterialLabel(selectedMaterial.materialId, selectedMaterial.materialName) }}</span><span v-if="selectedMaterial" class="selected-material-date">首次投放时间 <strong>{{ formatCreationDate(selectedMaterial.creationDate) }}</strong></span></div>
                <template v-if="selectedMaterial">
                  <h5 class="selected-material-name">{{ selectedMaterial.materialName ?? '名称未知' }}</h5>
                  <div class="selected-metrics-grid">
                    <div><span>当天消耗</span><strong><MetricValue :result="selectedMaterial.dailySpend.find(d => d.date === topResult!.selectedDate)?.spend" /></strong></div>
                    <div><span>当天 ROI</span><strong><MetricValue :result="selectedMaterial.dayRoi" /></strong></div>
                    <div><span>近 3 天消耗</span><strong><MetricValue :result="selectedMaterial.recent3Spend" /></strong></div>
                    <div><span>近 3 天 ROI</span><strong><MetricValue :result="selectedMaterial.recent3Roi" /></strong></div>
                    <div><span>近 3 天 CPM</span><strong><MetricValue :result="selectedMaterial.recent3Cpm" /></strong></div>
                    <div><span>近 3 天 CPC</span><strong><MetricValue :result="selectedMaterial.recent3Cpc" /></strong></div>
                    <div><span>近 3 天展现次数</span><strong><MetricValue :result="selectedMaterial.recent3Impressions" format="integer" /></strong></div>
                    <div><span>近 3 天点击次数</span><strong><MetricValue :result="selectedMaterial.recent3Clicks" format="integer" /></strong></div>
                    <div><span>近 3 天点击率</span><strong><MetricValue :result="selectedMaterial.recent3Ctr" format="percent" /></strong></div>
                    <div><span>近 3 天转化率</span><strong><MetricValue :result="selectedMaterial.recent3Cvr" format="percent" /></strong></div>
                    <div><span>累计消耗（已有数据）</span><strong><MetricValue :result="selectedMaterial.lifetimeSpend" hide-partial /></strong></div>
                    <div><span>累计ROI（已有数据）</span><strong><MetricValue :result="selectedMaterial.lifetimeRoi" hide-partial /></strong></div>
                  </div>
                  <div class="selected-curve-heading"><strong>近 7 天消耗走势</strong><span>近 7 天 ROI <MetricValue :result="selectedMaterial.windowRoi" /></span></div>
                  <SpendCurve :days="selectedMaterial.dailySpend" expanded />

      </template>
                <p v-else class="module-note selected-material-empty">{{ topEmptyMessage }}</p>
              </section>
            </div>
            <div class="module-header top-table-header">
              <div>
                <p class="overline">MATERIAL COLLECTION</p><h4>素材榜单与趋势</h4>
              </div>
            </div>
            <div class="material-toolbar"><label class="material-search"><UiIcon name="search" :size="16"/><input v-model="materialSearch" placeholder="搜索当前榜单的素材名称或 ID" aria-label="搜索当前素材榜单"/><button v-if="materialSearch" @click="materialSearch = ''" aria-label="清空素材搜索"><UiIcon name="close" :size="14"/></button></label><div class="view-switch"><button :class="{active:materialView==='table'}" @click="materialView='table'"><UiIcon name="table" :size="15"/>数据视图</button><button :class="{active:materialView==='cards'}" @click="materialView='cards'"><UiIcon name="grid" :size="15"/>素材卡片</button></div><small>{{ filteredTopRows.length }} 条素材 · 按原榜单排名</small></div>
            <MaterialGallery v-if="materialView === 'cards'" :rows="filteredTopRows" :date="topResult.selectedDate" :selected="selectedMaterialId" @select="openMaterialDrawer"/>
            <p v-if="materialSearch && !filteredTopRows.length" class="search-empty">当前榜单没有匹配素材。<button class="text-link" @click="materialSearch=''">清空搜索</button></p>
            <div v-show="materialView === 'table'" class="table-shell table-shell--wide" tabindex="0" aria-label="TOP 素材近日字段结构表">
              <table class="business-table top-material-table">
                <caption class="sr-only">独立 TOP 素材、七日逐日消耗和近三日汇总；按所选日正消耗入榜</caption>
                <thead><tr><th>排名 / 素材</th><th>七日消耗走势</th><th>当天<br /><small>消耗 / ROI</small></th><th>近 3 天<br /><small>消耗 / ROI</small></th><th>近 7 天<br /><small>消耗 / ROI</small></th><th>已有数据<br /><small>累计消耗 / ROI</small></th><th aria-label="展开详情"></th></tr></thead>
                <tbody>
                  <template v-for="row in filteredTopRows" :key="row.materialId">
                    <tr class="material-row" :class="{ 'is-selected': selectedMaterialId === row.materialId }" @click="toggleMaterial(row.materialId)">
                      <td class="material-identity"><div class="material-heading"><span class="rank-badge">{{ row.rank }}</span><strong :title="row.materialName ?? formatMaterialLabel(row.materialId, row.materialName)">{{ row.materialName ?? '—（名称未知）' }}</strong></div><small class="material-id">{{ formatMaterialLabel(row.materialId, row.materialName) }}</small><small>首次投放时间 {{ formatCreationDate(row.creationDate) }}</small></td>
                      <td class="curve-cell"><small class="row-metric-label">七日消耗走势</small><SpendCurve :days="row.dailySpend" /><small>{{ shiftIsoDate(topResult.selectedDate, -6).slice(5).replace('-', '/') }} — {{ topResult.selectedDate.slice(5).replace('-', '/') }}</small></td>
                      <td class="metric-pair"><small class="row-metric-label">当天消耗</small><strong><MetricValue :result="row.dailySpend.find(d => d.date === topResult!.selectedDate)?.spend" /></strong><span>ROI <MetricValue :result="row.dayRoi" /></span></td>
                      <td class="metric-pair"><small class="row-metric-label">近 3 天消耗</small><strong><MetricValue :result="row.recent3Spend" /></strong><span>ROI <MetricValue :result="row.recent3Roi" /></span></td>
                      <td class="metric-pair"><small class="row-metric-label">近 7 天消耗</small><strong><MetricValue :result="row.windowSpend" /></strong><span>ROI <MetricValue :result="row.windowRoi" /></span></td>
                      <td class="metric-pair"><small class="row-metric-label">累计消耗（已有数据）</small><strong><MetricValue :result="row.lifetimeSpend" hide-partial /></strong><span>ROI <MetricValue :result="row.lifetimeRoi" hide-partial /></span></td>
                      <td class="expand-cell"><button type="button" class="expand-button" :aria-label="`${expandedMaterialId === row.materialId ? '收起' : '展开'}第 ${row.rank} 名素材详情`" :aria-expanded="expandedMaterialId === row.materialId" @click.stop="toggleMaterial(row.materialId)"><svg viewBox="0 0 16 16" aria-hidden="true" :class="{ 'is-expanded': expandedMaterialId === row.materialId }"><path d="m3 5 5 5 5-5" /></svg><span>{{ expandedMaterialId === row.materialId ? '收起' : '展开' }}</span></button></td>
                    </tr>
                    <tr v-if="expandedMaterialId === row.materialId" class="expanded-material"><td colspan="7"><div class="material-detail"><div><h4>{{ row.materialName ?? '名称未知' }}</h4><p class="module-note">{{ formatMaterialLabel(row.materialId, row.materialName) }} · 首次投放时间 {{ formatCreationDate(row.creationDate) }}</p><SpendCurve :days="row.dailySpend" expanded /><div class="daily-value-list"><span v-for="day in [...row.dailySpend].reverse()" :key="day.date">{{ day.date.slice(5) }} <MetricValue :result="day.spend" /></span></div></div><aside><h4>补充指标</h4><p>近 3 天 CPM <MetricValue :result="row.recent3Cpm" /></p><p>近 3 天 CPC <MetricValue :result="row.recent3Cpc" /></p><p>近 3 天展现次数 <MetricValue :result="row.recent3Impressions" format="integer" /></p><p>近 3 天点击次数 <MetricValue :result="row.recent3Clicks" format="integer" /></p><p>近 3 天点击率 <MetricValue :result="row.recent3Ctr" format="percent" /></p><p>近 3 天转化率 <MetricValue :result="row.recent3Cvr" format="percent" /></p></aside></div></td></tr>
                  </template>
                  <tr v-if="!topResult.rows.length" class="placeholder-row">
                    <td colspan="7">{{ topEmptyMessage }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="module-note">
              点击右侧箭头展开或收起消耗曲线。
            </p>
          </div>
        </section>
        <dialog v-if="!recoveryReadOnly" ref="downloadDialog" class="download-dialog" tabindex="-1" aria-labelledby="download-dialog-title" aria-describedby="download-dialog-notice" :aria-busy="downloadBusy" @keydown="trapDownloadsFocus" @cancel.prevent="closeDownloads" @close="downloadsClosed" @click="event=>{if(event.target===downloadDialog)closeDownloads()}">
          <header class="download-dialog-heading"><div><p class="overline">PROJECT DATA EXPORT</p><h2 id="download-dialog-title">下载数据</h2><p>{{ selectedProject.name }} · 选择范围，保留每一份数据</p></div><div class="download-reference-actions"><button ref="downloadCloseButton" type="button" class="icon-button" aria-label="关闭下载数据面板" :disabled="downloadBusy" @click="closeDownloads"><UiIcon name="close"/></button><CalculationSchemaViewer :disabled="downloadBusy"/></div></header>
          <p id="download-dialog-notice" class="download-attention" role="status">{{ downloadBusy ? '正在处理，请勿关闭面板、刷新页面或重复提交。完成前关闭按钮、Esc 和遮罩暂不可用，请等待结果。' : '请先核对可下载范围再生成文件；处理期间请保持本页打开，完成后可关闭面板。' }}</p>
          <p v-if="props.localReadOnlyPreview" class="download-attention">{{ isSynthetic ? '当前仅演示虚构数据的下载界面，不读取真实下载范围，不生成文件。' : '只读预览可核对真实下载范围；生成 Excel 会写入临时文件，本入口已禁用。' }}</p>
          <CollectedExportPanel :project-id="selectedProjectId" :active="downloadOpen" :projects="projects" @busy="setDownloadBusy" @select-project="chooseExportProject" :adapter="props.collectedExportAdapter" :read-only="!props.collectedExportAdapter && (props.historyReadOnly || isSynthetic)" :download-disabled="isSynthetic || props.localReadOnlyPreview" :download-disabled-message="props.localReadOnlyPreview ? (isSynthetic ? '仅为虚构数据演示；隔离只读预览不生成文件。' : '以上为本地已保存数据的范围；只读预览不生成文件。') : undefined"/>
        </dialog>
      </template>
      <MaterialDrawer v-if="materialDrawerOpen && selectedMaterial && topResult" :row="selectedMaterial" :date="topResult.selectedDate" @close="materialDrawerOpen=false"/>
      </div>
    </main>
    <ReturnToTop :blocked="downloadOpen || materialDrawerOpen || projectPickerOpen" />
  </div>
</template>
<style scoped>
.download-attention { color: var(--danger, #b42318); line-height: 1.6; margin: 20px 32px 0; }
.download-dialog .icon-button:disabled { opacity: .5; cursor: not-allowed; }
.download-reference-actions { display:flex;align-items:center;gap:14px;flex-wrap:wrap;justify-content:flex-end; }
.download-reference-actions > .icon-button { order:2; }
.workspace-intro { justify-content:flex-end; }
.workspace-intro:has(p) { justify-content:space-between; }
@media(max-width:540px) { .user-avatar { display:grid; } }
.local-preview-notice { margin:16px 24px; padding:12px 16px; border:1px solid #79565d; border-radius:10px; color:var(--danger, #ff9b9b); background:#281b22; font-size:12px; line-height:1.6; }
.local-preview-notice p { color:inherit; margin:4px 0 0; }
</style>
