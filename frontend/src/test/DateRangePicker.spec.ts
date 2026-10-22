import {flushPromises,mount} from '@vue/test-utils'
import {describe,expect,it} from 'vitest'
import DateRangePicker from '../components/DateRangePicker.vue'
import {presetRange,validRange} from '../time/ranges'
import {comparisonClass} from '../domain/comparison'
const value={start:'2026-10-01',end:'2026-10-07'}
describe('period range picker',()=>{
 it('keeps an empty draft uncommitted while using the committed month when switching fields',async()=>{
  const wrapper=mount(DateRangePicker,{props:{modelValue:value,label:'本周期'}})
  await wrapper.get('[aria-label="本周期开始日期"]').trigger('click')
  await wrapper.get('input[data-part="start"]').setValue('')
  await wrapper.get('[aria-label="本周期开始日期"]').trigger('click')
  expect((wrapper.get('input[data-part="start"]').element as HTMLInputElement).value).toBe('')
  expect(wrapper.get('.range-calendar strong').text()).toBe('2026年10月')
  expect(wrapper.emitted('confirm')).toBeUndefined()
  await wrapper.get('[aria-label="本周期结束日期"]').trigger('click')
  expect(wrapper.findAll('.range-calendar')).toHaveLength(2)
  expect(wrapper.emitted('confirm')).toBeUndefined()
 })
 it('waits for a valid end and preserves the start when switching the outer right field',async()=>{
  const wrapper=mount(DateRangePicker,{props:{modelValue:value,label:'本周期'}})
  await wrapper.get('[aria-label="本周期开始日期"]').trigger('click')
  await wrapper.get('[aria-label="2026-10-03"]').trigger('click')
  expect(wrapper.emitted('confirm')).toBeUndefined()
  await wrapper.get('[aria-label="本周期结束日期"]').trigger('click')
  expect((wrapper.get('input[data-part="start"]').element as HTMLInputElement).value).toBe('2026-10-03')
  await wrapper.get('[aria-label="2026-10-02"]').trigger('click')
  expect(wrapper.emitted('confirm')).toBeUndefined()
  expect(wrapper.get('[role="alert"]').text()).toContain('不能早于')
  await wrapper.get('[aria-label="2026-10-05"]').trigger('click')
  expect(wrapper.emitted('confirm')).toEqual([[{start:'2026-10-03',end:'2026-10-05'}]])
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
 })
 it('cancels drafts on Escape and outside pointer without submitting',async()=>{
  const wrapper=mount(DateRangePicker,{props:{modelValue:value,label:'上周期'},attachTo:document.body})
  await wrapper.get('[aria-label="上周期开始日期"]').trigger('click')
  await wrapper.get('[aria-label="2026-10-04"]').trigger('click')
  await wrapper.trigger('keydown',{key:'Escape'})
  expect(wrapper.emitted('confirm')).toBeUndefined()
  await wrapper.get('[aria-label="上周期开始日期"]').trigger('click')
  expect((wrapper.get('input[data-part="start"]').element as HTMLInputElement).value).toBe(value.start)
  document.body.dispatchEvent(new Event('pointerdown',{bubbles:true}));await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  wrapper.unmount()
 })
 it('permits future valid ranges and rejects invalid or out of bounds dates',()=>{
  expect(validRange({start:'2099-01-01',end:'2099-01-07'})).toBe(true)
  expect(validRange({start:'2025-02-29',end:'2025-03-01'})).toBe(false)
  expect(validRange({start:'1899-12-31',end:'1900-01-01'})).toBe(false)
 })
})
describe('Shanghai period shortcuts',()=>{
 it('uses Shanghai midnight and ends recent N on yesterday across year boundaries',()=>{
  const now=new Date('2025-12-31T16:30:00Z')
  expect(presetRange('今天',now)).toEqual({start:'2026-01-01',end:'2026-01-01'})
  expect(presetRange('昨天',now)).toEqual({start:'2025-12-31',end:'2025-12-31'})
  expect(presetRange('最近3天',now)).toEqual({start:'2025-12-29',end:'2025-12-31'})
  expect(presetRange('最近30天',now)).toEqual({start:'2025-12-02',end:'2025-12-31'})
 })
 it('uses complete Monday–Sunday weeks including future days',()=>{
  const now=new Date('2026-01-01T03:00:00Z')
  expect(presetRange('本周',now)).toEqual({start:'2025-12-29',end:'2026-01-04'})
  expect(presetRange('上周',now)).toEqual({start:'2025-12-22',end:'2025-12-28'})
 })
 it('uses full natural months including leap February',()=>{
  expect(presetRange('上月',new Date('2024-03-12T00:00:00Z'))).toEqual({start:'2024-02-01',end:'2024-02-29'})
  expect(presetRange('本月',new Date('2025-02-12T00:00:00Z'))).toEqual({start:'2025-02-01',end:'2025-02-28'})
  expect(presetRange('上月',new Date('2026-01-12T00:00:00Z'))).toEqual({start:'2025-12-01',end:'2025-12-31'})
 })
})
describe('comparison-only color semantics',()=>{
 it('colors ratios relative to one, never unknown or zero denominators',()=>{
  expect(comparisonClass({status:'VALID',value:1.01})).toBe('comparison-up')
  expect(comparisonClass({status:'VALID',value:.99})).toBe('comparison-down')
  expect(comparisonClass({status:'VALID',value:1})).toBe('comparison-neutral')
  expect(comparisonClass({status:'UNKNOWN_INPUT',value:null})).toBe('')
  expect(comparisonClass({status:'ZERO_DENOMINATOR',value:null})).toBe('')
 })
})
