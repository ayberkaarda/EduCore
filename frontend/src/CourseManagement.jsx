import { useState, useEffect } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { Book, Plus, Loader2, Calendar } from 'lucide-react'
import CourseManagement from './CourseManagement'

const API_BASE = 'http://localhost:8080/api/v1'

export default function CourseManagement() {
    const [courses, setCourses] = useState([])
    const [isLoading, setIsLoading] = useState(true)
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [newCourse, setNewCourse] = useState({ name: '', term: '' })

    useEffect(() => { fetchData() }, [])

    const fetchData = async () => {
        setIsLoading(true)
        try {
            const res = await axios.get(`${API_BASE}/courses`)
            setCourses(res.data)
        } catch (error) {
            toast.error('Dersler çekilemedi.')
        } finally {
            setIsLoading(false)
        }
    }

    const handleCreate = async (e) => {
        e.preventDefault()
        try {
            await axios.post(`${API_BASE}/courses`, newCourse)
            toast.success('Ders başarıyla eklendi!')
            setIsModalOpen(false)
            setNewCourse({ name: '', term: '' })
            fetchData()
        } catch (error) {
            toast.error('Ders eklenirken hata oluştu.')
        }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                    <h2><Book size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> Ders Kataloğu ve Yönetimi</h2>
                    <p className="text-gray">Sistemde aktif olan tüm dersleri görüntüleyin veya yeni ders müfredatı açın.</p>
                </div>
                <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                    <Plus size={18} /> Yeni Ders Aç
                </button>
            </div>

            {/* AKTİF DERSLER LİSTESİ */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '1.5rem', marginTop: '2rem' }}>
                {isLoading ? <Loader2 className="spin text-gray" /> : courses.map(c => (
                    <div key={c.id} className="course-item" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', padding: '1.5rem', border: '1px solid #e5e7eb', borderRadius: '0.75rem', backgroundColor: '#f9fafb' }}>
                        <h4 style={{ margin: '0 0 0.5rem 0', fontSize: '1.1rem', color: '#111827' }}>{c.name}</h4>
                        <span className="badge" style={{ backgroundColor: '#e0e7ff', color: '#3730a3', marginTop: '0.5rem' }}><Calendar size={12} style={{display:'inline', marginRight:'4px'}}/> Dönem: {c.term}</span>
                    </div>
                ))}
            </div>

            {/* YENİ DERS MODALI */}
            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content" style={{ padding: '2.5rem' }}>
                        <h3 style={{ marginTop: 0, marginBottom: '1.5rem' }}>Yeni Ders Oluştur</h3>
                        <form onSubmit={handleCreate}>
                            <div style={{ marginBottom: '1.2rem' }}>
                                <label style={{ display: 'block', marginBottom: '0.4rem', fontWeight: 500, fontSize: '0.9rem' }}>Ders Adı</label>
                                <input required type="text" placeholder="Örn: Veri Yapıları" value={newCourse.name} onChange={e => setNewCourse({...newCourse, name: e.target.value})} style={{ width: '100%', padding: '0.75rem', borderRadius: '0.5rem', border: '1px solid #d1d5db', outline: 'none', boxSizing: 'border-box' }} />
                            </div>
                            <div style={{ marginBottom: '2rem' }}>
                                <label style={{ display: 'block', marginBottom: '0.4rem', fontWeight: 500, fontSize: '0.9rem' }}>Dönem Bilgisi</label>
                                <input required type="text" placeholder="Örn: 2026/1" value={newCourse.term} onChange={e => setNewCourse({...newCourse, term: e.target.value})} style={{ width: '100%', padding: '0.75rem', borderRadius: '0.5rem', border: '1px solid #d1d5db', outline: 'none', boxSizing: 'border-box' }} />
                            </div>
                            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>İptal</button>
                                <button type="submit" className="btn-primary">Müfredata Ekle</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}