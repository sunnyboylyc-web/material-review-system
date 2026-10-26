<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { DateRange, TopResult, TopRow } from '../domain/dashboard'
import type { OperationsDay, OperationsResult } from '../domain/operations'
import { operationsRequestMatches, validateOperationsRange } from '../domain/operations'
import type { OperationsDataAdapter } from '../adapters/operationsAdapter'
import { httpOperationsAdapter } from '../adapters/httpOperationsAdapter'
import { shanghaiIsoDate, shiftIsoDate } from '../time/shanghai'

const props = withDefaults(defineProps<{
  projectId: string
  projectName: string
  active?: boolean
  adapter?: OperationsDataAdapter
  historyTopResult?: TopResult | null
}>(), { active: true })
const adapter = props.adapter ?? httpOperationsAdapter
const yesterday = shanghaiIsoDate(new Date(), -1)
const draft = ref<DateRange>({ start: shiftIsoDate(yesterday, -6), end: yesterday })
const committed = ref<DateRange>({ ...draft.value })
const result = ref<OperationsResult | null>(null)
const loading = ref(false)
const error = ref('')
let requestVersion = 0
function isSourceDateObservation(entry: OperationsResult['missingDetails'][number]): boolean {
  return ['createdDate', 'creationDate'].includes(entry.field)
    && ['MISSING_CREATION_DATE', 'CONFLICTING_CREATION_DATE', 'CREATION_AFTER_OBSERVATION'].includes(entry.reason)
    && entry.affectedMetrics.length === 0
}
const hasAttention = computed(() => Boolean(result.value
  && (result.value.summary.missingDays > 0 || result.value.summary.isolatedDays > 0
    || result.value.missingDetailsTruncated
    || result.value.missingDetails?.some(entry => !isSourceDateObservation(entry)))))
const hasSourceDateNotes = computed(() => Boolean(result.value?.missingDetails?.some(isSourceDateObservation)))
const historyTop = computed(() => props.historyTopResult?.projectId === props.projectId ? props.historyTopResult : null)
const historyUnavailable = computed(() => historyTop.value && ['UNAVAILABLE', 'UNKNOWN_INPUT'].includes(historyTop.value.rankingState))
const historyAttention = computed(() => (historyTop.value?.rows ?? []).filter(row =>
  !row.lifetimeCoverage || row.lifetimeCoverage.state === 'PARTIAL'
  || row.lifetimeSpend.partial || row.lifetimeRoi.partial
  || row.lifetimeSpend.status === 'UNKNOWN_INPUT' || row.lifetimeRoi.status === 'UNKNOWN_INPUT'))
const historyReasons: Record<string, string> = {
  MISSING_ACCOUNT_DAYS: '累计范围内账户日缺失', MISSING_FIELD: '累计计算所需来源字段缺失，原因待核',
  UNKNOWN_SPEND: '部分消耗未知', UNKNOWN_CREATION_DATE: '累计起点未知',
  MISSING_MATERIAL_NAME: '素材名称缺失', COHORT_UNASSIGNED: '素材分类待核',
}
function historyReason(row: TopRow): string {
  const reasons = [...new Set([...(row.lifetimeSpend.partialReasons ?? []), ...(row.lifetimeRoi.partialReasons ?? [])])]
  return reasons.map(reason => historyReasons[reason] ?? reason).join('；')
    || (row.lifetimeSpend.status === 'UNKNOWN_INPUT' || row.lifetimeRoi.status === 'UNKNOWN_INPUT'
      ? '累计指标输入未知' : !row.lifetimeCoverage ? '累计覆盖范围未知' : '累计覆盖不完整')
}
function historyMissingSpan(row: TopRow): string {
  const dates = [...(row.lifetimeCoverage?.missingDates ?? [])].sort()
  return dates.length ? `${dates[0]} — ${dates[dates.length - 1]}（${dates.length} 天，不代表连续缺失）` : '无整日缺失记录'
}

function resetPending(): void {
  requestVersion += 1
  result.value = null
  loading.value = false
}

