import { index, layout, route, type RouteConfig } from '@react-router/dev/routes'

// Public pages (Turkish at the root, English under /en; docs/seo/I18N.md) are prerendered at build time
// (react-router.config.ts); everything under /app is the client-rendered SPA.
// Roles per route mirror docs/security/RBAC_MATRIX.md; the API enforces them independently.
function publicPages(locale: 'tr' | 'en') {
  const id = (name: string) => `public-${locale}-${name}`
  return [
    index('routes/public/home.tsx', { id: id('home') }),
    route('courses', 'routes/public/courses.tsx', { id: id('courses') }),
    route('courses/:slug', 'routes/public/course.tsx', { id: id('course') }),
    route('about', 'routes/public/about.tsx', { id: id('about') }),
    route('faq', 'routes/public/faq.tsx', { id: id('faq') }),
    route('privacy', 'routes/public/privacy.tsx', { id: id('privacy') }),
    route('security', 'routes/public/security.tsx', { id: id('security') }),
    route('*', 'routes/public/not-found.tsx', { id: id('not-found') }),
  ]
}

export default [
  layout('routes/public/layout.tsx', { id: 'public-tr' }, publicPages('tr')),
  route('en', 'routes/public/layout.tsx', { id: 'public-en' }, publicPages('en')),

  route('app', 'routes/app/session.tsx', [
    route('login', 'routes/app/login.tsx'),
    route('change-password', 'routes/app/change-password.tsx'),
    layout('routes/app/shell.tsx', [
      index('routes/app/dashboard.tsx'),
      route('profile', 'routes/app/profile.tsx'),
      route('courses', 'routes/app/courses.tsx'),
      layout('routes/app/admin.tsx', [
        route('students', 'routes/app/students.tsx'),
        route('students/:id', 'routes/app/student-detail.tsx'),
        route('users', 'routes/app/users.tsx'),
        route('audit', 'features/profile-lifecycle/components/AuditScreen.tsx'),
        route('logs', 'routes/app/logs.tsx'),
        route('ip-rules', 'routes/app/ip-rules.tsx'),
        route('ip-allocations', 'routes/app/ip-allocations.tsx'),
        route('imports', 'routes/app/imports.tsx'),
        route('webhooks', 'routes/app/webhooks.tsx'),
      ]),
      route('*', 'routes/app/unknown.tsx'),
    ]),
  ]),

  // Pre-P8 paths: client-side redirects to their /app/* equivalents. "/courses" is the public catalog since P9.
  route('login', 'routes/legacy-redirect.tsx', { id: 'legacy-login' }),
  route('unauthorized', 'routes/legacy-redirect.tsx', { id: 'legacy-unauthorized' }),
  route('profile', 'routes/legacy-redirect.tsx', { id: 'legacy-profile' }),
  route('students', 'routes/legacy-redirect.tsx', { id: 'legacy-students' }),
  route('students/:id', 'routes/legacy-redirect.tsx', { id: 'legacy-student-detail' }),
  route('users', 'routes/legacy-redirect.tsx', { id: 'legacy-users' }),
  route('logs', 'routes/legacy-redirect.tsx', { id: 'legacy-logs' }),
  route('ips', 'routes/legacy-redirect.tsx', { id: 'legacy-ips' }),
] satisfies RouteConfig
