import { expect, test } from "@playwright/test";

test("packaged application serves the real frontend and favicon", async ({ page, request }) => {
  await page.route("**/api/**", route => route.fulfill({ json: [] }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeVisible();
  const favicon = await request.get("/favicon-32.png");
  expect(favicon.status()).toBe(200);
  expect(favicon.headers()["content-type"]).toContain("image/png");
});

test("packaged backend rejects invalid bodies even for browser Accept headers", async ({ request }) => {
  const response = await request.get("/api/ephemeris/trajectory?body=mars", { headers: { Accept: "text/html" } });
  expect(response.status()).toBe(400);
  expect(response.headers()["content-type"]).toContain("application/json");
});
