import { useState, useEffect } from 'react'
import axios from 'axios'
import { FileText, CheckCircle, XCircle, Loader2, FileSearch } from 'lucide-react'

export default function JobLogs({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [logs, setLogs] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [selectedLog, setSelectedLog] = useState(null) // YENİ: Seçilen Log Modalı için

    useEffect(() => {
        if(isAdmin) {
            axios.get('http://localhost:8080/api/v1/logs')
                .then(res => setLogs(res.data))
                .catch(err => console.error(err))
                .finally(() => setIsLoading(false))
        }
    }, [isAdmin])

    if (!isAdmin) return <div className="card"><h2>Access Denied</h2><p>Only administrators can view system logs.</p></div>

    // JSON metnini güvenle parse eden yardımcı fonksiyon
    const parseDetails = (jsonString) => {
        try { return JSON.parse(jsonString); }
        catch(e) { return []; }
    }

    return (
        <div className="card">
            <div className="detail-header" style={{ marginBottom: '2rem' }}>
                <div>
                    <h2><FileText size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> CSV Import Job Logs</h2>
                    <p className="text-gray">Monitor the status of automated student imports and view line details.</p>
                </div>
            </div>

            <div className="table-responsive">
                {isLoading ? <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Unit (Entity)</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>File Name</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Date</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Success</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Failed</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Status</th>
                            <th style={{ padding: '1rem', color: '#6b7280', textAlign: 'right' }}>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {logs.map((log) => (
                            <tr key={log.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                <td style={{ padding: '1rem' }}><span className="badge" style={{ backgroundColor: log.entityType === 'COURSES' ? '#fef3c7' : '#e0e7ff', color: log.entityType === 'COURSES' ? '#b45309' : '#3730a3' }}>{log.entityType}</span></td>
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
                                <td style={{ padding: '1rem', textAlign: 'right' }}>
                                    <button className="btn-secondary" onClick={() => setSelectedLog(log)} style={{ padding: '0.4rem 0.8rem', display: 'inline-flex', alignItems: 'center', gap: '0.5rem' }}>
                                        <FileSearch size={16} /> Details
                                    </button>
                                </td>
                            </tr>
                        ))}
                        {logs.length === 0 && <tr><td colSpan="6" className="empty-state">No jobs have been run yet.</td></tr>}
                        </tbody>
                    </table>
                )}
            </div>

            {/* --- DETAY MODALI (AÇILIR PENCERE) --- */}
            {selectedLog && (
                <div className="modal-overlay">
                    <div className="modal-content" style={{ maxWidth: '650px', width: '100%' }}>
                        <h3 style={{ marginTop: 0, borderBottom: '1px solid #e5e7eb', paddingBottom: '1rem' }}>
                            Execution Details: <span style={{color: '#4f46e5'}}>{selectedLog.fileName}</span>
                        </h3>

                        <div style={{ maxHeight: '400px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '1rem', paddingRight: '0.5rem' }}>
                            {!selectedLog.detailedLogs || parseDetails(selectedLog.detailedLogs).length === 0 ? (
                                <p className="text-gray text-center">No detailed logs recorded for this execution.</p>
                            ) : (
                                parseDetails(selectedLog.detailedLogs).map((detail, idx) => (
                                    <div key={idx} style={{ padding: '0.8rem', borderRadius: '0.5rem', backgroundColor: detail.status === 'SUCCESS' ? '#f0fdf4' : '#fef2f2', border: `1px solid ${detail.status === 'SUCCESS' ? '#bbf7d0' : '#fecaca'}`, display: 'flex', gap: '1rem', alignItems: 'flex-start' }}>
                                        {detail.status === 'SUCCESS' ? <CheckCircle size={20} color="#10b981" style={{marginTop: '2px', minWidth: '20px'}}/> : <XCircle size={20} color="#ef4444" style={{marginTop: '2px', minWidth: '20px'}}/>}
                                        <span style={{ color: '#374151', lineHeight: '1.4' }}>{detail.message}</span>
                                    </div>
                                ))
                            )}
                        </div>

                        <div className="modal-actions" style={{ marginTop: '1.5rem' }}>
                            <button type="button" className="btn-secondary" onClick={() => setSelectedLog(null)} style={{width: '100%', justifyContent: 'center'}}>Close Details</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}