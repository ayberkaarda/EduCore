import { useQuery } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import { fetchWeather, weatherKeys } from './api'

export function useWeather() {
  return useQuery({
    queryKey: weatherKeys.all,
    queryFn: ({ signal }) => fetchWeather(signal),
    staleTime: STALE_TIME.weather,
    retry: false,
  })
}
