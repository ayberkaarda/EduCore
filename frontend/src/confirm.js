// options.confirmLabel defaults to 'Delete'; options.destructive (default true) picks the danger style.
export default function confirmAction(message, { confirmLabel = 'Delete', destructive = true } = {}) {
  return new Promise(resolve => {
    const previousFocus = document.activeElement
    const dialog = document.createElement('dialog')
    dialog.className = 'modal-content confirmation-dialog'
    const heading = document.createElement('h2')
    heading.id = 'confirmation-title'
    heading.textContent = message
    dialog.setAttribute('aria-labelledby', heading.id)
    const actions = document.createElement('div')
    actions.className = 'modal-actions'
    const cancel = document.createElement('button')
    cancel.className = 'btn-secondary'
    cancel.textContent = 'Cancel'
    const accept = document.createElement('button')
    accept.className = destructive ? 'btn-primary btn-danger' : 'btn-primary'
    accept.textContent = confirmLabel
    const finish = value => { dialog.close(); dialog.remove(); previousFocus?.focus(); resolve(value) }
    cancel.addEventListener('click', () => finish(false))
    accept.addEventListener('click', () => finish(true))
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(false) })
    actions.append(cancel, accept)
    dialog.append(heading, actions)
    document.body.append(dialog)
    dialog.showModal()
    cancel.focus()
  })
}
