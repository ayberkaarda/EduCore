import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { Account } from '../app/lib/types'
import { API, adminUser, server, sessionHandlers } from './msw'
import { renderApp } from './render-app'

const students: Account[] = Array.from({ length: 11 }, (_, index) => ({
  id: index + 10,
  status: 'ACTIVE',
  deleteAfter: null,
  username: `student${index}`,
  firstName: `Student${String(index).padStart(2, '0')}`,
  lastName: 'Test',
  studentNumber: `26010${String(index).padStart(2, '0')}`,
  role: 'USER',
  ipAddress: null,
}))

describe('students list', () => {
  it('pages through the server-side listing with one request per query', async () => {
    const user = userEvent.setup()
    const requests: URLSearchParams[] = []
    server.use(
      ...sessionHandlers(adminUser),
      http.get(`${API}/admin/accounts/students`, ({ request }) => {
        const params = new URL(request.url).searchParams
        requests.push(params)
        const page = Number(params.get('page'))
        const size = Number(params.get('size'))
        const matching = students.filter(student => student.firstName.toLowerCase().includes((params.get('search') ?? '').toLowerCase()))
        return HttpResponse.json({
          content: matching.slice(page * size, page * size + size),
          page,
          size,
          totalElements: matching.length,
          totalPages: Math.ceil(matching.length / size),
        })
      }),
    )

    renderApp('/app/students')

    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 8)
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(requests).toHaveLength(1)
    expect(Object.fromEntries(requests[0])).toEqual({ search: '', page: '0', size: '8', direction: 'asc', deleted: 'false' })

    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(1 + 3)
    expect(screen.getByRole('cell', { name: '#2601010' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(requests).toHaveLength(2)
    expect(requests[1].get('page')).toBe('1')

    // Going back is served from the cache (staleTime), so no third request is sent.
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(requests).toHaveLength(2)
  })

  it('a debounced search sends one request for the final term and resets to the first page', async () => {
    const user = userEvent.setup()
    const searches: string[] = []
    server.use(
      ...sessionHandlers(adminUser),
      http.get(`${API}/admin/accounts/students`, ({ request }) => {
        const params = new URL(request.url).searchParams
        searches.push(`${params.get('search')}@${params.get('page')}`)
        const page = Number(params.get('page'))
        return HttpResponse.json({ content: students.slice(page * 8, page * 8 + 8), page, size: 8, totalElements: 11, totalPages: 2 })
      }),
    )
    renderApp('/app/students')
    await screen.findByText('Page 1 of 2')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Page 2 of 2')

    await user.type(screen.getByRole('textbox', { name: 'Search by name or number' }), 'Stu')

    await waitFor(() => expect(searches).toContain('Stu@0'))
    expect(searches.filter(entry => entry.startsWith('S') && entry !== 'Stu@0')).toEqual([])
  })
})

it('clamps the last page after deleting its last row', async () => {
  const user = userEvent.setup()
  let remaining = students.slice(0, 9)
  server.use(
    ...sessionHandlers(adminUser),
    http.get(`${API}/admin/accounts/students`, ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json({ content: remaining.slice(page * 8, page * 8 + 8), page, size: 8, totalElements: remaining.length, totalPages: Math.ceil(remaining.length / 8) })
    }),
    http.delete(`${API}/admin/accounts/:id`, ({ params }) => {
      remaining = remaining.filter(student => student.id !== Number(params.id))
      return new HttpResponse(null, { status: 204 })
    }),
  )
  renderApp('/app/students')
  await screen.findByText('Page 1 of 2')
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByText('Page 2 of 2')
  await user.click(screen.getByRole('button', { name: 'Delete' }))
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
  await waitFor(() => expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(9))
  expect(screen.queryByText('Page 2 of 2')).not.toBeInTheDocument()
})
