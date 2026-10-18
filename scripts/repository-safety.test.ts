import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";
import { parse } from "yaml";

const source = (path: string) => readFileSync(new URL("../" + path, import.meta.url), "utf8");

it("live integration profile excludes emulator-only tests", () => {
  const profile = source("backend/pom.xml").split("<id>integration-tests</id>")[1].split("</profile>")[0];
  expect(profile).toContain("<test.excludedGroups>firestore</test.excludedGroups>");
});

it("CI exercises mandatory emulator and packaged-browser checks without failure bypasses", () => {
  const { jobs } = parse(source(".github/workflows/ci.yml"));
  for (const job of [jobs.firestore, jobs.frontend]) {
    expect(job).not.toHaveProperty("if");
    expect(job).not.toHaveProperty("continue-on-error");
  }
  for (const step of jobs.firestore.steps) {
    expect(step).not.toHaveProperty("if");
    expect(step).not.toHaveProperty("continue-on-error");
  }
  const emulator = jobs.firestore.steps.find((step: { run?: string }) => step.run?.includes("-Pfirestore-tests"));
  expect(emulator.env.FIRESTORE_EMULATOR_HOST).toBe("127.0.0.1:18085");
  expect(emulator.run).toMatch(/mvn .*test -Pfirestore-tests/);
  const commands = emulator.run.trim().split("\n").map((line: string) => line.trim());
  expect(commands.at(-1)).toBe("mvn -B -f backend/pom.xml test -Pfirestore-tests");
  expect(emulator.run).not.toMatch(/\bset\s+[^\n;]*\+[^\s;]*e/);
  const browser = jobs.frontend.steps.find((step: { run?: string }) => step.run === "npm run test:e2e");
  expect(browser).toBeDefined();
  expect(browser).not.toHaveProperty("if");
  expect(browser).not.toHaveProperty("continue-on-error");
  expect(jobs.frontend.steps.some((step: { run?: string }) => step.run === "npx playwright install --with-deps chromium")).toBe(true);
});

it("CI gates moderate runtime advisories and reports the full audit without blocking", () => {
  const steps = parse(source(".github/workflows/ci.yml")).jobs.frontend.steps;
  const gate = steps.find((step: { run?: string }) => step.run === "npm audit --omit=dev --audit-level=moderate");
  expect(gate).toBeDefined();
  expect(gate).not.toHaveProperty("if");
  expect(gate).not.toHaveProperty("continue-on-error");
  const report = steps.find((step: { run?: string }) => step.run === "npm audit --audit-level=low");
  expect(report["continue-on-error"]).toBe(true);
  expect(report).not.toHaveProperty("if");
});

it("packaging defaults to the repository Maven wrapper", () => {
  expect(source("scripts/package-e2e.mjs")).toContain('process.env.MAVEN ?? "./mvnw"');
});

it("public operations docs describe pool isolation without dated operator status", () => {
  const readme = source("README.md").replace(/\s+/g, " ");
  expect(readme).not.toMatch(/Rollout status reported by the operator|Production service .*project|Still pending:/);
  expect(readme).toContain("16 cold-trajectory, 16 warm-trajectory response");
  expect(readme).toContain("cannot consume catalog-read capacity");
  expect(readme).toMatch(/\bat least 80\b/);
  expect(readme).toContain("in-flight limits of four per client key and eight per IPv6 /56");
  expect(readme).toContain("these limits last until the response finishes, independently of minute-quota resets");
  expect(readme).toContain("An IPv4 client key represents the whole NAT: one page load (four downloads) at a time.");
  expect(readme).toContain("Malformed chains can hold only one trajectory response at a time.");
});

it("tracked text files contain no deployment region or revision identifiers", () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const files = execFileSync("git", ["ls-files", "-z"], { cwd: root, encoding: "utf8" }).split("\0").filter(Boolean);
  for (const file of files) {
    const bytes = readFileSync(new URL("../" + file, import.meta.url));
    if (bytes.includes(0)) continue; // Binary assets are not text.
    const text = bytes.toString("utf8");
    expect(text, file).not.toMatch(/\b(us|europe|asia|australia|northamerica|southamerica|me|africa)-[a-z]+\d{1,2}\b/i);
    expect(text, file).not.toMatch(/\bsolar-system-\d{5}\b/i);
  }
});

