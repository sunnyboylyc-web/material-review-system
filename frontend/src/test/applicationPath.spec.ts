import { describe, expect, it } from 'vitest'
import { applicationPath } from '../domain/applicationPath'

describe('application deployment path', () => {
  it('keeps root deployments compatible', () => {
    expect(applicationPath('/api/projects', '/')).toBe('/api/projects')
  })
  it('scopes APIs, login, logout and downloads without double prefixes', () => {
    for (const path of ['/api/projects', '/login', '/logout', '/api/operations/history/template', '/']) {
      expect(applicationPath(path, '/uranus/titania/')).toBe('/uranus/titania' + path)
    }
    expect(applicationPath('/uranus/titania/login', '/uranus/titania/')).toBe('/uranus/titania/login')
  })
  it('rejects external paths and unsafe deployment prefixes', () => {
    expect(() => applicationPath('//other.example/path', '/')).toThrow()
    expect(() => applicationPath('/api/projects', '//other.example/')).toThrow()
    expect(() => applicationPath('/api/projects', '/../../')).toThrow()
  })
})
