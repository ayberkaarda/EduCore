import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAccessToken } from '../app/lib/api'
import { formatAccountDate } from '../app/lib/date-format'
import type { Account, Profile, SecurityEvent } from '../app/lib/types'
import { API, adminUser, anonymousHandlers, problem, regularUser, server, sessionFor, sessionHandlers } from './msw'
import { renderApp } from './render-app'

const deleteAfter = '2026-11-01T12:00:00Z'
const profile: Profile = { id: 2, username: 'ali', firstName: 'Ali', lastName: 'Veli', studentNumber: null, role: 'USER', status: 'ACTIVE', deleteAfter: null }
const pendingUser = { ...regularUser, status: 'PENDING_DELETION' as const }
const account: Account = { ...profile, id: 10, username: 'student10', ipAddress: null }
function profileHandlers() {
  return [
    ...sessionHandlers(regularUser),
    http.get(`${API}/me`, () => HttpResponse.json(profile)),
    http.get(`${API}/me/enrollments`, () => HttpResponse.json([])),
    http.get(`${API}/courses`, () => HttpResponse.json([])),
  ]
}
afterEach(() => vi.restoreAllMocks())

describe('pending deletion session', () => {
  it('honours a pending status returned by /auth/me', async () => {
    server.use(...sessionHandlers(regularUser))
    server.use(
      http.get(`${API}/auth/me`, () => HttpResponse.json(pendingUser)),
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: 'PENDING_DELETION', deleteAfter })))
    renderApp('/app/profile')
    await screen.findByRole('heading', { name: 'Deletion scheduled' })
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(getAccessToken()).toBe('token-2')
  })
  it('shows only restore and sign out, preserves the token and restores the app', async () => {
    const user = userEvent.setup()
    let restored = false
    let restores = 0
    server.use(...sessionHandlers(pendingUser))
    server.use(
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: restored ? 'ACTIVE' : 'PENDING_DELETION', deleteAfter: restored ? null : deleteAfter })),
      http.post(`${API}/me/restore`, async ({ request }) => { expect(await request.json()).toEqual({ currentPassword: 'my-password' }); restores++; restored = true; return HttpResponse.json(sessionFor(regularUser, 'restored-token')) }),
      http.get(`${API}/auth/me`, () => restored ? HttpResponse.json(regularUser) : problem(403, 'account/pending-deletion')),
      http.get(`${API}/courses`, () => HttpResponse.json([])),
    )
    renderApp('/app/courses')
    await screen.findByRole('heading', { name: 'Deletion scheduled' })
    await waitFor(() => expect(document.querySelector('time')).toHaveAttribute('datetime', deleteAfter))
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(getAccessToken()).toBe('token-2')
    await user.type(screen.getByLabelText('Current password (required)'), 'my-password')
    await user.click(screen.getByRole('button', { name: 'Restore' }))
    await screen.findByRole('heading', { name: 'Courses' })
    expect(restores).toBe(1)
    expect(getAccessToken()).toBe('restored-token')
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument()
  })

  it('signs out a pending session', async () => {
    let signouts = 0
    server.use(...sessionHandlers(pendingUser))
    server.use(
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: 'PENDING_DELETION', deleteAfter })),
      http.post(`${API}/auth/logout`, () => {
        expect(getAccessToken()).toBeNull()
        signouts++
        return new HttpResponse(null, { status: 204 })
      }))
    renderApp('/app/profile')
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Sign out' }))
    await screen.findByRole('heading', { name: 'Sign in to EduCore' })
    await waitFor(() => expect(signouts).toBe(1))
    expect(getAccessToken()).toBeNull()
  })

  it('maps an arbitrary pending-deletion 403 to the full page without a toast', async () => {
    server.use(...sessionHandlers(regularUser),
      http.get(`${API}/courses`, () => problem(403, 'account/pending-deletion')),
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: 'PENDING_DELETION', deleteAfter })))
    renderApp('/app/courses')
    await screen.findByRole('heading', { name: 'Deletion scheduled' })
    expect(getAccessToken()).toBe('token-2')
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.queryByText('Failed to load courses.')).not.toBeInTheDocument()
  })

  it('maps a pending /auth/me bootstrap response without forgetting the token', async () => {
    server.use(...sessionHandlers(regularUser))
    server.use(
      http.get(`${API}/auth/me`, () => problem(403, 'account/pending-deletion')),
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: 'PENDING_DELETION', deleteAfter })))
    renderApp('/app/profile')
    await screen.findByRole('heading', { name: 'Deletion scheduled' })
    expect(getAccessToken()).toBe('token-2')
  })

  it('accepts pending-deletion login and bypasses password-change navigation', async () => {
    server.use(...anonymousHandlers(),
      http.post(`${API}/auth/login`, () => HttpResponse.json(sessionFor({ ...pendingUser, mustChangePassword: true }))),
      http.get(`${API}/me`, () => HttpResponse.json({ ...profile, status: 'PENDING_DELETION', deleteAfter })))
    renderApp('/app/login')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Username (required)'), 'ali')
    await user.type(screen.getByLabelText('Password (required)'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await screen.findByRole('heading', { name: 'Deletion scheduled' })
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })
})

