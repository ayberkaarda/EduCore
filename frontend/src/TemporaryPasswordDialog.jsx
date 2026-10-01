import { useId, useRef, useState } from 'react'
import Dialog from './Dialog'

export default function TemporaryPasswordDialog({ student, onSaved }) {
    const passwordId = useId()
    const warningId = useId()
    const passwordRef = useRef(null)
    const [copyMessage, setCopyMessage] = useState('')

    const copyPassword = async () => {
        try {
            await navigator.clipboard.writeText(student.temporaryPassword)
            setCopyMessage('Password copied.')
        } catch {
            passwordRef.current?.focus()
            passwordRef.current?.select()
            setCopyMessage('Automatic copying is unavailable. The password is selected; use Ctrl+C or Command+C to copy it manually.')
        }
    }

    return (
        <div className="modal-overlay">
            <Dialog className="modal-content temporary-password-dialog">
                <h3>Student created</h3>
                <p>{student.firstName} {student.lastName} · Student number: {student.studentNumber}</p>
                <p id={warningId}>This password is shown only once. The student must change it at first sign-in.</p>
                <p className="field-hint">Escape and clicking outside do not dismiss this dialog. Save the password, then choose “I have saved it”.</p>
                <div className="form-group">
                    <label htmlFor={passwordId}>Temporary password</label>
                    <input
                        ref={passwordRef}
                        id={passwordId}
                        className="temporary-password-field"
                        type="text"
                        value={student.temporaryPassword ?? ''}
                        readOnly
                        autoFocus
                        autoComplete="off"
                        spellCheck={false}
                        aria-describedby={warningId}
                    />
                </div>
                <p role="status" aria-live="polite" aria-atomic="true">{copyMessage}</p>
                <div className="modal-actions">
                    <button type="button" className="btn-secondary" onClick={copyPassword}>Copy password</button>
                    <button type="button" className="btn-primary" onClick={onSaved}>I have saved it</button>
                </div>
            </Dialog>
        </div>
    )
}
