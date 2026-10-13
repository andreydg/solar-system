import { spawnSync } from "node:child_process";
import assert from "node:assert/strict";

const host = process.env.FIRESTORE_EMULATOR_HOST;
assert.match(host ?? "", /^(127\.0\.0\.1|localhost):[0-9]+$/);
const collection = "migration-smoke-" + Date.now();
const base = `http://${host}/v1/projects/solar-system-test/databases/(default)/documents/${collection}/legacy`;
const time = "2027-01-01T00:00:00Z";
const fields = Object.fromEntries(Object.entries({
  id: "legacy", type: "opposition", bodyA: "earth", bodyB: "mars",
  computedTimeUtc: time, computedSource: "VSOP", validationStatus: "pending",
  rangeStartUtc: time, rangeEndUtc: "2028-01-01T00:00:00Z", generatedAtUtc: time,
}).map(([key, value]) => [key, { stringValue: value }]));
async function seed() {
  const result = await fetch(base, { method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields }) });
  assert.equal(result.status, 200, await result.text());
}
function run(apply, status = 0) {
  const env = { ...process.env, GOOGLE_CLOUD_PROJECT: "solar-system-test" };
  delete env.FIRESTORE_EMULATOR_HOST;
  const result = spawnSync("java", ["-jar", "backend/target/solar-system-backend-0.1.0.jar",
    "--spring.main.web-application-type=none", "--solar-system.trajectories.prewarm-enabled=false",
    "--solar-system.jpl.async-validation-enabled=false", "--solar-system.firestore.emulator-host=" + host,
    "--solar-system.firestore.collection=" + collection, "--solar-system.migration.enabled=true",
    "--solar-system.migration.apply=" + apply], { env, timeout: 45_000, encoding: "utf8" });
  assert.ifError(result.error);
  assert.equal(result.status, status, result.stdout + result.stderr);
}
await seed();
run(false);
assert.equal((await (await fetch(base)).json()).fields.displayTime, undefined);
run(true);
assert.ok((await (await fetch(base)).json()).fields.displayTime);
run(true);
fields.computedTimeUtc.stringValue = "invalid-date";
await seed();
run(true, 1);
console.log("Migration CLI dry-run/apply/reapply/corrupt-input exit checks passed.");