async function load(): Promise<void> {
  const version = ++requestVersion
  const projectId = props.projectId
  const range = { ...committed.value }
  result.value = null
  error.value = ''
  if (!props.active || !projectId) { loading.value = false; return }
  loading.value = true
  const isCurrent = () => version === requestVersion && props.active && props.projectId === projectId
    && committed.value.start === range.start && committed.value.end === range.end
  try {
    const response = await adapter.fetchDays(projectId, range)
    if (!isCurrent()) return
    if (!operationsRequestMatches(response, projectId, range)) {
      error.value = '运维返回的项目或日期与当前查询不一致，未展示结果。'
      return
    }
    result.value = response
  } catch (failure) {
    if (isCurrent()) error.value = failure instanceof Error ? failure.message : '运维查询失败，未展示结果。'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function confirmRange(): Promise<void> {
  const message = validateOperationsRange(draft.value)
  if (message) {
    resetPending()
    error.value = message
    return
  }
  committed.value = { ...draft.value }
  await load()
}

function stateLabel(day: OperationsDay): string {
  return day.state === 'MISSING' ? '缺少当前事实' : day.state === 'EMPTY' ? '完整空日' : '已入库'
}
const detailLabels: Record<string,string> = { creationDate:'创建日期', cohort:'新老素材分类', all:'全部看板指标', accountDay:'账户日', NO_CURRENT_VERSION:'无当前事实版本', FIELD_NOT_RECORDED:'源字段未记录，原因待核', createdDate: '创建日期', spend: '消耗', gmv: '成交金额', impressions: '曝光', clicks: '点击', orders: '订单', MISSING_ACCOUNT_DAY: '整日事实缺失', MISSING_ACCOUNT_DAYS: '整日事实缺失', MISSING_FIELD: '来源字段缺失，原因待核', MISSING_CREATION_DATE: '创建日期缺失', CONFLICTING_CREATION_DATE: '创建日期冲突', CREATION_AFTER_OBSERVATION: '创建日期晚于事实日', roi: 'ROI', ctr: '点击率', cvr: '转化率', cpm: 'CPM', cpc: 'CPC', lifetimeSpend: '累计消耗', lifetimeRoi: '累计ROI', materialCount: '素材数', classification: '新老分类' }
const detailLabel = (value: string) => detailLabels[value] ?? value

function preflightTemplate(date: string): string {
  return `cn.datadashboard.local.LocalFactPreflightMain <项目 .local 内私有配置路径> ${date}`
}

function repairTemplate(day: OperationsDay): string {
  const expectedCurrent = day.currentVersion?.contentHash ?? 'MISSING'
  return `cn.datadashboard.local.LocalFactRepairMain "<项目 .local 内私有配置路径>" ${day.date} "<SOURCE_READY 预检回执路径>" "<预检回执 SHA-256>" ${expectedCurrent} --apply`
}

watch([() => props.projectId, () => props.active], () => {
  if (props.active) void load()
  else resetPending()
}, { immediate: true })
onBeforeUnmount(resetPending)
</script>

<template>
  <details class="panel operations-panel">
    <summary class="operations-auxiliary-summary"><span>辅助检查 · 数据状态与技术维护说明</span><small>只查看系统已保存的数据，不采集、不导入</small></summary>
    <section aria-labelledby="operations-title">
    <div class="panel__header">
      <div>
        <p class="panel__index">只读检查</p>
        <h3 id="operations-title">数据状态</h3>
        <p>查看系统已经保存了哪些日期的数据，以及哪些日期需要检查。这里仅查询，不采集、不导入、不修改配置。</p>
      </div>
      <span class="scope-chip">项目：{{ projectName }}</span>
    </div>

    <p v-if="adapter.evidenceKind === 'SYNTHETIC'" class="synthetic-coverage-warning">
      运维合成测试数据，不代表真实账户或日期状态。
    </p>

    <details class="operations-history-coverage">
      <summary>每日 TOP · 累计历史覆盖提示<template v-if="historyTop">（{{ historyAttention.length }} 条需检查）</template></summary>
      <p class="module-note">仅使用当前已查询的每日 TOP 及所选统计日，不是全库审计；展开不发起额外查询，也不采集或补齐数据。</p>
      <p v-if="!historyTop" class="module-note">请先查看当前项目的每日 TOP，再回到此处检查累计历史覆盖。</p>
      <template v-else>
        <p class="history-scope">{{ projectName }} · 统计日 {{ historyTop.selectedDate }} · 当前 TOP {{ historyTop.rows.length }} 条（显示上限 {{ historyTop.displayLimit }} 条）</p>
        <p v-if="historyTop.evidenceKind === 'SYNTHETIC'" class="module-note">以下为合成测试结果，不代表真实数据。</p>
        <p v-if="historyUnavailable" class="module-note">当前统计日 TOP 未就绪，无法判断累计历史覆盖。</p>
        <p v-else-if="!historyTop.rows.length" class="module-note">当前 TOP 无素材可检查，不代表全库历史完整。</p>
        <p v-else-if="!historyAttention.length" class="module-note">当前 TOP 无累计缺失；仅限上述素材及统计日。</p>
        <ul v-else class="history-attention-list">
          <li v-for="row in historyAttention" :key="row.materialId">
            <strong>{{ row.materialName ?? '名称未知' }}</strong><span>素材 ID：{{ row.materialId }} · 统计日 {{ historyTop.selectedDate }}</span>
            <span v-if="row.lifetimeCoverage">覆盖：已有 {{ row.lifetimeCoverage.coveredDays }} / 应到 {{ row.lifetimeCoverage.expectedDays }} 天（累计范围，不是采集排期）</span>
            <span v-else>覆盖：已有 / 应到天数未知</span>
            <span>缺失日期首末：{{ historyMissingSpan(row) }}</span><span class="history-reason">{{ historyReason(row) }}</span>
          </li>
        </ul>
      </template>
    </details>

    <form class="date-form date-form--operations" @submit.prevent="confirmRange">
      <label for="operations-start"><span>开始日期</span><input id="operations-start" v-model="draft.start" type="date" min="1900-01-01" max="2200-12-31" /></label>
      <label for="operations-end"><span>结束日期</span><input id="operations-end" v-model="draft.end" type="date" min="1900-01-01" max="2200-12-31" /></label>
      <button type="submit" class="primary-button">{{ loading ? '正在查询，可更改日期重新查询' : '确认并刷新状态' }}</button>
    </form>
    <p class="module-note">选择开始和结束日期后点击“确认并刷新状态”，两端日期都包含在内，单次最多 366 天。这里只检查，不会自动补齐缺少的数据；也不会改变大屏的查询日期。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="loading" class="module-note" role="status">正在校验当前事实版本；旧查询结果已撤下。</p>

    <div class="operations-boundary">
      <strong>状态说明</strong>
      <p>“缺少当前事实”表示系统里没有当天可用的数据，不一定是采集失败；“完整空日”表示当天已确认没有投放，不是漏采。这里只查看数据，不判断定时采集是否按时运行。</p>
      <p>“已入库”表示数据已经保存到系统，但某些指标仍可能没提供；缺失指标显示“数据未知”，不能当作 0。记录更新时间也不一定是采集时间。</p>
      <details><summary>技术检查范围（供维护人员排查）</summary><p>仅校验数据库当前事实哈希，未重读源文件；上一版本和隔离版本只显示元数据，未逐行核验。</p></details>
    </div>

    <div v-if="result" class="operations-results" aria-live="polite">
      <div class="coverage" :class="hasAttention ? 'coverage--partial' : 'coverage--complete'">
        <div class="coverage__headline">
          <span class="signal" aria-hidden="true"></span>
          <strong :class="{ 'operations-warning': hasAttention }">{{ hasAttention ? '需维护人员检查' : hasSourceDateNotes ? '当前事实已入库，另有源日期观察记录' : '所选日期当前事实完整' }}</strong>
          <span>{{ result.range.start }} → {{ result.range.end }}</span>
        </div>
        <p class="coverage__complete-copy">只读状态查询，不代表采集运行正常或全部业务指标可计算。</p>
        <p v-if="hasSourceDateNotes" class="operations-source-date-note">源日期观察不等于计算失败：普通素材按截至查询日可见的有效源创建日期与最早事实统计日取早计算，不使用入库时间，不改写原始记录。</p>
      </div>
      <dl class="operations-summary">
        <div><dt>查询天数</dt><dd>{{ result.summary.queriedDays }}</dd></div>
        <div><dt>已入库（含完整空日）</dt><dd>{{ result.summary.availableDays }}</dd></div>
        <div><dt>其中完整空日</dt><dd>{{ result.summary.emptyDays }}</dd></div>
        <div :class="{ 'operations-warning': result.summary.missingDays > 0 }"><dt>缺少当前事实</dt><dd>{{ result.summary.missingDays }}</dd></div>
        <div :class="{ 'operations-warning': result.summary.isolatedDays > 0 }"><dt>存在隔离输入的日期</dt><dd>{{ result.summary.isolatedDays }}</dd></div>
      </dl>
      <p class="module-note">
        当前存储事实范围：{{ result.observedRange.first ?? '暂无已存事实' }}<template v-if="result.observedRange.last"> → {{ result.observedRange.last }}</template>。
        这不是采集应到范围；隔离日期数与入库/缺失状态独立，不应相加计算总天数。
      </p>

      <div class="table-shell operations-table-shell" tabindex="0" aria-label="每日入库状态表">
        <table class="business-table operations-table">
          <caption class="sr-only">当前事实版本、来源、数量、哈希、数据库记录更新时间和维护步骤</caption>
          <thead><tr><th>统计日</th><th>当前状态</th><th>当前来源</th><th>事实行数</th><th>当前版本 / SHA-256</th><th>上一版本</th><th>隔离输入</th><th>记录更新时间（非采集时间）</th><th>维护步骤</th></tr></thead>
          <tbody>
            <tr v-for="day in result.days" :key="day.date" :data-date="day.date">
              <td>{{ day.date }}</td>
              <td><span class="operations-state" :class="day.state === 'MISSING' ? 'operations-state--missing' : 'operations-state--complete'">{{ stateLabel(day) }}</span></td>
              <td>{{ day.currentVersion?.sourceType === 'MCP_AUTO' ? '自动采集（MCP_AUTO）' : day.currentVersion?.sourceType === 'MANUAL_HISTORY' ? '人工历史（MANUAL_HISTORY）' : '—（无当前版本）' }}</td>
              <td class="operations-row-count">{{ day.currentVersion ? day.currentVersion.rowCount.toLocaleString('zh-CN') : '—（数据未知）' }}</td>
              <td>
                <template v-if="day.currentVersion">
                  <strong>#{{ day.currentVersion.versionId }}</strong>
                  <code class="operations-hash">{{ day.currentVersion.contentHash }}</code>
                </template>
                <span v-else>—（无当前版本）</span>
              </td>
              <td>{{ day.previousVersionId === null ? '—（无）' : `#${day.previousVersionId}` }}</td>
              <td :class="{ 'operations-warning': day.isolatedVersionCount > 0 }">{{ day.isolatedVersionCount > 0 ? `${day.isolatedVersionCount} 版待人工检查，未生效` : '无隔离记录' }}</td>
              <td><time v-if="day.currentVersion" :datetime="day.currentVersion.updatedAt">{{ day.currentVersion.updatedAt }}</time><span v-else>—（无当前记录）</span></td>
              <td>
                <details class="operations-repair">
                  <summary>查看 {{ day.date }} 维护步骤（供技术人员操作）</summary>
                  <ol>
                    <li>核对当前项目与 {{ day.date }} 的输入。仅在本项目私有配置中定位账户和证据；不要改写原始 JSON 或旧目录。</li>
                    <li>如需复检本地源文件，由维护人员准备运行环境后使用以下入口模板（不是完整可执行命令）：<code>{{ preflightTemplate(day.date) }}</code>须替换配置路径占位符；页面不执行该命令，也不提供凭据。</li>
                    <li>检查预检回执：SOURCE_READY 仅表示本地输入通过校验，不表示已入库、已补齐或允许激活变更版本；INPUT_REJECTED 需处理校验问题；NO_CONFIGURED_INPUT 仅表示配置未列此日，不证明存档不存在。</li>
                    <li v-if="day.isolatedVersionCount > 0">该日有隔离输入：保持当前生效版，不自动激活、替换或删除。先由维护人员核对差异，再按明确授权处理。</li>
                    <li>只有取得本项目该账户、{{ day.date }} 单日写入授权后，维护人员才可使用受控导入入口模板（不是完整可执行命令）：<code class="operations-repair-command">{{ repairTemplate(day) }}</code>替换配置与预检回执路径、预检回执文件的 SHA-256；路径须在本项目 .local 内，不填写凭据。该模板仅绑定这一日期，不执行全历史配置。</li>
                    <li>模板中的写入前预期为本行当前内容哈希；缺失日才使用 MISSING，完整空日仍使用其哈希。先刷新并核对当前版本，数据库若已变化会拒绝；不要为绕过拒绝而猜填新哈希。SOURCE_READY 不授权写入，也不算导入成功。</li>
                    <li>执行后由维护人员核对 CLI 回执：只有 CURRENT_VERIFIED 表示通过独立新只读数据库会话的当前版本全量回读；STORED_NOT_CURRENT 表示上一版或隔离版，不是当前发布成功；COMMITTED_UNVERIFIED 表示事务结果已记录但独立后验未通过，不能宣称成功或自动回滚。</li>
                    <li>REJECTED 表示已知条件或事务拒绝，先查固定错误码，不据此把所有数据库故障都当作已回滚；EXECUTION_UNCERTAIN 表示执行结果不确定。隔离、未验、不确定或未结束的 RUNNING 回执均须排查，不自动重试、不自动激活或删除版本。</li>
                    <li>取得 CURRENT_VERIFIED 回执后，手动点击本页“确认并刷新状态”，核对 {{ day.date }} 的当前内容哈希与该回执 contentHash 一致。页面展示的是刷新时的当前事实，不是任务执行回执，绿灯不能替代 CLI 独立后验。</li>
                    <li>仍需真实补采时另走明确授权流程。本数据状态区域不执行补采、导入或配置写入，也不读取修复任务回执；配置维护见独立配置区。</li>
                  </ol>
                  <p>预检回执位置：<code>.local/repair-preflight/&lt;唯一运行目录&gt;/preflight.json</code>。源文件预检不连接数据库。</p>
                  <p>受控导入回执位置：<code>.local/repair-runs/&lt;唯一运行目录&gt;/receipt.json</code>。本页不会因展示此模板而执行导入或判断修复成功。</p>
                </details>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <details class="operations-missing-details"><summary>源字段观察与缺失影响明细（{{ result.missingDetailTotal }} 项）</summary><p v-if="result.missingDetailsTruncated" class="module-note operations-warning">仅显示前 {{ result.missingDetails.length }} 项，尚不能据此排除其余缺失；请缩小日期范围查看。</p><div class="table-shell"><table class="business-table"><thead><tr><th>日期</th><th>素材</th><th>字段</th><th>原因</th><th>受影响指标</th><th>来源版本</th></tr></thead><tbody><tr v-for="(entry,index) in result.missingDetails ?? []" :key="index"><td>{{ entry.date }}</td><td>{{ entry.materialId ?? '账户整日' }}</td><td>{{ detailLabel(entry.field) }}</td><td>{{ detailLabel(entry.reason) }}</td><td>{{ entry.affectedMetrics.length ? entry.affectedMetrics.map(detailLabel).join('、') : '无直接影响' }}</td><td>{{ entry.sourceVersionId === null ? '无当前版本' : `#${entry.sourceVersionId}` }}</td></tr><tr v-if="!result.missingDetails?.length"><td colspan="6">本次检查未发现所检查字段缺失。</td></tr></tbody></table></div></details>
    </div>
    </section>
  </details>
</template>

<style scoped>
.operations-auxiliary-summary { cursor: pointer; color: #425864; font-weight: 600; padding: .35rem 0; }
.operations-auxiliary-summary small { margin-left: 1rem; color: #71818a; font-weight: 400; }
.operations-panel[open] > section { margin-top: 1.25rem; }
.operations-history-coverage { margin: 1rem 0; padding: .8rem 1rem; border: 1px solid #dce5eb; border-radius: 10px; }
.operations-history-coverage summary { cursor: pointer; font-weight: 600; color: #425864; }
.history-scope { font-size: .85rem; color: #425864; }
.history-attention-list { list-style: none; padding: 0; margin: .75rem 0 0; }
.history-attention-list li { display: grid; gap: .35rem; padding: .7rem 0; border-top: 1px solid #e4e9ee; overflow-wrap: anywhere; }
.history-attention-list span { font-size: .8rem; color: #536977; }
.history-attention-list .history-reason { color: #9b4b34; }
@media (max-width: 720px) { .operations-auxiliary-summary small { display: block; margin: .5rem 0 0; } }
</style>
