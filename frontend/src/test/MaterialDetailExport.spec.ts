import {mount,flushPromises} from '@vue/test-utils'
import {describe,it,expect,vi,beforeEach} from 'vitest'
import MaterialDetailExport from '../components/MaterialDetailExport.vue'
import type {ReviewMaterialRow} from '../domain/dashboard'
const n=(value:number)=>({status:'VALID' as const,value})
beforeEach(()=>{
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:vi.fn()})
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:vi.fn()})
})
function rows():ReviewMaterialRow[]{return Array.from({length:25},(_,i)=>({rank:i+1,materialId:`DEMO-${i}`,materialName:`示例${i}`,creationDate:{status:'VERIFIED',date:'2026-01-01',reason:null},currentSpend:n(i),previousSpend:n(i),roi:n(3),ctr:n(.02),cvr:n(.03),cpm:n(20),dailySpend:[{date:'2026-01-01',spend:n(123)}]} as ReviewMaterialRow))}
describe('filtered material detail export',()=>{
 it('defaults to ten, selects more matching rows independently and never includes expanded curves',async()=>{
  const data=rows(),before=JSON.stringify(data),w=mount(MaterialDetailExport,{attachTo:document.body,props:{rows:data,title:'历史素材明细',context:'合成项目'}})
  await w.get('.meeting-export-trigger').trigger('click');await flushPromises()
  expect(w.get('select[aria-label="导出数量"]').element).toHaveProperty('value','10')
  expect(w.findAll('.meeting-content tbody tr')).toHaveLength(10)
  expect(w.get('.meeting-sheet').text()).not.toContain('完整名录尚未就绪')
  await w.get('select[aria-label="导出数量"]').setValue(20)
  await w.get('select[aria-label="每页条数"]').setValue(20)
  expect(w.findAll('.meeting-content tbody tr')).toHaveLength(20)
  expect(w.get('.meeting-content').text()).toContain('示例19')
  expect(w.find('.meeting-content svg').exists()).toBe(false)
  expect(w.find('.meeting-content .expand-button').exists()).toBe(false)
  expect(JSON.stringify(data)).toBe(before);w.unmount()
 })
 it('disables export while the complete candidate list is still unavailable',()=>{
  const w=mount(MaterialDetailExport,{props:{rows:[],title:'整体明细',context:'合成',disabled:true}})
  expect(w.get('.meeting-export-trigger').attributes('disabled')).toBeDefined();w.unmount()
 })
})
