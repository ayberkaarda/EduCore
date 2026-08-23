import React, { useEffect, useState } from 'react';
import axios from 'axios';

const WeatherWidget = () => {
    const [weatherData, setWeatherData] = useState([]);
    const [selectedCityIndex, setSelectedCityIndex] = useState(0);
    const [status, setStatus] = useState('Hava Durumu Yükleniyor...');

    useEffect(() => {
        const fetchWeather = async () => {
            try {
                // Axios kullandığımız için token App.jsx'teki interceptor'dan otomatik eklenecek
                const response = await axios.get('http://localhost:8080/api/weather');

                if (response.data && response.data.length > 0) {
                    setWeatherData(response.data);
                    setStatus('success');
                } else {
                    setStatus('Hava durumu verisi bulunamadı.');
                }
            } catch (error) {
                setStatus(`API Hatası: ${error.response?.status || error.message}`);
            }
        };

        fetchWeather();
    }, []);

    // Yükleniyor veya Hata durumunda ekranda mesajı gösterir
    if (status !== 'success') {
        return (
            <div style={{ color: '#fff', fontSize: '13px', fontWeight: '500' }}>
                {status}
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
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', color: '#fff' }}>
            <div style={{ fontSize: '28px' }}>
                {getWeatherIcon(currentCity.description)}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
                <select
                    value={selectedCityIndex}
                    onChange={(e) => setSelectedCityIndex(Number(e.target.value))}
                    style={{
                        background: 'transparent',
                        border: 'none',
                        color: '#fff',
                        fontWeight: 'bold',
                        fontSize: '14px',
                        outline: 'none',
                        cursor: 'pointer',
                        padding: 0,
                    }}
                >
                    {weatherData.map((item, idx) => (
                        <option key={item.city} value={idx} style={{ color: '#000' }}>
                            {item.city}
                        </option>
                    ))}
                </select>
                <div style={{ display: 'flex', gap: '8px', fontSize: '13px', marginTop: '2px' }}>
                    <span style={{ fontWeight: 'bold', color: '#fca5a5' }}>
                        {currentCity.temperature != null ? `${currentCity.temperature}°C` : '--'}
                    </span>
                    <span style={{ color: '#d1d5db' }}>{currentCity.description}</span>
                </div>
            </div>
        </div>
    );
};

export default WeatherWidget;