import { expect, test } from "@playwright/test";

const event = {
  id: "test-mars", type: "closestApproach", bodyA: "earth", bodyB: "mars",
  timeUtc: "2027-02-20T12:00:00Z", computedTimeUtc: "2027-02-20T12:00:00Z",
  distanceAu: 0.68, distanceKm: 101726552, angleDeg: null, magnitude: null,
  source: "JPL_HORIZONS", validationStatus: "pending",
};
test.beforeEach(async ({ page }) => {
  // Offline by construction. Real packaged UI; explicit API fixtures only.
  await page.route("**/api/**", route => route.fulfill({ status: 503, contentType: "application/json", body: '{"message":"Offline test"}' }));
});

test("production jar serves the built favicon and WebGL scene; UTC editor survives empty values", async ({ page, request }) => {
  const icon = await request.get("/favicon-32.png");
  expect(icon.ok()).toBeTruthy();
  expect(icon.headers()["content-type"]).toContain("image/png");
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(page.locator("canvas")).toBeVisible();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-07-04T12:30");
  await expect(date).toHaveValue("2026-07-04T12:30");
  await expect(page.locator(".time-readout")).toContainText("12:30");
  await date.fill("");
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeVisible();
});

test("Find becomes next/prev; JPL-sourced pending data is not called validated", async ({ page }) => {
  await page.route("**/api/events?**", route => route.fulfill({ json: [event] }));
  await page.goto("/");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Prev/ })).toBeVisible();
  await expect(page.getByText(/Provisional.*queued/i)).toBeVisible();
  await page.getByRole("combobox", { name: "Body B", exact: true }).selectOption("jupiter");
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeVisible();
});

test("changing criteria cancels a pending search without resurrecting the old result", async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/events?**", async route => { await gate; await route.fulfill({ json: [event] }); });
  await page.goto("/");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await page.getByRole("combobox", { name: "Body B", exact: true }).selectOption("jupiter");
  release();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeEnabled();
  await expect(page.getByRole("button", { name: /Prev/ })).toHaveCount(0);
});

test("Halley failure stays actionable without invoking unsupported browser ephemeris", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  await page.getByRole("combobox", { name: "Body B", exact: true }).selectOption("halley");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByText("Offline test", { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test("validated-event dialog does not query until requested", async ({ page }) => {
  let queries = 0;
  await page.route("**/api/events/validated?**", route => { queries++; return route.fulfill({ json: [] }); });
  await page.goto("/");
  await page.getByRole("button", { name: "Validated events", exact: true }).click();
  expect(queries).toBe(0);
  await page.getByRole("button", { name: "Query", exact: true }).click();
  await expect(page.getByText("No validated events match these criteria.")).toBeVisible();
  expect(queries).toBe(1);
});

test("UTC year can be typed one digit at a time", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-07-04T12:30");
  for (let i = 0; i < 8; i++) await date.press("ArrowLeft");
  await date.press("ArrowRight");
  await date.press("ArrowRight");
  await date.pressSequentially("2030");
  await page.getByRole("heading", { name: "Time", exact: true }).click();
  await expect(date).toHaveValue("2030-07-04T12:30");
  await date.fill("0020-07-04T12:30");
  await page.getByRole("heading", { name: "Time", exact: true }).click();
  await expect(date).toHaveValue("2030-07-04T12:30");
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
});

test("catalog requests work without newer AbortSignal static APIs", async ({ page }) => {
  let requests = 0;
  await page.addInitScript(() => {
    Object.defineProperty(AbortSignal, "any", { value: undefined });
    Object.defineProperty(AbortSignal, "timeout", { value: undefined });
  });
  await page.route("**/api/events?**", route => { requests++; return route.fulfill({ json: [{ ...event, type: "opposition" }] }); });
  await page.goto("/");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  expect(requests).toBe(1);
  await expect(page.getByText("Loaded provisional event.", { exact: true })).toBeVisible();
  await expect(page.getByText(/Backend unavailable/)).toHaveCount(0);
});

test("missing WebGL is contained and Retry preserves the chosen date", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext;
    const flags = window as Window & { noWebgl: boolean };
    flags.noWebgl = true;
    HTMLCanvasElement.prototype.getContext = function (...args: Parameters<typeof original>) {
      if (flags.noWebgl && String(args[0]).startsWith("webgl")) return null;
      return Reflect.apply(original, this, args);
    } as typeof original;
  });
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("3D view could not load");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("1700-01-01T12:30");
  await date.press("Tab");
  await page.evaluate(() => { (window as Window & { noWebgl: boolean }).noWebgl = false; });
  await page.getByRole("button", { name: "Retry 3D view" }).click();
  await expect(page.locator("canvas")).toBeVisible();
  await expect(date).toHaveValue("1700-01-01T12:30");
  expect(errors).toEqual([]);
});

