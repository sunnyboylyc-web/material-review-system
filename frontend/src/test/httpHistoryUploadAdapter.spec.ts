import { describe, expect, it, vi } from 'vitest'
import { HttpHistoryUploadAdapter } from '../adapters/httpHistoryUploadAdapter'
describe('history upload HTTP transport', () => {
  it.each(['UPLOAD_SLOT_OCCUPIED', 'UPLOAD_RECORD_UNAVAILABLE'])('recognizes XHR %s refusal without retrying', async code => {
    const xhr={open:vi.fn(),setRequestHeader:vi.fn(),send:vi.fn(),upload:{},status:409,responseText:JSON.stringify({code}),onload:null as null|(()=>void)}
    vi.stubGlobal('XMLHttpRequest',vi.fn(function(){return xhr}))
    try {
      const pending=new HttpHistoryUploadAdapter().preview('demo',new File(['x'],'a.xlsx'),vi.fn())
      const assertion=expect(pending).rejects.toMatchObject({outcome:'NOT_EXECUTED'})
      xhr.onload!(); await assertion; expect(xhr.send).toHaveBeenCalledTimes(1)
    } finally { vi.unstubAllGlobals() }
  })
  it.each([[409,'CONFIGURATION_CHANGED','NOT_EXECUTED'],[409,'PROJECT_UNAVAILABLE','NOT_EXECUTED'],[403,'LOCAL_REQUEST_REQUIRED','NOT_EXECUTED'],[409,'REQUEST_INVALID','NOT_EXECUTED'],[409,'HISTORY_BUSY','UNKNOWN'],[500,'CONFIGURATION_CHANGED','UNKNOWN'],[409,'UNRECOGNIZED','UNKNOWN']])('classifies HTTP %s %s conservatively', async (status,code,outcome) => {
    const api = new HttpHistoryUploadAdapter(vi.fn(async()=>new Response(JSON.stringify({code}),{status:Number(status)})))
    await expect(api.apply('demo','one','hash')).rejects.toMatchObject({outcome})
  })
  it('normalizes unavailable-record warnings without exposing arbitrary diagnostic contents', async () => {
    const api = new HttpHistoryUploadAdapter(vi.fn(async()=>new Response(JSON.stringify({schemaVersion:'history-upload-api-v1',projectId:'demo',writeEnabled:true,uploads:[],warnings:[{code:'UPLOAD_RECORD_UNAVAILABLE',message:'private other-project path'}]}))))
    const result = await api.context('demo')
    expect(result.warnings?.[0]?.message).toContain('联系维护人员')
    expect(JSON.stringify(result)).not.toContain('private')
  })
  it('uses actual XHR upload progress, then validates the final preview response', async () => {
    const xhr={open:vi.fn(),setRequestHeader:vi.fn(),send:vi.fn(),upload:{onprogress:null as null|((e:Partial<ProgressEvent>)=>void),onload:null as null|(()=>void)},status:200,responseText:JSON.stringify({uploadId:'one',projectId:'demo',status:'PREVIEW_READY',days:[],sourceFileState:'RETAINED'}),onload:null as null|(()=>void)}
    vi.stubGlobal('XMLHttpRequest',vi.fn(function(){return xhr}))
    try {
      const progress=vi.fn(); const file=new File(['synthetic'],'a.xlsx'); const pending=new HttpHistoryUploadAdapter().preview('demo',file,progress)
      xhr.upload.onprogress!({lengthComputable:true,loaded:3,total:9}); expect(progress).toHaveBeenLastCalledWith(3,9)
      xhr.upload.onload!(); expect(progress).toHaveBeenLastCalledWith(file.size,file.size)
      xhr.onload!(); expect((await pending).status).toBe('PREVIEW_READY'); expect(xhr.send).toHaveBeenCalledWith(file)
      expect(xhr.setRequestHeader).toHaveBeenCalledWith('X-SCSJ-History-Write','1')
    } finally { vi.unstubAllGlobals() }
  })
  it('uses raw file body and explicit write header, not multipart', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ uploadId: 'one', projectId: 'demo', status: 'VALIDATION_FAILED', days: [], sourceFileState: 'RETAINED' })))
    const adapter = new HttpHistoryUploadAdapter(fetcher); const file = new File(['synthetic'], 'test.xlsx')
    await adapter.preview('demo', file)
    expect(fetcher).toHaveBeenCalledWith('/api/operations/history/projects/demo/preview', expect.objectContaining({ method: 'POST', credentials: 'same-origin', body: file, headers: expect.objectContaining({ 'Content-Type': 'application/octet-stream', 'X-SCSJ-History-Write': '1' }) }))
  })
  it('readonly adapter rejects writes before fetch', async () => {
    const fetcher = vi.fn(); const adapter = new HttpHistoryUploadAdapter(fetcher, true)
    await expect(adapter.apply('demo','one','hash')).rejects.toThrow('只读')
    await expect(adapter.discard('demo','one','hash')).rejects.toThrow('只读')
    await expect(adapter.preview('demo',new File(['x'],'a.xlsx'))).rejects.toThrow('只读')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('does not report unavailable API as success', async () => {
    const adapter = new HttpHistoryUploadAdapter(vi.fn(async () => new Response('{}', { status: 503 })))
    await expect(adapter.context('demo')).rejects.toThrow('尚未就绪')
  })
})
