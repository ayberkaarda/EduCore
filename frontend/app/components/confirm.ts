interface ConfirmOptions {
  /** Label of the accept button (default "Delete"). */
  confirmLabel?: string
  /** Danger style for the accept button (default true). */
  destructive?: boolean
}

/** Modal confirmation built from DOM nodes (textContent only, no HTML parsing). Resolves true on accept. */
export default function confirmAction(message: string, { confirmLabel = 'Delete', destructive = true }: ConfirmOptions = {}): Promise<boolean> {
  return new Promise(resolve => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = document.createElement('dialog')
    dialog.className = 'modal-content confirmation-dialog'
    const heading = document.createElement('h2')
    heading.id = 'confirmation-title'
    heading.textContent = message
    dialog.setAttribute('aria-labelledby', heading.id)
    const actions = document.createElement('div')
    actions.className = 'modal-actions'
    const cancel = document.createElement('button')
    cancel.type = 'button'
    cancel.className = 'btn-secondary'
    cancel.textContent = 'Cancel'
    const accept = document.createElement('button')
    accept.type = 'button'
    accept.className = destructive ? 'btn-primary btn-danger' : 'btn-primary'
    accept.textContent = confirmLabel
    const finish = (value: boolean) => {
      if (dialog.open) dialog.close()
      dialog.remove()
      previousFocus?.focus()
      resolve(value)
    }
    cancel.addEventListener('click', () => finish(false))
    accept.addEventListener('click', () => finish(true))
    dialog.addEventListener('cancel', event => {
      event.preventDefault()
      finish(false)
    })
    actions.append(cancel, accept)
    dialog.append(heading, actions)
    document.body.append(dialog)
    dialog.showModal()
    cancel.focus()
  })
}
