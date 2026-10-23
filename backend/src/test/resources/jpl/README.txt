Recorded NASA/JPL Horizons acceptance and rejection fixtures are in cases/.
See cases/provenance.json for exact URLs, target IDs, date ranges and step sizes.
Initially captured 2026-10-13; Uranus–Neptune ranges recaptured 2026-10-17,
and 15 coarse files recaptured 2026-10-18 with wider generator coverage.
Saturn–Uranus coarse files recaptured 2026-10-22: 2055-11-01 10:00 through
2056-06-01 10:00 UTC, 360-minute steps, covering the exterior-ramp generator anchor.
These are heliocentric geometric ICRF/ecliptic vectors, AU-D,
UT time, Gregorian calendar, fractional seconds. Numeric rows are unmodified;
the calendar timestamp is normalized to ISO UTC by scripts/capture-jpl-fixture.mjs.
Mars through Neptune use planetary-system barycentres 4–8; Earth uses 399.
The application performs the real event geometry and photometry on these samples.
No Horizons network call runs in the default tests.
Ceres candidate orbit: normalized from the unmodified NASA/JPL response captured
2026-10-12 for #36 (ceres-v1.txt). Target 1;, heliocentric ICRF/ecliptic, AU-D,
UT/Gregorian, daily vectors from 2023-09-14 to 2028-04-20; same frame/time options
as the acceptance fixtures. Used only for candidate generation, not acceptance.

Vesta candidate orbit: normalized from the unmodified NASA/JPL response captured
2026-10-12 for #36 (vesta-v1.txt), target 4;, with the same frame/time options.
Daily vectors from 2024-03-09 to 2027-10-26.
Used for model-seam regressions and approximation caveats, not acceptance.
planetary-table-baseline.csv preserves pre-blend model positions away from the
transition intervals; it is a regression baseline, NOT independent JPL evidence.
