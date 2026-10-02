import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { IpRule } from '../app/lib/types'
import { API, adminUser, problem, server, sessionHandlers } from './msw'
import { renderApp } from './render-app'

function setup(onCreate: (body: unknown) => Response | Promise<Response>) {
  const rules: IpRule[] = []
  const posts: unknown[] = []
  server.use(
    ...sessionHandlers(adminUser),
    http.get(`${API}/admin/ip-allocations`, () => HttpResponse.json(rules)),
    http.post(`${API}/admin/ip-allocations`, async ({ request }) => {
      const body = await request.json()
      posts.push(body)
      const response = await onCreate(body)
      if (response.status === 201) rules.push({ id: rules.length + 1, ...(body as Omit<IpRule, 'id'>) })
      return response
    }),
  )
  return { posts }
}

async function openForm() {
  const user = userEvent.setup()
  renderApp('/app/ip-allocations')
  await user.click(await screen.findByRole('button', { name: /Add rule/ }))
  const dialog = await screen.findByRole('dialog', { name: 'Add rule' })
  return { user, dialog }
}

describe('IP rule form validation', () => {
  it('rejects a malformed single address before calling the API', async () => {
    const { posts } = setup(() => HttpResponse.json({}, { status: 201 }))
    const { user, dialog } = await openForm()

    await user.type(within(dialog).getByLabelText('IP address (required)'), '192.168.1.256')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))

    expect(await within(dialog).findByText('Enter an IPv4 address such as 192.168.1.5.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('IP address (required)')).toHaveAttribute('aria-invalid', 'true')
    expect(posts).toEqual([])
  })

  it('rejects leading zeros, reversed ranges and prefixes above /32', async () => {
    const { posts } = setup(() => HttpResponse.json({}, { status: 201 }))
    const { user, dialog } = await openForm()

    await user.type(within(dialog).getByLabelText('IP address (required)'), '010.0.0.1')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText('Enter an IPv4 address such as 192.168.1.5.')).toBeInTheDocument()

    await user.selectOptions(within(dialog).getByLabelText('Type (required)'), 'RANGE')
    await user.type(within(dialog).getByLabelText('Start address (required)'), '10.0.0.20')
    await user.type(within(dialog).getByLabelText('End address (required)'), '10.0.0.10')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText('The end address must be greater than or equal to the start address.')).toBeInTheDocument()

    await user.selectOptions(within(dialog).getByLabelText('Type (required)'), 'CIDR')
    await user.type(within(dialog).getByLabelText('Subnet (CIDR) (required)'), '10.0.0.0/33')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText('The subnet prefix must be between /0 and /32.')).toBeInTheDocument()

    expect(posts).toEqual([])
  })

  it('posts a valid range as "start-end" and lists the new rule', async () => {
    const { posts } = setup(() => HttpResponse.json({ id: 1, type: 'RANGE', originalValue: '10.0.0.1-10.0.0.9' }, { status: 201 }))
    const { user, dialog } = await openForm()

    await user.selectOptions(within(dialog).getByLabelText('Type (required)'), 'RANGE')
    await user.type(within(dialog).getByLabelText('Start address (required)'), ' 10.0.0.1 ')
    await user.type(within(dialog).getByLabelText('End address (required)'), '10.0.0.9')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))

    expect(await screen.findByRole('cell', { name: '10.0.0.1-10.0.0.9' })).toBeInTheDocument()
    expect(posts).toEqual([{ type: 'RANGE', originalValue: '10.0.0.1-10.0.0.9' }])
    expect(await screen.findByText('IP allocation added.')).toBeInTheDocument()
  })

  it('shows the server-side ip-allocation/invalid problem on the value field', async () => {
    setup(() => problem(400, 'ip-allocation/invalid'))
    const { user, dialog } = await openForm()

    await user.type(within(dialog).getByLabelText('IP address (required)'), '10.0.0.1')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))

    expect(await within(dialog).findByText('Enter a valid IPv4 allocation. CIDR blocks must use the network address with no host bits set.')).toBeInTheDocument()
  })
})
