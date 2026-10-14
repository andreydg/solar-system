# Solar System Explorer

An interactive solar system web app. The frontend computes smooth major-planet
positions in the browser with `astronomy-engine`, while the backend provides a
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

curl -H "Authorization: Bearer $SOLAR_SYSTEM_ADMIN_TOKEN" \
  'http://127.0.0.1:8080/api/validation/jpl/position?body=mars&time=2026-01-01T00:00:00Z'
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
- Eleven event types with next/previous search and explicit validation status.
- Ceres, Vesta, Encke, and Halley paths loaded only when requested.
- Major-planet closest-approach fallback finds the next/previous local minimum
  within five years when the backend is unavailable.

## Architecture Notes

The browser still owns smooth animation because analytical ephemeris calculations
are fast enough for frame-by-frame interaction. Backend event search lives behind
`src/lib/eventCatalogApi.ts`, with local browser fallback in `src/lib/ephemeris.ts`.

The visualization intentionally separates physical data from display scale:

- Planet positions and event distances are computed in astronomical units.
- Rendered planet radii are exaggerated so bodies remain visible.
- Scene distance uses a fixed AU-to-scene-unit scale.

## Backend API

- `GET /api/events` queries cataloged events.
- `POST /api/events/generate` idempotently generates closest approaches,
  and the other supported event types for a pair/range.
- `GET /api/catalog/options` lists supported event types and major bodies.
- `GET /api/validation/jpl/position` fetches a Sun-centered vector from JPL Horizons.
- `POST /api/validation/jpl/event/{eventId}` administratively queues revalidation.
- `POST /api/admin/validation/drain` drains due validation jobs within a bounded request.

Generated events and durable jobs are stored immediately as provisional catalog entries.
When `JPL_ASYNC_VALIDATION_ENABLED=true`, the configured authenticated worker trigger
validates queued events and updates the same Firestore document with JPL-derived values. API
responses derive display fields by preferring validated values when present, while
preserving the original computed values for traceability.

## Verification

### Durable validation worker (required for automatic validation)

Generated events and their initial `events-validation/{eventId}` jobs are now
inserted in the same transaction. Queries never schedule retries or mutate jobs.
A single Firestore lease limits validation work across instances. Job leases last
ten minutes; abandoned work becomes eligible again. Completion updates the event
and job atomically and is fenced by the lease token, so a late worker cannot
overwrite a newer result. Transient failures retry up to 12 attempts, starting at five minutes and capping
at six hours (over 24 hours of outage coverage);
permanent/exhausted failures stop until an administrator explicitly requeues them.

Configure **one** deployment-level scheduler to POST `/api/admin/validation/drain`
over HTTPS with the admin Bearer token. Each request drains up to 200 due jobs or eight minutes of work;
set Cloud Run's **request timeout** to at least 600 seconds and Cloud Scheduler's
**attempt deadline** to at least 600 seconds (for example `gcloud run services update SERVICE --timeout=600s`
and `gcloud scheduler jobs update http JOB --attempt-deadline=600s`).
The defaults are not sufficient for this worker. Start with a one-minute schedule.
A budget-limited in-flight job is deferred without consuming its attempt allowance.
Overlapping invocations safely return `idle-or-busy`. Do not put this trigger or
token in browser code. This PR does not provision the scheduler, secret, or IAM.
Without a worker trigger, events remain honestly PENDING; they are not silently
validated in an in-memory post-response task. `JPL_ASYNC_VALIDATION_ENABLED=false`
pauses claims without losing work. Restore it to resume.

Run the catalog migration command below (dry-run first, then apply) to backfill
jobs for current-algorithm PENDING documents. It does not automatically retry FAILED records.
Authenticated POST `/api/validation/jpl/event/{eventId}` explicitly requeues an
event and returns 202 **without clearing accepted values**. Revalidation failures
leave the last accepted result available; errors belong to the job. A failed
result commit is retried as a storage failure, not a scientific rejection.

