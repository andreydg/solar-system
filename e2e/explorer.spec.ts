import { expect, test } from "@playwright/test";

const event = {
  id: "test-mars", type: "closestApproach", bodyA: "earth", bodyB: "mars",
  timeUtc: "2027-02-20T12:00:00Z", computedTimeUtc: "2027-02-20T12:00:00Z",
  distanceAu: 0.68, distanceKm: 101726552, angleDeg: null, magnitude: null,
  source: "JPL_HORIZONS", validationStatus: "pending",
};
test.beforeEach(async ({ page }) => {
  // Offline by default. Individual real-catalog cases explicitly opt into the packaged API.
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
  // Intentionally after the fixed fixture: this test must not depend on today.
  await page.clock.setSystemTime(new Date("2030-03-01T00:00:00Z"));
  await page.route("**/api/events?**", route => route.fulfill({ json: [event] }));
  await page.goto("/");
  await page.getByLabel("Jump to date (UTC)").fill("2027-01-01T00:00");
  await page.getByLabel("Jump to date (UTC)").press("Tab");
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
  // Intentionally after the fixed fixture: this test must not depend on today.
  await page.clock.setSystemTime(new Date("2030-03-01T00:00:00Z"));
  let requests = 0;
  await page.addInitScript(() => {
    Object.defineProperty(AbortSignal, "any", { value: undefined });
    Object.defineProperty(AbortSignal, "timeout", { value: undefined });
  });
  await page.route("**/api/events?**", route => { requests++; return route.fulfill({ json: [{ ...event, type: "opposition" }] }); });
  await page.goto("/");
  await page.getByLabel("Jump to date (UTC)").fill("2027-01-01T00:00");
  await page.getByLabel("Jump to date (UTC)").press("Tab");
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
  const navigation = page.waitForEvent("framenavigated", { predicate: frame => frame === page.mainFrame(), timeout: 5_000 });
  await page.getByRole("button", { name: "Retry 3D view", exact: true }).click();
  await navigation;
  await expect(page.locator("canvas")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  expect(documents).toBe(2);
  expect(attempts).toBe(3);
});
test("Retry while offline preserves the in-place alert and controls without navigating", async ({ page, context }) => {
  let attempts = 0, documents = 0;
  page.on("request", request => { if (request.isNavigationRequest()) documents++; });
  await page.route("**/assets/SolarSystemScene-*.js*", route => { attempts++; return route.abort(); });
  await page.goto("/");
  await expect(page.getByRole("alert")).toBeVisible();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("1700-01-01T12:30");
  await date.press("Tab");
  await context.setOffline(true);
  await expect.poll(() => page.evaluate(() => navigator.onLine)).toBe(false);
  await page.getByRole("button", { name: "Retry 3D view", exact: true }).click();
  await expect.poll(() => attempts).toBe(2);
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page.getByRole("alert")).toContainText("3D view could not load");
  await expect(date).toHaveValue("1700-01-01T12:30");
  expect(documents).toBe(1);
  expect(page.url()).not.toContain("chrome-error");
  await context.setOffline(false);
});
for (const failure of ["refused", "captive portal", "HTTP error", "hung"] as const) {
  test(`Retry with an online OS and ${failure} index preserves controls without navigation`, async ({ page }) => {
    let documents = 0, probes = 0;
    page.on("request", request => { if (request.isNavigationRequest()) documents++; });
    await page.route("**/assets/SolarSystemScene-*.js*", route => route.abort());
    await page.goto("/");
    await expect(page.getByRole("alert")).toBeVisible();
    const date = page.getByLabel("Jump to date (UTC)");
    await date.fill("1700-01-01T12:30"); await date.press("Tab");
    expect(await page.evaluate(() => navigator.onLine)).toBe(true);
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    await page.route(page.url(), async route => {
      // Do not hold a bad reload behind the probe gate: it must fail promptly.
      if (route.request().isNavigationRequest()) return route.continue();
      probes++;
      if (failure === "refused") return route.abort("connectionrefused");
      if (failure === "hung") { await gate; return route.abort(); }
      return route.fulfill({ status: failure === "HTTP error" ? 503 : 200,
        contentType: "text/html", body: "<html>Sign in to Wi-Fi</html>" });
    });
    try {
      await page.getByRole("button", { name: "Retry 3D view", exact: true }).click();
      await expect.poll(() => probes).toBe(1);
      await expect(page.getByRole("alert")).toBeVisible();
      await expect(date).toHaveValue("1700-01-01T12:30");
      expect(documents).toBe(1);
      expect(page.url()).not.toContain("chrome-error");
    } finally { release(); }
  });
}

test("Find advances past a stored event and Prev keeps the UI one-day guard", async ({ page }) => {
  await page.route("**/api/events**", route => route.continue());
  await page.goto("/");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-01-01T00:00"); await date.press("Tab");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  const first = await date.inputValue();
  const firstCard = await page.locator(".event-card").textContent();
  const type = page.getByRole("combobox", { name: "Event type", exact: true });
  await type.selectOption("opposition"); await type.selectOption("closestApproach");
  const storedResponse = page.waitForResponse(response => new URL(response.url()).pathname === "/api/events");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await expect.poll(() => date.inputValue()).not.toBe(first);
  expect(await date.inputValue() > first).toBe(true);
  const storedEvents = await (await storedResponse).json() as { timeUtc: string }[];
  expect(storedEvents).toHaveLength(1);
  const anchor = Date.parse(storedEvents[0].timeUtc);
  const previousRequest = page.waitForRequest(request => {
    const url = new URL(request.url());
    return url.pathname === "/api/events" && url.searchParams.get("descending") === "true";
  });
  await page.getByRole("button", { name: /Prev/ }).click();
  const query = new URL((await previousRequest).url()).searchParams;
  // The UI's day guard and the library's strict 1ms boundary have distinct jobs.
  expect(Date.parse(query.get("to")!)).toBe(anchor - 86_400_000 - 1);
  await expect(date).toHaveValue(first);
  await expect(page.locator(".event-card")).toHaveText(firstCard!);
});