test("a failed scene chunk can retry in place", async ({ page }) => {
  let attempts = 0;
  await page.route("**/assets/SolarSystemScene-*.js*", route => ++attempts === 1 ? route.abort() : route.continue());
  await page.goto("/");
  await expect(page.getByRole("alert")).toBeVisible();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("1700-01-01T12:30");
  await date.press("Tab");
  await page.getByRole("button", { name: "Retry 3D view" }).click();
  await expect(page.locator("canvas")).toBeVisible();
  await expect(date).toHaveValue("1700-01-01T12:30");
});

test("a texture failure can retry without resetting controls", async ({ page }) => {
  let attempts = 0;
  await page.route("**/textures/marsmap1k.jpg", route => ++attempts === 1 ? route.abort() : route.continue());
  await page.goto("/");
  await expect(page.getByRole("alert")).toBeVisible();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("1700-01-01T12:30");
  await date.press("Tab");
  await page.getByRole("button", { name: "Retry 3D view" }).click();
  await expect(page.locator("canvas")).toBeVisible();
  await expect.poll(() => attempts).toBe(2);
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await expect(page.locator(".planet-label").filter({ hasText: "Mars" })).toHaveCount(1);
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect(date).toHaveValue("1700-01-01T12:30");
});

test("Find uses the actual packaged catalog and generation endpoint", async ({ page }) => {
  await page.route("**/api/events**", route => route.continue());
  await page.goto("/");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-01-01T00:00");
  await date.press("Tab");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await expect(page.locator(".event-card")).toContainText("2027");
  await expect(page.getByText(/Provisional.*queued/i)).toBeVisible();
});

