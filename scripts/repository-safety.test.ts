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