async function serveNavigationCatalog(page: import("@playwright/test").Page, read: () => typeof event[]) {
  const queries: URL[] = [];
  await page.route("**/api/events?**", route => {
    const url = new URL(route.request().url());
    queries.push(url);
    const params = url.searchParams;
    const from = Date.parse(params.get("from")!), to = Date.parse(params.get("to")!);
    const direction = params.get("descending") === "true" ? -1 : 1;
    const rows = read().filter(row => Date.parse(row.timeUtc) >= from && Date.parse(row.timeUtc) <= to)
      .sort((a, b) => direction * (Date.parse(a.timeUtc) - Date.parse(b.timeUtc)));
    const offset = Number(params.get("cursor") ?? 0);
    return route.fulfill({ json: rows.slice(offset, offset + 1),
      headers: offset + 1 < rows.length ? { "X-Next-Cursor": String(offset + 1) } : {} });
  });
  return queries;
}

test("Prev does not reselect an opposition moved earlier by validation", async ({ page }) => {
  const shown = { ...event, type: "opposition", timeUtc: "2027-02-19T15:44:00Z" };
  const previous = { ...shown, id: "previous", timeUtc: "2025-01-16T02:37:00Z", validationStatus: "validated" };
  let catalog = [shown, previous];
  await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-01-01T00:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2027-02-19T15:44");
  catalog = [{ ...shown, timeUtc: "2027-02-19T15:43:10Z", validationStatus: "validated" }, previous];
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2025-01-16T02:37");
  await expect(page.getByText("Loaded event.", { exact: true })).toBeVisible();
});