After recovering from an outage, POST `/api/admin/validation/requeue-exhausted?limit=100`
with the admin token. Follow its `nextCursor` as `after` until null. This explicit,
bounded recovery only resets exhausted retries, not scientific rejections. Repair
malformed source documents before requeuing them individually. Inspect terminal `state=failed` job documents and alert on
old `availableAt` times or growing backlog. No periodic browser/database polling
is introduced; only the single configured worker trigger checks for due jobs.

### Indexed catalog rollout (required before serving catalog traffic)

Catalog queries now use native Firestore timestamps, canonical pair keys, database
filters, stable `(displayTime, document ID)` cursors, and bounded pages. The public
list endpoints preserve their array response; `X-Next-Cursor` supplies the next
page. `limit` is 1–100, `descending` changes database order, and `cursor` continues
the same query. The validated-events UI sends date filters and loads 50 at a time.
Next/previous event lookups request one result, with one lookahead document for
pagination. Empty generation windows are remembered for ten minutes in a bounded
256-entry per-instance cache, never indefinitely and never as cached event data.

Do **not** enable traffic on a new database/revision before this rollout:

1. Back up the catalog and stop/drain old generation and validation writers.
2. Deploy `firestore.indexes.json` with `firebase deploy --only firestore:indexes
   --project YOUR_PROJECT` and wait for every index to finish building. If using a
   named database/custom collection, deploy the indexes to that exact target.
3. Build the new jar and run the migration in dry-run mode against the explicitly
   selected project/database. No records are deleted or scientific values changed.

   ```bash
   GOOGLE_CLOUD_PROJECT=YOUR_PROJECT java -jar backend/target/solar-system-backend-0.1.0.jar \
     --spring.main.web-application-type=none \
     --solar-system.jpl.async-validation-enabled=false \
     --solar-system.migration.enabled=true
   ```

4. Review the counts, repair malformed records, then repeat with
   `--solar-system.migration.apply=true`. Writes use update-time preconditions so
   concurrent changes abort instead of being overwritten. It is safe to rerun.
5. The migration activates `_catalogMetadata/events.indexedQueriesReady` only
   after a complete successful scan. Smoke-test filtered reads and both paging
   directions, then resume traffic. Without that marker the app returns an explicit
   503 instead of silently omitting legacy records. Rollback requires stopping new
   writers before restoring an old revision; older writers do not maintain indexes.

For a local emulator use `FIRESTORE_EMULATOR_HOST=127.0.0.1:18085` and a test project
with the same command. Repository/migration regression tests refuse non-local
hosts: `FIRESTORE_EMULATOR_HOST=127.0.0.1:18085 mvn -f backend/pom.xml test
-Pfirestore-tests`. Normal tests and the live JPL profile remain separate.

### API safety and operations

Generation/query windows must be increasing and at most 31 years; dates are bounded
to 1600–2999. Generation has a cooperative 30-second calculation budget. Expensive
planet-only generation is limited to two concurrent requests, independently of 32 catalog-read,
16 trajectory, and eight JPL-bound slots (including small-body generation). Only admitted requests consume the per-client
minute quotas (60 generation / 120 JPL-bound / 600 reads); a high 6,000/minute
instance backstop and a bounded 4,096-client table protect memory. Deploy edge
quotas and cap Cloud Run instances for a deployment-wide cost budget. Size Cloud
Run concurrency to leave capacity for static files and health checks (at least 64).

