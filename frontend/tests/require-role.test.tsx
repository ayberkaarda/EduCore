import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { API, regularUser, server, sessionHandlers } from './msw'
import { renderApp } from './render-app'

describe('RequireRole', () => {
  it.each(['/app/users', '/app/students', '/app/students/7', '/app/logs', '/app/ip-rules', '/app/ip-allocations', '/app/imports', '/app/webhooks', '/app/audit'])(
    'denies a USER on %s without calling the admin API',
    async path => {
      const adminCalls: string[] = []
      server.use(
        ...sessionHandlers(regularUser),
        http.all(`${API}/admin/*`, ({ request }) => {
          adminCalls.push(request.url)
          return HttpResponse.json({}, { status: 403 })
        }),
      )

      renderApp(path)

      expect(await screen.findByRole('heading', { name: 'You do not have access to this page' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Go to my profile' })).toHaveAttribute('href', '/app/profile')
      // The admin navigation is hidden for USER as well.
      expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument()
      expect(adminCalls).toEqual([])
    },
  )
})
