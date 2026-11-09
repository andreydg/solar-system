import { expect, test } from "@playwright/test";

const pausedView = "/?v=1&time=2027-02-19T15%3A45%3A00Z&event=closestApproach&a=earth&b=mars";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/**", route => route.fulfill({
    status: 503, contentType: "application/json", body: '{"message":"Offline test"}',
  }));
});

for (const [width, height] of [[1915, 873], [1280, 800]] as const) {
  test(`compact right panel keeps Find fully visible without scrolling at ${width}x${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await page.goto(pausedView);
    await page.evaluate(() => document.fonts.ready);
    const panel = page.getByRole("complementary", { name: "Simulation controls" });
    const heading = panel.getByRole("heading", { name: "Solar System Explorer", exact: true });
    await expect(heading).toBeVisible();
    await expect(panel.getByText("An atlas in motion")).toHaveCount(0);
    const layout = await page.evaluate(() => {
      const panel = document.querySelector(".control-panel")!;
      const header = panel.querySelector(".panel-header")!;
      const icon = header.querySelector("img")!;
      const heading = header.querySelector("h1")!;
      return {
        panel: panel.getBoundingClientRect().toJSON(),
        scene: document.querySelector(".hero-panel")!.getBoundingClientRect().toJSON(),
        header: header.getBoundingClientRect().toJSON(),
        icon: icon.getBoundingClientRect().toJSON(),
        heading: heading.getBoundingClientRect().toJSON(),
        iconLoaded: icon.complete && icon.naturalWidth > 0,
        scrollTop: panel.scrollTop,
        bodyColumns: getComputedStyle(panel.querySelector(".body-grid")!).gridTemplateColumns.split(" ").length,
      };
    });
    expect(layout.panel.right).toBe(width);
    expect(layout.panel.width).toBe(400);
    expect(layout.scene.right).toBe(layout.panel.left);
    expect(layout.header.height).toBeLessThanOrEqual(40);
    expect(layout.iconLoaded).toBe(true);
    expect(layout.icon.right).toBeLessThanOrEqual(layout.heading.left);
    expect(Math.abs(layout.icon.top - layout.heading.top)).toBeLessThan(4);
    expect(layout.bodyColumns).toBe(3);
    expect(layout.scrollTop).toBe(0);
    const find = panel.getByRole("button", { name: "Find", exact: true });
    // No scrollIntoView/click before this assertion: that would hide the regression.
    await expect(find).toBeInViewport({ ratio: 1 });
    const bounds = await find.boundingBox();
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(height - 16);
    expect(bounds!.height).toBeGreaterThanOrEqual(44);
    await expect(find).toHaveCSS("background-color", "rgb(52, 127, 76)");
    await expect(find).toHaveCSS("color", "rgb(255, 255, 255)");
    await expect(panel).toHaveCSS("color-scheme", "light");
    await expect(panel).toHaveCSS("background-color", "rgb(244, 244, 242)");
    await expect(page.locator(".hero-panel")).toHaveCSS("color-scheme", "dark");
    await expect(page.locator(".hero-panel")).toHaveCSS("color", "rgb(232, 232, 232)");
  });
}

test("production jar serves and loads both self-hosted fonts and their license", async ({ page, request }) => {
  for (const name of ["dm-sans-latin-v17", "dm-serif-display-latin-v17"]) {
    const response = await request.get(`/fonts/${name}.woff2`);
    expect(response.ok()).toBe(true);
    expect(response.headers()["content-type"]).not.toContain("text/html");
    expect((await response.body()).subarray(0, 4).toString()).toBe("wOF2");
  }
  const license = await request.get("/fonts/OFL.txt");
  expect(license.ok()).toBe(true);
  expect(await license.text()).toContain("SIL OPEN FONT LICENSE Version 1.1");
  const fontRequests: string[] = [];
  page.on("request", request => { if (request.resourceType() === "font") fontRequests.push(request.url()); });
  await page.goto(pausedView);
  const fonts = await page.evaluate(async () => {
    await document.fonts.ready;
    return Array.from(document.fonts, font => ({ family: font.family, status: font.status }));
  });
  expect(fonts).toEqual(expect.arrayContaining([
    { family: "DM Sans", status: "loaded" }, { family: "DM Serif Display", status: "loaded" },
  ]));
  expect(fontRequests).toHaveLength(2);
  for (const url of fontRequests) expect(new URL(url).origin).toBe(new URL(page.url()).origin);
});

test("font download failure leaves readable controls and Find in view", async ({ page }) => {
  await page.setViewportSize({ width: 1915, height: 873 });
  await page.route("**/fonts/*.woff2", route => route.abort());
  await page.goto(pausedView);
  await page.evaluate(() => document.fonts.ready);
  await expect(page.getByRole("heading", { name: "Solar System Explorer", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeInViewport({ ratio: 1 });
  await page.getByRole("checkbox", { name: "Mercury", exact: true }).uncheck();
  await expect(page.getByRole("checkbox", { name: "Mercury", exact: true })).not.toBeChecked();
});

for (const [width, fontSize] of [[320, 16], [320, 24], [360, 20], [1280, 24]] as const) {
  test(`panel heading and body labels fit at ${width}px with ${fontSize}px text`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(pausedView);
    await page.addStyleTag({ content: `html { font-size: ${fontSize}px !important; }` });
    await page.evaluate(() => document.fonts.ready);
    const layout = await page.evaluate(() => {
      const panel = document.querySelector(".control-panel")!;
      const box = panel.getBoundingClientRect();
      const scene = document.querySelector(".hero-panel")!.getBoundingClientRect();
      const elements = Array.from(panel.querySelectorAll(".body-toggle, h1"));
      return {
        panelTop: box.top, sceneBottom: scene.bottom,
        pageWidth: document.documentElement.scrollWidth,
        bodyColumns: getComputedStyle(panel.querySelector(".body-grid")!).gridTemplateColumns.split(" ").length,
        labels: elements.map(element => {
          const range = document.createRange();
          range.selectNodeContents(element);
          const text = range.getBoundingClientRect(), bounds = element.getBoundingClientRect();
          return { text: element.textContent, left: text.left, right: text.right,
            top: text.top, bottom: text.bottom, bounds: bounds.toJSON() };
        }),
      };
    });
    expect(layout.pageWidth).toBeLessThanOrEqual(width);
    expect(layout.bodyColumns).toBe(2);
    if (width <= 980) expect(layout.panelTop).toBeGreaterThanOrEqual(layout.sceneBottom);
    for (const label of layout.labels) {
      expect(label.left, label.text!).toBeGreaterThanOrEqual(label.bounds.left - 1);
      expect(label.right, label.text!).toBeLessThanOrEqual(label.bounds.right + 1);
      expect(label.top, label.text!).toBeGreaterThanOrEqual(label.bounds.top - 1);
      expect(label.bottom, label.text!).toBeLessThanOrEqual(label.bounds.bottom + 1);
    }
  });
}

test("light dialogs and explanation remain legible and interactive", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.route("**/api/events/validated?**", route => route.fulfill({ json: [] }));
  await page.goto(pausedView);
  await page.getByRole("button", { name: "Show event type help" }).click();
  const help = page.getByRole("dialog", { name: "Event types", exact: true });
  await expect(help).toHaveCSS("background-color", "rgb(255, 255, 255)");
  await expect(help.getByRole("heading")).toHaveCSS("color", "rgb(43, 38, 32)");
  await help.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("button", { name: "Validated events", exact: true }).click();
  const catalog = page.getByRole("dialog", { name: "Validated events", exact: true });
  await expect(catalog).toHaveCSS("background-color", "rgb(255, 255, 255)");
  await expect(catalog.getByRole("heading")).toHaveCSS("color", "rgb(43, 38, 32)");
  await catalog.getByRole("button", { name: "Query", exact: true }).click();
  await expect(catalog.getByText("No validated events match these criteria.")).toBeVisible();
  await catalog.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("button", { name: "About This Date", exact: true }).click();
  const explanation = page.getByRole("region", { name: "Event explanation", exact: true });
  await expect(explanation).toHaveCSS("background-color", "rgb(248, 247, 243)");
  await expect(explanation.locator("svg text").first()).toHaveCSS("fill", "rgb(107, 99, 89)");
  await expect(explanation.locator("svg polyline")).toHaveCSS("stroke", "rgb(138, 96, 40)");
  await page.getByRole("button", { name: "Close explanation", exact: true }).click();
  await expect(explanation).toHaveCount(0);
});
