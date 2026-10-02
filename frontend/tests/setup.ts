import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { api, clearSession } from '../app/lib/api'
import { toast } from '../app/lib/toast'
import { server, SESSION_CREDENTIAL_HEADER } from './msw'

// jsdom implements <dialog> without the modal API; this mirrors the browser behaviour the app relies on
// (open attribute, and a "close" event dispatched as a separate task).
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.show = function show(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    setTimeout(() => this.dispatchEvent(new Event('close')), 0)
  }
}

let credentialInterceptor: number
beforeAll(() => {
  credentialInterceptor = api.interceptors.request.use(config => {
    config.headers.delete(SESSION_CREDENTIAL_HEADER)
    if (config.withCredentials === true) config.headers.set(SESSION_CREDENTIAL_HEADER, 'include')
    return config
  })
  server.listen({ onUnhandledFrame: 'error' })
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearSession()
  toast.clear()
  document.body.querySelectorAll('dialog').forEach(dialog => dialog.remove())
})
afterAll(() => {
  api.interceptors.request.eject(credentialInterceptor)
  server.close()
})