Direct local deployments ignore forwarded headers. Direct Cloud Run ingress
(`K_SERVICE` present) requires an explicit `SOLAR_SYSTEM_TRUSTED_PROXY_HOPS` setting:
1 for direct ingress; the verified trusted suffix length for other topologies.
Omitting it fails startup instead of silently collapsing visitors into one proxy quota.
IPv6 addresses share /64 quotas; malformed chains and a full client table use tight
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
```

## Scientific provenance and catalog version 2

Browser regression tests run against the production jar with the real Vite build:
`npx playwright install chromium && npm run test:e2e` (Java and Maven required;
set `MAVEN=/absolute/path/to/mvn` if Maven is not on PATH). API responses are
explicit offline fixtures; UI, assets, HTTP static serving, and browser timezone
behavior are real. No live Horizons or Firestore access is needed. The test server
uses port 18080 and shuts down automatically.

Candidates are approximate, not automatically JPL-validated. Planet candidates use
JPL's [approximate Keplerian elements](https://ssd.jpl.nasa.gov/planets/approx_pos.html)
(Table 1 in 1800–2050; Table 2 and outer-planet corrections outside that interval).
Small-body candidates use interpolated, periodically repeated trajectories. Neither
method is an observation-grade ephemeris.

Validation requires a bracketed crossing or interior extremum in fresh geometric
Horizons vectors, then a 5-minute refinement grid. That grid is **not an accuracy
guarantee**. Transit acceptance also checks apparent disk overlap and foreground
distance. Brightness remains a photometric model using JPL geometry, not a JPL
measurement. These are geocentric/geometric events, not local visibility forecasts;
atmosphere, observer location, light-time, and historical time-scale differences
are outside their scope. Recorded Horizons fixtures test acceptance offline.

This release requires the updated composite indexes and **read schema version 2**.
Follow the migration runbook above again: deploy/wait for indexes, dry-run, then
apply with old writers drained. Legacy documents without an algorithm version are
retained as version 0, but excluded from current searches. New generation uses
version-prefixed IDs, so old incorrect validations cannot suppress corrected events.
Only current-version pending events receive backfilled jobs; obsolete queued jobs
are abandoned without promoting their results. No historical records are deleted.

## Trajectory and performance operations

Versioned, unmodified Horizons responses in `backend/src/main/resources/trajectories/`
ship with the jar (see their README for dates/query/provenance). Default cold loads
use these local snapshots, not outbound requests. They are visual/candidate
approximations, not substitutes for live event validation. Refreshing a snapshot
requires an explicit reviewed data update; runtime `TRAJECTORY_REFRESH_FROM_JPL=true`
is an operator override, not a startup prewarming flag. Disk format 3 distinguishes
explicit UT vectors from older caches. Saves are atomic; malformed/stale files are
ignored. Per-body cold loads are single-flight and the point cache is bounded.

The UI loads only visible small bodies, keeps successful paths independently, and
offers manual retry on failure. It no longer polls live positions or retries the
whole group. Simulation state updates at most ten times/sec, pauses work in hidden
tabs, and starts paused for reduced-motion users. Texture and hashed-asset HTTP
caching are explicit; the runtime container runs as an unprivileged user.

## Sharing and organic discovery

Share view preserves the current draft (including a loaded event) in browser history before its first share. Back/Forward restore snapshots; hash-only navigation leaves the scene alone. Editing a shared field clears the selectable link and removes its stale query string once, not on every animation frame. Choose Share view again to publish the updated snapshot. Sky tracks use the conventional east-left orientation.

Share view copies a versioned URL and also displays a selectable link if clipboard
access is unavailable. It preserves UTC time, body visibility, search criteria and
focused body, opens paused, and supports browser back/forward. It does not preserve
an arbitrary camera rotation or assert that a displayed date is validated.
Explain this date captures an on-demand snapshot, with a geometric Earth-view
track for major planets. It does not recalculate that chart every animation frame.

`npm run build` also pre-renders three human-written guides and a sitemap; robots
and page/social metadata are served as real production resources. Guides load
without JavaScript or any catalog request. Shared query URLs canonicalize to the
home page, so they do not create thousands of thin indexed event pages.

Before paid acquisition (operator checklist, not automated by this PR):

1. Complete the index/schema migration and worker-trigger rollout above; verify
   latency, 429/5xx rates, worker backlog age and Firestore cost at a capped load.
2. After deployment, verify guide URLs and the sitemap in Search Console.
3. Share a few genuinely useful examples with astronomy educators/clubs, asking
   for feedback before broad outreach. Pair a retrograde sky track with its 3D
   view rather than advertising precision the model does not provide.
4. Measure guide-to-explorer visits and repeat usage using an explicitly approved,
   privacy-conscious analytics setup. No analytics vendor, tracking cookie,
   outbound promotion, or cloud resource is enabled by this code.

## Catalog rollback and retries

After **any** rollback to an older writer, drain/stop that revision, rerun the
index migration with `--solar-system.migration.apply=true`, and verify the
indexes before enabling traffic on this version again. The readiness marker is
not a live detector of writes from old binaries. Never run old and new writers
against the collection concurrently. New generation uses create-only transactions
so an existing validated event is never downgraded even when a legacy document is
missing from the indexed query.

Public catalog reads never schedule validation retries. Use the authenticated
POST validation endpoint to request retries; reads must remain free of hidden
JPL work. The durable-worker PR makes this an explicit queued operation.

### Scientific coverage and acceptance fixtures

Validation uses Mars–Neptune system barycentres (Horizons IDs 4–8), with Earth's
centre (399) as observer. These planetary targets cover the full 1600–2999 UI
range. Small-body integrations can still have shorter coverage. Missing Horizons
data is shown as **provisional / JPL data unavailable**, with a bounded public coverage summary (raw errors remain on the private job),
not hidden as a rejected event. A genuine geometry rejection remains excluded
from event search. See [JPL's target and coverage documentation](https://ssd.jpl.nasa.gov/horizons/manual.html#selecting-major-bodies).

Slow-pair distance windows scale with the synodic period (7–180 days); refinement
requires a real interior extremum, and generation never substitutes an unbracketed
window edge. Recorded JPL accept/reject fixtures cover all eleven event types and
the 2056 Saturn–Uranus / 2084 Uranus–Neptune farthest-approach regressions.
Requests and retrieval provenance are in `backend/src/test/resources/jpl/cases/provenance.json`.
The capture script prints raw numeric samples; normal tests use only the checked-in
recordings and real application geometry/photometry, not network calls.

### Date editing and browser recovery

The UTC editor keeps incomplete keyboard input as a draft. Valid dates commit;
leaving an incomplete field restores the last committed value. An explicit date
jump (including Today) pauses playback and clears the selected event so the
visible timestamp and highlighted event cannot silently disagree; the UI explains
this next to the input. Network timeouts are 45 seconds per request and 90 seconds
per search, longer than the server's 30-second generation calculation budget.
Only actual fetch failures / server outages enable approximate browser fallback;
programming errors and cancellations do not. Unsupported WebGL shows an actionable
fallback, and Retry remounts only the scene, preserving the current view controls.

If the lazy JavaScript chunk fails again on Retry (for example after deployment),
the browser reloads once to obtain the current asset manifest.

### Trajectory and rendering recovery

Trajectory reads use the independent cached-read pool and compressed JSON. Adding
another body does not cancel already-wanted downloads. Transient failures get at
most two automatic retries, respecting Retry-After; hiding a body or unmounting
cancels both the request and its backoff. Persistent errors remain visible beside
event status. Dates outside a loaded snapshot explicitly show a repeated-orbit
approximation warning. All four bundled snapshots are validated and cached during startup. They must match the target, Sun-centred
geometric ecliptic frame, AU-D units and UT/Gregorian time metadata before serving traffic.

The scene reads a frame-rate clock ref; bodies, comet tails, labels, event lines and
camera-follow move every frame. React controls publish at most ten updates/second. Hidden tabs pause without catch-up, stalls are capped at 250 ms, and
changing the speed does not restart the clock. Long-lived asset cache headers are
set only after successful resource resolution; missing assets remain no-store.

### Share and history consistency

Share synchronously pauses the live clock before copying its timestamp. Back and
Forward restore an original share's URL along with its view, while a reload of a
bare URL ignores stale shared history state. Explicit body focus wins over a
restored event's pair framing. In-progress search messages are not stored as
finished history snapshots; share keys do not require a secure-context crypto API.

## Credits

Planet, Sun, and moon surface textures (`public/textures/`) are the solar-system
maps by **James Hastings-Trew — [Planet Pixel Emporium](https://planetpixelemporium.com/planets.html)**,
used with credit per the author's usage terms. See
[`public/textures/CREDITS.md`](public/textures/CREDITS.md).
