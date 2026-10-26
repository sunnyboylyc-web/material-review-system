<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

defineProps<{ disabled?: boolean }>()
const selected = ref<'dimension' | 'summary' | null>(null)
const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()
let returnFocus: HTMLElement | null = null
const dimension = [
  ['accountId', '项目存储锚点', '按稳定抖音号对应的存储空间隔离；不是当前可变广告账户。', 'SYNTHETIC_STORAGE'],
  ['materialId', '素材 ID', '和存储锚点一起定位素材，名称不作为主键。', 'SYNTHETIC_MATERIAL_01'],
  ['observed', '变更观察日期', '截至查询锚点二分查找可见版本，避免使用未来名称或日期。', '2026-10-20'],
  ['name', '素材名称', '截至锚点可见的最近一个非空名称，用于类型识别。', '原创-AI-合成示例'],
  ['creation.status / date / reason', '首次投放时间及状态', '普通素材取截至锚点的有效源创建日期与事实统计日期的最早值；集合素材不适用。', 'VERIFIED / 2026-10-15 / null'],
  ['firstSpend.status / date', '首次正消耗时间及核验状态', '与首次投放时间不同；结合创建日、覆盖完整性和未知消耗验证。', 'VERIFIED / 2026-10-16'],
  ['firstSpend.earliestObservedDate', '最早观察到正消耗日期', '保留已观察到的日期，不把待核状态伪装为已验证。', '2026-10-16'],
]
const summary = [
  ['accountId / materialId / YearMonth', '汇总键', '每个存储锚点、每条素材、每个月各自汇总，不跨抖音号合并。', 'SYNTHETIC_STORAGE / SYNTHETIC_MATERIAL_01 / 2026-10'],
  ['sums[5]', '五个基础值的已知合计', '顺序为消耗、成交金额、展现次数、点击次数、成交订单数；仅计正消耗资格行。', '[100, 300, 10000, 200, 10]'],
  ['known[5]', '各基础字段有效记录数', '与 sums 同顺序；区分已知 0 和数据未知。', '[2, 2, 2, 2, 2]'],
  ['pairNumerators[5]', '比率有效配对分子合计', 'ROI、点击率、转化率、CPM、CPC 的分子；只累计分子分母同时有效的记录。', '[300, 200, 10, 100, 100]'],
  ['pairDenominators[5]', '比率有效配对分母合计', '与分子一一配对；CPM 展示时再乘 1000，不平均每日比率。', '[100, 10000, 200, 10000, 200]'],
  ['paired[5]', '各比率的有效配对记录数', '判断比率能否计算，保留未知与零分母的区别。', '[2, 2, 2, 2, 2]'],
  ['included', '可计数素材 ID 集合', '有效名称不含 ZZ 的正消耗素材；查询合并时按 ID 去重。', '{SYNTHETIC_MATERIAL_01}'],
  ['uncertain', '名称待核素材 ID 集合', '保留正消耗但名称未知的素材，不直接按 0 计数。', '{}'],
  ['unknownSpend', '是否出现未知消耗', '未知消耗不当成 0，影响结果完整性标记。', 'false'],
  ['eligibleCount', '正消耗资格记录数', '这是事实行数，不能当成去重素材数量。', '2'],
]
const rows = computed(() => selected.value === 'dimension' ? dimension : summary)
const title = computed(() => selected.value === 'dimension' ? '维度表' : '汇总表')
// A business projection of the actual index; ratios and joined names are display columns.
const dimensionHeaders = ['项目', '素材编号', '素材名称', '首次投放时间', '首次正消耗时间', '日期确认状态', '资料截至日期']
const summaryHeaders = ['项目', '素材编号', '素材名称', '汇总月份', '消耗（元）', '成交金额（元）', '展现次数', '点击次数', '成交订单数', 'ROI', '点击率', '转化率', 'CPM', 'CPC']
const exampleNames = ['原创-示例素材01', '混剪-示例素材02', 'AI商单-示例素材03']
const dimensionExamples = exampleNames.map((name, i) => ['示例项目', `示例-00${i+1}`, name, `2026-10-${15+i}`, `2026-10-${16+i}`, '已确认', '2026-10-20'])
const summaryExamples = exampleNames.map((name, i) => ['示例项目', `示例-00${i+1}`, name, '2026-10',
  ...[['1,000.00','3,000.00','100,000','2,000','100','3.00','2.00%','5.00%','10.00','0.50'],
    ['600.00','1,200.00','60,000','1,200','36','2.00','2.00%','3.00%','10.00','0.50'],
    ['400.00','1,000.00','40,000','800','24','2.50','2.00%','3.00%','10.00','0.50']][i]])