describe('your data', () => {
  it('downloads a JSON blob with the requested dated filename', async () => {
    const exported = { format: 'educore.account-export.v1', profile, enrollments: [], securityEvents: [], securityEventsTruncated: false }
    let blob: Blob | undefined
    let filename = ''
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: (value: Blob) => { blob = value; return 'blob:export' } })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download })
    server.use(...profileHandlers(), http.get(`${API}/me/export`, () => HttpResponse.json(exported)))
    try {
      renderApp('/app/profile')
      await userEvent.setup().click(await screen.findByRole('button', { name: 'Export' }))
      await waitFor(() => expect(filename).toMatch(/^educore-export-\d{4}-\d{2}-\d{2}\.json$/))
      expect(blob?.type).toBe('application/json')
      expect(blob?.size).toBeGreaterThan(0)
      const contents = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result))
        reader.onerror = () => reject(reader.error)
        reader.readAsText(blob!)
      })
      expect(JSON.parse(contents)).toEqual(exported)
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:export')
    } finally {
      Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: originalCreate })
      Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: originalRevoke })
    }
  })

  it('shows Retry-After seconds for a rate-limited export', async () => {
    server.use(...profileHandlers(), http.get(`${API}/me/export`, () => HttpResponse.json({ code: 'rate-limit/exceeded' }, { status: 429, headers: { 'Retry-After': '37' } })))
    renderApp('/app/profile')
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Export' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 37 seconds.')
  })

  it('validates the password, resists accidental dismissal and clears the session after scheduling', async () => {
    let body: unknown
    server.use(...profileHandlers(), http.delete(`${API}/me`, async ({ request }) => { body = await request.json(); return HttpResponse.json({ status: 'PENDING_DELETION', deleteAfter }, { status: 202 }) }))
    renderApp('/app/profile')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Delete my account' }))
    const dialog = screen.getByRole('dialog')
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    expect(dialog).toHaveAttribute('open')
    await user.click(within(dialog).getByRole('button', { name: 'Schedule deletion' }))
    expect(await screen.findByText('Enter your current password.')).toBeInTheDocument()
    expect(body).toBeUndefined()
    await user.type(within(dialog).getByLabelText('Current password (required)'), 'my-password')
    await user.click(within(dialog).getByRole('button', { name: 'Schedule deletion' }))
    await screen.findByRole('heading', { name: 'Sign in to EduCore' })
    expect(body).toEqual({ currentPassword: 'my-password' })
    expect(getAccessToken()).toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent(`Deletion scheduled until ${formatAccountDate(deleteAfter)}; sign in to restore.`)
    await screen.findByRole('heading', { name: 'Sign in to EduCore' })
  })

  it.each([
    [400, 'auth/invalid-current-password', 'The current password is incorrect.'],
    [423, 'auth/account-locked', 'The account is locked for now.'],
    [429, 'auth/too-many-attempts', 'Too many sign-in attempts.'],
    [409, 'account/last-admin', 'At least one administrator must remain.'],
  ])('keeps deletion errors inline (%s)', async (status, code, message) => {
    server.use(...profileHandlers(), http.delete(`${API}/me`, () => problem(Number(status), String(code))))
    renderApp('/app/profile')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Delete my account' }))
    await user.type(screen.getByLabelText('Current password (required)'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Schedule deletion' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(String(message))
    expect(getAccessToken()).toBe('token-2')
  })
})

