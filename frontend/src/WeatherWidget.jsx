import React, { useEffect, useState } from 'react';

const WeatherWidget = () => {
    const [weatherData, setWeatherData] = useState([]);
    const [selectedCityIndex, setSelectedCityIndex] = useState(0);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchWeather = async () => {
            try {
                const token = localStorage.getItem('token');
                const headers = token ? { 'Authorization': `Bearer ${token}` } : {};

                const response = await fetch('http://localhost:8080/api/weather', { headers });
                if (response.ok) {
                    const data = await response.json();
                    setWeatherData(data);
                }
            } catch (error) {
                console.error('Hava durumu verisi alınamadı:', error);
            } finally {
                setLoading(false);
            }
        };

        fetchWeather();
        // 10 dakikada bir otomatik güncelleme
        const interval = setInterval(fetchWeather, 10 * 60 * 1000);
        return () => clearInterval(interval);
    }, []);

    if (loading || weatherData.length === 0) {
        return (
            <div className="weather-widget-loading">
                <span>Hava Durumu yükleniyor...</span>
            </div>
        );
    }

    const currentCity = weatherData[selectedCityIndex];

    const getWeatherIcon = (description) => {
        if (!description) return '🌤️';
        if (description.includes('Açık')) return '☀️';
        if (description.includes('Bulutlu')) return '⛅';
        if (description.includes('Yağmur')) return '🌧️';
        if (description.includes('Kar')) return '❄️';
        if (description.includes('Fırtına')) return '⛈️';
        if (description.includes('Sis')) return '🌫️';
        return '🌤️';
    };

    return (
        <div className="weather-widget-container">
            <span className="weather-icon">{getWeatherIcon(currentCity.description)}</span>
            <div className="weather-info">
                <div className="weather-header">
                    <select
                        value={selectedCityIndex}
                        onChange={(e) => setSelectedCityIndex(Number(e.target.value))}
                        className="weather-city-select"
                    >
                        {weatherData.map((item, idx) => (
                            <option key={item.city} value={idx}>
                                {item.city}
                            </option>
                        ))}
                    </select>
                </div>
                <div className="weather-details">
                    <span className="weather-temp">{currentCity.temperature != null ? `${currentCity.temperature}°C` : '--'}</span>
                    <span className="weather-desc">{currentCity.description}</span>
                </div>
            </div>
        </div>
    );
};

export default WeatherWidget;