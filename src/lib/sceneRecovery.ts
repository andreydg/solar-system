/** Check the current index before abandoning working controls for a full reload. */
export async function canReloadApp(): Promise<boolean> {
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 2_000);
  try {
    const response = await fetch("/", { cache: "no-store", signal: controller.signal });
    if (!response.ok || !response.headers.get("content-type")?.toLowerCase().includes("text/html")) return false;
    const html = await response.text();
    return (html.includes('id="root" data-app="solar-system-explorer"')
      || html.includes("<title>Solar System Explorer</title>"))
      && /<script\b[^>]*\btype=["']module["'][^>]*\bsrc=["']\/(?:assets\/|src\/main\.tsx)/.test(html);
  } catch { return false; }
  finally { clearTimeout(deadline); }
}
