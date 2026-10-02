import { fireEvent, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { API, adminUser, problem, server, sessionHandlers } from './msw'
import { renderApp } from './render-app'
import { deleteJobLogs } from '../app/features/job-logs/api'
const page = <T,>(content: T[], current = 0, totalPages = 1) => ({ content, page: current, size: 20, totalElements: totalPages * content.length, totalPages })
const deny = { id: 1, kind: 'STATIC', value: '203.0.113.7', startIp: '203.0.113.7', endIp: '203.0.113.7', source: 'AUTO', reason: 'Repeated failures', expiresAt: null, createdAt: '2026-10-01T12:00:00Z', createdBy: null }
const webhook = { id: 1, url: 'https://receiver.example/events', events: ['import.completed'], active: true, createdBy: 1, createdAt: '2026-10-01T12:00:00Z', updatedAt: '2026-10-01T12:00:00Z', droppedEvents: 0 }

describe('IP allocations and deny rules', () => {
  it('associates a server CIDR rejection with the allocation value field', async () => {
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/ip-allocations`, () => HttpResponse.json([])), http.post(`${API}/admin/ip-allocations`, () => problem(400, 'ip-allocation/invalid')))
    const user = userEvent.setup()
    renderApp('/app/ip-allocations')
    await user.click(await screen.findByRole('button', { name: /Add rule/ }))
    const dialog = screen.getByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Type (required)'), 'CIDR')
    await user.type(within(dialog).getByLabelText('Subnet (CIDR) (required)'), '10.0.0.0/8')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText('Enter a valid IPv4 allocation. CIDR blocks must use the network address with no host bits set.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Subnet (CIDR) (required)')).toHaveAttribute('aria-invalid', 'true')
  })
  it('shows the CIDR host-bits error before allocating', async () => {
    let posts = 0
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/ip-allocations`, () => HttpResponse.json([])), http.post(`${API}/admin/ip-allocations`, () => { posts++; return HttpResponse.json({}) }))
    const user = userEvent.setup()
    renderApp('/app/ip-allocations')
    await user.click(await screen.findByRole('button', { name: /Add rule/ }))
    const dialog = screen.getByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Type (required)'), 'CIDR')
    await user.type(within(dialog).getByLabelText('Subnet (CIDR) (required)'), '10.0.0.5/8')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText(/Use the network address with no host bits set/)).toBeInTheDocument()
    expect(posts).toBe(0)
  })
  it.each([
    ['ip-rule/invalid', 'The value does not match the selected rule type, or the range ends before it starts.'],
    ['ip-rule/ipv6-unsupported', 'IPv6 deny rules are not supported. Enter an IPv4 definition.'],
    ['ip-rule/self-deny', 'This rule would block your current IP address. Choose a different range.'],
    ['ip-rule/trusted-proxy', 'This rule would block a trusted proxy. Choose a different range.'],
  ])('maps %s onto the deny value field', async (code, message) => {
    const bodies: unknown[] = []
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/ip-rules`, () => HttpResponse.json(page([deny]))), http.post(`${API}/admin/ip-rules`, async ({ request }) => { bodies.push(await request.json()); return problem(code.includes('deny') || code.includes('proxy') ? 409 : 400, code) }))
    const user = userEvent.setup()
    renderApp('/app/ip-rules')
    expect(await screen.findByText('AUTO')).toHaveClass('warning')
    await user.click(screen.getByRole('button', { name: 'Add deny rule' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('Value (required)'), '203.0.113.7')
    await user.type(within(dialog).getByLabelText('Reason'), 'Manual review')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText(message)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Value (required)')).toHaveAttribute('aria-invalid', 'true')
    expect(bodies).toEqual([{ kind: 'STATIC', value: '203.0.113.7', reason: 'Manual review' }])
  })
})

describe('P6 job logs', () => {
  it('applies filters, pages results and loads entries only when opened', async () => {
    const requests: URL[] = []
    const entries: URL[] = []
    const log = { id: 7, fileName: 'students.csv', entityType: 'STUDENTS', status: 'PARTIAL', reason: null, readRecords: 2, successfulRecords: 1, failedRecords: 1, importedFileId: 3, createdAt: '2026-10-01T12:00:00', startedAt: null, finishedAt: null }
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/job-logs`, ({ request }) => { const url = new URL(request.url); requests.push(url); return HttpResponse.json(page([log], Number(url.searchParams.get('page')), 2)) }), http.get(`${API}/admin/job-logs/7/entries`, ({ request }) => { entries.push(new URL(request.url)); return HttpResponse.json(page([{ id: 9, rowNumber: 2, level: 'WARN', reason: 'ALREADY_EXISTS', rawMasked: 'A***,B***' }], 0, 2)) }))
    const user = userEvent.setup()
    renderApp('/app/logs')
    expect(await screen.findByText('students.csv')).toBeInTheDocument()
    expect(entries).toHaveLength(0)
    await user.selectOptions(screen.getByLabelText('Status'), 'PARTIAL')
    await user.type(screen.getByLabelText('File'), 'students')
    await user.type(screen.getByLabelText('From'), '2026-10-01T00:00:00Z')
    await user.type(screen.getByLabelText('To'), '2026-10-02T00:00:00Z')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('status')).toBe('PARTIAL'))
    expect(requests.at(-1)?.searchParams.get('file')).toBe('students')
    expect(requests.at(-1)?.searchParams.get('from')).toBe('2026-10-01T00:00:00Z')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'))
    await user.click(await screen.findByRole('button', { name: 'Details' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('A***,B***')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(entries.at(-1)?.searchParams.get('page')).toBe('1'))
    expect(entries[0].searchParams.get('size')).toBe('50')
  })
  it('splits deletion into at most 500 positive ids per request', async () => {
    const batches: string[][] = []
    server.use(http.delete(`${API}/admin/job-logs`, ({ request }) => { batches.push(new URL(request.url).searchParams.get('ids')!.split(',')); return new HttpResponse(null, { status: 204 }) }))
    await deleteJobLogs(Array.from({ length: 501 }, (_, index) => index + 1))
    expect(batches.map(batch => batch.length)).toEqual([500, 1])
    expect(batches[1]).toEqual(['501'])
  })
})

