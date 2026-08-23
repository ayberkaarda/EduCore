import React, { useEffect, useState } from 'react';
import axios from 'axios';

const WeatherWidget = () => {
    const [weatherData, setWeatherData] = useState([]);
    const [selectedCityIndex, setSelectedCityIndex] = useState(0);
    const [status, setStatus] = useState('Yükleniyor...');

    useEffect(() => {
        const fetchWeather = async () => {
            try {
                const response = await axios.get('http://localhost:8081/api/weather');
                if (Array.isArray(response.data) && response.data.length > 0) {
                    setWeatherData(response.data);
                    setStatus('success');
                } else {
                    setStatus('Veri bulunamadı.');
                }
            } catch (error) {
                setStatus(`Bağlantı Hatası`);
            }
        };

        fetchWeather();
    }, []);

    if (status !== 'success') {
        return (
            <div style={{
                background: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
                color: '#fff', padding: '16px 24px', borderRadius: '16px',
                boxShadow: '0 10px 20px rgba(0,180,255,0.2)'
            }}>
                <span style={{ fontWeight: '500' }}>{status}</span>
            </div>
        );
    }

    const currentCity = weatherData[selectedCityIndex];

    const getWeatherIcon = (description) => {
        if (!description) return '🌤️';
        const desc = description.toLowerCase();
        if (desc.includes('açık')) return '☀️';
        if (desc.includes('bulut')) return '☁️';
        if (desc.includes('yağmur')) return '🌧️';
        if (desc.includes('kar')) return '❄️';
        if (desc.includes('fırtına')) return '⛈️';
        if (desc.includes('sis')) return '🌫️';
        return '🌤️';
    };

    return (
        <div style={{
            background: 'linear-gradient(135deg, #3b82f6 0%, #0ea5e9 100%)', // Şık mavi gradyan
            borderRadius: '16px',
            padding: '16px 24px',
            display: 'flex',
            alignItems: 'center',
            gap: '24px',
            color: 'white',
            boxShadow: '0 10px 25px -5px rgba(59, 130, 246, 0.4)', // Mavi gölge
            width: '320px',
            fontFamily: 'system-ui, -apple-system, sans-serif'
        }}>
            {/* Sol Kısım: Büyük İkon */}
            <div style={{
                fontSize: '64px',
                lineHeight: '1',
                filter: 'drop-shadow(2px 4px 6px rgba(0,0,0,0.2))'
            }}>
                {getWeatherIcon(currentCity.description)}
            </div>

            {/* Sağ Kısım: Bilgiler */}
            <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>

                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    {/* Derece (Büyük ve İnce Font) */}
                    <div style={{ fontSize: '42px', fontWeight: '300', lineHeight: '1', letterSpacing: '-1px' }}>
                        {currentCity.temperature != null ? `${Math.round(currentCity.temperature)}°` : '--'}
                    </div>

                    {/* Konum İkonu ve Şehir Seçici */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px', marginTop: '4px' }}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                            <circle cx="12" cy="10" r="3"></circle>
                        </svg>
                        <select
                            value={selectedCityIndex}
                            onChange={(e) => setSelectedCityIndex(Number(e.target.value))}
                            style={{
                                background: 'transparent',
                                border: 'none',
                                color: '#fff',
                                fontWeight: '600',
                                fontSize: '15px',
                                outline: 'none',
                                cursor: 'pointer',
                                padding: 0,
                                textAlign: 'right'
                            }}
                        >
                            {weatherData.map((item, idx) => (
                                <option key={item.city} value={idx} style={{ color: '#1f2937' }}>
                                    {item.city}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>

                {/* Hava Durumu Açıklaması */}
                <div style={{
                    marginTop: '8px',
                    fontSize: '15px',
                    fontWeight: '500',
                    opacity: 0.9,
                    letterSpacing: '0.3px'
                }}>
                    {currentCity.description}
                </div>

            </div>
        </div>
    );
};

export default WeatherWidget;