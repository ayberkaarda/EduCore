import { Cloud, CloudFog, CloudLightning, CloudRain, CloudSun, Snowflake, Sun } from 'lucide-react'
import { useState } from 'react'
import { useWeather } from '../hooks'

// The backend describes the weather in Turkish; the icon is picked from those words.
function WeatherIcon({ description }: { description: string | null }) {
  const text = (description ?? '').toLocaleLowerCase('tr')
  const props = { size: 16, 'aria-hidden': true } as const
  if (text.includes('açık')) return <Sun {...props} />
  if (text.includes('bulut')) return <Cloud {...props} />
  if (text.includes('yağmur')) return <CloudRain {...props} />
  if (text.includes('kar')) return <Snowflake {...props} />
  if (text.includes('fırtına')) return <CloudLightning {...props} />
  if (text.includes('sis')) return <CloudFog {...props} />
  return <CloudSun {...props} />
}

/** Top-bar weather line (GET /v1/weather); failures stay quiet. */
export default function WeatherWidget() {
  const weather = useWeather()
  const [cityIndex, setCityIndex] = useState(0)

  if (weather.isPending) return <span className="weather-line">Loading weather</span>
  const cities = weather.data ?? []
  if (weather.isError || cities.length === 0) return <span className="weather-line">Weather unavailable</span>
  const current = cities[Math.min(cityIndex, cities.length - 1)]
  return (
    <div className="weather-line">
      <WeatherIcon description={current.description} />
      <select aria-label="Weather city" value={Math.min(cityIndex, cities.length - 1)} onChange={event => setCityIndex(Number(event.target.value))}>
        {cities.map((item, index) => <option key={item.city} value={index}>{item.city}</option>)}
      </select>
      <span>{current.status === 'UNAVAILABLE' ? 'Weather unavailable' : <>{current.temperature != null ? `${Math.round(current.temperature)}°` : '—'}, {current.description}{current.status === 'STALE' && ' (last known weather)'}</>}</span>
    </div>
  )
}
