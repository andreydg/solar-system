# Solar System Explorer

An interactive solar system web app. The frontend computes smooth major-planet
positions in the browser with `astronomy-engine`, while the Phase 2 backend adds a
searchable event catalog and JPL Horizons validation.

## Stack

- React + TypeScript + Vite
- Three.js via `@react-three/fiber`
- `@react-three/drei` for camera controls, labels, and scene helpers
- `astronomy-engine` for analytical ephemeris calculations
- Java 21 + Spring Boot for event catalogs and validation APIs
- Firestore emulator locally, real Firestore-ready in Google Cloud

## Run Locally

```bash
npm install
npm run dev
```

The Vite dev server proxies `/api` to Spring Boot at `http://127.0.0.1:8080`.

## Run Backend Locally

Start the Firestore emulator on a port that does not conflict with Spring Boot:

```bash
gcloud emulators firestore start --host-port=127.0.0.1:8085
```

Then run the backend with emulator settings. The Firestore client picks up the
project ID from the standard `GOOGLE_CLOUD_PROJECT` environment variable:

```bash
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8085
export GOOGLE_CLOUD_PROJECT=solar-system-local
mvn -f backend/pom.xml spring-boot:run
```

For quick local smoke tests without Firestore:

```bash
SOLAR_SYSTEM_STORAGE=in-memory mvn -f backend/pom.xml spring-boot:run
```

Useful API checks:

```bash
curl http://127.0.0.1:8080/api/health

curl -X POST http://127.0.0.1:8080/api/events/generate \
  -H 'Content-Type: application/json' \
  -d '{"type":"closestApproach","bodyA":"earth","bodyB":"mars","from":"2026-01-01T00:00:00Z","to":"2029-01-01T00:00:00Z"}'

curl 'http://127.0.0.1:8080/api/validation/jpl/position?body=mars&time=2026-01-01T00:00:00Z'
```

## Deploy To Cloud Run

Use the repository `Dockerfile` in Google Cloud Build:

- Branch: `^main$`
- Build type: Dockerfile
- Build context directory: `/`
- Dockerfile path: `Dockerfile`

The container builds the Vite app, copies the compiled `dist` directory into the
Spring Boot jar, and runs the backend on Cloud Run's `PORT` environment variable.
Do not set `FIRESTORE_EMULATOR_HOST` in Cloud Run; the Firestore client will use
the deployed service account against real Firestore. The GCP project ID is resolved
implicitly from the Cloud Run runtime (same as durak-game).

Required Cloud Run environment variable:

- `SOLAR_SYSTEM_TRUSTED_PROXY_HOPS` — explicitly set the authenticated ingress suffix: `1` for direct Cloud Run. An external load balancer requires its actual trusted-hop count and ingress restriction against bypass. Startup fails on Cloud Run if this setting is omitted; topology is never guessed.

Optional Cloud Run environment variables:

- `FIRESTORE_DATABASE_ID` — defaults to `(default)`. Set this if you use a named Firestore database.

The Cloud Run service account needs Firestore access (for example `roles/datastore.user`).
Deploy the service in the same GCP project as your Firestore database.

## Current Features

- Sun-centered 3D view of Mercury through Neptune.
- Real ephemeris-backed planet positions for the selected time.
- Play/pause, jump-to-date, and adjustable accelerated time.
- Planet toggles, orbit trails, labels, and Saturn rings.
- Earth-Mars closest-approach search over the next three years, backed by the
  event catalog when the backend is available and browser computation otherwise.

## Architecture Notes

The browser still owns smooth animation because analytical ephemeris calculations
are fast enough for frame-by-frame interaction. Backend event search lives behind
`src/lib/eventCatalogApi.ts`, with local browser fallback in `src/lib/ephemeris.ts`.

The visualization intentionally separates physical data from display scale:

- Planet positions and event distances are computed in astronomical units.
- Rendered planet radii are exaggerated so bodies remain visible.
- Scene distance uses a fixed AU-to-scene-unit scale.

## Phase 2 Backend

- `GET /api/events` queries cataloged events.
- `POST /api/events/generate` idempotently generates closest approaches,
  oppositions, or conjunctions for a pair/range.