it("the Vite development dependency includes the launch-editor advisory fixes", () => {
  const version = JSON.parse(source("package-lock.json")).packages["node_modules/vite"].version.split(".").map(Number);
  expect(version[0] > 8 || (version[0] === 8 && (version[1] > 3 || (version[1] === 3 && version[2] >= 4)))).toBe(true);
});

it("Vitest includes the manual-mock registration and traversal fixes", () => {
  const lock = JSON.parse(source("package-lock.json"));
  for (const name of ["vitest", "@vitest/mocker"]) {
    const version = lock.packages["node_modules/" + name].version.split(".").map(Number);
    expect(version[0] > 4 || (version[0] === 4 && (version[1] > 1 || (version[1] === 1 && version[2] >= 12)))).toBe(true);
  }
});

it("catalog runbook keeps rollout steps together and describes hidden immutable quarantine versions", () => {
  const readme = source("README.md");
  expect(readme).toContain("{updateSeconds}-{updateNanos}");
  expect(readme).toContain("stays hidden from search and generation until repaired");
  expect(readme).toContain("up to 2,000");
  expect(readme.indexOf("4. Review")).toBeLessThan(readme.indexOf("Generation quarantines malformed originals"));
  const steps = readme.slice(readme.indexOf("1. Back up"), readme.indexOf("Generation quarantines malformed originals"));
  expect([...steps.matchAll(/^(\d+)\. /gm)].map(match => Number(match[1]))).toEqual([1, 2, 3, 4, 5]);
  for (const step of steps.split(/(?=^\d+\. )/m)) {
    for (const line of step.split("\n").slice(1)) {
      expect(line.trim() === "" || line.startsWith("   "), "unindented paragraph splits rollout steps: " + line).toBe(true);
    }
  }
  expect(readme).not.toContain("one lookahead document");
  expect(readme).not.toContain("create-only transactions");
  expect(readme).toContain("transactional existence checks and writes");
  expect(readme).toContain("Finite numeric strings remain accepted");
  expect(readme).toContain("including their string representations");
  expect(source("backend/src/test/java/dev/andreydg/solarsystem/storage/CatalogNumericParsingTests.java"))
    .toContain("nonFiniteNumbersAndTheirStringRepresentationsCannotEnterCatalogGeometry");
  expect(readme).toMatch(/### API safety and operations\n\nGeneration\/query/);
});

it("catalog production repositories have no test-only bulk API or unused explicit imports", () => {
  const directory = "backend/src/main/java/dev/andreydg/solarsystem/storage/";
  expect(source(directory + "EventCatalogRepository.java")).not.toMatch(/\bupsertAll\s*\(/);
  for (const file of ["FirestoreEventCatalogRepository.java", "InMemoryEventCatalogRepository.java"]) {
    const text = source(directory + file);
    const imports = [...text.matchAll(/^\s*import\s+(?:static\s+)?([\w.*\s]+);/gm)];
    const body = text.replace(/^\s*import\s+[^;]+;/gm, "")
      .replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, "")
      .replace(/\s*\.\s*/g, ".");
    for (const match of imports) {
      const imported = match[1].replace(/\s+/g, "");
      expect(imported, file + ": use explicit imports").not.toContain("*");
      const simple = imported.split(".").at(-1)!;
      // Qualified mentions do not consume an import, even with whitespace around dots.
      expect(body, file + ": unused " + imported).toMatch(new RegExp("(?<![\\w.$])" + simple + "\\b"));
    }
  }
});
it("worker runbook explains the reservation and scheduling capacity tradeoff", () => {
  const readme = source("README.md");
  for (const text of ["319 seconds", "161-second claim window", "33 five-second jobs", "one-minute, overlap-safe"])
    expect(readme).toContain(text);
});
it("fixture overview records every capture date and the fifteen recaptured coarse files", () => {
  const provenance = JSON.parse(source("backend/src/test/resources/jpl/cases/provenance.json"));
  const dates = new Set([provenance.retrieved, ...provenance.recaptured]);
  const entries = provenance.requests as { retrieved?: string }[];
  expect(entries.filter(entry => entry.retrieved === "2026-10-18")).toHaveLength(15);
  expect(dates).toEqual(new Set(entries.map(entry => entry.retrieved ?? provenance.retrieved)));
  const readme = source("backend/src/test/resources/jpl/README.txt");
  for (const date of dates) expect(readme).toContain(date);
  expect(readme).toContain("15 coarse files recaptured 2026-10-18");
});

it("scientific documentation states brightness filtering and repeated-orbit seam limitations", () => {
  const readme = source("README.md").replace(/\s+/g, " ");
  expect(readme).toContain("at least 0.05 mag within 120 days on both sides");
  expect(readme).toContain("repeated-orbit wrap seams can produce opposite stationary crossings within 24 hours");
  expect(readme).toContain("for Ceres, Vesta, Encke and Halley at or beyond the edges of native coverage.");
  expect(readme).toContain("These provisional artifacts are not confirmed physical events");
  expect(readme).toContain("preserve JPL Table 1 throughout its native 1800–2050 fit");
  expect(readme).toContain("J2000 centuries −2.1..−2 and 0.5..0.6");
  expect(readme).toContain("in the ramps, where JPL gives no accuracy guarantee.");
  const fixtures = source("backend/src/test/resources/jpl/README.txt");
  expect(fixtures).toContain("Uranus–Neptune ranges");
  expect(fixtures.replace(/\s+/g, " ")).toContain(
    "Vesta candidate orbit: normalized from the unmodified NASA/JPL response captured " +
    "2026-10-12 for #36 (vesta-v1.txt), target 4;, with the same frame/time options. " +
    "Daily vectors from 2024-03-09 to 2027-10-26.");
  const rows = source("backend/src/test/resources/jpl/vesta-orbit.csv").trim().split("\n");
  const times = rows.map(row => Date.parse(row.split(",")[0]));
  expect(new Date(times[0]).toISOString().slice(0, 10)).toBe("2024-03-09");
  expect(new Date(times.at(-1)!).toISOString().slice(0, 10)).toBe("2027-10-26");
  expect(times.slice(1).every((time, i) => time - times[i] === 86_400_000)).toBe(true);
  expect(fixtures).not.toContain("outer-pair ranges");
});
it("scene recovery docs state the bounded fresh-index check without an offline guarantee", () => {
  expect(source("index.html")).toContain('id="root" data-app="solar-system-explorer"');
  const readme = source("README.md").replace(/\s+/g, " ");
  expect(readme).toContain("a module script plus either this app's index marker or its exact title");
  expect(readme).toContain("two-second deadline");
  expect(readme).toContain("Connectivity can still change after a successful");
  expect(readme).not.toContain("navigates to the browser's offline error page");
});
it("browser test documentation names the wrapper default and real packaged API coverage", () => {
  const readme = source("README.md").replace(/\s+/g, " ");
  expect(readme).toContain("Packaging defaults to the repository's `./mvnw`");
  expect(readme).toContain("real-catalog tests and the invalid-request check exercise the packaged backend");
  expect(readme).not.toContain("if Maven is not on PATH");
  expect(readme).not.toContain("API responses are explicit offline fixtures");
});

it("event navigation documentation distinguishes snapshot replay from fresh guarded searches", () => {
  const readme = source("README.md").replace(/\s+/g, " ");
  expect(readme).toContain("Next undoes Prev, and Prev undoes Next");
  expect(readme).toContain("at most 100 events and resets when the date is edited (date field or Today) or event criteria change. Playback does not reset it.");
  expect(readme).toContain("Revisited snapshots are not refreshed from the catalog");
  expect(readme).toContain("a fresh search starts one day after/before the shown event");
});
