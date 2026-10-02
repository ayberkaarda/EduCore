import js from '@eslint/js'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// Security rules (P8): no raw HTML, no inline style attributes (CSP style-src 'self'), no target=_blank without
// rel="noopener noreferrer", and no Web Storage outside the theme helper (tokens and identity stay in memory).
const securityRules = {
  'no-restricted-syntax': [
    'error',
    {
      selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
      message: 'Raw HTML is banned (XSS). Render text through JSX.',
    },
    {
      selector: "MemberExpression[property.name=/^(innerHTML|outerHTML)$/]",
      message: 'Raw HTML is banned (XSS). Use textContent or JSX.',
    },
    {
      selector: "CallExpression[callee.property.name='insertAdjacentHTML']",
      message: 'Raw HTML is banned (XSS). Use textContent or JSX.',
    },
    {
      selector: "JSXOpeningElement[name.name!=/^[A-Z]/] > JSXAttribute[name.name='style']",
      message: 'Inline style attributes break the Content-Security-Policy (style-src). Use a CSS class.',
    },
    {
      selector: "JSXOpeningElement:has(JSXAttribute[name.name='target'][value.value='_blank']):not(:has(JSXAttribute[name.name='rel'][value.value='noopener noreferrer']))",
      message: 'Links that open a new tab need rel="noopener noreferrer".',
    },
  ],
  'no-restricted-globals': [
    'error',
    { name: 'localStorage', message: 'Only app/lib/theme-storage.ts may use Web Storage (the theme value).' },
    { name: 'sessionStorage', message: 'Session values live in memory (AuthProvider), never in Web Storage.' },
  ],
  'no-restricted-properties': [
    'error',
    { object: 'window', property: 'localStorage', message: 'Only app/lib/theme-storage.ts may use Web Storage (the theme value).' },
    { object: 'window', property: 'sessionStorage', message: 'Session values live in memory (AuthProvider), never in Web Storage.' },
    { object: 'globalThis', property: 'localStorage', message: 'Only app/lib/theme-storage.ts may use Web Storage (the theme value).' },
    { object: 'globalThis', property: 'sessionStorage', message: 'Session values live in memory (AuthProvider), never in Web Storage.' },
  ],
  'no-restricted-imports': ['error', {
    paths: [{ name: 'zod', message: "Import z from app/lib/zod.ts (jitless, so script-src needs no 'unsafe-eval')." }],
  }],
  'no-eval': 'error',
  'no-implied-eval': 'error',
  'no-new-func': 'error',
}

// Route modules may export meta/links/HydrateFallback/ErrorBoundary/Layout next to the component.
const routeExports = ['meta', 'links', 'headers', 'loader', 'clientLoader', 'action', 'clientAction', 'handle', 'HydrateFallback', 'ErrorBoundary', 'Layout']

export default defineConfig([
  globalIgnores(['dist', 'build', '.react-router', 'coverage', 'node_modules']),
  {
    files: ['**/*.{js,mjs,jsx,ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      jsxA11y.flatConfigs.recommended,
    ],
    plugins: { 'react-refresh': reactRefresh },
    languageOptions: {
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      ...securityRules,
      // <RequireRole role="ADMIN"> is a component prop, not an ARIA role.
      'jsx-a11y/aria-role': ['error', { ignoreNonDOM: true }],
      'react-refresh/only-export-components': ['error', { allowConstantExport: true, allowExportNames: routeExports }],
    },
  },
  {
    files: ['app/lib/zod.ts'],
    rules: { 'no-restricted-imports': 'off' },
  },
  {
    files: ['app/lib/theme-storage.ts'],
    rules: { 'no-restricted-properties': 'off', 'no-restricted-globals': 'off' },
  },
  {
    files: ['*.config.{js,ts}', 'scripts/**/*.mjs'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['tests/**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: { 'react-refresh/only-export-components': 'off' },
  },
])