describe('manual import', () => {
  it('validates extension and size before upload', async () => {
    let calls = 0
    server.use(...sessionHandlers(adminUser), http.post(`${API}/admin/imports`, () => { calls++; return HttpResponse.json({}) }))
    const user = userEvent.setup({ applyAccept: false })
    renderApp('/app/imports')
    const picker = await screen.findByLabelText('CSV file (required)')
    await user.upload(picker, new File(['data'], 'data.txt'))
    await user.click(screen.getByRole('button', { name: 'Upload CSV' }))
    expect(await screen.findByText('Choose a .csv file.')).toBeInTheDocument()
    await user.upload(picker, new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'students.csv'))
    await user.click(screen.getByRole('button', { name: 'Upload CSV' }))
    expect(await screen.findByText('The CSV file must be 5 MB or smaller.')).toBeInTheDocument()
    expect(calls).toBe(0)
  })
  it.each(['import/file-too-large', 'request/payload-too-large'])('maps upload problem %s', async code => {
    server.use(...sessionHandlers(adminUser), http.post(`${API}/admin/imports`, () => problem(413, code)))
    const user = userEvent.setup()
    renderApp('/app/imports')
    await user.upload(await screen.findByLabelText('CSV file (required)'), new File(['firstName\nAda'], 'students.csv', { type: 'text/csv' }))
    await user.click(screen.getByRole('button', { name: 'Upload CSV' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(code === 'import/file-too-large' ? 'The CSV file must be 5 MB or smaller.' : 'The upload is too large.')
    expect(screen.getByRole('link', { name: 'View job logs' })).toHaveAttribute('href', '/app/logs')
  })
})

describe('webhooks', () => {
  it('requires HTTPS and shows a signing secret only until acknowledged', async () => {
    const subscriptions: unknown[] = []
    const bodies: unknown[] = []
    const secret = `whsec_${'a'.repeat(64)}`
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/webhooks`, () => HttpResponse.json(subscriptions)), http.post(`${API}/admin/webhooks`, async ({ request }) => { bodies.push(await request.json()); subscriptions.push(webhook); return HttpResponse.json({ webhook, secret }, { status: 201 }) }))
    const user = userEvent.setup()
    renderApp('/app/webhooks')
    await user.click(await screen.findByRole('button', { name: 'Add webhook' }))
    const form = screen.getByRole('dialog')
    await user.type(within(form).getByLabelText('URL (required)'), 'http://receiver.example/events')
    await user.click(within(form).getByLabelText('import.completed'))
    await user.click(within(form).getByRole('button', { name: 'Save webhook' }))
    expect(await within(form).findByText('Enter an absolute HTTPS URL without credentials or a fragment.')).toBeInTheDocument()
    expect(bodies).toEqual([])
    await user.clear(within(form).getByLabelText('URL (required)'))
    await user.type(within(form).getByLabelText('URL (required)'), webhook.url)
    await user.click(within(form).getByRole('button', { name: 'Save webhook' }))
    const secretDialog = await screen.findByRole('dialog', { name: 'Webhook created' })
    expect(within(secretDialog).getByLabelText('Signing secret')).toHaveValue(secret)
    await user.keyboard('{Escape}')
    fireEvent(secretDialog, new Event('cancel', { cancelable: true }))
    expect(secretDialog).toBeInTheDocument()
    await user.click(within(secretDialog).getByRole('button', { name: 'I have saved it' }))
    expect(screen.queryByDisplayValue(secret)).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    expect(screen.queryByLabelText('Signing secret')).not.toBeInTheDocument()
    expect(within(screen.getByRole('dialog')).getByLabelText('URL (required)')).toHaveValue(webhook.url)
  })
  it.each([['webhook/queue-full', 409, 'The delivery queue is full.'], ['webhook/too-many-test-events', 429, 'Too many test events.']])('maps test-event failure %s', async (code, status, message) => {
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/webhooks`, () => HttpResponse.json([webhook])), http.post(`${API}/admin/webhooks/1/test`, () => problem(Number(status), String(code))))
    const user = userEvent.setup()
    renderApp('/app/webhooks')
    await user.click(await screen.findByRole('button', { name: 'Send test event' }))
    expect(await screen.findByText(new RegExp(String(message).replaceAll('.', '\\.')))).toBeInTheDocument()
  })
})
