# 0005. Keep the weather widget, proxied and authenticated through the backend

- Status: Accepted
- Date: 2026-09-25
- Original decisions: D-05, D-NEW-08 (route), weather limit from D-NEW-31

## Context

The signed-in layout shows a weather widget fed by the Open-Meteo forecast API. Calling the provider from
the browser would expose the provider choice and let every client generate outbound traffic; an anonymous
backend route (`GET /api/weather`) would let anyone drive outbound calls to the provider.

## Decision

The widget is kept and served by the backend `weather` package:

- `GET /api/v1/weather` requires authentication (it was anonymous as `GET /api/weather`).
- `WeatherClient` (OpenFeign, `https://api.open-meteo.com`) uses 3 s connect and read timeouts and does not
  follow redirects.
- `WeatherService` caches each city for 5 minutes in Caffeine; when the provider fails, the last good value
  is returned as `STALE` (or `UNAVAILABLE`) and the failure is cached for 1 minute.
- `WeatherController` limits each account to `educore.weather.requests-per-minute` (30) requests per minute,
  independent of the global rate limiter (429 with `Retry-After`).

## Consequences

Positive:

- Outbound calls are bounded by the cache and per-account limit, and only authenticated users can trigger
  them.
- The widget degrades gracefully when the provider is slow or down.

Negative:

- An extra external dependency and package to maintain for a non-essential feature.
- Data can be up to 5 minutes old.

Revert of the route change: add `/api/v1/weather` to the anonymous list in `SecurityConfig`.

## References

- [`src/main/java/com/educore/weather/WeatherClient.java`](../../src/main/java/com/educore/weather/WeatherClient.java)
- [`src/main/java/com/educore/weather/WeatherService.java`](../../src/main/java/com/educore/weather/WeatherService.java)
- [`src/main/java/com/educore/weather/WeatherController.java`](../../src/main/java/com/educore/weather/WeatherController.java)
- [`src/test/java/com/educore/weather/WeatherServiceTest.java`](../../src/test/java/com/educore/weather/WeatherServiceTest.java)
- [`src/test/java/com/educore/weather/WeatherRateLimitIT.java`](../../src/test/java/com/educore/weather/WeatherRateLimitIT.java)
- [`docs/api/ROUTES.md`](../api/ROUTES.md)