test("offline Prev from a server closest-approach time finds the preceding physical event", async ({ page }) => {
  let offline = false, requests = 0;
  await page.route("**/api/events?**", route => {
    requests++;
    return offline ? route.fulfill({ status: 503, json: { message: "offline after Find" } })
      : route.fulfill({ json: [{ ...event, timeUtc: "2029-03-29T13:00:00Z" }] });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2028-01-01T00:00"); await date.press("Tab");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2029-03-29T13:00");
  offline = true;
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue(/^2027-02-20T/);
  await expect(page.getByText(/Backend unavailable; used browser calculation/)).toBeVisible();
  const fallbackCard = await page.locator(".event-card").textContent();
  const fallbackDate = await date.inputValue();
  const count = requests;
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2029-03-29T13:00");
  await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue(fallbackDate);
  await expect(page.locator(".event-card")).toHaveText(fallbackCard!);
  await expect(page.getByText(/Backend unavailable; used browser calculation/)).toBeVisible();
  expect(requests).toBe(count);
});

for (const previous of [true, false]) {
  test((previous ? "Prev" : "Next") + " skips the shown ID after a 31-day validation shift", async ({ page }) => {
    const shown = { ...event, type: "opposition" };
    let catalog = [shown];
    const queries = await serveNavigationCatalog(page, () => catalog);
    await page.goto("/");
    await page.getByRole("button", { name: "Pause", exact: true }).click();
    const date = page.getByLabel("Jump to date (UTC)");
    await date.fill("2026-01-01T00:00"); await date.press("Tab");
    await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(date).toHaveValue("2027-02-20T12:00");
    const adjacent = { ...shown, id: "adjacent",
      timeUtc: previous ? "2025-01-16T02:37:00Z" : "2029-03-25T00:00:00Z", validationStatus: "validated" };
    catalog = [{ ...shown, timeUtc: new Date(Date.parse(shown.timeUtc) + (previous ? -31 : 31) * 86_400_000).toISOString() }, adjacent];
    await page.getByRole("button", { name: previous ? /Prev/ : /Next/ }).click();
    await expect(date).toHaveValue(adjacent.timeUtc.slice(0, 16));
    expect(queries.some(query => query.searchParams.get("cursor") === "1")).toBe(true);
  });
}

test("event history makes Next undo Prev across the dense Vesta pair without another request", async ({ page }) => {
  // Actual recorded-orbit results: a fresh day-guarded search alone cannot undo Prev.
  const catalog = ["2016-11-30T20:02:54Z", "2016-12-05T11:59:59Z", "2016-12-06T11:59:59Z", "2017-03-06T21:00:31Z"]
    .map((timeUtc, i) => ({ ...event, id: String(i), type: "stationary", bodyB: "vesta", timeUtc, source: "JPL_TRAJECTORY_APPROX", validationStatus: i === 0 ? "validated" : "pending" }));
  // Event status and trajectory failure notices deliberately coexist in this layer.
  const eventStatus = page.locator(".control-section").filter({ has: page.getByRole("heading", { name: "Events", exact: true }) }).locator(".status-text");
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2016-12-05T12:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("stationary");
  await page.getByRole("combobox", { name: "Target", exact: true }).selectOption("vesta");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2016-12-06T11:59");
  await expect(page.getByText(/Vesta's path could not load/)).toBeVisible();
  const originalCard = await page.locator(".event-card").textContent();
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2016-11-30T20:02");
  expect(Date.parse(queries.at(-1)!.searchParams.get("to")!)).toBe(Date.parse(catalog[2].timeUtc) - 86_400_000 - 1);
  await expect(eventStatus).toHaveText("Loaded event.");
  const count = queries.length;
  // Later catalog validation must not silently replace a snapshot during undo.
  catalog[2] = { ...catalog[2], timeUtc: "2016-12-05T11:59:30Z", validationStatus: "validated" };
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2016-12-06T11:59");
  await expect(page.locator(".event-card")).toHaveText(originalCard!);
  await expect(eventStatus).toHaveText("Loaded provisional event.");
  expect(queries).toHaveLength(count);
  await page.getByRole("button", { name: /Next/ }).click(); // edge: fresh search still has its day guard
  await expect(date).toHaveValue("2017-03-06T21:00");
  expect(Date.parse(queries.at(-1)!.searchParams.get("from")!)).toBe(Date.parse("2016-12-06T11:59:59Z") + 86_400_000 + 1);
  const freshCount = queries.length;
  for (const expected of ["2016-12-06T11:59", "2016-11-30T20:02"]) {
    await page.getByRole("button", { name: /Prev/ }).click();
    await expect(date).toHaveValue(expected);
  }
  await page.getByRole("checkbox", { name: "Vesta", exact: true }).uncheck();
  await page.getByRole("button", { name: "Earth", exact: true }).click();
  await expect(page.locator(".focus-chip")).toContainText("Following Earth");
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2016-12-06T11:59");
  await expect(page.getByRole("checkbox", { name: "Vesta", exact: true })).toBeChecked();
  await expect(page.locator(".focus-chip")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2017-03-06T21:00");
  expect(queries).toHaveLength(freshCount);
});

for (const reset of ["date", "type", "bodyA", "bodyB"] as const) {
  test(`event history resets on ${reset} changes before a new Find`, async ({ page }) => {
    let catalog = ["2025-01-01T12:00:00Z", "2027-01-01T12:00:00Z"]
      .map((timeUtc, i) => ({ ...event, id: String(i), timeUtc }));
    const queries = await serveNavigationCatalog(page, () => catalog);
    await page.goto("/");
    const date = page.getByLabel("Jump to date (UTC)");
    await date.fill("2026-01-01T00:00"); await date.press("Tab");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(date).toHaveValue("2027-01-01T12:00");
    await page.getByRole("button", { name: /Prev/ }).click();
    await expect(date).toHaveValue("2025-01-01T12:00");
    if (reset === "date") { await date.fill("2030-01-01T00:00"); await date.press("Tab"); }
    if (reset === "type") await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
    if (reset === "bodyA") await page.getByRole("combobox", { name: "Body A", exact: true }).selectOption("venus");
    if (reset === "bodyB") await page.getByRole("combobox", { name: "Body B", exact: true }).selectOption("jupiter");
    catalog = ["2023-01-01T12:00:00Z", "2031-01-01T12:00:00Z"].map((timeUtc, i) => ({
      ...event, id: "reset-" + i, timeUtc, type: reset === "type" ? "opposition" : "closestApproach",
      bodyA: reset === "bodyA" ? "venus" : "earth", bodyB: reset === "bodyB" ? "jupiter" : "mars",
    }));
    const count = queries.length;
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(date).toHaveValue("2031-01-01T12:00");
    expect(queries).toHaveLength(count + 1);
    await page.getByRole("button", { name: /Prev/ }).click();
    await expect(date).toHaveValue("2023-01-01T12:00");
    expect(queries).toHaveLength(count + 2);
  });
}

test("failed searches leave event undo history intact and restore the original status", async ({ page }) => {
  const catalog = ["2025-01-01T12:00:00Z", "2027-01-01T12:00:00Z"]
    .map((timeUtc, i) => ({ ...event, id: String(i), timeUtc, type: "opposition" }));
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-01-01T00:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2027-01-01T12:00");
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2025-01-01T12:00");
  let failed = 0;
  await page.route("**/api/events?**", route => {
    failed++;
    return route.fulfill({ status: 503, json: { message: "History test failure" } });
  });
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(page.locator(".status-text")).toHaveText("History test failure");
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2027-01-01T12:00");
  await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
  expect(failed).toBe(1);
  expect(queries).toHaveLength(2);
});

test("an empty edge search does not consume the previous or next event snapshot", async ({ page }) => {
  const catalog = ["2997-01-01T12:00:00Z", "2999-12-31T12:00:00Z"]
    .map((timeUtc, i) => ({ ...event, id: String(i), timeUtc, type: "opposition" }));
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2996-01-01T00:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2997-01-01T12:00");
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2999-12-31T12:00");
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(page.locator(".status-text")).toContainText("No matching events found");
  const count = queries.length;
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2997-01-01T12:00");
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2999-12-31T12:00");
  await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
  expect(queries).toHaveLength(count);
});

test("a cancelled search cannot seed history for the replacement criteria", async ({ page }) => {
  const catalog = ["2023-01-01T12:00:00Z", "2031-01-01T12:00:00Z"]
    .map((timeUtc, i) => ({ ...event, id: String(i), timeUtc, bodyB: "jupiter" }));
  const queries = await serveNavigationCatalog(page, () => catalog);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let first = true, released = false;
  await page.route("**/api/events?**", async route => {
    if (!first) return route.fallback();
    first = false;
    await gate;
    await route.fulfill({ json: [event] });
    released = true;
  });
  try {
    await page.goto("/");
    const date = page.getByLabel("Jump to date (UTC)");
    await date.fill("2026-01-01T00:00"); await date.press("Tab");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect.poll(() => first).toBe(false);
    await page.getByRole("combobox", { name: "Body B", exact: true }).selectOption("jupiter");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(date).toHaveValue("2031-01-01T12:00");
    release();
    await expect.poll(() => released).toBe(true);
    await expect(date).toHaveValue("2031-01-01T12:00");
    await page.getByRole("button", { name: /Prev/ }).click();
    await expect(date).toHaveValue("2023-01-01T12:00");
    expect(queries).toHaveLength(2);
  } finally { release(); }
});

for (const previous of [false, true]) {
  test(`same-task ${previous ? "Prev" : "Next"} replay then search uses the replayed anchor and ID`, async ({ page }) => {
    // No scene is needed to exercise real App clicks, history and HTTP query construction.
    await page.route("**/assets/SolarSystemScene-*.js", route => route.abort());
    const first = { ...event, id: "first", type: "opposition", timeUtc: "2027-01-01T12:00:00Z" };
    const second = { ...first, id: "second", timeUtc: "2029-01-01T12:00:00Z" };
    let catalog = [first, second];
    const queries = await serveNavigationCatalog(page, () => catalog);
    await page.goto("/");
    const date = page.getByLabel("Jump to date (UTC)");
    await date.fill("2026-01-01T00:00"); await date.press("Tab");
    await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(date).toHaveValue("2027-01-01T12:00");
    await page.getByRole("button", { name: /Next/ }).click();
    await expect(date).toHaveValue("2029-01-01T12:00");
    if (!previous) {
      await page.getByRole("button", { name: /Prev/ }).click();
      await expect(date).toHaveValue("2027-01-01T12:00");
    }
    const replayed = previous ? first : second;
    const adjacent = { ...first, id: "adjacent", timeUtc: previous ? "2025-01-01T12:00:00Z" : "2031-01-01T12:00:00Z" };
    catalog = [{ ...replayed, timeUtc: new Date(Date.parse(replayed.timeUtc) + (previous ? -31 : 31) * 86_400_000).toISOString() }, adjacent];
    const count = queries.length;
    await page.evaluate(label => {
      const button = [...document.querySelectorAll("button")].find(b => b.textContent?.includes(label))!;
      // Both clicks use the same render's handlers; the first synchronously changes history.
      button.click(); button.click();
    }, previous ? "Prev" : "Next");
    await expect(date).toHaveValue(adjacent.timeUtc.slice(0, 16));
    expect(Date.parse(queries[count].searchParams.get(previous ? "to" : "from")!))
      .toBe(Date.parse(replayed.timeUtc) + (previous ? -1 : 1) * (86_400_000 + 1));
    expect(queries.slice(count).some(query => query.searchParams.get("cursor") === "1")).toBe(true);
  });
}
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
  // This tests App's loader/notice, not SwiftShader shader compilation.
  await page.route("**/assets/SolarSystemScene-*.js", route => route.abort());
  if (process.env.E2E_CPU_THROTTLE) {
    const session = await page.context().newCDPSession(page);
    await session.send("Emulation.setCPUThrottlingRate", { rate: Number(process.env.E2E_CPU_THROTTLE) });
  }
  let requests = 0;
  await page.route("**/api/ephemeris/trajectory?body=halley", async route => {
    requests++;
    if (requests === 1) return route.fulfill({ status: 429, headers: { "Retry-After": "1" }, json: { message: "busy" } });
    // Deliberately model a slow real download: a 5s assertion must fail this scenario.
    await new Promise(resolve => setTimeout(resolve, 6000));
    return route.continue();
  });
  await page.goto("/");
  await page.getByLabel("Jump to date (UTC)").fill("1700-01-01T00:00");
  await page.getByRole("checkbox", { name: "Halley", exact: true }).check();
  await expect(page.getByText(/Halley: repeated-orbit approximation/)).toBeVisible({ timeout: 30_000 });
  expect(requests).toBe(2);
  await expect(page.locator("canvas")).toHaveCount(0);
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


test("share links reopen paused with exact UTC state and restore browser history", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByLabel("Jump to date (UTC)").fill("2027-02-19T15:45");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  const first = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  await page.getByLabel("Jump to date (UTC)").fill("2028-05-01T12:30");
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await page.goBack();
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("2027-02-19T15:45");
  await page.goto(first);
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Event type", exact: true })).toHaveValue("opposition");
  await page.getByRole("button", { name: "Explain this date", exact: true }).click();
  await expect(page.getByRole("img", { name: /Earth-view sky track/ })).toBeVisible();
});

test("real packaged guides, robots, and sitemap are readable without JavaScript", async ({ browser, request, baseURL }) => {
  const robots = await request.get("/robots.txt");
  expect(robots.ok()).toBeTruthy();
  expect(await robots.text()).toContain("Sitemap: https://solar.andreyg.com/sitemap.xml");
  const sitemap = await request.get("/sitemap.xml");
  expect(sitemap.ok()).toBeTruthy();
  for (const name of ["retrograde", "opposition", "elongation"]) {
    expect(await sitemap.text()).toContain("/learn/" + name + ".html");
    const guide = await request.get("/learn/" + name + ".html");
    expect(guide.ok()).toBeTruthy();
    const html = await guide.text();
    expect(html).toContain('rel="canonical"');
    expect(html).toContain('property="og:description"');
    expect(html).not.toContain("<script");
  }
  const context = await browser.newContext({ baseURL, javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto("/learn/retrograde.html");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("move backward");
  await expect(page.getByRole("link", { name: "Open the example in 3D" })).toBeVisible();
  await context.close();
});

test("mobile shared view shows explanation and reduced-motion starts paused", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Explain this date", exact: true }).click();
  await expect(page.getByRole("img", { name: /Earth-view sky track/ })).toBeVisible();
  await page.getByRole("button", { name: "Close explanation", exact: true }).click();
  await expect(page.getByRole("img", { name: /Earth-view sky track/ })).toHaveCount(0);
});

test("Back after the first Share preserves the bare-page draft, result and hash-only navigation", async ({ page }) => {
  await page.route("**/api/events?**", route => route.fulfill({ json: [
    { ...event, type: "opposition", timeUtc: "1700-01-02T12:00:00Z", computedTimeUtc: "1700-01-02T12:00:00Z" },
  ] }));
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByLabel("Jump to date (UTC)").fill("1700-01-01T00:00");
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await page.goBack();
  expect(new URL(page.url()).search).toBe("");
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("1700-01-02T12:00");
  await expect(page.getByRole("checkbox", { name: "Mercury", exact: true })).not.toBeChecked();
  await expect(page.getByRole("combobox", { name: "Event type", exact: true })).toHaveValue("opposition");
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await expect(page.getByText(/Provisional.*queued/i)).toBeVisible();
  await page.goForward();
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("1700-01-02T12:00");
  await page.evaluate(() => { window.location.hash = "x"; });
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
});

test("edits clear stale share links and invalidate the address bar only once", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByLabel("Jump to date (UTC)").fill("2027-02-19T15:45");
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  const original = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  await page.evaluate(() => {
    const replace = history.replaceState.bind(history);
    Object.assign(window, { replacements: 0 });
    history.replaceState = (...args) => { (window as unknown as { replacements: number }).replacements++; replace(...args); };
  });
  await page.getByLabel("Jump to date (UTC)").fill("2030-06-01T12:00");
  await expect(page.getByLabel("Share link (date, bodies, and focus)")).toHaveCount(0);
  expect(new URL(page.url()).search).toBe("");
  await page.getByRole("checkbox", { name: "Jupiter", exact: true }).uncheck();
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await page.evaluate(() => new Promise<void>(resolve => {
    let frames = 0;
    const tick = () => { if (++frames === 125) resolve(); else requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  }));
  expect(await page.evaluate(() => (window as unknown as { replacements: number }).replacements)).toBe(1);
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  const updated = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  expect(updated).not.toBe(original);
  expect(new URL(updated).searchParams.get("bodies")).not.toContain("jupiter");
  await page.goto(original);
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await expect.poll(() => new URL(page.url()).search).toBe("");
});

test("share pages keep canonical SEO without an incorrect home-page Open Graph URL", async ({ page, request }) => {
  const html = await (await request.get("/?v=1&time=2027-02-19T15:45:00Z")).text();
  expect(html).toContain('rel="canonical"');
  expect(html).not.toContain('property="og:url"');
  await page.goto("/?v=1&time=2027-02-19T15:45:00Z");
  await page.getByRole("button", { name: "Explain this date", exact: true }).click();
  await expect(page.getByText("← increasing RA (east)", { exact: true })).toBeVisible();
});
test("Back restores an edited share's URL, and reload ignores a mismatched history snapshot", async ({ page }) => {
  const date = page.getByLabel("Jump to date (UTC)");
  const share = page.getByRole("button", { name: "Share view", exact: true });
  await page.goto("/?v=1&time=2027-01-01T00:00:00Z");
  await share.click();
  const first = page.url();
  await date.fill("2028-01-01T00:00");
  await share.click();
  await page.goBack();
  await expect(date).toHaveValue("2027-01-01T00:00");
  await expect(page).toHaveURL(first);
  // Share reads the live frame clock, not the already-correct React date field.
  await share.click();
  expect(new URL(await page.getByLabel("Share link (date, bodies, and focus)").inputValue()).searchParams.get("time"))
    .toBe("2027-01-01T00:00:00.000Z");
  await page.reload();
  await expect(date).toHaveValue("2027-01-01T00:00");

  // A stripped shared entry survives reload in history.state, but must not own the new draft.
  await date.fill("2028-01-01T00:00");
  await expect.poll(() => new URL(page.url()).search).toBe("");
  await page.reload();
  await date.fill("1700-01-01T00:00");
  await share.click();
  await page.goBack();
  await expect(date).toHaveValue("1700-01-01T00:00");
  expect(new URL(page.url()).search).toBe("");
});

test("shared views and Share work when crypto.randomUUID is unavailable", async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(crypto, "randomUUID", { value: undefined }));
  await page.goto("/?v=1&time=1700-01-01T00:00:00Z");
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("1700-01-01T00:00");
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await expect(page.getByLabel("Share link (date, bodies, and focus)")).toBeVisible();
});

test("Share immediately after a queued playback tick keeps one exact paused timestamp", async ({ page }) => {
  await page.addInitScript(() => {
    let next = 0;
    const queued = new Map<number, FrameRequestCallback>();
    window.requestAnimationFrame = callback => { queued.set(++next, callback); return next; };
    window.cancelAnimationFrame = id => { queued.delete(id); };
    Object.assign(window, { stepFrame: (now: number) => {
      const callbacks = [...queued.values()]; queued.clear();
      callbacks.forEach(callback => callback(now));
    } });
  });
  await page.goto("/?v=1&time=2027-01-01T00:00:00Z");
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await page.evaluate(() => {
    const step = (window as unknown as { stepFrame: (now: number) => void }).stepFrame;
    step(1000); step(1110); // publish a tick without letting React render it yet
    [...document.querySelectorAll("button")].find(b => b.textContent === "Share view")!.click();
    step(1210); // callback already queued before Share must now be inert
  });
  const expected = "2027-01-02T02:24:00.000Z"; // 110 ms at 10 simulated days/sec
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("2027-01-02T02:24");
  const link = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  expect(new URL(link).searchParams.get("time")).toBe(expected);
  await expect(page).toHaveURL(link);
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
});

test("sharing an active search never restores a phantom Searching status", async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/events?**", async route => { await pending; await route.fulfill({ json: [event] }); });
  try {
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByLabel("Jump to date (UTC)").fill("2026-01-01T00:00");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByText("Searching for next event...", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await page.goBack();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeEnabled();
  await expect(page.getByText("Searching for next event...", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Search cancelled", { exact: true })).toHaveCount(0);
  await page.goForward();
  await expect(page.getByText("Searching for next event...", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Search cancelled", { exact: true })).toHaveCount(0);
  } finally { release(); }
});

test("Forward restores the live clock as well as the date field", async ({ page }) => {
  const date = page.getByLabel("Jump to date (UTC)");
  const share = page.getByRole("button", { name: "Share view", exact: true });
  await page.goto("/?v=1&time=2027-01-01T00:00:00Z");
  await share.click();
  await date.fill("2028-01-01T00:00");
  await share.click();
  await page.goBack();
  await expect(date).toHaveValue("2027-01-01T00:00");
  await date.fill("2040-01-01T00:00");
  await page.getByRole("heading", { name: "Time", exact: true }).click();
  await expect.poll(() => new URL(page.url()).search).toBe("");
  await page.goForward();
  await expect(date).toHaveValue("2028-01-01T00:00");
  await share.click();
  const link = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  expect(new URL(link).searchParams.get("time")).toBe("2028-01-01T00:00:00.000Z");
});

test("Share cancels a pending Find immediately and late results cannot strip the copied link", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", { value: {
      writeText: async (text: string) => { Object.assign(window, { copiedShare: text }); },
    } });
  });
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let delivered = false;
  await page.route("**/api/events?**", async route => {
    await pending;
    await route.fulfill({ json: [event] });
    delivered = true;
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.getByLabel("Jump to date (UTC)").fill("2026-01-01T00:00");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByText("Searching for next event...", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeEnabled();
  await expect(page.getByText("Searching for next event...", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Search cancelled", { exact: true })).toBeVisible();
  const link = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  expect(await page.evaluate(() => (window as unknown as { copiedShare: string }).copiedShare)).toBe(link);
  release();
  await expect.poll(() => delivered).toBe(true);
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page).toHaveURL(link);
  await expect(page.getByLabel("Share link (date, bodies, and focus)")).toHaveValue(link);
  await expect(page.getByLabel("Jump to date (UTC)")).toHaveValue("2026-01-01T00:00");
  await expect(page.getByRole("button", { name: /Next/ })).toHaveCount(0);
  await expect(page.getByText("Search cancelled", { exact: true })).toBeVisible();
});

test("defence-in-depth pause holds under synthetic popstate frame ordering", async ({ page }) => {
  await page.addInitScript(() => {
    let next = 0;
    const queued = new Map<number, FrameRequestCallback>();
    window.requestAnimationFrame = callback => { queued.set(++next, callback); return next; };
    window.cancelAnimationFrame = id => { queued.delete(id); };
    const stepFrame = (now: number) => {
      const callbacks = [...queued.values()]; queued.clear();
      callbacks.forEach(callback => callback(now));
    };
    Object.assign(window, { stepFrame, stepAfterRestore: false });
    const add = window.addEventListener.bind(window);
    const remove = window.removeEventListener.bind(window);
    const wrapped = new Map<EventListenerOrEventListenerObject, EventListener>();
    window.addEventListener = (type, listener, options) => {
      if (type !== "popstate" || !listener) return add(type, listener, options);
      const restore: EventListener = event => {
        if (typeof listener === "function") listener.call(window, event);
        else listener.handleEvent(event);
        // Synthetic ordering, not a real browser race: run frames inside the listener.
        // Real popstate navigation flushes React before the next frame.
        if ((window as unknown as { stepAfterRestore: boolean }).stepAfterRestore) {
          stepFrame(1100); stepFrame(1200);
        }
      };
      wrapped.set(listener, restore);
      add(type, restore, options);
    };
    window.removeEventListener = (type, listener, options) =>
      remove(type, listener && (wrapped.get(listener) ?? listener), options);
  });
  const date = page.getByLabel("Jump to date (UTC)");
  const share = page.getByRole("button", { name: "Share view", exact: true });
  await page.goto("/?v=1&time=2027-01-01T00:00:00Z");
  await share.click();
  await date.fill("2030-01-01T00:00");
  await share.click();
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await page.evaluate(() => {
    const step = (window as unknown as { stepFrame: (now: number) => void }).stepFrame;
    step(1000); step(1050);
    Object.assign(window, { stepAfterRestore: true });
  });
  await page.goBack();
  await expect(date).toHaveValue("2027-01-01T00:00");
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
  await share.click();
  const link = await page.getByLabel("Share link (date, bodies, and focus)").inputValue();
  expect(new URL(link).searchParams.get("time")).toBe("2027-01-01T00:00:00.000Z");
});
test("a later Share never saves or restores the transient Search cancelled notice", async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/events?**", async route => { await pending; await route.fulfill({ json: [event] }); });
  try {
    await page.goto("/");
    await page.getByRole("button", { name: "Pause", exact: true }).click();
    await page.getByLabel("Jump to date (UTC)").fill("2026-01-01T00:00");
    await page.getByRole("button", { name: "Find", exact: true }).click();
    await expect(page.getByText("Searching for next event...", { exact: true })).toBeVisible();
    const share = page.getByRole("button", { name: "Share view", exact: true });
    const cancelled = page.getByText("Search cancelled", { exact: true });
    await share.click();
    await expect(cancelled).toBeVisible();
    await share.click(); // Re-copy with no new search; this used to persist the notice.
    await expect(cancelled).toBeVisible();
    expect(await page.evaluate(() => window.history.state.eventStatus)).toBeNull();
    await page.goBack();
    await expect(cancelled).toHaveCount(0);
    await page.goForward();
    await expect(cancelled).toHaveCount(0);
  } finally { release(); }
});

for (const completed of [false, true]) {
  test(`Share with no pending search preserves status ${completed ? "after a completed Find" : "on a fresh page"}`, async ({ page }) => {
    await page.route("**/api/events?**", route => route.fulfill({ json: [event] }));
    await page.goto("/");
    await page.getByRole("button", { name: "Pause", exact: true }).click();
    if (completed) {
      await page.getByLabel("Jump to date (UTC)").fill("2026-01-01T00:00");
      await page.getByRole("button", { name: "Find", exact: true }).click();
      await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
      await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
    } else await expect(page.locator(".status-text")).toHaveCount(0);
    const before = await page.locator(".status-text").allTextContents();
    await page.getByRole("button", { name: "Share view", exact: true }).click();
    await expect(page.getByLabel("Share link (date, bodies, and focus)")).toBeVisible();
    expect(await page.locator(".status-text").allTextContents()).toEqual(before);
    await expect(page.getByText("Search cancelled", { exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => window.history.state.eventStatus)).toBe(before[0] ?? null);
    if (completed) {
      // Distinct real statuses prove restoration, not just persistence of the visible text.
      await page.route("**/api/events?**", route => route.fulfill({
        status: 503, json: { message: "Second search unavailable" },
      }));
      await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
      await page.getByRole("button", { name: "Find", exact: true }).click();
      await expect(page.locator(".status-text")).toHaveText("Second search unavailable");
      await page.getByRole("button", { name: "Share view", exact: true }).click();
      expect(await page.evaluate(() => window.history.state.eventStatus)).toBe("Second search unavailable");
      await page.goBack();
      await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
      expect(await page.evaluate(() => window.history.state.eventStatus)).toBe("Loaded provisional event.");
      await expect(page.getByText("Search cancelled", { exact: true })).toHaveCount(0);
      await page.goBack(); // Reach the original bare-page draft, not just the first share.
      expect(new URL(page.url()).search).toBe("");
      await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
      expect(await page.evaluate(() => window.history.state.eventStatus)).toBe("Loaded provisional event.");
      expect(await page.evaluate(() => window.history.state.kind)).toBe("unshared");
      await page.goForward();
      await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
      await page.goForward();
      await expect(page.locator(".status-text")).toHaveText("Second search unavailable");
      expect(await page.evaluate(() => window.history.state.eventStatus)).toBe("Second search unavailable");
      await expect(page.getByText("Search cancelled", { exact: true })).toHaveCount(0);
    }
  });
}

test("browser Back/Forward resets event navigation and seeds the restored snapshot", async ({ page }) => {
  let catalog = [
    { ...event, id: "first", timeUtc: "2027-02-20T12:00:00Z" },
    { ...event, id: "previous", timeUtc: "2025-01-16T02:37:00Z", validationStatus: "validated" },
  ];
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  const share = page.getByRole("button", { name: "Share view", exact: true });
  await date.fill("2026-01-01T00:00"); await date.press("Tab");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2027-02-20T12:00");
  const firstCard = await page.locator(".event-card").textContent();
  await share.click();
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2025-01-16T02:37");
  const previousCard = await page.locator(".event-card").textContent();
  await share.click();
  // Remove both snapshots from the server; only the restored view may seed undo.
  catalog = [{ ...event, id: "following", timeUtc: "2031-01-16T02:37:00Z" }];
  for (const view of [
    { direction: "back", date: "2027-02-20T12:00", card: firstCard, status: "Loaded provisional event." },
    { direction: "forward", date: "2025-01-16T02:37", card: previousCard, status: "Loaded event." },
  ]) {
    if (view.direction === "back") await page.goBack();
    else await page.goForward();
    await expect(date).toHaveValue(view.date);
    const count = queries.length;
    await page.getByRole("button", { name: /Next/ }).click();
    await expect(date).toHaveValue("2031-01-16T02:37");
    expect(queries).toHaveLength(count + 1);
    await page.getByRole("button", { name: /Prev/ }).click();
    await expect(date).toHaveValue(view.date);
    await expect(page.locator(".event-card")).toHaveText(view.card!);
    await expect(page.locator(".status-text")).toHaveText(view.status);
    expect(queries).toHaveLength(count + 1);
  }
});

async function prepareHistoryEdges(page: import("@playwright/test").Page) {
  await page.route("**/assets/SolarSystemScene-*.js", route => route.abort());
  const catalog = ["2027-01-01T12:00:00Z", "2029-01-01T12:00:00Z", "2031-01-01T12:00:00Z"]
    .map((timeUtc, index) => ({ ...event, id: "history-" + index, type: "opposition", timeUtc, computedTimeUtc: timeUtc }));
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2026-01-01T00:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("opposition");
  return { date, queries, catalog, find: page.getByRole("button", { name: "Find", exact: true }),
    next: page.getByRole("button", { name: /Next/ }), prev: page.getByRole("button", { name: /Prev/ }),
    share: page.getByRole("button", { name: "Share view", exact: true }) };
}

test("Back to an entry without an event clears stale replay history and Find uses its date", async ({ page }) => {
  const h = await prepareHistoryEdges(page);
  await h.share.click(); // first share has no event
  await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
  await h.prev.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await h.share.click();
  await page.goBack();
  await expect(h.date).toHaveValue("2026-01-01T00:00");
  await expect(page.locator(".event-card")).toHaveCount(0);
  const count = h.queries.length;
  await h.find.click();
  await expect(h.date).toHaveValue("2027-01-01T12:00");
  expect(h.queries).toHaveLength(count + 1);
  expect(h.queries.at(-1)!.searchParams.get("from")).toBe("2026-01-01T00:00:00.001Z");
});

test("hash-only navigation preserves Prev replay without another request", async ({ page }) => {
  const h = await prepareHistoryEdges(page);
  await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
  const count = h.queries.length;
  await page.evaluate(() => new Promise<void>(resolve => {
    window.addEventListener("popstate", () => resolve(), { once: true });
    window.location.hash = "history-edge";
  }));
  await expect(h.date).toHaveValue("2029-01-01T12:00");
  await expect(h.prev).toBeVisible();
  await h.prev.click();
  await expect(h.date).toHaveValue("2027-01-01T12:00");
  expect(h.queries).toHaveLength(count);
});

test("Back to the draft seeds its event and replays the exact saved 503 status", async ({ page }) => {
  const h = await prepareHistoryEdges(page);
  await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  let unavailable = true;
  await page.route("**/api/events?**", route => unavailable
    ? route.fulfill({ status: 503, json: { message: "Saved service unavailable" } }) : route.fallback());
  await h.next.click();
  await expect(page.getByText("Saved service unavailable", { exact: true })).toBeVisible();
  await h.share.click();
  await h.date.fill("2040-01-01T00:00"); await h.date.press("Tab");
  await h.share.click();
  await page.goBack(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await page.goBack();
  expect(new URL(page.url()).search).toBe("");
  expect(await page.evaluate(() => history.state.kind)).toBe("unshared");
  await expect(page.getByText("Saved service unavailable", { exact: true })).toBeVisible();
  unavailable = false;
  const count = h.queries.length;
  await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
  expect(h.queries).toHaveLength(count + 1);
  await h.prev.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await expect(page.getByText("Saved service unavailable", { exact: true })).toBeVisible();
  expect(h.queries).toHaveLength(count + 1);
});

test("a search pending at Back cannot apply or enter the restored event history", async ({ page }) => {
  const h = await prepareHistoryEdges(page);
  await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await h.share.click();
  await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
  await h.share.click();
  let release!: () => void, pending = true, delivered = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/events?**", async route => {
    if (!pending) return route.fallback();
    pending = false;
    await gate;
    await route.fulfill({ json: [h.catalog[2]] });
    delivered = true;
  });
  try {
    await h.next.click();
    await expect(page.getByText("Searching for next event...", { exact: true })).toBeVisible();
    await page.goBack();
    await expect(h.date).toHaveValue("2027-01-01T12:00");
    release(); await expect.poll(() => delivered).toBe(true);
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(h.date).toHaveValue("2027-01-01T12:00");
    const count = h.queries.length;
    await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
    expect(h.queries).toHaveLength(count + 1);
    await h.prev.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
    expect(h.queries).toHaveLength(count + 1);
  } finally { release(); }
});

for (const navigation of ["reload", "cross-document Back"] as const) {
  test(`${navigation} restores a matching shared event, status and replay seed`, async ({ page }) => {
    const h = await prepareHistoryEdges(page);
    await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
    await h.next.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
    let unavailable = true;
    await page.route("**/api/events?**", route => unavailable
      ? route.fulfill({ status: 503, json: { message: "Saved reload failure" } }) : route.fallback());
    await h.next.click();
    await expect(page.getByText("Saved reload failure", { exact: true })).toBeVisible();
    const card = await page.locator(".event-card").textContent();
    await h.share.click();
    const shared = page.url();
    if (navigation === "reload") await page.reload();
    else {
      await page.getByRole("link", { name: "Why do planets appear to move backward?" }).click();
      await expect(page).toHaveURL(/\/learn\/retrograde\.html$/);
      await page.goBack();
    }
    await expect(page).toHaveURL(shared);
    await expect(h.date).toHaveValue("2029-01-01T12:00");
    await expect(page.locator(".event-card")).toHaveText(card!);
    await expect(page.getByText("Saved reload failure", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
    expect(await page.evaluate(() => history.state.eventStatus)).toBe("Saved reload failure");
    unavailable = false;
    const count = h.queries.length;
    await h.next.click(); await expect(h.date).toHaveValue("2031-01-01T12:00");
    expect(h.queries).toHaveLength(count + 1);
    await h.prev.click(); await expect(h.date).toHaveValue("2029-01-01T12:00");
    await expect(page.getByText("Saved reload failure", { exact: true })).toBeVisible();
    expect(h.queries).toHaveLength(count + 1);
  });
}

test("reload of an edited bare URL ignores its mismatched saved event", async ({ page }) => {
  const h = await prepareHistoryEdges(page);
  await h.find.click(); await expect(h.date).toHaveValue("2027-01-01T12:00");
  await h.share.click();
  await h.date.fill("2040-01-01T00:00"); await h.date.press("Tab");
  await expect.poll(() => new URL(page.url()).search).toBe("");
  expect(await page.evaluate(() => history.state.eventResult.id)).toBe("history-0");
  await page.reload();
  await expect(h.find).toBeVisible();
  await expect(page.locator(".event-card")).toHaveCount(0);
  await expect(page.getByText("Loaded provisional event.", { exact: true })).toHaveCount(0);
});
