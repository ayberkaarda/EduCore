import { useState, useEffect } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { Globe, Plus, Trash2, Loader2 } from 'lucide-react'

export default function IpManagement({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'
    const [blocks, setBlocks] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [form, setForm] = useState({ type: 'STATIC', val1: '', val2: '' })

    useEffect(() => { if (isAdmin) fetchData() }, [isAdmin])

    const fetchData = async () => {
        setIsLoading(true)
        try {
            const res = await axios.get('http://localhost:8080/api/v1/ip-blocks')
            setBlocks(res.data)
        } catch (e) { toast.error("Failed to load IP Blocks.") }
        finally { setIsLoading(false) }
    }

    const handleCreate = async (e) => {
        e.preventDefault()
        let originalValue = form.val1
        if (form.type === 'RANGE') originalValue = `${form.val1}-${form.val2}`

        try {
            await axios.post('http://localhost:8080/api/v1/ip-blocks', { type: form.type, originalValue })
            toast.success("IP Block defined successfully!")
            setIsModalOpen(false)
            setForm({ type: 'STATIC', val1: '', val2: '' })
            fetchData()
        } catch (err) {
            toast.error(err.response?.data?.error || "Error adding IP Block.")
        }
    }

    const handleDelete = async (id) => {
        if (!window.confirm("Delete this IP definition?")) return;
        try {
            await axios.delete(`http://localhost:8080/api/v1/ip-blocks/${id}`)
            toast.success("Deleted successfully.")
            fetchData()
        } catch (e) { toast.error("Delete failed.") }
    }

    if (!isAdmin) return <div className="card"><h2>Access Denied</h2></div>

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header" style={{ marginBottom: '2rem' }}>
                <div>
                    <h2><Globe size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> IP Address Management</h2>
                    <p className="text-gray">Define allowed IPv4 Addresses, Ranges, and CIDR Subnets.</p>
                </div>
                <button className="btn-primary" onClick={() => setIsModalOpen(true)}><Plus size={16}/> New Definition</button>
            </div>

            <div className="table-responsive">
                {isLoading ? <Loader2 className="spin text-gray"/> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem' }}>Type</th>
                            <th style={{ padding: '1rem' }}>Definition</th>
                            <th style={{ padding: '1rem', textAlign: 'right' }}>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {blocks.map(b => (
                            <tr key={b.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                <td style={{ padding: '1rem' }}>
                                    <span className="badge" style={{backgroundColor: '#e0e7ff', color: '#3730a3'}}>{b.type}</span>
                                </td>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>{b.originalValue}</td>
                                <td style={{ padding: '1rem', textAlign: 'right' }}>
                                    <button className="btn-secondary" onClick={() => handleDelete(b.id)} style={{ padding: '0.4rem', color: '#b91c1c', border: 'none' }}><Trash2 size={16}/></button>
                                </td>
                            </tr>
                        ))}
                        {blocks.length === 0 && <tr><td colSpan="3" className="text-center text-gray" style={{padding:'2rem'}}>No IP definitions found.</td></tr>}
                        </tbody>
                    </table>
                )}
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3>New IP Definition</h3>
                        <form onSubmit={handleCreate}>
                            <div className="form-group">
                                <label>Definition Type</label>
                                <select required value={form.type} onChange={e => setForm({...form, type: e.target.value, val1:'', val2:''})} style={{width:'100%', padding:'0.75rem', borderRadius:'0.5rem', border:'1px solid #d1d5db'}}>
                                    <option value="STATIC">Single IP (Static)</option>
                                    <option value="RANGE">IP Range</option>
                                    <option value="CIDR">Subnet (CIDR)</option>
                                </select>
                            </div>

                            {form.type === 'STATIC' && (
                                <div className="form-group"><label>IP Address</label><input required type="text" placeholder="192.168.1.5" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/></div>
                            )}
                            {form.type === 'RANGE' && (
                                <div style={{display:'flex', gap:'1rem'}}>
                                    <div className="form-group" style={{flex:1}}><label>Start IP</label><input required type="text" placeholder="192.168.1.1" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/></div>
                                    <div className="form-group" style={{flex:1}}><label>End IP</label><input required type="text" placeholder="192.168.1.255" value={form.val2} onChange={e=>setForm({...form, val2: e.target.value})}/></div>
                                </div>
                            )}
                            {form.type === 'CIDR' && (
                                <div className="form-group"><label>CIDR Notation</label><input required type="text" placeholder="192.168.1.0/24" value={form.val1} onChange={e=>setForm({...form, val1: e.target.value})}/></div>
                            )}

                            <div className="modal-actions">
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button>
                                <button type="submit" className="btn-primary">Save Definition</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}