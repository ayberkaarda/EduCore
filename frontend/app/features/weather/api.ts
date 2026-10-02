import { api } from '../../lib/api'
import type { CityWeather } from '../../lib/types'

export const weatherKeys = { all: ['weather'] as const }

export async function fetchWeather(signal?: AbortSignal): Promise<CityWeather[]> {
  const { data } = await api.get<CityWeather[]>('/v1/weather', { signal })
  return Array.isArray(data) ? data : []
}