const sheetHeaders = computed(() => selected.value === 'dimension' ? dimensionHeaders : summaryHeaders)
const sheetRows = computed(() => selected.value === 'dimension' ? dimensionExamples : summaryExamples)
const letter = (index: number) => String.fromCharCode(65+index)
async function open(kind: 'dimension' | 'summary') {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  selected.value = kind
  await nextTick()
  dialog.value?.showModal?.()
  closeButton.value?.focus()
}
function close() { dialog.value?.close?.(); selected.value = null; returnFocus?.focus() }
onBeforeUnmount(() => dialog.value?.close?.())
</script>

<template>
  <div class="calculation-schema-actions" aria-label="计算结构只读查看">
    <button type="button" :disabled="disabled" aria-haspopup="dialog" @click="open('dimension')">维度表 <small>只读</small></button>
    <button type="button" :disabled="disabled" aria-haspopup="dialog" @click="open('summary')">汇总表 <small>只读</small></button>
  </div>
  <Teleport to="body">
    <dialog v-if="selected" ref="dialog" class="calculation-schema-dialog pr-dialog" aria-labelledby="calculation-schema-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
      <header class="pr-dialog-head"><div><h3 id="calculation-schema-title">{{ title }} · 只读参考</h3><p>像查看 Excel 一样了解数据组成。下方全部为虚构示例。</p></div><button ref="closeButton" type="button" aria-label="关闭结构参考" @click="close">×</button></header>
      <div class="pr-dialog-body">
        <p v-if="selected === 'dimension'" class="schema-reference-note"><b>一行代表一个项目里的一条素材。</b>这里记录“它是谁、叫什么、何时开始投放”，为素材分类、新老判断和测试周期筛选提供依据。同一个素材在不同项目中分别记录。</p>
        <p v-else class="schema-reference-note"><b>一行代表一个项目、一条素材、一个月份的汇总。</b>它帮助快速计算消耗、成交和各项比率；不是每日明细，也不是整个项目的总计。</p>
        <div class="schema-sheet-bar"><b>{{ title }}</b><span>虚构示例 · 只读</span><small>可横向滚动查看全部列</small></div>
        <div class="schema-sheet-scroll"><table class="schema-sheet" :aria-label="`${title}虚构样例`"><thead><tr class="sheet-letters"><th aria-label="行号"></th><th v-for="(header,index) in sheetHeaders" :key="header" scope="col">{{ letter(index) }}</th></tr><tr class="sheet-headers"><th scope="row">1</th><th v-for="header in sheetHeaders" :key="header" scope="col">{{ header }}</th></tr></thead><tbody><tr v-for="(row,index) in sheetRows" :key="index"><th scope="row">{{ index+2 }}</th><td v-for="(value,column) in row" :key="column">{{ value }}</td></tr></tbody></table></div>
        <div class="schema-business-help"><p v-if="selected === 'dimension'">首次投放时间用于筛选“这批素材是哪段时间开始投放的”；首次正消耗时间表示首次出现消耗的时间，两者不同。日期缺失会显示“数据未知”，素材集合显示“不适用”，不会补造日期。</p><p v-else>消耗、成交金额及次数为累计值。ROI＝成交金额÷消耗；点击率＝点击次数÷展现次数；转化率＝成交订单数÷点击次数；CPM＝消耗÷展现次数×1000；CPC＝消耗÷点击次数。实际计算只配对有效记录，不平均每日比率，数据未知与分母为 0 分开处理。</p><p>{{ selected === 'dimension' ? '分类时读取素材名称，筛测试周期时读取首次投放时间。' : '素材名称来自维度资料，ROI 等比率由汇总值计算，是便于阅读的展示列。完整月份复用汇总，首尾不满整月时读取相应日期的明细。' }} 此处仅作结构展示，不读取业务记录。</p></div>
        <details class="schema-technical"><summary>维护参考：实际字段与存储方式</summary>
        <p class="module-note">当前实际实现为内存计算索引，不是独立 MySQL 业务表。下表展开实际字段，便于维护人员核对；上方为中文业务展示。</p>
        <div class="table-shell"><table class="business-table schema-reference-table"><thead><tr><th>实际字段 / 外层键</th><th>业务含义</th><th>计算用途与口径</th><th>虚构示例</th></tr></thead><tbody><tr v-for="row in rows" :key="row[0]"><td><code>{{ row[0] }}</code></td><th scope="row">{{ row[1] }}</th><td>{{ row[2] }}</td><td>{{ row[3] }}</td></tr></tbody></table></div>
        <div class="schema-reference-footer"><p>原始事实保留在版本化事实表中。维度和汇总可重建；新增辅助计算不应覆盖原始事实。结构依据：DashboardCalculator.PreparedSnapshot / MaterialSeries / DimensionPoint / Summary。</p></div>
        </details>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.calculation-schema-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.calculation-schema-actions button{border:1px solid var(--line,#35384d);background:var(--surface,#181b2b);color:var(--ink,#e8e5dc);border-radius:8px;padding:9px 12px;cursor:pointer;white-space:nowrap}.calculation-schema-actions small{font-size:10px;color:var(--muted,#999db0);margin-left:5px}.calculation-schema-actions button:disabled{opacity:.5;cursor:wait}.calculation-schema-dialog{width:min(1220px,94vw);max-height:88vh;color:var(--ink,#e8e5dc);background:var(--surface,#111522);border:1px solid var(--line,#35384d);border-radius:16px;padding:0}.calculation-schema-dialog::backdrop{background:#000a}.schema-reference-note{padding:12px 16px;background:#d5bd7920;border:1px solid #d5bd7940;border-radius:9px;line-height:1.7}.schema-reference-table{min-width:850px;width:100%}.schema-reference-table :is(th,td){padding:14px 16px;vertical-align:top;text-align:left;line-height:1.65}.schema-reference-table code{font-size:12px;color:#cdbd8d;overflow-wrap:anywhere}.schema-reference-table td:last-child{font-size:12px;overflow-wrap:anywhere;max-width:220px}.schema-reference-footer{margin-top:20px;line-height:1.75;color:var(--muted,#b0b3c3)}.schema-reference-footer h4{color:var(--ink,#eee)}
.schema-sheet-bar{margin-top:22px;display:flex;align-items:center;gap:16px;background:#1b664b;color:#fff;padding:12px 16px;border-radius:8px 8px 0 0}.schema-sheet-bar span{font-size:12px;opacity:.9}.schema-sheet-bar small{margin-left:auto;opacity:.8}.schema-sheet-scroll{overflow:auto;max-width:100%;border:1px solid #c1cec7;border-radius:0 0 8px 8px}.schema-sheet{border-collapse:separate;border-spacing:0;width:max-content;min-width:100%;font-family:system-ui,sans-serif;background:#fff;color:#273b32;font-size:13px}.schema-sheet :is(th,td){padding:14px 18px;white-space:nowrap;border-right:1px solid #d9e1dc;border-bottom:1px solid #d9e1dc;text-align:left}.schema-sheet .sheet-letters th{background:#e9eeeb;text-align:center;color:#67786e;padding:7px 18px;font-size:12px;font-weight:500}.schema-sheet .sheet-headers th{background:#e3f0e9;color:#21553c;font-weight:650}.schema-sheet tr>:first-child{position:sticky;left:0;z-index:1;background:#edf1ee;text-align:center;color:#67786e;min-width:42px;padding-left:12px;padding-right:12px}.schema-sheet tbody tr:hover td{background:#f0f8f3}.schema-business-help{line-height:1.85;font-size:14px;color:var(--muted,#b0b3c3);margin:18px 0}.schema-business-help p+p{margin-top:8px}.schema-technical{border-top:1px solid #35384d;margin-top:24px;padding-top:16px}.schema-technical summary{cursor:pointer;color:#a9b6b0;margin-bottom:16px}
</style>
