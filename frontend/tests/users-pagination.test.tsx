import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { API, adminUser, server, sessionHandlers } from './msw'
import { renderApp } from './render-app'

it('requests users in pages of 20, reports totals and resets search', async () => {
  const user = userEvent.setup()
  const requests: URLSearchParams[] = []
  server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/accounts`, ({ request }) => {
    const params = new URL(request.url).searchParams
    requests.push(params)
    const page = Number(params.get('page'))
    return HttpResponse.json({ content: [{ id: page + 10, username: `user${page}`, firstName: `Person${page}`, lastName: '', studentNumber: null, role: 'USER', ipAddress: null, status: 'ACTIVE', deleteAfter: null }], page, size: 20, totalElements: 21, totalPages: 2 })
  }))
  renderApp('/app/users')
  await screen.findByText('Page 1 of 2')
  expect(screen.getByText('21 users')).toBeInTheDocument()
  expect(requests[0].get('size')).toBe('20')
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByText('Person1')
  expect(requests[1].get('page')).toBe('1')
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  await user.type(screen.getByRole('textbox', { name: 'Search users' }), 'Ada')
  await waitFor(() => expect(requests.some(params => params.get('search') === 'Ada' && params.get('page') === '0')).toBe(true))
})
