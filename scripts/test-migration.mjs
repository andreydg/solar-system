import { spawnSync } from "node:child_process";
import assert from "node:assert/strict";

const host = process.env.FIRESTORE_EMULATOR_HOST;
assert.match(host ?? "", /^(127\.0\.0\.1|localhost):[0-9]+$/);
const root = `http://${host}/v1/projects/solar-system-test/databases/(default)/documents`;
const time = "2027-01-01T00:00:00Z";
for (const explicitHost of [false, true]) {
  const collection = "migration-smoke-" + Date.now() + "-" + explicitHost;
  const base = `${root}/${collection}/legacy`;
  const marker = `${root}/_catalogMetadata/${collection}`;
  const fields = Object.fromEntries(Object.entries({
    id: "legacy", type: "opposition", bodyA: "earth", bodyB: "mars",
    computedTimeUtc: time, computedSource: "VSOP", validationStatus: "pending",
    rangeStartUtc: time, rangeEndUtc: "2028-01-01T00:00:00Z", generatedAtUtc: time,
  }).map(([key, value]) => [key, { stringValue: value }]));
  async function seed(url = base) {
    const response = await fetch(url, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fields }) });
    assert.equal(response.status, 200, await response.text());
  }
  function run(apply, status = 0, target = collection) {
    const env = { ...process.env, GOOGLE_CLOUD_PROJECT: "solar-system-test" };
    if (explicitHost) delete env.FIRESTORE_EMULATOR_HOST;
    const result = spawnSync("java", ["-jar", "backend/target/solar-system-backend-0.1.0.jar",
      "--spring.main.web-application-type=none", "--solar-system.trajectories.prewarm-enabled=false",
      "--solar-system.jpl.async-validation-enabled=false",
      ...(explicitHost ? ["--solar-system.firestore.emulator-host=" + host] : []),
      "--solar-system.firestore.collection=" + target, "--solar-system.migration.enabled=true",
      "--solar-system.migration.apply=" + apply], { env, timeout: 45_000, encoding: "utf8" });
    assert.ifError(result.error);
    assert.equal(result.status, status, result.stdout + result.stderr);
  }
  await seed();
  run(false);
  assert.equal((await (await fetch(base)).json()).fields.displayTime, undefined);
  assert.equal((await fetch(marker)).status, 404, "dry run must not activate the catalog");
  run(true);
  assert.ok((await (await fetch(base)).json()).fields.displayTime);
  assert.equal((await (await fetch(marker)).json()).fields.indexedQueriesReady.booleanValue, true);
  run(true);
  assert.equal((await (await fetch(marker)).json()).fields.indexedQueriesReady.booleanValue, true);
  const broken = collection + "-broken";
  fields.computedTimeUtc.stringValue = "invalid-date";
  await seed(`${root}/${broken}/legacy`);
  run(true, 1, broken);
  assert.equal((await fetch(`${root}/_catalogMetadata/${broken}`)).status, 404, "malformed migration must not activate");
}
console.log("Migration CLI exit and activation checks passed with env-var and explicit emulator configuration.");
