import { useState } from 'react'
import { Link } from 'react-router'
import { describeApiError } from '../../../lib/error-messages'
import { useUploadImport } from '../hooks'
import { importFileSchema } from '../schemas'
export default function ImportsScreen() {
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState('')
  const upload = useUploadImport()
  const submit = async () => {
    const parsed = importFileSchema.safeParse(file)
    if (!parsed.success) { setError(parsed.error.issues[0].message); return }
    setError('')
    try { await upload.mutateAsync(parsed.data) } catch (failure) { setError(describeApiError(failure, 'Could not upload the CSV file. Check the file and try again.')) }
  }
  return <div><h2>Import CSV</h2><p className="text-gray">Upload a student or course CSV file, up to 5 MB.</p><form onSubmit={event => { event.preventDefault(); void submit() }} noValidate>
    <div className="form-group"><label htmlFor="import-file">CSV file (required)</label><input id="import-file" type="file" accept=".csv" disabled={upload.isPending} aria-invalid={!!error} aria-describedby={error ? 'import-error' : 'import-hint'} onChange={event => { setFile(event.target.files?.[0] ?? null); setError(''); upload.reset() }} /><p id="import-hint" className="field-hint">A .csv file containing the required header and data rows.</p>{error && <p id="import-error" className="field-error" role="alert">{error}</p>}</div>
    <button className="btn-primary" disabled={upload.isPending}>{upload.isPending ? 'Uploading…' : 'Upload CSV'}</button>
  </form>{upload.data && <div role="status"><h3>Import accepted</h3><p><span className="mono">{upload.data.inboxFileName}</span> · {upload.data.kind} · {upload.data.rows} rows · {upload.data.size} bytes</p><p>The file is queued for processing. Follow its result in job logs.</p></div>}<p><Link to="/app/logs">View job logs</Link></p></div>
}
