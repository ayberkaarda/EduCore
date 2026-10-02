import { BookOpen, KeyRound, LayoutGrid, LogOut, Menu, Network, ScrollText, Users, type LucideIcon } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import { useAuth } from '../features/auth/auth-context'
import WeatherWidget from '../features/weather/components/WeatherWidget'
import { LogoMark } from './Brand'
import ThemeToggle from './ThemeToggle'

const LABELS: Record<string, string> = {
  '/app': 'Dashboard',
  '/app/students': 'Students',
  '/app/courses': 'Courses',
  '/app/logs': 'Job logs',
  '/app/ip-rules': 'IP rules',
  '/app/ip-allocations': 'IP allocations',
  '/app/imports': 'Import CSV',
  '/app/webhooks': 'Webhooks',
  '/app/profile': 'My profile',
  '/app/users': 'Users',
  '/app/audit': 'Security events',
}

/** Sidebar, top bar and content area of the signed-in app. Admin items are hidden for USER (cosmetic only). */
export default function AppShell({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const isAdmin = user?.role === 'ADMIN'
  const name = user?.firstName ?? ''

  const navItem = (to: string, Icon: LucideIcon, label: string) => (
    <li>
      <NavLink to={to} end={to === '/app'} onClick={() => setMenuOpen(false)} title={label}>
        <Icon size={20} /><span>{label}</span>
      </NavLink>
    </li>
  )

  return (
    <div className={menuOpen ? 'app-layout menu-open' : 'app-layout'}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <nav className="sidebar" aria-label="Main navigation">
        <Link to="/app" className="brand" aria-label="EduCore home"><span className="sidebar-lockup"><LogoMark /><span>EduCore</span></span></Link>
        <p className="nav-group">Registry</p>
        <ul className="nav-links">
          {navItem('/app', LayoutGrid, 'Dashboard')}
          {isAdmin && navItem('/app/students', Users, 'Students')}
          {navItem('/app/profile', Users, 'My profile')}
          {navItem('/app/courses', BookOpen, 'Courses')}
          {navItem('/app/change-password', KeyRound, 'Password')}
        </ul>
        {isAdmin && (
          <>
            <p className="nav-group">Administration</p>
            <ul className="nav-links">
              {navItem('/app/users', Users, 'Users')}
              {navItem('/app/audit', ScrollText, 'Security events')}
              {navItem('/app/logs', ScrollText, 'Job logs')}
              {navItem('/app/ip-rules', Network, 'IP rules')}
              {navItem('/app/ip-allocations', Network, 'IP allocations')}
              {navItem('/app/imports', ScrollText, 'Import CSV')}
              {navItem('/app/webhooks', Network, 'Webhooks')}
            </ul>
          </>
        )}
        <div className="sidebar-footer">
          <div className="user-profile">
            <span className="avatar">{name.slice(0, 2).toUpperCase()}</span>
            <div><p>{name}</p><span className="badge neutral sidebar-badge">{isAdmin ? 'Administrator' : 'User'}</span></div>
          </div>
          <button type="button" onClick={() => void logout()} className="sign-out" title="Sign out"><LogOut size={16} /><span>Sign out</span></button>
        </div>
      </nav>
      <main className="content-area" id="main-content" tabIndex={-1}>
        <header className="top-bar">
          <button type="button" className="menu-toggle btn-secondary" aria-label="Toggle navigation" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}><Menu size={20} /></button>
          <span className="breadcrumb">{LABELS[location.pathname] ?? 'Students / Details'}</span>
          <div className="top-tools"><WeatherWidget /><ThemeToggle /></div>
        </header>
        <div className="page-content">{children}</div>
      </main>
    </div>
  )
}
