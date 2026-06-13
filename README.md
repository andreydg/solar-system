# Solar System Explorer

An interactive frontend-first solar system web app. The MVP computes major-planet
positions in the browser with `astronomy-engine` and renders them with React Three
Fiber.

## Stack

- React + TypeScript + Vite
- Three.js via `@react-three/fiber`
- `@react-three/drei` for camera controls, labels, and scene helpers
- `astronomy-engine` for analytical ephemeris calculations

## Run Locally

```bash
npm install
npm run dev
```

## Current Features

- Sun-centered 3D view of Mercury through Neptune.
- Real ephemeris-backed planet positions for the selected time.
- Play/pause, jump-to-date, and adjustable accelerated time.
- Planet toggles, orbit trails, labels, and Saturn rings.
- Earth-Mars closest-approach search over the next three years.

## Architecture Notes

The browser owns the MVP because analytical ephemeris calculations are fast enough
for interactive visualization and first-pass event searches. The ephemeris boundary
lives in `src/lib/ephemeris.ts`; a later Java/Spring backend can replace that module
with REST calls for precomputed catalogs, saved sessions, or JPL Horizons validation.

The visualization intentionally separates physical data from display scale:

- Planet positions and event distances are computed in astronomical units.
- Rendered planet radii are exaggerated so bodies remain visible.
- Scene distance uses a fixed AU-to-scene-unit scale.

## Likely Phase 2

- Move long-running or broad event catalog searches to Spring Boot.
- Store precomputed events and saved scenarios in Postgres.
- Validate selected positions and event dates against JPL Horizons.
- Add Web Workers before a backend if client-side searches become UI-blocking.
