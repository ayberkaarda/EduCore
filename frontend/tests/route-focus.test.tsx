import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Link, Outlet, RouterProvider } from 'react-router'
import { expect, it } from 'vitest'
import RouteFocus from '../app/components/RouteFocus'

it('focuses the new page heading and announces it after navigation', async () => {
  const router = createMemoryRouter([{ element: <><Link to="/second">Next page</Link><main><Outlet /></main><RouteFocus /></>, children: [
    { path: '/', element: <h2>First page</h2> },
    { path: '/second', element: <h2>Second page</h2> },
  ] }])
  const { container } = render(<RouterProvider router={router} />)
  await userEvent.setup().click(screen.getByRole('link', { name: 'Next page' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Second page' })).toHaveFocus())
  expect(screen.getByRole('heading', { name: 'Second page' })).toHaveAttribute('tabindex', '-1')
  expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('Second page')
})