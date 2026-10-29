import { describe,it,expect } from 'vitest'
import { permitsPreviewQuery,permitsPreviewSource } from '../../preview-policy'
describe('local live preview query boundary',()=>{
 it('requires HTTPS except for an explicitly enabled local query bridge',()=>{
  expect(permitsPreviewSource('https://example.com/app/')).toBe(true)
  expect(permitsPreviewSource('http://127.0.0.1:5190/')).toBe(false)
  expect(permitsPreviewSource('http://127.0.0.1:5190/',true)).toBe(true)
  for(const url of ['http://example.com/','http://127.0.0.1.evil.test/','http://192.168.1.5/','https://user:pass@example.com/','https://example.com/?token=x'])expect(permitsPreviewSource(url,true)).toBe(false)
 })
 it('allows only named dashboard query endpoints',()=>{
  for(const path of ['/api/projects','/api/projects/demo/top?date=2026-10-01','/api/projects/demo/review','/api/projects/demo/material-types','/api/projects/demo/review-materials','/api/projects/demo/freshness'])expect(permitsPreviewQuery('GET',path)).toBe(true)
 })
 it('rejects production mutations, file access, credentials and path traversal',()=>{
  for(const method of ['POST','PUT','PATCH','DELETE','HEAD'])expect(permitsPreviewQuery(method,'/api/projects/demo/review')).toBe(false)
  for(const path of ['/api/files','/api/operations/configuration','/api/auth/session','/api/projects/demo/review/refresh','/api/projects/../files','/api/projects/%2e%2e/top','/api/projects/demo/period-snapshot'])expect(permitsPreviewQuery('GET',path)).toBe(false)
 })
})
