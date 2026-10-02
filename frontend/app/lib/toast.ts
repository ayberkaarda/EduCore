import { useSyncExternalStore } from 'react'

export type ToastKind = 'success' | 'error' | 'info'

export interface Toast {
  id: string
  kind: ToastKind
  action?: { label: string; onClick: () => void }
  persistent?: boolean
  message: string
}

export interface ToastOptions {
  /** Reusing an id replaces the toast instead of stacking a duplicate. */
  id?: string
  /** Milliseconds; `Infinity` keeps the toast until it is dismissed. */
  duration?: number
  action?: Toast['action']
}

const DEFAULT_DURATION: Record<ToastKind, number> = { success: 4000, info: 4000, error: 8000 }
/** Transient toasts are dropped to meet this limit; persistent actions remain until dismissed. */
export const MAX_VISIBLE_TOASTS = 3

let toasts: Toast[] = []
let counter = 0
const timers = new Map<string, ReturnType<typeof setTimeout>>()
const subscribers = new Set<() => void>()

const emit = () => subscribers.forEach(notify => notify())

export function dismissToast(id: string): void {
  const timer = timers.get(id)
  if (timer) clearTimeout(timer)
  timers.delete(id)
  if (!toasts.some(item => item.id === id)) return
  toasts = toasts.filter(item => item.id !== id)
  emit()
}

function show(kind: ToastKind, message: string, options: ToastOptions = {}): string {
  counter += 1
  const id = options.id ?? `toast-${counter}`
  const existingTimer = timers.get(id)
  if (existingTimer) clearTimeout(existingTimer)
  const duration = options.duration ?? DEFAULT_DURATION[kind]
  const next = [...toasts.filter(item => item.id !== id), { id, kind, message, action: options.action, persistent: !Number.isFinite(duration) }]
  const dropped = next.filter(item => !item.persistent).slice(0, Math.max(0, next.length - MAX_VISIBLE_TOASTS))
  dropped.forEach(item => {
    const timer = timers.get(item.id)
    if (timer) clearTimeout(timer)
    timers.delete(item.id)
  })
  toasts = next.filter(item => !dropped.includes(item))
  if (Number.isFinite(duration)) timers.set(id, setTimeout(() => dismissToast(id), duration))
  emit()
  return id
}

export const toast = {
  success: (message: string, options?: ToastOptions) => show('success', message, options),
  error: (message: string, options?: ToastOptions) => show('error', message, options),
  info: (message: string, options?: ToastOptions) => show('info', message, options),
  dismiss: dismissToast,
  /** Removes every toast (used by tests and on sign-out). */
  clear: () => {
    timers.forEach(timer => clearTimeout(timer))
    timers.clear()
    toasts = []
    emit()
  },
}

const subscribe = (notify: () => void) => {
  subscribers.add(notify)
  return () => {
    subscribers.delete(notify)
  }
}
const snapshot = () => toasts
const EMPTY: Toast[] = []
const emptySnapshot = () => EMPTY

export function useToasts(): Toast[] {
  return useSyncExternalStore(subscribe, snapshot, emptySnapshot)
}
