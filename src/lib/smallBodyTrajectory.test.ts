import { describe, expect, it } from "vitest";
import type { Vec3 } from "../domain/solarSystem";
import {
  buildOrbitTrailSegments,
  chunkScenePoints,
  interpolateTrajectory,
  sortTrajectory,
  splitTrajectorySegments,
  trajectoryCoversTime,
  type SmallBodyTrajectory,
} from "./smallBodyTrajectory";

const vec = (x: number, y: number, z: number): Vec3 => ({ x, y, z });

const TRAJECTORY: SmallBodyTrajectory = [
  { time: new Date("2026-01-01T00:00:00Z"), positionAu: vec(1, 0, 0) },
  { time: new Date("2026-01-11T00:00:00Z"), positionAu: vec(0, 1, 0) },
  { time: new Date("2026-01-21T00:00:00Z"), positionAu: vec(-1, 0, 0) },
];

describe("smallBodyTrajectory", () => {
  describe("sortTrajectory", () => {
    it("orders points by time without mutating the input", () => {
      const unsorted: SmallBodyTrajectory = [TRAJECTORY[2], TRAJECTORY[0], TRAJECTORY[1]];
      const sorted = sortTrajectory(unsorted);
      expect(sorted.map((p) => p.time.toISOString())).toEqual([
        "2026-01-01T00:00:00.000Z",
        "2026-01-11T00:00:00.000Z",
        "2026-01-21T00:00:00.000Z",
      ]);
      expect(unsorted[0]).toBe(TRAJECTORY[2]);
    });
  });

  describe("trajectoryCoversTime", () => {
    it("is true inside the range and at the boundaries", () => {
      expect(trajectoryCoversTime(TRAJECTORY, new Date("2026-01-10T00:00:00Z"))).toBe(true);
      expect(trajectoryCoversTime(TRAJECTORY, new Date("2026-01-01T00:00:00Z"))).toBe(true);
      expect(trajectoryCoversTime(TRAJECTORY, new Date("2026-01-21T00:00:00Z"))).toBe(true);
    });

    it("is false outside the range or for an empty trajectory", () => {
      expect(trajectoryCoversTime(TRAJECTORY, new Date("2025-12-31T00:00:00Z"))).toBe(false);
      expect(trajectoryCoversTime([], new Date("2026-01-10T00:00:00Z"))).toBe(false);
    });
  });

  describe("interpolateTrajectory", () => {
    it("returns null for an empty trajectory", () => {
      expect(interpolateTrajectory([], new Date("2026-01-10T00:00:00Z"))).toBeNull();
    });

    it("returns exact sample positions at the boundaries", () => {
      expect(interpolateTrajectory(TRAJECTORY, new Date("2026-01-01T00:00:00Z"))).toEqual(vec(1, 0, 0));
      expect(interpolateTrajectory(TRAJECTORY, new Date("2026-01-21T00:00:00Z"))).toEqual(vec(-1, 0, 0));
    });

    it("linearly interpolates between samples", () => {
      // Halfway between 2026-01-01 (1,0,0) and 2026-01-11 (0,1,0).
      const result = interpolateTrajectory(TRAJECTORY, new Date("2026-01-06T00:00:00Z"));
      expect(result?.x).toBeCloseTo(0.5, 10);
      expect(result?.y).toBeCloseTo(0.5, 10);
      expect(result?.z).toBeCloseTo(0, 10);
    });

    it("returns null outside the range when no orbit period is given", () => {
      expect(interpolateTrajectory(TRAJECTORY, new Date("2025-12-01T00:00:00Z"))).toBeNull();
      expect(interpolateTrajectory(TRAJECTORY, new Date("2026-02-01T00:00:00Z"))).toBeNull();
    });
  });

  describe("splitTrajectorySegments", () => {
    it("returns no segments for fewer than two points", () => {
      expect(splitTrajectorySegments([TRAJECTORY[0]])).toEqual([]);
    });

    it("keeps contiguous points in one segment", () => {
      const segments = splitTrajectorySegments(TRAJECTORY);
      expect(segments).toHaveLength(1);
      expect(segments[0]).toHaveLength(3);
    });

    it("splits across gaps larger than 45 days", () => {
      const withGap: SmallBodyTrajectory = [
        ...TRAJECTORY,
        { time: new Date("2026-06-01T00:00:00Z"), positionAu: vec(0, -1, 0) },
        { time: new Date("2026-06-11T00:00:00Z"), positionAu: vec(0, 0, 1) },
      ];
      const segments = splitTrajectorySegments(withGap);
      expect(segments).toHaveLength(2);
    });
  });

  describe("chunkScenePoints", () => {
    it("returns no chunks for fewer than two points", () => {
      expect(chunkScenePoints([[0, 0, 0]])).toEqual([]);
    });

    it("splits into overlapping chunks that stitch end-to-end", () => {
      const points: [number, number, number][] = Array.from({ length: 10 }, (_, i) => [i, 0, 0]);
      const chunks = chunkScenePoints(points, 4);
      // Chunks overlap by one point so the rendered line has no gaps.
      expect(chunks[0][chunks[0].length - 1]).toEqual(chunks[1][0]);
    });
  });

  // Guards the small-body orbit pipeline: given trajectory data, the scene must always be able
  // to draw an orbit (a regression here is why Halley's orbit could silently disappear).
  describe("orbit trail rendering pipeline", () => {
    const denseTrajectory = (samples: number, stepDays: number): SmallBodyTrajectory => {
      const start = new Date("2020-01-01T00:00:00Z").getTime();
      return Array.from({ length: samples }, (_, i) => ({
        time: new Date(start + i * stepDays * 86_400_000),
        positionAu: vec(Math.cos(i * 0.05), Math.sin(i * 0.05), 0.1 * Math.sin(i * 0.02)),
      }));
    };

    it("produces a renderable orbit (>=1 segment, all points, each drawable) for dense data", () => {
      const segments = buildOrbitTrailSegments(denseTrajectory(400, 5)); // ~5.5 yr, no gaps > 45d
      expect(segments.length).toBeGreaterThanOrEqual(1);
      expect(segments.reduce((n, s) => n + s.length, 0)).toBe(400);
      expect(segments.every((s) => s.length >= 2)).toBe(true);
    });

    it("chunks a long orbit into stitched, drawable line chunks", () => {
      const segments = buildOrbitTrailSegments(denseTrajectory(1200, 3));
      expect(segments).toHaveLength(1);
      const scenePoints = segments[0].map((p) => [p.x, p.y, p.z] as [number, number, number]);
      const chunks = chunkScenePoints(scenePoints);
      expect(chunks.length).toBeGreaterThan(1);
      expect(chunks.every((c) => c.length >= 2)).toBe(true);
      for (let i = 1; i < chunks.length; i += 1) {
        expect(chunks[i][0]).toEqual(chunks[i - 1][chunks[i - 1].length - 1]);
      }
    });

    it("yields nothing to render (no crash) when the trajectory is missing or too short", () => {
      expect(buildOrbitTrailSegments([])).toEqual([]);
      expect(buildOrbitTrailSegments([{ time: new Date(), positionAu: vec(1, 0, 0) }])).toEqual([]);
    });
  });
});
