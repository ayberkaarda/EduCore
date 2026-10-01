import { api, apiError } from './api'
import confirmAction from './confirm'
import { useState, useEffect, useCallback } from 'react'
import axios from 'axios'
import toast from 'react-hot-toast'
import Toaster from './Toasts'
import Dialog from './Dialog'
import { Plus, Trash2, Loader2 } from 'lucide-react'

// YARDIMCI FONKSİYONLAR: IPv4 format kontrolü ve matematiksel büyüklük kontrolü
const isValidIpv4 = (ip) => {
    return /^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/.test(ip);
};

const ipToLong = (ip) => {
    return ip.split('.').reduce((acc, octet) => (acc << 8) + parseInt(octet, 10), 0) >>> 0;
};

export default function IpManagement({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [blocks, setBlocks] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [validationError, setValidationError] = useState('')
    const [form, setForm] = useState({ type: 'STATIC', val1: '', val2: '' })

    // isLoading starts as true, so the initial load does not need to set it.
    const loadBlocks = useCallback(() => axios.get(api.ipRules)
        .then(res => setBlocks(res.data))
        .catch((error) => toast.error(apiError(error, "Could not load IP rules.")))
        .finally(() => setIsLoading(false)), [])

    const fetchData = () => {
        setIsLoading(true)
        return loadBlocks()
    }

    useEffect(() => { if (isAdmin) loadBlocks() }, [isAdmin, loadBlocks])

    const handleCreate = async (e) => {
        e.preventDefault()
        setValidationError('')

        // --- YENİ: FRONTEND GÜVENLİK KONTROLLERİ ---
        const v1 = form.val1.trim();
        const v2 = form.val2.trim();

        if (form.type === 'STATIC') {
            if (!isValidIpv4(v1)) {
                return setValidationError("Enter an IPv4 address such as 192.168.1.5.");
            }
        }
        else if (form.type === 'RANGE') {
            if (!isValidIpv4(v1) || !isValidIpv4(v2)) {
                return setValidationError("Enter valid start and end IPv4 addresses.");
            }
            if (ipToLong(v1) > ipToLong(v2)) {
                return setValidationError("The end address must be greater than or equal to the start address.");
            }
        }
        else if (form.type === 'CIDR') {
            const parts = v1.split('/');
            if (parts.length !== 2 || !isValidIpv4(parts[0])) {
                return setValidationError("Enter a subnet such as 192.168.1.0/24.");
            }
            const prefix = parseInt(parts[1], 10);
            if (isNaN(prefix) || prefix < 0 || prefix > 32) {
                return setValidationError("The subnet prefix must be between /0 and /32.");
            }
        }
        // ------------------------------------------

        let originalValue = v1;
        if (form.type === 'RANGE') originalValue = `${v1}-${v2}`;

        try {
            await axios.post(api.ipRules, { type: form.type, originalValue })
            toast.success("IP rule added.")
            setIsModalOpen(false)
            setForm({ type: 'STATIC', val1: '', val2: '' })
            fetchData()
        } catch (err) {
            toast.error(apiError(err, "Could not add the IP rule."))
        }
    }

    const handleDelete = async (id) => {
        if (!await confirmAction(`Delete rule ${blocks.find(block => block.id === id)?.originalValue || id}?`)) return;
        try {
            await axios.delete(api.ipRule(id))
            toast.success("Deleted successfully.")
            fetchData()
        } catch (error) { toast.error(apiError(error, "Delete failed.")) }
    }

    if (!isAdmin) return <div className="card"><h2>You do not have access to this page</h2></div>

    return (
        <div className="card">
            <Toaster />
            
            <div className="detail-header">
                <div>
                    <h2>IP rules</h2>
                    <p className="text-gray">Allowed IPv4 addresses, ranges and subnets</p>
                </div>
                <button className="btn-primary" onClick={() => setIsModalOpen(true)}><Plus size={16}/> Add rule</button>
            </div>

            <div className="table-responsive">
                {isLoading ? <div className="empty-state"><Loader2 className="spin text-gray"/></div> : (
                    <table>
                        <thead>
                        <tr>
                            <th>Type</th>
                            <th>Definition</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {blocks.map(b => (
                            <tr key={b.id}>
                                <td>
                                    <span className="badge">{b.type === 'STATIC' ? 'Single address' : b.type === 'RANGE' ? 'Range' : 'Subnet'}</span>
                                </td>
                                <td className="mono">{b.originalValue}</td>
                                <td>
                                    <button className="btn-secondary" onClick={() => handleDelete(b.id)} aria-label="Delete" title="Delete"><Trash2 size={16}/></button>
                                </td>
                            </tr>
                        ))}
                        {blocks.length === 0 && <tr><td colSpan="3"><div className="empty-state"><h4>No IP rules yet.</h4><p>Add an address, range or subnet.</p></div></td></tr>}
                        </tbody>
                    </table>
                )}
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <Dialog className="modal-content">
                        <h3>Add rule</h3>
                        <form onSubmit={handleCreate}>
                            <div className="form-group">
                                <label htmlFor="ipmanagement-field-1">Type (required)</label>
                                <select id="ipmanagement-field-1" required value={form.type} onChange={e => setForm({...form, type: e.target.value, val1:'', val2:''})}>
                                    <option value="STATIC">Single address</option>
                                    <option value="RANGE">Range</option>
                                    <option value="CIDR">Subnet (CIDR)</option>
                                </select>
                            </div>

                            {form.type === 'STATIC' && (
                                <div className="form-group">
                                    <label htmlFor="ipmanagement-field-2">IP address (required)</label>
                                    <input id="ipmanagement-field-2" required type="text" placeholder="e.g. 192.168.1.5" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/>
                                </div>
                            )}
                            {form.type === 'RANGE' && (
                                <div className="inline-fields">
                                    <div className="form-group flex-field">
                                        <label htmlFor="ipmanagement-field-3">Start address (required)</label>
                                        <input id="ipmanagement-field-3" required type="text" placeholder="e.g. 192.168.1.1" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/>
                                    </div>
                                    <div className="form-group flex-field">
                                        <label htmlFor="ipmanagement-field-4">End address (required)</label>
                                        <input id="ipmanagement-field-4" required type="text" placeholder="e.g. 192.168.1.255" value={form.val2} onChange={e=>setForm({...form, val2: e.target.value})}/>
                                    </div>
                                </div>
                            )}
                            {form.type === 'CIDR' && (
                                <div className="form-group">
                                    <label htmlFor="ipmanagement-field-5">Subnet (CIDR) (required)</label>
                                    <input id="ipmanagement-field-5" required type="text" placeholder="e.g. 192.168.1.0/24" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/>
                                </div>
                            )}

                            {validationError && <p className="inline-error" role="alert">{validationError}</p>}
                            <div className="modal-actions">
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button>
                                <button type="submit" className="btn-primary">Save rule</button>
                            </div>
                        </form>
                    </Dialog>
                </div>
            )}
        </div>
    )
}