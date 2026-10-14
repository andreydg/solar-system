Version 1 snapshots, retrieved 2026-10-12 from NASA/JPL Horizons:
https://ssd.jpl.nasa.gov/api/horizons.api
These are unmodified public vector-table responses (metadata and source solution
identifiers are in each file), not synthetic test fixtures. Used for approximate
visualization/candidate generation; actual event acceptance fetches fresh vectors.

Common query: format=text, CENTER='500@10', REF_SYSTEM='ICRF',
REF_PLANE='ECLIPTIC', EPHEM_TYPE='VECTORS', TIME_TYPE='UT',
CAL_TYPE='GREGORIAN', TIME_DIGITS='FRACSEC', VEC_CORR='NONE',
VEC_TABLE='2', OUT_UNITS='AU-D', CSV_FORMAT='YES', OBJ_DATA='NO'.
COMMAND: Ceres '1;', Vesta '4;', Encke '90000091;', Halley '90000030;'.
Windows: epoch 2026-01-01 +/- round(typicalOrbitDays/2) days.
Periods (days): Ceres 1680, Vesta 1325, Encke 1204, Halley 27520.
STEP_SIZE: 1 d for Ceres/Vesta/Encke, 2 d for Halley.
Do not treat periodic wrapping outside the table as an accurate future ephemeris.

To update: download with these explicit parameters, verify identity/frame/time
metadata, monotonic finite vectors and coverage, bump snapshot filenames/version,
then run TrajectoryReliabilityTests and packaged-app browser tests. No build/test
step downloads these files automatically. Review new data separately from code.
