import { api } from './api'
import { useEffect, useState } from 'react';
import { CloudSun, Sun, Cloud, CloudRain, Snowflake, CloudLightning, CloudFog } from 'lucide-react';
import axios from 'axios';

const WeatherWidget = () => {
    const [weatherData, setWeatherData] = useState([]);
    const [selectedCityIndex, setSelectedCityIndex] = useState(0);
    const [status, setStatus] = useState('Loading weather');

    useEffect(() => {
        const fetchWeather = async () => {
            try {
                const response = await axios.get(api.weather, { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } });
                if (Array.isArray(response.data) && response.data.length > 0) {
                    setWeatherData(response.data);
                    setStatus('success');
                } else {
                    setStatus('Weather unavailable');
                }
            } catch {
                setStatus('Weather unavailable');
            }
        };

        fetchWeather();
    }, []);

    if (status !== 'success') return <span className="weather-line">{status}</span>
    const currentCity = weatherData[selectedCityIndex];
    const desc = (currentCity.description || '').toLowerCase();
    const WeatherIcon = desc.includes('açık') ? Sun : desc.includes('bulut') ? Cloud : desc.includes('yağmur') ? CloudRain : desc.includes('kar') ? Snowflake : desc.includes('fırtına') ? CloudLightning : desc.includes('sis') ? CloudFog : CloudSun;
    return <div className="weather-line"><WeatherIcon size={16}/><select aria-label="Weather city" value={selectedCityIndex} onChange={e => setSelectedCityIndex(Number(e.target.value))}>{weatherData.map((item, idx) => <option key={item.city} value={idx}>{item.city}</option>)}</select><span>{currentCity.temperature != null ? Math.round(currentCity.temperature)+'°' : '—'}, {currentCity.description}</span></div>
};
export default WeatherWidget;
