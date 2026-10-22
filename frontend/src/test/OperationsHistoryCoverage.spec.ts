import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import OperationsPanel from '../components/OperationsPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { TopResult } from '../domain/dashboard'
import type { OperationsDataAdapter } from '../adapters/operationsAdapter'

async function completeTop(): Promise<TopResult> {
  const top = await new SyntheticMockAdapter().fetchTop('sample-project', '2026-10-12', 1)
  top.rows = top.rows.slice(0, 1)
  top.rows[0].lifetimeCoverage = { state: 'COMPLETE', coveredDays: 10, expectedDays: 10, missingDates: [] }
  top.rows[0].lifetimeSpend = { status: 'VALID', value: 100, partial: false, partialReasons: [] }
  top.rows[0].lifetimeRoi = { status: 'VALID', value: 2, partial: false, partialReasons: [] }
  return top
}
function panel(top: TopResult | null = null) {
  const fetchDays = vi.fn()
  const adapter: OperationsDataAdapter = { evidenceKind: 'SYNTHETIC', fetchDays }
  const wrapper = mount(OperationsPanel, { props: { projectId: 'sample-project', projectName: '合成项目', active: false, adapter, historyTopResult: top } })
  return { wrapper, fetchDays }
}

describe('operations current TOP accumulated-history coverage', () => {
  it('prompts for a current TOP query without triggering an additional request', async () => {
    const { wrapper, fetchDays } = panel()
    expect(wrapper.get('.operations-history-coverage').text()).toContain('请先查看当前项目的每日 TOP')
    await wrapper.get('.operations-history-coverage summary').trigger('click')
    expect(fetchDays).not.toHaveBeenCalled()
    expect(wrapper.get('.operations-history-coverage').text()).toContain('不是全库审计')
  })
  it('shows selected scope, coverage counts and missing first/last without implying consecutive dates', async () => {
    const top = await completeTop()
    top.rows[0].lifetimeCoverage = { state: 'PARTIAL', coveredDays: 8, expectedDays: 10, missingDates: ['2026-10-05', '2026-10-02'] }
    top.rows[0].lifetimeSpend.partial = true
    top.rows[0].lifetimeSpend.partialReasons = ['MISSING_ACCOUNT_DAYS']
    const { wrapper, fetchDays } = panel(top)
    const block = wrapper.get('.operations-history-coverage').text()
    expect(block).toContain('统计日 2026-10-12')
    expect(block).toContain('当前 TOP 1 条')
    expect(block).toContain('已有 8 / 应到 10 天')
    expect(block).toContain('2026-10-02 — 2026-10-05')
    expect(block).toContain('不代表连续缺失')
    expect(block).toContain(top.rows[0].materialId)
    expect(fetchDays).not.toHaveBeenCalled()
  })
  it('retains missing-field diagnostics even when account-day coverage is complete', async () => {
    const top = await completeTop()
    top.rows[0].lifetimeRoi = { status: 'VALID', value: 2, partial: true, partialReasons: ['MISSING_FIELD'] }
    const { wrapper } = panel(top)
    expect(wrapper.get('.operations-history-coverage').text()).toContain('来源字段缺失，原因待核')
    expect(wrapper.findAll('.history-attention-list li')).toHaveLength(1)
  })
  it('reports a scoped clean result but rejects stale project and unavailable rankings', async () => {
    const top = await completeTop()
    const { wrapper } = panel(top)
    expect(wrapper.get('.operations-history-coverage').text()).toContain('当前 TOP 无累计缺失')
    await wrapper.setProps({ projectId: 'different-project' })
    expect(wrapper.get('.operations-history-coverage').text()).toContain('请先查看当前项目')
    await wrapper.setProps({ projectId: 'sample-project', historyTopResult: { ...top, rankingState: 'UNAVAILABLE', rows: [] } })
    expect(wrapper.get('.operations-history-coverage').text()).toContain('无法判断累计历史覆盖')
    expect(wrapper.get('.operations-history-coverage').text()).not.toContain('当前 TOP 无累计缺失')
  })
})
