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
  const queries = await serveNavigationCatalog(page, () => catalog);
  await page.goto("/");
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  const date = page.getByLabel("Jump to date (UTC)");
  await date.fill("2016-12-05T12:00"); await date.press("Tab");
  await page.getByRole("combobox", { name: "Event type", exact: true }).selectOption("stationary");
  await page.getByRole("combobox", { name: "Target", exact: true }).selectOption("vesta");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(date).toHaveValue("2016-12-06T11:59");
  const originalCard = await page.locator(".event-card").textContent();
  await page.getByRole("button", { name: /Prev/ }).click();
  await expect(date).toHaveValue("2016-11-30T20:02");
  expect(Date.parse(queries.at(-1)!.searchParams.get("to")!)).toBe(Date.parse(catalog[2].timeUtc) - 86_400_000 - 1);
  await expect(page.locator(".status-text")).toHaveText("Loaded event.");
  const count = queries.length;
  // Later catalog validation must not silently replace a snapshot during undo.
  catalog[2] = { ...catalog[2], timeUtc: "2016-12-05T11:59:30Z", validationStatus: "validated" };
  await page.getByRole("button", { name: /Next/ }).click();
  await expect(date).toHaveValue("2016-12-06T11:59");
  await expect(page.locator(".event-card")).toHaveText(originalCard!);
  await expect(page.locator(".status-text")).toHaveText("Loaded provisional event.");
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
