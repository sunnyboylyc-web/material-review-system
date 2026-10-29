import { mount, flushPromises } from '@vue/test-utils'
import { describe,it,expect,vi,afterEach,beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import MeetingExport from '../components/MeetingExport.vue'
const capture=vi.hoisted(()=>vi.fn())
beforeEach(()=>{
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:vi.fn()})
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:vi.fn()})
})
vi.mock('html-to-image',()=>({toPng:capture}))
function fixture(){
  const rows=Array.from({length:25},(_,i)=>`<tr><td>素材${i+1}</td><td>${i}</td></tr>`).join('')
  return mount(defineComponent({components:{MeetingExport},template:`<section data-meeting-section><MeetingExport title="素材类型" context="合成项目 · 本周期 2026-01-01 — 2026-01-10" synthetic/><h4>素材类型</h4><table><tbody>${rows}</tbody></table></section>`}),{attachTo:document.body})
}
afterEach(()=>{vi.restoreAllMocks();capture.mockReset()})
describe('meeting block export',()=>{
 it('limits the daily ranking table and card view independently from paging and excludes expanded rows',async()=>{
  const w=mount(defineComponent({components:{MeetingExport},template:`<section data-meeting-section><MeetingExport title="素材榜单" context="合成" material-detail/><div class="material-gallery">${Array.from({length:25},(_,i)=>`<button class="material-card">示例${i}</button>`).join('')}</div><table><tbody>${Array.from({length:25},(_,i)=>`<tr><td>示例${i}</td></tr>`).join('')}<tr class="expanded-material"><td>不导出曲线</td></tr></tbody></table></section>`}),{attachTo:document.body})
  await w.get('.meeting-export-trigger').trigger('click');await flushPromises()
  expect(w.get('select[aria-label="导出数量"]').element).toHaveProperty('value','10')
  expect(w.findAll('.meeting-content tbody tr')).toHaveLength(10)
  expect(w.findAll('.meeting-content .material-card')).toHaveLength(10)
  expect(w.find('.meeting-content .expanded-material').exists()).toBe(false)
  await w.get('select[aria-label="导出数量"]').setValue(20)
  await w.get('select[aria-label="每页条数"]').setValue(20)
  expect(w.findAll('.meeting-content tbody tr')).toHaveLength(20)
  expect(w.findAll('.meeting-content .material-card')).toHaveLength(20);w.unmount()
 })
 it('scopes paper styling to the export sheet and keeps original data and night presentation intact',async()=>{
  const w=fixture(),original=w.get(':scope > table').text()
  await w.get('.meeting-export-trigger').trigger('click');await flushPromises()
  expect(w.get('.meeting-sheet').classes()).toContain('meeting-paper')
  expect(w.classes()).not.toContain('meeting-paper')
  expect(w.get(':scope > table').text()).toBe(original)
  expect(w.find('select[aria-label="导出样式"]').exists()).toBe(false)
  expect(w.find('select[aria-label="导出清晰度"]').exists()).toBe(false);w.unmount()
 })
 it('freezes only the chosen block, paginates without altering original rows, and retains context and synthetic labels',async()=>{
  const w=fixture();await w.get('.meeting-export-trigger').trigger('click');await flushPromises()
  expect(w.get('.meeting-sheet').text()).toContain('纯虚构演示数据')
  expect(w.get('.meeting-sheet').text()).toContain('2026-01-01 — 2026-01-10')
  expect(w.findAll('.meeting-content tbody tr')).toHaveLength(10)
  expect(w.findAll(':scope > table tbody tr')).toHaveLength(25)
  await w.get('.meeting-pages button:last-child').trigger('click')
  expect(w.get('.meeting-content tbody tr').text()).toContain('素材11')
  expect(w.find('.meeting-content .meeting-export-trigger').exists()).toBe(false)
  await w.get('[data-close]').trigger('click');expect(w.find('dialog').exists()).toBe(false);w.unmount()
 })
 it('exports fixed 2x PNG and keeps a failed export recoverable',async()=>{
  const w=fixture();await w.get('.meeting-export-trigger').trigger('click');await flushPromises()
  capture.mockRejectedValueOnce(new Error('导出失败')).mockResolvedValueOnce('data:image/png;base64,AAAA')
  vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  await w.get('.meeting-download').trigger('click');await flushPromises()
  expect(w.get('[role=alert]').text()).toContain('导出失败')
  await w.get('.meeting-download').trigger('click');await flushPromises()
  expect(capture.mock.calls[1][1].pixelRatio).toBe(2)
  expect(w.get('[role=status]').text()).toContain('已生成第 1 / 3 页 PNG')
  expect(w.findAll(':scope > table tbody tr')).toHaveLength(25);w.unmount()
 })
})

