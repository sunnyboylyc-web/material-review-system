import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminAccountsPanel from '../components/AdminAccountsPanel.vue'
import App from '../App.vue'
import { httpAdminAccounts, type AccountList, type AdminAccounts } from '../adapters/adminAccounts'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { initializeSession } from '../domain/session'

const initial: AccountList = { revision: 1, accounts: [{ username: 'admin', role: 'ADMIN' }, { username: 'viewer', role: 'VIEWER' }] }
const api = (): AdminAccounts => ({ list: vi.fn().mockResolvedValue(initial),
  create: vi.fn().mockResolvedValue(initial), remove: vi.fn().mockResolvedValue({ revision: 2, accounts: [initial.accounts[0]] }) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('administrator accounts', () => {
  it('shows initial identities, validates and creates without persisting the plaintext password in form', async () => {
    const adapter = api(), page = mount(AdminAccountsPanel, { props: { adapter } }); await flushPromises()
    expect(page.text()).toContain('固定管理员，不可删除')
    expect(page.find('[aria-label="删除账号 admin"]').exists()).toBe(false)
    await page.get('input[name="new-account"]').setValue('new-user')
    await page.get('input[type="password"]').setValue('abc')
    await page.get('form').trigger('submit'); await flushPromises()
    expect(adapter.create).not.toHaveBeenCalled()
    expect(page.text()).toContain('密码需为 6～30')
    await page.get('input[type="password"]').setValue('Aa!234567890')
    await page.get('select').setValue('OPERATIONS')
    await page.get('form').trigger('submit'); await flushPromises()
    expect(adapter.create).toHaveBeenCalledWith('new-user', 'Aa!234567890', 'OPERATIONS')
    expect((page.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(page.text()).not.toContain('Aa!234567890')
    page.unmount()
  })
  it('requires specific-account confirmation and cancellation makes no request', async () => {
    const adapter = api(), page = mount(AdminAccountsPanel, { props: { adapter } }); await flushPromises()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await page.get('[aria-label="删除账号 viewer"]').trigger('click'); await flushPromises()
    expect(adapter.remove).not.toHaveBeenCalled()
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('账号“viewer”'))
    confirm.mockReturnValue(true)
    await page.get('[aria-label="删除账号 viewer"]').trigger('click'); await flushPromises()
    expect(adapter.remove).toHaveBeenCalledWith('viewer')
    expect(page.find('[aria-label="删除账号 viewer"]').exists()).toBe(false)
    expect(page.text()).toContain('业务数据未删除')
    page.unmount()
  })
  for (const administrator of [undefined, false, true]) it(`only administrator=${administrator} mounts accounts before configuration`, async () => {
    const adapter = new SyntheticMockAdapter()
    Object.defineProperty(adapter, 'evidenceKind', { value: 'CURRENT_FACTS' }) // Mock response, production-role branch only.
    const page = mount(App, { props: { adapter,
      session: { username: 'test', operations: true, administrator, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test' },
      periodPreferences: { load: async () => null, save: async () => {} },
      periodSnapshots: { load: async () => null, refresh: vi.fn() } },
      global: { stubs: { AdminAccountsPanel: true, ConfigurationPanel: true, HistoryUploadPanel: true, OperationsPanel: true, CollectedExportPanel: true } } })
    await flushPromises()
    expect(page.find('admin-accounts-panel-stub').exists()).toBe(false)
    await page.get('.operations-toggle').trigger('click'); await flushPromises()
    expect(page.find('admin-accounts-panel-stub').exists()).toBe(administrator === true)
    if (administrator) expect(page.html().indexOf('<admin-accounts-panel-stub')).toBeLessThan(page.html().indexOf('<configuration-panel-stub'))
    page.unmount()
  })
  it('uses authenticated CSRF writes and sanitized error messages; validates administrator session flag', async () => {
    await initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify({ username: 'admin', operations: true,
      administrator: true, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'csrf-test' }))))
    const fetcher = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(initial)))
    vi.stubGlobal('fetch', fetcher)
    expect(await httpAdminAccounts.list()).toEqual(initial)
    await httpAdminAccounts.create('one', 'abcdef', 'VIEWER')
    expect(fetcher.mock.calls.at(-1)?.[1]).toMatchObject({ method: 'POST', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-test' }), body: JSON.stringify({ username: 'one', password: 'abcdef', role: 'VIEWER' }) })
    await httpAdminAccounts.remove('one')
    expect(fetcher.mock.calls.at(-1)?.[1]).toMatchObject({ method: 'DELETE', body: '{"confirmed":true}' })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 'ACCOUNT_NAME_UNAVAILABLE' }), { status: 400 })))
    await expect(httpAdminAccounts.create('one', 'abcdef', 'VIEWER')).rejects.toThrow('曾被删除')
    await expect(initializeSession(vi.fn().mockResolvedValue(new Response(JSON.stringify({ username: 'test', operations: false,
      administrator: true, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test' }))))).rejects.toThrow()
  })
})
