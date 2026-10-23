import { expect, it } from "vitest";
import { simplifyTrail } from "./smallBodyTrajectory";
it("bounds rendering geometry without mutating full-resolution interpolation data", () => {
  const points = Array.from({length: 14000}, (_, i) => ({x: Math.cos(i/13999*Math.PI*2), y: Math.sin(i/13999*Math.PI*2), z: 0}));
  const simplified = simplifyTrail(points);
  expect(simplified.length).toBeLessThan(100);
  expect(simplified[0]).toBe(points[0]);
  expect(simplified.at(-1)).toBe(points.at(-1));
  expect(points).toHaveLength(14000);
  for (let i=1; i<simplified.length; i++) {
    const a=simplified[i-1], b=simplified[i];
    expect(1-Math.hypot((a.x+b.x)/2,(a.y+b.y)/2)).toBeLessThanOrEqual(0.005);
  }
});
