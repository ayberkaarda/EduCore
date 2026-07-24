import { useState, useEffect } from 'react'
import axios from 'axios'
import { FileText, CheckCircle, XCircle, Loader2, FileSearch, Trash2, Download } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'

export default function JobLogs({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [logs, setLogs] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [selectedLog, setSelectedLog] = useState(null)

    const [selectedIds, setSelectedIds] = useState([])

    const fetchLogs = () => {
        setIsLoading(true)
        axios.get('http://localhost:8081/api/v1/logs')
            .then(res => {
                setLogs(res.data)
                setSelectedIds([])
            })
            .catch(err => console.error(err))
            .finally(() => setIsLoading(false))
    }

    useEffect(() => {
        if(isAdmin) { fetchLogs() }
    }, [isAdmin])

    if (!isAdmin) return <div className="card"><h2>Access Denied</h2><p>Only administrators can view system logs.</p></div>

    const parseDetails = (jsonString) => {
        try { return JSON.parse(jsonString); }
        catch(e) { return []; }
    }

    const handleSelect = (id) => {
        if (selectedIds.includes(id)) {
            setSelectedIds(selectedIds.filter(itemId => itemId !== id));
        } else {
            setSelectedIds([...selectedIds, id]);
        }
    }

    const handleSelectAll = (e) => {
        if (e.target.checked) {
            setSelectedIds(logs.map(log => log.id));
        } else {
            setSelectedIds([]);
        }
    }

    // --- SİLME İŞLEMİ ---
    const handleDeleteSelected = async () => {
        if (selectedIds.length === 0) return;
        if (!window.confirm(`Are you sure you want to delete ${selectedIds.length} log(s)?`)) return;

        try {
            await axios.delete(`http://localhost:8081/api/v1/logs?ids=${selectedIds.join(',')}`);
            toast.success('Logs successfully deleted!');
            fetchLogs();
        } catch (error) {
            toast.error('Failed to delete logs. Server might be unreachable.');
        }
    }

    // --- GÜNCELLENDİ: HER LOG İÇİN AYRI DOSYA İNDİRME İŞLEMİ ---
    const handleDownloadSelected = () => {
        if (selectedIds.length === 0) return;

        const logsToExport = logs.filter(log => selectedIds.includes(log.id));

        // Döngüye alıp her birini ayrı ayrı indiriyoruz
        logsToExport.forEach(log => {
            const detailedData = {
                ...log,
                detailedLogs: parseDetails(log.detailedLogs)
            };
            const dataStr = JSON.stringify(detailedData, null, 2);
            const blob = new Blob([dataStr], { type: "application/json" });
            const url = URL.createObjectURL(blob);

            const link = document.createElement('a');
            link.href = url;
            // Dosya adını birime ve log ismine göre özel yapıyoruz
            link.download = `Log_${log.entityType}_${log.fileName}_ID-${log.id}.json`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);
        });

        toast.success(`${selectedIds.length} file(s) downloaded!`);
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header" style={{ marginBottom: '2rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                    <h2><FileText size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> System Import Logs</h2>
                    <p className="text-gray">Monitor the status of automated student and course imports.</p>
                </div>

                {selectedIds.length > 0 && (
                    <div style={{ display: 'flex', gap: '0.75rem' }}>
                        <button className="btn-secondary" onClick={handleDownloadSelected} style={{ backgroundColor: '#eff6ff', color: '#1d4ed8', border: '1px solid #bfdbfe', padding: '0.6rem 1rem', display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                            <Download size={16} />
                            Download ({selectedIds.length})
                        </button>
                        <button className="btn-secondary" onClick={handleDeleteSelected} style={{ backgroundColor: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca', padding: '0.6rem 1rem', display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                            <Trash2 size={16} />
                            Delete ({selectedIds.length})
                        </button>
                    </div>
                )}
            </div>

            <div className="table-responsive">
                {isLoading ? <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', width: '40px' }}>
                                <input type="checkbox" onChange={handleSelectAll} checked={logs.length > 0 && selectedIds.length === logs.length} style={{ cursor: 'pointer', transform: 'scale(1.2)' }} />
                            </th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>File Name</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Unit (Entity)</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Date</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Success</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Failed</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Status</th>
                            <th style={{ padding: '1rem', color: '#6b7280', textAlign: 'right' }}>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {logs.map((log) => (
                            <tr key={log.id} style={{ borderBottom: '1px solid #e5e7eb', backgroundColor: selectedIds.includes(log.id) ? '#eff6ff' : 'transparent', transition: 'background-color 0.2s' }}>
                                <td style={{ padding: '1rem' }}>
                                    <input type="checkbox" checked={selectedIds.includes(log.id)} onChange={() => handleSelect(log.id)} style={{ cursor: 'pointer', transform: 'scale(1.2)' }} />
                                </td>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>{log.fileName}</td>
                                <td style={{ padding: '1rem' }}>
                      <span className="badge" style={{ backgroundColor: log.entityType === 'COURSES' ? '#fef3c7' : '#e0e7ff', color: log.entityType === 'COURSES' ? '#b45309' : '#3730a3' }}>
                          {log.entityType || 'UNKNOWN'}
                      </span>
                                </td>
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
                        {logs.length === 0 && <tr><td colSpan="8" className="empty-state">No jobs have been run yet.</td></tr>}
                        </tbody>
                    </table>
                )}
            </div>

            {selectedLog && (
                <div className="modal-overlay" style={{ alignItems: 'flex-start', overflowY: 'auto', padding: '3rem 1rem' }}>
                    <div className="modal-content" style={{ maxWidth: '650px', width: '100%', margin: '0 auto' }}>
                        <h3 style={{ marginTop: 0, borderBottom: '1px solid #e5e7eb', paddingBottom: '1rem' }}>
                            Execution Details: <span style={{color: '#4f46e5'}}>{selectedLog.fileName}</span>
                        </h3>

                        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '1rem' }}>
                            {!selectedLog.detailedLogs || parseDetails(selectedLog.detailedLogs).length === 0 ? (
                                <p className="text-gray text-center" style={{ margin: '2rem 0' }}>No detailed logs recorded for this execution.</p>
                            ) : (
                                parseDetails(selectedLog.detailedLogs).map((detail, idx) => (
                                    <div key={idx} style={{ padding: '0.8rem', borderRadius: '0.5rem', backgroundColor: detail.status === 'SUCCESS' ? '#f0fdf4' : '#fef2f2', border: `1px solid ${detail.status === 'SUCCESS' ? '#bbf7d0' : '#fecaca'}`, display: 'flex', gap: '1rem', alignItems: 'flex-start' }}>
                                        {detail.status === 'SUCCESS' ? <CheckCircle size={20} color="#10b981" style={{marginTop: '2px', minWidth: '20px'}}/> : <XCircle size={20} color="#ef4444" style={{marginTop: '2px', minWidth: '20px'}}/>}
                                        <span style={{ color: '#374151', lineHeight: '1.4' }}>{detail.message}</span>
                                    </div>
                                ))
                            )}
                        </div>

                        <div className="modal-actions" style={{ marginTop: '2rem' }}>
                            <button type="button" className="btn-secondary" onClick={() => setSelectedLog(null)} style={{width: '100%', justifyContent: 'center', padding: '0.8rem'}}>Close Details</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}