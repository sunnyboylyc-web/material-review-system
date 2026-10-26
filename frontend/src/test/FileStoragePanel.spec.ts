import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import FileStoragePanel from '../components/FileStoragePanel.vue'
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('private backup files', () => {
  it('loads only on expand and downloads saved files, not pending files', async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ writeEnabled:true,chunkBytes:4194304,files:[
      {id:'a',name:'备份.zip',bytes:123,received:123,status:'READY',sha256:'abc',createdAt:'2026-10-15T00:00:00Z'},
      {id:'b',name:'中断.zip',bytes:123,received:12,status:'UPLOADING',sha256:'',createdAt:'2026-10-15T00:00:00Z'},
    ] }) })
    vi.stubGlobal('fetch',fetcher)
    const wrapper=mount(FileStoragePanel)
    expect(fetcher).not.toHaveBeenCalled()
    ;(wrapper.element as HTMLDetailsElement).open=true
    await wrapper.trigger('toggle'); await flushPromises()
    expect(wrapper.findAll('a')).toHaveLength(1)
    expect(wrapper.find('a').attributes('href')).toBe('/api/operations/files/a/download')
    expect(wrapper.text()).toContain('不能作为有效备份')
    expect(wrapper.text()).toContain('不能代替电脑或异地备份')
  })
  it('reports denied service without permitting upload', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403,json:async()=>({error:'ACCESS_DENIED'})}))
    const wrapper=mount(FileStoragePanel)
    ;(wrapper.element as HTMLDetailsElement).open=true
    await wrapper.trigger('toggle'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('403')
    expect(wrapper.find('input').attributes('disabled')).toBeDefined()
  })
  it('cancels without a request and deletes only after named confirmation', async () => {
    const file={id:'abc',name:'同名.zip',bytes:123,received:123,status:'READY',sha256:'abc',createdAt:'2026-10-15T00:00:00Z'}
    let deleted=false
    const fetcher=vi.fn().mockImplementation(async (_url,init) => {
      if(init?.method==='DELETE') { deleted=true; return {ok:true,json:async()=>({deleted:true})} }
      return {ok:true,json:async()=>({writeEnabled:true,chunkBytes:4194304,files:deleted?[]:[file]})}
    })
    vi.stubGlobal('fetch',fetcher)
    const confirmation=vi.spyOn(window,'confirm').mockReturnValue(false)
    const wrapper=mount(FileStoragePanel)
    ;(wrapper.element as HTMLDetailsElement).open=true
    await wrapper.trigger('toggle'); await flushPromises()
    await wrapper.get('.file-storage__delete').trigger('click'); await flushPromises()
    expect(fetcher.mock.calls.filter(c=>c[1]?.method==='DELETE')).toHaveLength(0)
    expect(confirmation).toHaveBeenCalledWith(expect.stringContaining('同名.zip'))
    confirmation.mockReturnValue(true)
    await wrapper.get('.file-storage__delete').trigger('click'); await flushPromises()
    expect(fetcher).toHaveBeenCalledWith('/api/operations/files/abc',expect.objectContaining({method:'DELETE',body:'{"confirmed":true}'}))
    expect(wrapper.text()).toContain('已删除“同名.zip”')
    expect(wrapper.find('.file-storage__delete').exists()).toBe(false)
    wrapper.unmount()
  })
  it('keeps failed deletion retryable and never offers incomplete deletion as a download', async () => {
    const file={id:'abc',name:'备份.zip',bytes:123,received:123,status:'DELETING',sha256:'abc',createdAt:'2026-10-15T00:00:00Z'}
    vi.stubGlobal('fetch',vi.fn().mockImplementation(async (_url,init) => init?.method==='DELETE'
      ? {ok:false,status:503,json:async()=>({})}
      : {ok:true,json:async()=>({writeEnabled:true,chunkBytes:4194304,files:[file]})}))
    vi.spyOn(window,'confirm').mockReturnValue(true)
    const wrapper=mount(FileStoragePanel)
    ;(wrapper.element as HTMLDetailsElement).open=true
    await wrapper.trigger('toggle'); await flushPromises()
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.get('.file-storage__delete').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('删除未确认完成')
    expect(wrapper.text()).not.toContain('已删除“')
    expect(wrapper.get('.file-storage__delete').text()).toBe('重试删除')
    wrapper.unmount()
  })
})
