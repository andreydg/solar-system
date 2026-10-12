import { expect, it } from "vitest";
import { readSharedView, shareUrl, type SharedView } from "./sharedView";
import { skyTrack } from "./skyTrack";

const view: SharedView = { time: new Date("2027-02-19T15:45:12.123Z"), visibleBodies: ["earth","mars"],
  eventType: "opposition", bodyA: "earth", bodyB: "mars", focus: "mars" };
it("round-trips exact UTC time, criteria, visibility and focus without status claims", () => {
  const url = new URL(shareUrl(view, "https://solar.andreyg.com"));
  expect(readSharedView(url.search)).toEqual(view);
  expect(url.search).not.toContain("validated");
  expect(url.pathname).toBe("/");
});
it("rejects malformed, obsolete, impossible and oversized shared views", () => {
  const url = new URL(shareUrl(view, "https://solar.andreyg.com"));
  for (const [key,value] of [["v","2"],["time","2027-02-30T15:45:00Z"],["time","3000-01-01T00:00:00Z"],
    ["b","earth"],["bodies","earth,unknown"],["focus","halley"],["event","<script>"]]) {
    const modified = new URL(url); modified.searchParams.set(key,value);
    expect(readSharedView(modified.search)).toBeNull();
  }
  expect(readSharedView("x".repeat(2049))).toBeNull();
});
it("plots a finite continuous Mars sky track that reverses around opposition", () => {
  const points = skyTrack("mars", view.time);
  expect(points).toHaveLength(61);
  const differences = points.slice(1).map((p,i) => p.ra-points[i].ra);
  expect(differences.some(d => d < 0)).toBe(true);
  expect(differences.some(d => d > 0)).toBe(true);
  expect(differences.every(d => Math.abs(d)<30)).toBe(true);
  expect(points.every(p => Number.isFinite(p.ra) && Number.isFinite(p.dec))).toBe(true);
  expect(skyTrack("halley", view.time)).toEqual([]);
  expect(skyTrack("earth", view.time)).toEqual([]);
});
