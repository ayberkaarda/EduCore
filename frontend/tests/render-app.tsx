import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ComponentType } from 'react'
import { StrictMode } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import Toaster from '../app/components/Toaster'
import { createQueryClient } from '../app/lib/query-client'
import routeConfig from '../app/routes'

// The real route table (app/routes.ts) with the real route modules, rendered in a memory router.
const modules = import.meta.glob<{ default: ComponentType }>('../app/routes/**/*.tsx', { eager: true })
const featureModules = import.meta.glob<{ default: ComponentType }>('../app/features/profile-lifecycle/components/*.tsx', { eager: true })

interface ConfigEntry {
  id?: string
  path?: string
  index?: boolean
  file: string
  children?: ConfigEntry[]
}

function toRouteObject(entry: ConfigEntry): RouteObject {
  const routeModule = modules[`../app/${entry.file}`] ?? featureModules[`../app/${entry.file}`]
  if (!routeModule) throw new Error(`No route module for ${entry.file}`)
  const base = { id: entry.id ?? entry.file, Component: routeModule.default }
  if (entry.index) return { ...base, index: true }
  return { ...base, path: entry.path, children: entry.children?.map(toRouteObject) }
}

export const appRoutes: RouteObject[] = (routeConfig as unknown as ConfigEntry[]).map(toRouteObject)

/** Renders the app at `path` (StrictMode, fresh query cache, the single toast region). */
export function renderApp(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const queryClient = createQueryClient()
  const view = render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <Toaster />
      </QueryClientProvider>
    </StrictMode>,
  )
  return { ...view, router, queryClient }
}
