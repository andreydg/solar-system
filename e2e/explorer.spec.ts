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
  await date.press("Tab");
  await expect(date).toHaveValue("2030-07-04T12:30");
  await expect(page.getByRole("button", { name: "Play", exact: true })).toBeVisible();
});

test("catalog requests work without newer AbortSignal static APIs", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(AbortSignal, "any", { value: undefined });
    Object.defineProperty(AbortSignal, "timeout", { value: undefined });
  });
  await page.route("**/api/events?**", route => route.fulfill({ json: [event] }));
  await page.goto("/");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.getByRole("button", { name: /Next/ })).toBeVisible();
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
