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

test("real packaged guides, robots, and sitemap are readable without JavaScript", async ({ browser, request }) => {
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
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:18080/learn/retrograde.html");
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
  await page.goto("/?v=1&time=2026-01-01T00:00:00Z");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByText("Searching for next event...", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Share view", exact: true }).click();
  await page.goBack();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeEnabled();
  await expect(page.getByText("Searching for next event...", { exact: true })).toHaveCount(0);
  await page.goForward();
  await expect(page.getByText("Searching for next event...", { exact: true })).toHaveCount(0);
  release();
});
