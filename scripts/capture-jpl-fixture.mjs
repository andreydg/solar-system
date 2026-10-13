// Explicit fixture capture; not used by normal tests. Prints data; does not modify files.
// NASA/JPL Horizons geometric heliocentric ICRF/ecliptic vectors, AU-D, UT Gregorian.
const [body, from, to, step = "360m"] = process.argv.slice(2);
const params = new URLSearchParams({format:"text", COMMAND: "'" + body + "'", CENTER:"'500@10'",
  REF_SYSTEM:"'ICRF'", REF_PLANE:"'ECLIPTIC'", EPHEM_TYPE:"'VECTORS'", VEC_TABLE:"'2'",
  OUT_UNITS:"'AU-D'", CSV_FORMAT:"'YES'", OBJ_DATA:"'NO'", TIME_TYPE:"'UT'",
  CAL_TYPE:"'GREGORIAN'", TIME_DIGITS:"'FRACSEC'", VEC_CORR:"'NONE'",
  START_TIME:"'" + from + "'", STOP_TIME:"'" + to + "'", STEP_SIZE:"'" + step + "'"});
const url = "https://ssd.jpl.nasa.gov/api/horizons.api?" + params;
let response;
for (let attempt=0; attempt<3; attempt++) {
  response = await fetch(url);
  if (response.ok) break;
  await new Promise(resolve => setTimeout(resolve, 1000));
}
if (!response.ok) throw Error("Horizons HTTP " + response.status);
const raw = await response.text();
const data = raw.split("$$SOE")[1]?.split("$$EOE")[0];
if (!data) throw Error(raw.slice(-1800));
const rows = data.trim().split("\n").map(line => {
  const fields = line.split(",").map(value => value.trim());
  const date = new Date(fields[1].replace("A.D. ", "").replaceAll("-", " ") + " UTC").toISOString();
  if (fields.slice(2,5).some(value => !Number.isFinite(Number(value)))) throw Error("Invalid vector");
  return [date, ...fields.slice(2,5)].join(",");
});
console.log(JSON.stringify({body,from,to,step,url,rows}));