- `GET /api/catalog/options` lists supported event types and major bodies.
- `GET /api/validation/jpl/position` fetches a Sun-centered vector from JPL Horizons.
- `POST /api/validation/jpl/event/{eventId}` validates a cataloged event against
  JPL vectors and stores corrected/validated metadata where supported.

Generated events are stored immediately as provisional catalog entries. When
`JPL_ASYNC_VALIDATION_ENABLED=true`, the backend validates generated events in the
background and updates the same Firestore document with JPL-derived values. API
responses derive display fields by preferring validated values when present, while
preserving the original computed values for traceability.

## Verification

### API safety and operations

Generation/query windows must be increasing and at most 31 years; dates are bounded
to 1600–3000. Generation has a cooperative 30-second calculation budget. Expensive
planet-only generation is limited to two concurrent requests, independently of 32 catalog-read,
16 cold-trajectory, 16 warm-trajectory response, and eight JPL-bound slots (including small-body generation). Only admitted requests consume the per-client
minute quotas (60 generation / 120 JPL-bound / 600 reads); a high 6,000/minute
instance backstop and a bounded 8,192-client table protect memory. Deploy edge
quotas and cap Cloud Run instances for a deployment-wide cost budget. Size Cloud
Run concurrency to leave capacity for static files and health checks (at least 80).

Direct local deployments ignore forwarded headers. Direct Cloud Run ingress
(`K_SERVICE` present) requires an explicit `SOLAR_SYSTEM_TRUSTED_PROXY_HOPS` setting:
1 for direct ingress; the verified trusted suffix length for other topologies.
Omitting it fails startup instead of silently collapsing visitors into one proxy quota.
IPv6 addresses retain per-device /64 quotas, with a 4,800/minute /56 aggregate
that resets every minute and remains below the instance backstop. Warm trajectory
response writes use a separate semaphore and cannot consume catalog-read capacity.
Both cold and warm trajectories share in-flight limits of four per client key and eight per IPv6 /56;
these limits last until the response finishes, independently of minute-quota resets.
An IPv4 client key represents the whole NAT: one page load (four downloads) at a time.
Malformed chains can hold only one trajectory response at a time.
Malformed chains and a full client table use tight
shared buckets rather than unbounded admission. Multiple forwarded-header lines are combined.
Never enable it on directly reachable untrusted ingress. Behind an external Google
load balancer, verify the appended chain: its last hop is the load balancer, not
the visitor ([Google's header documentation](https://docs.cloud.google.com/load-balancing/docs/https#x-forwarded-for_header)).
Test two actual visitor IPs through your deployed ingress before increasing
traffic; do not assume a fixed chain after changing proxies. Use an edge quota if
the ingress cannot provide an authenticated client address.

PRs #31–#37 are stacked. Retarget each next PR to `main` **after** its predecessor
merges, then verify the remaining diff; do not merge it into a stale feature
branch. Every main merge deploys: coordinate a maintenance rollout, index
migration and worker scheduling before restoring traffic. Do not deploy
intermediate layers or enable traffic without their documented prerequisites.

Administrative `/api/validation/**` endpoints are disabled unless
`SOLAR_SYSTEM_ADMIN_TOKEN` contains at least 32 characters. Send it as a Bearer
token over HTTPS, never in a URL or frontend configuration. Event revalidation is
now POST, not GET. Store this secret in Secret Manager and rotate it as needed.
Do not expose the token in browser clients. Public catalog reads require no login.

Normal Spring tests inject explicitly synthetic small-body orbits and a JPL client
that throws on unexpected calls. These test catalog behavior, not ephemeris
accuracy; the opt-in live integration profile remains separate.

```bash
npm run build
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
npm run test:e2e # optionally E2E_PORT=18081 if the default test port is occupied
```

## Credits

Planet, Sun, and moon surface textures (`public/textures/`) are the solar-system
maps by **James Hastings-Trew — [Planet Pixel Emporium](https://planetpixelemporium.com/planets.html)**,
used with credit per the author's usage terms. See
[`public/textures/CREDITS.md`](public/textures/CREDITS.md).
