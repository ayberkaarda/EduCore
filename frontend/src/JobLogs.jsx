import { api, apiError } from './api'
import confirmAction from './confirm'
import { useState, useEffect, useCallback } from 'react'
import axios from 'axios'
import { CheckCircle, XCircle, AlertCircle, Loader2, FileSearch, Trash2, Download } from 'lucide-react'
import toast from 'react-hot-toast'
import Dialog from './Dialog'

export default function JobLogs({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [logs, setLogs] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [loadFailed, setLoadFailed] = useState(false)
    const [selectedLog, setSelectedLog] = useState(null)

    const [selectedIds, setSelectedIds] = useState([])

    // isLoading starts as true, so the initial load does not need to set it.
    const loadLogs = useCallback(() => axios.get(api.jobLogs)
        .then(res => {
            setLogs(res.data)
            setSelectedIds([])
        })
        .catch(err => {
            toast.error(apiError(err, 'Failed to load job logs.'))
            setLoadFailed(true)
        })
        .finally(() => setIsLoading(false)), [])

    const fetchLogs = () => {
        setIsLoading(true)
        setLoadFailed(false)
        loadLogs()
    }

    useEffect(() => {
        if(isAdmin) { loadLogs() }
    }, [isAdmin, loadLogs])

    if (!isAdmin) return <div className="card"><h2>You do not have access to this page</h2><p>Only administrators can view system logs.</p></div>

    const parseDetails = (logString) => {
        if (!logString) return [];
        
        if (logString.trim().startsWith('[')) {
            try { return JSON.parse(logString); } 
            catch { /* Hata olursa string olarak bölmeye devam et */ }
        }
        
        return logString.split('\n')
            .filter(line => line.trim() !== '') 
            .map(line => {
                const isSuccess = line.startsWith('✅');
                return {
                    status: isSuccess ? 'SUCCESS' : 'FAILED',
                    message: line.replace('✅ ', '').replace('❌ ', '') 
                };
            });
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

    const handleDeleteSelected = async () => {
        if (selectedIds.length === 0) return;
        if (!await confirmAction(`Delete ${selectedIds.length} selected logs?`)) return;

        try {
            for (let offset = 0; offset < selectedIds.length; offset += 500) {
                await axios.delete(api.jobLogs, { params: { ids: selectedIds.slice(offset, offset + 500).join(',') } });
            }
            toast.success('Logs deleted.');
            fetchLogs();
        } catch (error) {
            toast.error(apiError(error, 'Failed to delete logs. Server might be unreachable.'));
        }
    }

    const handleDownloadSelected = () => {
        if (selectedIds.length === 0) return;

        const logsToExport = logs.filter(log => selectedIds.includes(log.id));

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
            link.download = `Log_${log.entityType}_${log.fileName}_ID-${log.id}.json`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);
        });

        toast.success(`${selectedIds.length} file(s) downloaded!`);
    }

    const renderStatusBadge = (log) => {
    // Verileri kesin olarak sayıya çeviriyoruz (Güvenlik amaçlı Number kullanıyoruz)
    const success = Number(log.successfulRecords) || 0;
    const failed = Number(log.failedRecords) || 0;
    const total = success + failed;

    // 1. Durum: Hiç işlem yapılmadıysa veya başarısız sayısı başarılıdan fazlaysa
    if ((total === 0 && log.status === 'FAILED') || (success === 0 && failed > 0) || (failed > success)) {
        return (
            <span className="badge danger">
                <XCircle size={12} data-layout="inline-icon"/> Failed
            </span>
        );
    }

    // 2. Durum: Hata var ama başarılı kayıt sayısı daha fazla (Kısmi Başarı)
    if (failed > 0 && success >= failed) {
        return (
            <span className="badge warning">
                <AlertCircle size={12} data-layout="inline-icon"/> Partial
            </span>
        );
    }

    // 3. Durum: Hiç hata yok (Tam Başarı)
    return (
        <span className="badge success">
            <CheckCircle size={12} data-layout="inline-icon"/> Success
        </span>
    );
};

    return (
        <div className="card">
            <div className="detail-header split-row">
                <div>
                    <h2>Job logs</h2>
                    <p className="text-gray">Automated student and course imports</p>
                </div>

                {selectedIds.length > 0 && (
                    <div className="selection-bar"><span>{selectedIds.length} selected</span>
                        <button className="btn-secondary inline-group" onClick={handleDownloadSelected}>
                            <Download size={16} />
                            Download JSON ({selectedIds.length})
                        </button>
                        <button className="btn-secondary inline-group" onClick={handleDeleteSelected}>
                            <Trash2 size={16} />
                            Delete ({selectedIds.length})
                        </button>
                    </div>
                )}
            </div>

            <div className="table-responsive">
                {isLoading ? <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> : loadFailed ? (
                    <div className="empty-state" role="alert">
                        <AlertCircle size={24} />
                        <p>Could not load job logs. Check that the server is running and try again.</p>
                        <button type="button" className="btn-secondary" onClick={fetchLogs}>Retry</button>
                    </div>
                ) : (
                    <table>
                        <thead>
                        <tr>
                            <th>
                                <input type="checkbox" onChange={handleSelectAll} checked={logs.length > 0 && selectedIds.length === logs.length} ref={el => { if (el) el.indeterminate = selectedIds.length > 0 && selectedIds.length < logs.length }} aria-label="Select all logs"/>
                            </th>
                            <th>File</th>
                            <th>Entity</th>
                            <th>Started</th>
                            <th>Succeeded</th>
                            <th>Failed</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {logs.map((log) => (
                            <tr key={log.id} className={selectedIds.includes(log.id) ? 'selected-row' : ''}>
                                <td>
                                    <input type="checkbox" checked={selectedIds.includes(log.id)} onChange={() => handleSelect(log.id)} aria-label={`Select log ${log.fileName}, ${new Date(log.createdAt).toLocaleString()}`}/>
                                </td>
                                <td className="mono">{log.fileName}</td>
                                <td>
                                  <span className="badge neutral">
                                      {log.entityType || 'UNKNOWN'}
                                  </span>
                                </td>
                                <td className="mono">{new Date(log.createdAt).toLocaleString()}</td>
                                <td className="numeric">{log.successfulRecords}</td>
                                <td className={Number(log.failedRecords) > 0 ? 'numeric danger-text' : 'numeric'}>{log.failedRecords}</td>
                                <td>
                                    {renderStatusBadge(log)}
                                </td>
                                <td>
                                    <button className="btn-secondary" onClick={() => setSelectedLog(log)} data-layout="inline-action">
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
                <div className="modal-overlay log-overlay">
                    <Dialog className="modal-content">
                        <h3>
                            Execution details: <span className="mono">{selectedLog.fileName}</span>
                        </h3>

                        <div className="log-list">
                            {!selectedLog.detailedLogs || parseDetails(selectedLog.detailedLogs).length === 0 ? (
                                <p className="text-gray text-center">No detailed logs recorded for this execution.</p>
                            ) : (
                                parseDetails(selectedLog.detailedLogs).map((detail, idx) => (
                                    <div key={idx} className={detail.status === 'SUCCESS' ? 'log-line success' : 'log-line failed'}>
                                        {detail.status === 'SUCCESS' ? <CheckCircle size={20}/> : <XCircle size={20}/>}
                                        <span>{detail.message}</span>
                                    </div>
                                ))
                            )}
                        </div>

                        <div className="modal-actions">
                            <button type="button" className="btn-secondary" onClick={() => setSelectedLog(null)} data-layout="center-action">Close details</button>
                        </div>
                    </Dialog>
                </div>
            )}
        </div>
    )
}