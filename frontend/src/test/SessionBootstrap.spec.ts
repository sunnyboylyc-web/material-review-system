import { describe, it, expect, vi } from 'vitest'
import { initializeSession, csrfHeaders } from '../domain/session'

describe('session bootstrap', () => {
  it('accepts public access with write protection and rejects inconsistent public roles', async () => {
    const session = { username: '访客', publicAccess: true, operations: true, administrator: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'synthetic-token' }
    expect(await initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify(session))))).toEqual(session)
    expect(csrfHeaders()).toEqual({ 'X-CSRF-TOKEN': 'synthetic-token' })
    await expect(initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...session, administrator: true }))))).rejects.toThrow()
  })
  it('loads the authenticated identity and write token, then clears it for local mode', async () => {
    const session = { username: 'test', operations: false, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'synthetic-token' }
    expect(await initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify(session))))).toEqual(session)
    expect(csrfHeaders()).toEqual({ 'X-CSRF-TOKEN': 'synthetic-token' })
    expect(await initializeSession(vi.fn().mockResolvedValue(new Response('', { status: 404 })))).toBeUndefined()
    expect(csrfHeaders()).toEqual({})
  })
  it('does not interpret an unavailable service or malformed identity as local administrator mode', async () => {
    await expect(initializeSession(vi.fn().mockResolvedValue(new Response('', { status: 503 })))).rejects.toThrow()
    await expect(initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify({ username: 'test' }))))).rejects.toThrow()
    expect(csrfHeaders()).toEqual({})
  })
})