test("validated pagination keeps the applied range and sort when selectors are edited", async ({ page }) => {
  const requests: URL[] = [];
  await page.route("**/api/events/validated?**", route => {
    requests.push(new URL(route.request().url()));
    return route.fulfill({ json: requests.length === 1 ? [event] : [],
      headers: requests.length === 1 ? { "X-Next-Cursor": "next-page" } : {} });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Validated events", exact: true }).click();
  await expect(page.getByLabel("From (UTC)")).toHaveValue("1600-01-01");
  await page.getByRole("button", { name: "Query", exact: true }).click();
  await expect(page.getByRole("button", { name: "Load more" })).toBeVisible();
  await page.getByLabel("From (UTC)").fill("");
  await page.getByLabel("Sort by time").selectOption("desc");
  await expect(page.getByText(/Results for.*earliest first/)).toBeVisible();
  await page.getByRole("button", { name: "Load more" }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests[1].searchParams.get("from")).toBe("1600-01-01T00:00:00Z");
  expect(requests[1].searchParams.get("descending")).toBe("false");
});

test("Vesta's failed moon texture refetches and stays recovered after Retry", async ({ page }) => {
  let attempts = 0;
  await page.route("**/api/ephemeris/trajectory?**", route => route.fulfill({ json: [
    { timeUtc: "1600-01-01T00:00:00Z", x: 3, y: 2, z: 0 },
    { timeUtc: "2999-12-31T00:00:00Z", x: 3, y: 2, z: 0 },
  ] }));
  await page.route("**/textures/moon.jpg", route => ++attempts === 1 ? route.abort() : route.continue());
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByRole("checkbox", { name: "Vesta", exact: true }).check();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "Retry 3D view", exact: true }).click();
  await expect.poll(() => attempts).toBe(2);
  await expect(page.locator(".planet-label").filter({ hasText: "Vesta" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("a scene import that fails again on Retry reloads the document once", async ({ page }) => {
  let attempts = 0, documents = 0;
  page.on("request", request => { if (request.isNavigationRequest()) documents++; });
  await page.route("**/assets/SolarSystemScene-*.js*", route => ++attempts <= 2 ? route.abort() : route.continue());
  await page.goto("/");
  await expect(page.getByRole("alert")).toBeVisible();
  const navigation = page.waitForEvent("framenavigated", frame => frame === page.mainFrame());
  await page.getByRole("button", { name: "Retry 3D view", exact: true }).click();
  await navigation;
  await expect(page.locator("canvas")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  expect(documents).toBe(2);
  expect(attempts).toBe(3);
});

test("small-body paths are lazy and the real bundled Halley path supports camera focus", async ({ page }) => {
  const requested: string[] = [];
  await page.route("**/api/ephemeris/trajectory?**", route => {
    requested.push(new URL(route.request().url()).searchParams.get("body")!);
    return route.continue();
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeVisible();
  expect(requested).toEqual([]);
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByRole("checkbox", { name: "Halley", exact: true }).check();
  await expect(page.getByRole("button", { name: "Halley", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Halley", exact: true }).focus();
  await page.getByRole("button", { name: "Halley", exact: true }).press("Enter");
  await expect(page.getByText("Following Halley")).toBeVisible();
  expect(requested).toEqual(["halley"]);
});

test("failed scene chunk leaves controls usable with a retry action", async ({ page }) => {
  await page.route("**/assets/SolarSystemScene-*.js", route => route.abort());
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("3D view could not load");
  await expect(page.getByRole("button", { name: "Retry 3D view" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Today", exact: true })).toBeEnabled();
});

test("adding small bodies and toggling planets does not abort or reload wanted trajectories", async ({ page }) => {
  const requested: string[] = [];
  const failed: string[] = [];
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  page.on("requestfailed", request => { if (request.url().includes("/api/ephemeris/trajectory")) failed.push(request.url()); });
  await page.route("**/api/ephemeris/trajectory?**", async route => {
    requested.push(new URL(route.request().url()).searchParams.get("body")!);
    await gate;
    await route.continue();
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  for (const body of ["Ceres", "Vesta", "Encke", "Halley"])
    await page.getByRole("checkbox", { name: body, exact: true }).check();
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await expect.poll(() => requested.length).toBe(4);
  release();
  await expect(page.getByRole("button", { name: "Halley", exact: true })).toBeVisible();
  expect(requested.sort()).toEqual(["ceres", "encke", "halley", "vesta"]);
  expect(failed).toEqual([]);
});

test("trajectory 429 retries automatically and far-date approximation is labelled", async ({ page }) => {
  let requests = 0;
  await page.route("**/api/ephemeris/trajectory?body=halley", route => {
    requests++;
    return requests === 1 ? route.fulfill({ status: 429, headers: { "Retry-After": "1" }, json: { message: "busy" } }) : route.continue();
  });
  await page.goto("/");
  await page.getByLabel("Jump to date (UTC)").fill("1700-01-01T00:00");
  await page.getByRole("checkbox", { name: "Halley", exact: true }).check();
  await expect(page.getByText(/Halley: repeated-orbit approximation/)).toBeVisible();
  expect(requests).toBe(2);
});

test("trajectory errors remain visible beside event status", async ({ page }) => {
  await page.route("**/api/events?**", route => route.fulfill({ json: [event] }));
  await page.goto("/");
  await page.getByRole("checkbox", { name: "Halley", exact: true }).check();
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByText(/Halley's path could not load/)).toBeVisible();
  await expect(page.getByText(/Provisional.*queued/)).toBeVisible();
});

test("successful assets are cacheable, missing assets are not, and JSON is compressed", async ({ request }) => {
  const html = await (await request.get("/")).text();
  const asset = html.match(/src="(\/assets\/[^"]+\.js)"/)?.[1];
  expect(asset).toBeTruthy();
  const present = await request.get(asset!);
  expect(present.ok()).toBeTruthy();
  expect(present.headers()["cache-control"]).toContain("immutable");
  const missing = await request.get("/assets/never-built-review.js");
  expect(missing.status()).toBe(404);
  expect(missing.headers()["cache-control"]).toBe("no-store");
  const trajectory = await request.get("/api/ephemeris/trajectory?body=halley", { headers: { "Accept-Encoding": "gzip" } });
  expect(trajectory.ok()).toBeTruthy();
  expect(trajectory.headers()["content-encoding"]).toBe("gzip");
  expect((await trajectory.json()).length).toBeGreaterThan(10000);
});
test("the production JavaScript MIME type is gzip-compressed", async ({ request }) => {
  const html = await (await request.get("/")).text();
  const asset = html.match(/src="([^"]+\.js)"/)![1];
  const response = await request.get(asset, { headers: { "Accept-Encoding": "gzip" } });
  expect(response.ok()).toBeTruthy();
  expect(response.headers()["content-type"]).toContain("text/javascript");
  expect(response.headers()["content-encoding"]).toBe("gzip");
});

test("manual trajectory retry clears the stale error while its new request is pending", async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let requests = 0;
  await page.route("**/api/ephemeris/trajectory?**", async route => {
    if (++requests === 1) return route.fulfill({ status: 400, json: { message: "unavailable" } });
    await pending;
    await route.fulfill({ json: [
      { timeUtc: "2026-01-01Z", x: 1, y: 0, z: 0 }, { timeUtc: "2027-01-01Z", x: 2, y: 0, z: 0 },
    ] });
  });
  await page.goto("/");
  const body = page.getByRole("checkbox", { name: "Halley", exact: true });
  await body.check();
  await expect(page.getByText(/Halley's path could not load/)).toBeVisible();
  await body.uncheck();
  await body.check();
  await expect.poll(() => requests).toBe(2);
  await expect(page.getByText(/Halley's path could not load/)).toHaveCount(0);
  release();
});
