import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import CollectedExportPanel from '../components/CollectedExportPanel.vue'
import type { CollectedExportAdapter, CollectedExportPreview } from '../domain/collectedExport'
import { HttpCollectedExportAdapter } from '../adapters/httpCollectedExportAdapter'
function api(): CollectedExportAdapter { return { preview: vi.fn(async (projectId,startDate,endDate) => ({ projectId,projectName:'示例',accountId:'123',startDate,endDate,rowCount:2,days:[{statDate:startDate,rowCount:2}],excludedDates:[{statDate:endDate,reason:'MISSING'}] })), download: vi.fn(async()=>new Blob(['excel'])) } }
describe('collected data export',()=>{
  it('includes manual history and explains data recovery without promising original file recovery',async()=>{
    const adapter=api(); adapter.preview=vi.fn(async(projectId,startDate,endDate)=>({projectId,projectName:'示例',accountId:'1',startDate,endDate,rowCount:3,days:[{statDate:startDate,rowCount:1,sourceType:'MANUAL_HISTORY' as const},{statDate:endDate,rowCount:2,sourceType:'MCP_AUTO' as const}],excludedDates:[]}))
    const w=mount(CollectedExportPanel,{props:{projectId:'demo',adapter}}); await w.get('form').trigger('submit'); await flushPromises()
    expect(w.text()).toContain('机器采集 1 天，人工历史 1 天'); expect(w.text()).toContain('不会恢复原文件的排版'); expect(w.findAll('button')[1]!.attributes('disabled')).toBeUndefined(); w.unmount()
  })
  it.each([
    ['EXPORT_ROW_LIMIT', '超过 10 万行'], ['EXPORT_RANGE_INVALID', '日期范围无效'],
    ['EXPORT_NO_DATA', '没有可下载的已保存数据'], ['EXPORT_BUILD_FAILED', 'Excel 文件生成失败'],
    ['EXPORT_TIMEOUT', '等待超时'], ['EXPORT_READ_FAILED', '暂时无法读取已保存的数据'],
  ])('explains %s without requiring code knowledge', async(code, explanation)=>{
    const fetcher=vi.fn(async()=>new Response(JSON.stringify({code}),{status:422}))
    await expect(new HttpCollectedExportAdapter(fetcher).download('a','2026-01-01','2026-01-01')).rejects.toThrow(explanation)
  })
  it('requires preview, exposes missing dates and invalidates it on date changes',async()=>{
    const adapter=api(); const w=mount(CollectedExportPanel,{props:{projectId:'demo',adapter}})
    expect(w.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    await w.get('form').trigger('submit'); await flushPromises()
    expect(w.text()).toContain('可下载 1 天，共 2 行'); expect(w.text()).toContain('未包含 1 天'); expect(w.findAll('button')[1]!.attributes('disabled')).toBeUndefined()
    await w.findAll('input')[0]!.setValue('2026-01-01'); expect(w.find('.export-preview').exists()).toBe(false); w.unmount()
  })
  it('discards old project response and prevents duplicate requests',async()=>{
    const adapter=api(); let resolve!:(v:CollectedExportPreview)=>void
    adapter.preview=vi.fn(()=>new Promise<CollectedExportPreview>(r=>{resolve=r}))
    const w=mount(CollectedExportPanel,{props:{projectId:'old',adapter}})
    await w.get('form').trigger('submit'); await w.get('form').trigger('submit'); expect(adapter.preview).toHaveBeenCalledTimes(1)
    await w.setProps({projectId:'new'}); resolve({projectId:'old',projectName:'旧',accountId:'1',startDate:'2026-01-01',endDate:'2026-01-01',rowCount:0,days:[],excludedDates:[]}); await flushPromises()
    expect(w.find('.export-preview').exists()).toBe(false); w.unmount()
  })
  it('allows confirmed empty days, blocks no eligible days and readonly mode',async()=>{
    const adapter=api(); adapter.preview=vi.fn(async(projectId,startDate,endDate)=>({projectId,projectName:'示例',accountId:'1',startDate,endDate,rowCount:0,days:[{statDate:startDate,rowCount:0}],excludedDates:[]}))
    const w=mount(CollectedExportPanel,{props:{projectId:'demo',adapter}}); await w.get('form').trigger('submit'); await flushPromises()
    expect(w.findAll('button')[1]!.attributes('disabled')).toBeUndefined()
    await w.setProps({readOnly:true}); await w.get('form').trigger('submit'); expect(adapter.preview).toHaveBeenCalledTimes(1); w.unmount()
  })
  it('rejects JSON pretending to be a successful download',async()=>{
    const fetcher=vi.fn(async()=>new Response(JSON.stringify({code:'ERROR'}),{headers:{'content-type':'application/json'}}))
    await expect(new HttpCollectedExportAdapter(fetcher).download('a','2026-01-01','2026-01-01')).rejects.toThrow('未返回 Excel')
  })
  it('downloads once and reports browser handoff, not a successful save to disk',async()=>{
    const adapter=api(); const create=vi.fn(()=> 'blob:test'); const revoke=vi.fn()
    vi.stubGlobal('URL',Object.assign(URL,{createObjectURL:create,revokeObjectURL:revoke}))
    const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
    const w=mount(CollectedExportPanel,{props:{projectId:'demo',adapter}})
    await w.get('form').trigger('submit'); await flushPromises(); await w.findAll('button')[1]!.trigger('click'); await flushPromises()
    expect(adapter.download).toHaveBeenCalledTimes(1); expect(click).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('交给浏览器下载'); w.unmount(); click.mockRestore(); vi.unstubAllGlobals()
  })
  it('blocks an empty eligible range and more than 366 days',async()=>{
    const adapter=api(); adapter.preview=vi.fn(async(projectId,startDate,endDate)=>({projectId,projectName:'示例',accountId:'1',startDate,endDate,rowCount:0,days:[],excludedDates:[]}))
    const w=mount(CollectedExportPanel,{props:{projectId:'demo',adapter}})
    await w.get('form').trigger('submit'); await flushPromises(); expect(w.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    await w.findAll('input')[0]!.setValue('2020-01-01'); await w.get('form').trigger('submit')
    expect(w.text()).toContain('请缩短日期范围'); expect(adapter.preview).toHaveBeenCalledTimes(1); w.unmount()
  })
  it('checks response scope and handles server errors',async()=>{
    const fetcher=vi.fn(async()=>new Response(JSON.stringify({projectId:'other'}),{headers:{'content-type':'application/json'}}))
    await expect(new HttpCollectedExportAdapter(fetcher).preview('a','2026-01-01','2026-01-01')).rejects.toThrow('范围返回不一致')
    const failed=vi.fn(async()=>new Response(JSON.stringify({code:'EXPORT_NO_DATA'}),{status:422}))
    await expect(new HttpCollectedExportAdapter(failed).download('a','2026-01-01','2026-01-01')).rejects.toThrow('EXPORT_NO_DATA')
  })
})
