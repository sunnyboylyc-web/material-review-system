import { csrfHeaders, sessionFetch } from '../domain/session'

export type AccountRole = 'ADMIN' | 'OPERATIONS' | 'VIEWER'
export interface ManagedAccount { username: string; role: AccountRole }
export interface AccountList { revision: number; accounts: ManagedAccount[] }
export interface AdminAccounts {
  list(): Promise<AccountList>
  create(username: string, password: string, role: Exclude<AccountRole, 'ADMIN'>): Promise<AccountList>
  remove(username: string): Promise<AccountList>
}
const messages: Record<string, string> = {
  ACCOUNT_INVALID: '账号格式或密码不符合要求，请检查后重试。',
  ACCOUNT_NAME_UNAVAILABLE: '此账号已存在或曾被删除，请使用其他账号名称。',
  ACCOUNT_DELETE_FORBIDDEN: '不能删除管理员账号或当前登录账号。',
  ACCOUNT_CONFIRM_REQUIRED: '删除必须再次确认，未执行删除。',
  ACCOUNT_STORE_UNAVAILABLE: '账号存储暂不可用，请稍后刷新列表核对后重试。',
}
async function request(path = '', init?: RequestInit): Promise<AccountList> {
  const response = await sessionFetch(`/api/admin/accounts${path}`, { credentials: 'same-origin', cache: 'no-store', ...init,
    headers: { 'Content-Type': 'application/json', ...csrfHeaders(), ...init?.headers } })
  if (!response.ok) {
    let code = ''; try { code = (await response.json()).code } catch { /* Proxy may return non-JSON. */ }
    throw new Error(messages[code] ?? (response.status === 403 ? '只有管理员可以管理账号。' : `账号操作失败（HTTP ${response.status}），请刷新列表核对。`))
  }
  const value = await response.json()
  if (!value || !Number.isInteger(value.revision) || !Array.isArray(value.accounts)
    || !value.accounts.every((account: ManagedAccount) => account && typeof account.username === 'string' && ['ADMIN', 'OPERATIONS', 'VIEWER'].includes(account.role)))
    throw new Error('账号列表返回异常，请刷新重试。')
  return value
}
export const httpAdminAccounts: AdminAccounts = {
  list: () => request(),
  create: (username, password, role) => request('', { method: 'POST', body: JSON.stringify({ username, password, role }) }),
  remove: username => request(`/${encodeURIComponent(username)}`, { method: 'DELETE', body: JSON.stringify({ confirmed: true }) }),
}