describe('admin lifecycle and audit', () => {
  it.each([
    ['account/self-delete', 'You cannot delete your own account.'],
    ['account/last-admin', 'At least one administrator must remain.'],
  ])('explains admin deletion conflicts (%s)', async (code, message) => {
    server.use(...sessionHandlers(adminUser),
      http.get(`${API}/admin/accounts`, () => HttpResponse.json({ content: [account], page: 0, size: 20, totalElements: 1, totalPages: 1 })),
      http.post(`${API}/admin/accounts/10/purge`, () => problem(409, code)))
    renderApp('/app/users')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Delete permanently' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), account.username)
    await user.click(within(dialog).getByRole('button', { name: 'Delete permanently' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(message)
  })
  it('requires the exact username and reports confirmation mismatch before permanent deletion', async () => {
    let calls = 0
    let body: unknown
    server.use(...sessionHandlers(adminUser),
      http.get(`${API}/admin/accounts`, () => HttpResponse.json({ content: [account], page: 0, size: 20, totalElements: 1, totalPages: 1 })),
      http.post(`${API}/admin/accounts/10/purge`, async ({ request }) => { calls++; expect(new URL(request.url).search).toBe(''); body = await request.json(); return calls === 1 ? problem(400, 'account/confirmation-mismatch') : new HttpResponse(null, { status: 204 }) }))
    renderApp('/app/users')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Delete permanently' }))
    const dialog = screen.getByRole('dialog')
    const submit = within(dialog).getByRole('button', { name: 'Delete permanently' })
    const input = within(dialog).getByRole('textbox')
    expect(submit).toBeDisabled()
    await user.type(input, 'Student10')
    expect(submit).toBeDisabled()
    await user.clear(input)
    await user.type(input, account.username)
    expect(submit).toBeEnabled()
    await user.click(submit)
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('The username does not match.')
    await user.click(submit)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(body).toEqual({ confirm: account.username })
  })

  it.each(['/app/students', '/app/users'])('shows statuses and restores deleted-filter rows on %s', async path => {
    let restored = false
    const requests: boolean[] = []
    server.use(...sessionHandlers(adminUser),
      http.get(`${API}/admin/accounts${path.endsWith('students') ? '/students' : ''}`, ({ request }) => {
        const deleted = new URL(request.url).searchParams.get('deleted') === 'true'
        requests.push(deleted)
        return HttpResponse.json({ content: deleted ? [{ ...account, status: 'PENDING_DELETION', deleteAfter }, { ...account, id: 11, username: 'inactive', status: 'DEACTIVATED' }] : [account], page: 0, size: 20, totalElements: 2, totalPages: 1 })
      }),
      http.post(`${API}/admin/accounts/10/restore`, () => { restored = true; return HttpResponse.json(account) }))
    renderApp(path)
    const user = userEvent.setup()
    expect(await screen.findByText('Active')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show deleted' }))
    await screen.findByText('Deactivated')
    expect(screen.getByText(/Pending deletion/)).toBeInTheDocument()
    expect(document.querySelector('time')).toHaveAttribute('datetime', deleteAfter)
    await user.click(screen.getAllByRole('button', { name: 'Restore' })[0])
    await waitFor(() => expect(restored).toBe(true))
    expect(requests).toContain(true)
  })

  it('paginates audit events and renders purged account pseudonyms', async () => {
    const pages: number[] = []
    const event: SecurityEvent = { id: 1, type: 'ACCOUNT_PURGED', actorAccountId: null, targetAccountId: null, actorPseudonym: 'purged:0123456789abcdef', targetPseudonym: 'purged:fedcba9876543210', ip: null, requestId: 'req-1', at: '2026-10-02T10:00:00Z', details: { trigger: 'ADMIN_HARD_DELETE' } }
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/security-events`, ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page'))
      pages.push(page)
      expect(params.get('size')).toBe('20')
      return HttpResponse.json({ content: [{ ...event, id: page + 1, requestId: `req-${page + 1}` }], page, size: 20, totalElements: 21, totalPages: 2 })
    }))
    renderApp('/app/audit')
    expect(await screen.findByText(event.actorPseudonym!)).toBeInTheDocument()
    expect(screen.getByText(event.targetPseudonym!)).toBeInTheDocument()
    expect(screen.getByText('trigger: ADMIN_HARD_DELETE')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Next page' }))
    await screen.findByText('req-2')
    expect(pages).toEqual([0, 1])
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(screen.getByRole('link', { name: 'Security events' })).toHaveAttribute('href', '/app/audit')
  })
})
