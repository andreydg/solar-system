import assert from "node:assert/strict";
import { mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";

function files(directory, prefix = "") {
  return readdirSync(directory).flatMap(name => {
    const path = join(directory, name), relative = prefix + name;
    return statSync(path).isDirectory() ? files(path, relative + "/") : [relative];
  });
}

function verifyPackagedAssetsContainOnlyCurrentBuild() {
  const stale = "backend/target/classes/static/assets/audit-stale-sentinel.js";
  mkdirSync("backend/target/classes/static/assets", { recursive: true });
  writeFileSync(stale, "/* obsolete prior build */");
  try {
    execFileSync(process.execPath, ["scripts/package-e2e.mjs"], { stdio: "inherit" });
    const prefix = "BOOT-INF/classes/static/";
    const entries = execFileSync("jar", ["tf", "backend/target/solar-system-backend-0.1.0.jar"], { encoding: "utf8" })
      .split("\n").filter(name => name.startsWith(prefix) && !name.endsWith("/")).map(name => name.slice(prefix.length));
    assert(!entries.includes("assets/audit-stale-sentinel.js"), "obsolete hashed assets must not survive packaging");
    assert.deepEqual(entries.sort(), files("dist").sort(), "the jar must contain exactly the current real Vite build");
    console.log("verifyPackagedAssetsContainOnlyCurrentBuild passed");
  } finally {
    rmSync(stale, { force: true });
  }
}

verifyPackagedAssetsContainOnlyCurrentBuild();
