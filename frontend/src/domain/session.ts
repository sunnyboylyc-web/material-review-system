import { applicationPath } from './applicationPath'
export interface SessionIdentity { username: string; operations: boolean; administrator?: boolean; publicAccess?: boolean; recoveryReadOnly?: boolean; csrfHeader: string; csrfToken: string }
let identity: SessionIdentity | undefined
export function csrfHeaders(): Record<string, string> {
  return identity ? { [identity.csrfHeader]: identity.csrfToken } : {}
}
export async function initializeSession(fetcher: typeof fetch = fetch): Promise<SessionIdentity | undefined> {
  identity = undefined
  const response = await fetcher(applicationPath('/api/auth/session'), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 404) return undefined // Existing loopback-only development runner.
  if (response.status === 401) { window.location.assign(applicationPath('/login')); throw new Error('请先登录。') }
  if (!response.ok) throw new Error('无法核对登录状态，请刷新页面。')
  const value = await response.json() as SessionIdentity
  if (typeof value.username !== 'string' || typeof value.operations !== 'boolean'
    || (value.administrator !== undefined && typeof value.administrator !== 'boolean')
    || (value.publicAccess !== undefined && typeof value.publicAccess !== 'boolean')
    || (value.publicAccess === true && (value.administrator === true || !value.operations))
    || (value.recoveryReadOnly !== undefined && typeof value.recoveryReadOnly !== 'boolean')
    || (value.administrator === true && !value.operations)
    || value.csrfHeader !== 'X-CSRF-TOKEN' || typeof value.csrfToken !== 'string' || !value.csrfToken)
    throw new Error('登录状态返回异常，请重新登录。')
  identity = value
  return identity
}
export const sessionFetch: typeof fetch = async (input, init) => {
  const response = await fetch(typeof input === 'string' && input.startsWith('/') ? applicationPath(input) : input, init)
  if (identity && response.status === 401) window.location.assign(applicationPath('/login'))
  return response
}
