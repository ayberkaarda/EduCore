import { useState, useEffect } from 'react'
import axios from 'axios'
import { FileText, CheckCircle, XCircle, Loader2 } from 'lucide-react'

export default function JobLogs({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [logs, setLogs] = useState([])
    const [isLoading, setIsLoading] = useState(true)

    useEffect(() => {
        if(isAdmin) {
            axios.get('http://localhost:8080/api/v1/logs')
                .then(res => setLogs(res.data))
                .catch(err => console.error(err))
                .finally(() => setIsLoading(false))
        }
    }, [isAdmin])

    if (!isAdmin) return <div className="card"><h2>Access Denied</h2><p>Only administrators can view system logs.</p></div>

    return (
        <div className="card">
            <div className="detail-header" style={{ marginBottom: '2rem' }}>
                <div>
                    <h2><FileText size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> CSV Import Job Logs</h2>
                    <p className="text-gray">Monitor the status of automated student imports.</p>
                </div>
            </div>

            <div className="table-responsive">
                {isLoading ? <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>File Name</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Date</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Success</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Failed</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Status</th>
                        </tr>
                        </thead>
                        <tbody>
                        {logs.map((log) => (
                            <tr key={log.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>{log.fileName}</td>
                                <td style={{ padding: '1rem' }}>{new Date(log.createdAt).toLocaleString()}</td>
                                <td style={{ padding: '1rem', color: '#10b981', fontWeight: '600' }}>{log.successfulRecords}</td>
                                <td style={{ padding: '1rem', color: '#ef4444', fontWeight: '600' }}>{log.failedRecords}</td>
                                <td style={{ padding: '1rem' }}>
                                    {log.status === 'SUCCESS' ?
                                        <span className="badge success"><CheckCircle size={12} style={{display:'inline', marginRight:'4px'}}/> SUCCESS</span> :
                                        <span className="badge" style={{ backgroundColor: '#fee2e2', color: '#b91c1c' }}><XCircle size={12} style={{display:'inline', marginRight:'4px'}}/> FAILED</span>
                                    }
                                </td>
                            </tr>
                        ))}
                        {logs.length === 0 && <tr><td colSpan="5" className="empty-state">No jobs have been run yet.</td></tr>}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    )
}