import DateRangePicker from '../components/DateRangePicker.vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import OperationsPanel from '../components/OperationsPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import type { OperationsDataAdapter } from '../adapters/operationsAdapter'
import type { DateRange } from '../domain/dashboard'
import type { OperationsResult } from '../domain/operations'
import { operationsFixture } from './operationsFixture'
import { shanghaiIsoDate, shiftIsoDate } from '../time/shanghai'

function adapter(): OperationsDataAdapter & { fetchDays: ReturnType<typeof vi.fn<(projectId: string, range: DateRange) => Promise<OperationsResult>>> } {
  return { evidenceKind: 'SYNTHETIC', fetchDays: vi.fn(async (projectId, range) => operationsFixture(projectId, range)) }
}
function gate<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail })
  return { promise, resolve, reject }
}

describe('read-only operations panel', () => {
  function completeFixture(projectId: string, range: DateRange): OperationsResult {
    const data = operationsFixture(projectId, range)
    data.days = data.days.map(day => ({
      ...day,
      state: 'AVAILABLE',
      currentVersion: { versionId: 10, sourceType: 'MCP_AUTO', contentHash: 'a'.repeat(64), rowCount: 2, updatedAt: '2026-10-13T00:00:00Z' },
      isolatedVersionCount: 0,
    }))
    data.summary = { queriedDays: data.days.length, availableDays: data.days.length, emptyDays: 0, missingDays: 0, isolatedDays: 0 }
    return data
  }

  it.each(['MISSING_CREATION_DATE', 'CONFLICTING_CREATION_DATE', 'CREATION_AFTER_OBSERVATION'])(
    'shows %s without affected metrics as a source-date observation, retaining the raw detail', async reason => {
      const source = adapter()
      source.fetchDays.mockImplementation(async (project, range) => {
        const data = completeFixture(project, range)
        data.missingDetails = [{ date: range.start, materialId: 'synthetic-material', field: 'createdDate', reason, affectedMetrics: [], sourceVersionId: 10 }]
        data.missingDetailTotal = 1
        return data
      })
      const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
      await flushPromises()
      expect(wrapper.get('.coverage__headline').text()).toContain('当前事实已入库，另有源日期观察记录')
      expect(wrapper.get('.coverage').classes()).toContain('coverage--complete')
      expect(wrapper.find('.coverage__headline .operations-warning').exists()).toBe(false)
      expect(wrapper.get('.operations-source-date-note').text()).toContain('截至查询日可见的有效源创建日期与最早事实统计日取早计算')
      expect(wrapper.get('.operations-source-date-note').text()).toContain('不使用入库时间，不改写原始记录')
      const detail = wrapper.get('.operations-missing-details')
      expect(detail.get('summary').text()).toContain('源字段观察与缺失影响明细（1 项）')
      expect(detail.findAll('tbody tr')).toHaveLength(1)
      expect(detail.text()).toContain('synthetic-material')
      expect(detail.text()).toContain('无直接影响')
      expect(detail.text()).toContain('#10')
      expect(source.fetchDays).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    },
  )

  it.each([
    { name: 'date observations affecting a metric', field: 'createdDate', reason: 'MISSING_CREATION_DATE', affectedMetrics: ['cohort'] },
    { name: 'missing metric input', field: 'gmv', reason: 'FIELD_NOT_RECORDED', affectedMetrics: ['roi'] },
    { name: 'missing input with no current metric impact', field: 'gmv', reason: 'FIELD_NOT_RECORDED', affectedMetrics: [] },
    { name: 'unknown reason', field: 'createdDate', reason: 'UNKNOWN_SOURCE_REASON', affectedMetrics: [] },
    { name: 'unknown field with a date reason', field: 'unknownField', reason: 'MISSING_CREATION_DATE', affectedMetrics: [] },
  ])('keeps maintenance attention for $name', async ({ field, reason, affectedMetrics }) => {
    const source = adapter()
    source.fetchDays.mockImplementation(async (project, range) => {
      const data = completeFixture(project, range)
      data.missingDetails = [
        { date: range.start, materialId: 'synthetic-date', field: 'createdDate', reason: 'CREATION_AFTER_OBSERVATION', affectedMetrics: [], sourceVersionId: 10 },
        { date: range.start, materialId: 'synthetic-other', field, reason, affectedMetrics, sourceVersionId: 10 },
      ]
      data.missingDetailTotal = data.missingDetails.length
      return data
    })
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    expect(wrapper.get('.coverage__headline').text()).toContain('需维护人员检查')
    expect(wrapper.get('.coverage').classes()).toContain('coverage--partial')
    expect(wrapper.get('.coverage__headline strong').classes()).toContain('operations-warning')
    expect(wrapper.find('.operations-source-date-note').exists()).toBe(true)
    expect(wrapper.findAll('.operations-missing-details tbody tr')).toHaveLength(2)
    wrapper.unmount()
  })

  it.each(['missing', 'isolated', 'truncated'] as const)('keeps maintenance attention when source-date observations coexist with %s evidence', async kind => {
    const source = adapter()
    source.fetchDays.mockImplementation(async (project, range) => {
      const data = completeFixture(project, range)
      data.missingDetails = [{ date: range.start, materialId: 'synthetic-material', field: 'creationDate', reason: 'MISSING_CREATION_DATE', affectedMetrics: [], sourceVersionId: 10 }]
      data.missingDetailTotal = kind === 'truncated' ? 1001 : 1
      data.missingDetailsTruncated = kind === 'truncated'
      if (kind === 'missing') {
        data.days[1] = { ...data.days[1], state: 'MISSING', currentVersion: null }
        data.summary.missingDays = 1
        data.summary.availableDays -= 1
      }
      if (kind === 'isolated') {
        data.days[1].isolatedVersionCount = 1
        data.summary.isolatedDays = 1
      }
      return data
    })
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    expect(wrapper.get('.coverage__headline').text()).toContain('需维护人员检查')
    expect(wrapper.get('.coverage').classes()).toContain('coverage--partial')
    expect(wrapper.find('.operations-source-date-note').exists()).toBe(true)
    if (kind === 'truncated') {
      expect(wrapper.get('.operations-missing-details .operations-warning').text()).toContain('尚不能据此排除其余缺失')
      expect(wrapper.get('.operations-missing-details').text()).toContain('请缩小日期范围查看')
    }
    wrapper.unmount()
  })

  it('reports complete current facts without inventing a source-date observation', async () => {
    const source = adapter()
    source.fetchDays.mockImplementation(async (project, range) => completeFixture(project, range))
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    expect(wrapper.get('.coverage__headline').text()).toContain('所选日期当前事实完整')
    expect(wrapper.get('.coverage').classes()).toContain('coverage--complete')
    expect(wrapper.find('.operations-source-date-note').exists()).toBe(false)
    expect(wrapper.get('.coverage__complete-copy').text()).toContain('不代表采集运行正常或全部业务指标可计算')
    wrapper.unmount()
  })

  it('defaults to Shanghai yesterday and previous six days with truthful missing/empty/isolated states', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    const end = shanghaiIsoDate(new Date(), -1)
    expect(wrapper.get('details.operations-panel').attributes('open')).toBeUndefined()
    expect(wrapper.get('.operations-auxiliary-summary').text()).toContain('辅助检查')
    const start = shiftIsoDate(end, -6)
    expect(source.fetchDays).toHaveBeenCalledExactlyOnceWith('synthetic-project', { start, end })
    expect(wrapper.text()).toContain('运维合成测试数据')
    expect(wrapper.text()).toContain('不判断定时采集是否按时运行')
    expect(wrapper.text()).toContain('未重读源文件')
    expect(wrapper.text()).toContain('某些指标仍可能没提供')
    expect(wrapper.text()).toContain('记录更新时间也不一定是采集时间')
    const rows = wrapper.findAll('tbody tr')
    expect(rows[1].get('.operations-row-count').text()).toBe('0')
    expect(rows[1].get('.operations-state').classes()).toContain('operations-state--complete')
    expect(rows[2].get('.operations-row-count').text()).toBe('—（数据未知）')
    expect(rows[2].get('.operations-state').classes()).toContain('operations-state--missing')
    expect(rows[3].text()).toContain('2 版待人工检查，未生效')
    expect(rows[3].find('.operations-warning').exists()).toBe(true)
    expect(rows[0].text()).toContain('cn.datadashboard.local.LocalFactPreflightMain <项目 .local 内私有配置路径>')
    expect(rows[0].text()).toContain('SOURCE_READY 仅表示本地输入通过校验，不表示已入库')
    expect(rows[0].text()).toContain('NO_CONFIGURED_INPUT 仅表示配置未列此日')
    expect(wrapper.findAll('button')).toHaveLength(1)
  })

  it('refreshes only after confirmation and clears old results when a request fails or mismatches', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    await wrapper.get('#operations-start').setValue('2026-10-01')
    await wrapper.get('#operations-end').setValue('2026-10-04')
    expect(source.fetchDays).toHaveBeenCalledTimes(1)
    source.fetchDays.mockRejectedValueOnce(new Error('合成运维接口失败'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.operations-results').exists()).toBe(false)
    expect(wrapper.text()).toContain('合成运维接口失败')
    source.fetchDays.mockResolvedValueOnce(operationsFixture('other', { start: '2026-10-01', end: '2026-10-04' }))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.operations-results').exists()).toBe(false)
    expect(wrapper.text()).toContain('项目或日期与当前查询不一致')
  })

  it('binds controlled single-day repair templates to each date and exact current expectation', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    const start = source.fetchDays.mock.calls[0][1].start
    const command = (date: string, expectation: string) => `cn.datadashboard.local.LocalFactRepairMain "<项目 .local 内私有配置路径>" ${date} "<SOURCE_READY 预检回执路径>" "<预检回执 SHA-256>" ${expectation} --apply`
    expect(rows[0].get('.operations-repair-command').text()).toBe(command(start, 'a'.repeat(64)))
    expect(rows[1].get('.operations-repair-command').text()).toBe(command(shiftIsoDate(start, 1), 'a'.repeat(64)))
    expect(rows[1].get('.operations-row-count').text()).toBe('0')
    expect(rows[2].get('.operations-repair-command').text()).toBe(command(shiftIsoDate(start, 2), 'MISSING'))
    expect(rows[2].get('.operations-row-count').text()).toBe('—（数据未知）')
    expect(wrapper.text()).toContain('完整空日仍使用其哈希')
    expect(wrapper.text()).toContain('不要为绕过拒绝而猜填新哈希')
    expect(wrapper.text()).not.toContain('C:\\Users\\')
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(source.fetchDays).toHaveBeenCalledTimes(1)
  })

  it('explains repair outcomes without turning source readiness or a current green day into job success', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    const instructions = wrapper.findAll('.operations-repair')[0].text()
    expect(instructions).toContain('SOURCE_READY 不授权写入，也不算导入成功')
    expect(instructions).toContain('CURRENT_VERIFIED 表示通过独立新只读数据库会话的当前版本全量回读')
    expect(instructions).toContain('STORED_NOT_CURRENT 表示上一版或隔离版，不是当前发布成功')
    expect(instructions).toContain('COMMITTED_UNVERIFIED 表示事务结果已记录但独立后验未通过')
    expect(instructions).toContain('EXECUTION_UNCERTAIN 表示执行结果不确定')
    expect(instructions).toContain('不自动重试')
    expect(instructions).toContain('REJECTED 表示已知条件或事务拒绝')
    expect(instructions).toContain('不是任务执行回执，绿灯不能替代 CLI 独立后验')
    expect(instructions).toContain('当前内容哈希与该回执 contentHash 一致')
    expect(instructions).toContain('.local/repair-runs/<唯一运行目录>/receipt.json')
    expect(wrapper.findAll('a')).toHaveLength(0)
  })

  it('ignores slow previous ranges and project responses after newer queries', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    const slow = gate<OperationsResult>()
    source.fetchDays.mockReturnValueOnce(slow.promise)
    await wrapper.get('#operations-start').setValue('2026-10-01')
    await wrapper.get('#operations-end').setValue('2026-10-04')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.find('.operations-results').exists()).toBe(false)
    await wrapper.get('#operations-start').setValue('2026-10-02')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-date="2026-10-01"]').exists()).toBe(false)
    slow.resolve(operationsFixture('synthetic-project', { start: '2026-10-01', end: '2026-10-04' }))
    await flushPromises()
    expect(wrapper.find('[data-date="2026-10-01"]').exists()).toBe(false)

    const projectSlow = gate<OperationsResult>()
    source.fetchDays.mockReturnValueOnce(projectSlow.promise)
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ projectId: 'other-project', projectName: '另一合成项目' })
    await flushPromises()
    projectSlow.reject(new Error('旧项目失败不应覆盖当前结果'))
    await flushPromises()
    expect(wrapper.find('.operations-results').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('旧项目失败')
    expect(source.fetchDays).toHaveBeenLastCalledWith('other-project', { start: '2026-10-02', end: '2026-10-04' })
  })

  it('rejects invalid/oversized range and does not let pending results restore old content', async () => {
    const source = adapter()
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    await flushPromises()
    const slow = gate<OperationsResult>()
    source.fetchDays.mockReturnValueOnce(slow.promise)
    await wrapper.get('form').trigger('submit')
    const pendingRange = source.fetchDays.mock.calls.at(-1)![1]
    const calls = source.fetchDays.mock.calls.length
    await wrapper.get('#operations-start').setValue('2024-01-01')
    await wrapper.get('#operations-end').setValue('2025-01-01')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('最多 366 天')
    expect(source.fetchDays).toHaveBeenCalledTimes(calls)
    slow.resolve(operationsFixture('synthetic-project', pendingRange))
    await flushPromises()
    expect(wrapper.find('.operations-results').exists()).toBe(false)
    await wrapper.get('#operations-start').setValue('2026-10-05')
    await wrapper.get('#operations-end').setValue('2026-10-04')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('开始日期不能晚于结束日期')
  })

  it('invalidates in-flight requests while hidden and refreshes the retained operations range on return', async () => {
    const source = adapter()
    const slow = gate<OperationsResult>()
    source.fetchDays.mockReturnValueOnce(slow.promise)
    const wrapper = mount(OperationsPanel, { props: { projectId: 'synthetic-project', projectName: '合成项目', adapter: source } })
    const requestRange = source.fetchDays.mock.calls[0][1]
    await wrapper.setProps({ active: false })
    slow.resolve(operationsFixture('synthetic-project', requestRange))
    await flushPromises()
    expect(wrapper.find('.operations-results').exists()).toBe(false)
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(source.fetchDays).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.operations-results').exists()).toBe(true)
  })

  it('toggles within the existing app without altering project, business dates or independent limits', async () => {
    const source = adapter()
    const business = new SyntheticMockAdapter([{ id: 'sampleproject', name: '示例项目', enabled: true }])
    const review = vi.spyOn(business, 'fetchReview')
    const top = vi.spyOn(business, 'fetchTop')
    const wrapper = mount(App, { attachTo: document.body, props: { adapter: business, operationsAdapter: source } })
    await flushPromises()
    await wrapper.findAll('.global-nav button')[2].trigger('click')
    await setRangeField(wrapper, 'current', 'start', '2026-09-01')
    await setRangeField(wrapper, 'current', 'end', '2026-09-05')
    await setRangeField(wrapper, 'previous', 'start', '2026-08-01')
    await setRangeField(wrapper, 'previous', 'end', '2026-08-03')
    await wrapper.get('.date-form--review').trigger('submit')
    await wrapper.get('#top-date').setValue('2026-10-08')
    await wrapper.get('.date-form--top').trigger('submit')
    await wrapper.get('[aria-label="增加 TOP 显示数量"]').trigger('click')
    await flushPromises()
    review.mockClear()
    top.mockClear()
    expect(source.fetchDays).not.toHaveBeenCalled()
    await wrapper.get('.operations-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.get('.operations-toggle').text()).toBe('返回大屏')
    expect(source.fetchDays).not.toHaveBeenCalled()
    await wrapper.findAll('.operations-nav button').find(button => button.text().includes('历史数据'))!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.operations-panel').isVisible()).toBe(true)
    expect(wrapper.get('[aria-labelledby="review-title"]').isVisible()).toBe(false)
    expect(wrapper.get('.workspace-header h2').text()).toBe('示例项目')
    expect(source.fetchDays).toHaveBeenCalledTimes(1)
    await wrapper.get('.operations-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.get('.operations-toggle').text()).toBe('数据运维')
    expect(wrapper.get('[aria-labelledby="review-title"]').isVisible()).toBe(true)
    expect(wrapper.findAllComponents(DateRangePicker)[0].props('modelValue').start).toBe('2026-09-01')
    expect(wrapper.findAllComponents(DateRangePicker)[0].props('modelValue').end).toBe('2026-09-05')
    expect(wrapper.findAllComponents(DateRangePicker)[1].props('modelValue').start).toBe('2026-08-01')
    expect(wrapper.findAllComponents(DateRangePicker)[1].props('modelValue').end).toBe('2026-08-03')
    expect((wrapper.get('#top-date').element as HTMLInputElement).value).toBe('2026-10-08')
    expect((wrapper.get('.standalone-top-limit__value').element as HTMLSelectElement).value).toBe('11')
    expect(review).not.toHaveBeenCalled()
    expect(top).not.toHaveBeenCalled()
  })
})


async function setRangeField(wrapper: ReturnType<typeof mount>, period: 'current'|'previous', part:'start'|'end', value:string) {
 const picker=wrapper.findAllComponents(DateRangePicker)[period==='current'?0:1]
 const range={...picker.props('modelValue'),[part]:value}
 picker.vm.$emit('update:modelValue',range)
 await wrapper.vm.$nextTick()
 picker.vm.$emit('confirm',range)
 await wrapper.vm.$nextTick()
}
