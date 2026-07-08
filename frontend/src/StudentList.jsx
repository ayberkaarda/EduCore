import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { Search, ChevronRight, User, Loader2, Plus } from 'lucide-react'
import { useDebounce } from './hooks/useDebounce'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentList() {
    const [students, setStudents] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)

    // Custom Hook'umuzu kullanıyoruz (500ms gecikme)
    const debouncedSearchTerm = useDebounce(searchTerm, 500)
    const navigate = useNavigate()

    useEffect(() => {
        fetchStudents(debouncedSearchTerm)
    }, [debouncedSearchTerm])

    const fetchStudents = async (search) => {
        setIsLoading(true)
        try {
            // Backend'deki sayfalamalı ve filtreli endpoint'e istek atıyoruz
            const response = await axios.get(`${API_BASE}/accounts/students?search=${search}&page=0&size=50`)
            setStudents(response.data.content) // Spring Boot Page objesi 'content' dizisi döner
        } catch (error) {
            console.error("Öğrenciler yüklenemedi", error)
        } finally {
            setIsLoading(false)
        }
    }
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [newStudent, setNewStudent] = useState({ firstName: '', lastName: '', studentNumber: '' })
    const handleCreateStudent = async (e) => {
        e.preventDefault()
        try {
            await axios.post(`${API_BASE}/accounts/student`, newStudent)
            toast.success('Öğrenci başarıyla oluşturuldu! ID otomatik atandı.')
            setIsModalOpen(false)
            setNewStudent({ firstName: '', lastName: '', studentNumber: '' })
            fetchStudents(debouncedSearchTerm) // Listeyi hemen tazele
        } catch (error) {
            toast.error('Öğrenci numarası kullanılıyor olabilir veya eksik bilgi girdiniz.')
        }
    }

    return (
        <div className="card">
            <div className="detail-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                    <h2>Öğrenci Yönetimi</h2>
                    <p className="text-gray">Ders ataması yapmak için listeden bir öğrenci seçin.</p>
                </div>

                {/* ARAMA KUTUSU - UI/UX FIX */}
                <div className="search-box" style={{ position: 'relative', width: '100%', maxWidth: '300px' }}>
                    <Search className="search-icon" size={20} style={{ position: 'absolute', left: '10px', top: '10px', color: '#6b7280' }} />
                    <input
                        type="text"
                        placeholder="Ara..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                        style={{
                            width: '100%',
                            padding: '0.6rem 1rem 0.6rem 2.5rem',
                            borderRadius: '0.5rem',
                            border: '1px solid #e5e7eb',
                            outline: 'none',
                            boxSizing: 'border-box'
                        }}
                    />
                </div>
                <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                    <Plus size={18} /> Yeni Öğrenci
                </button>
            </div>

            {/* ÖĞRENCİ LİSTESİ */}
            <div className="table-responsive">
                {isLoading ? (
                    <div className="empty-state"><Loader2 className="spin text-gray" size={32} /><p>Yükleniyor...</p></div>
                ) : students.length === 0 ? (
                    <div className="empty-state"><p>Öğrenci bulunamadı.</p></div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Öğrenci No</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Ad Soyad</th>
                            <th style={{ padding: '1rem', textAlign: 'right', color: '#6b7280' }}>İşlemler</th>
                        </tr>
                        </thead>
                        <tbody>
                        {students.map((student) => (
                            <tr key={student.id} style={{ borderBottom: '1px solid #e5e7eb', transition: 'background 0.2s' }} className="table-row-hover">
                                <td style={{ padding: '1rem', fontWeight: '500' }}>#{student.studentNumber}</td>
                                <td style={{ padding: '1rem' }}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                                        <div style={{ backgroundColor: '#f3f4f6', padding: '0.5rem', borderRadius: '50%' }}><User size={18} color="#4f46e5"/></div>
                                        {student.firstName} {student.lastName}
                                    </div>
                                </td>
                                <td style={{ padding: '1rem', textAlign: 'right' }}>
                                    <button
                                        className="btn-primary"
                                        onClick={() => navigate(`/students/${student.id}`)}
                                        style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }}
                                    >
                                        Detaylar <ChevronRight size={16} />
                                    </button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>
            {/* YENİ ÖĞRENCİ MODALI */}
            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3>Yeni Öğrenci Ekle</h3>
                        <p className="text-gray" style={{fontSize: '0.875rem', marginBottom: '1rem'}}>ID sistemi tarafından otomatik atanacaktır.</p>
                        <form onSubmit={handleCreateStudent}>
                            <div className="form-group">
                                <label>Ad</label>
                                <input required type="text" value={newStudent.firstName} onChange={(e) => setNewStudent({ ...newStudent, firstName: e.target.value })} />
                            </div>
                            <div className="form-group">
                                <label>Soyad</label>
                                <input required type="text" value={newStudent.lastName} onChange={(e) => setNewStudent({ ...newStudent, lastName: e.target.value })} />
                            </div>
                            <div className="form-group">
                                <label>Öğrenci Numarası</label>
                                <input required type="text" placeholder="Örn: 2401050" value={newStudent.studentNumber} onChange={(e) => setNewStudent({ ...newStudent, studentNumber: e.target.value })} />
                            </div>
                            <div className="modal-actions">
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>İptal</button>
                                <button type="submit" className="btn-primary">Kaydet</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}