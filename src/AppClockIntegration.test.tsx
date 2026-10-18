// @vitest-environment happy-dom
import { act, type ComponentProps, type ReactElement, type ReactNode, useLayoutEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { create } from "@react-three/test-renderer";
import { useThree, type RootState } from "@react-three/fiber";
import * as THREE from "three";
import type { Line2, OrbitControls } from "three-stdlib";
import { afterEach, expect, it, vi } from "vitest";
import App from "./App";
import type ControlPanel from "./components/ControlPanel";
import type { SolarSystemSceneProps } from "./components/SolarSystemScene";
import { findNextEvent, findPreviousEvent, type CatalogEventResult } from "./lib/eventCatalogApi";
import { getBodyPositions } from "./lib/ephemeris";
import { getVisualRadius, toScenePoint } from "./lib/sceneSpace";
import { BODY_BY_ID, type BodyId } from "./domain/solarSystem";
import { interpolateTrajectory, type SmallBodyTrajectory } from "./lib/smallBodyTrajectory";

const bridge = vi.hoisted(() => ({
  children: null as ReactNode,
  controls: null as ComponentProps<typeof ControlPanel> | null,
  scene: null as SolarSystemSceneProps | null,
  trajectories: {} as Partial<Record<BodyId, SmallBodyTrajectory>>,
  beforePaint: null as ((state: RootState) => void) | null,
}));
vi.mock("@react-three/fiber", async original => ({
  ...(await original<typeof import("@react-three/fiber")>()),
  // Keep the actual scene composition, but supply the test renderer's WebGL host.
  Canvas: ({ children }: { children: ReactNode }) => { bridge.children = children; return null; },
}));
vi.mock("@react-three/drei", async original => ({
  ...(await original<typeof import("@react-three/drei")>()),
  useTexture: (url: string) => { const texture = new THREE.Texture(); texture.name = url; return texture; },
  Html: ({ children }: { children: ReactElement<{ children: ReactNode; className?: string }> }) =>
    <group name={"html:" + (children.props.className === "pair-distance-label" ? "pair-distance" : String(children.props.children))} />,
  useCursor: () => {},
}));
vi.mock("./components/CelestialSphere", () => ({ default: () => null }));
vi.mock("./components/SceneBoundary", async () => {
  const { default: Scene } = await import("./components/SolarSystemScene");
  return { default: (props: SolarSystemSceneProps) => { bridge.scene = props; return <Scene {...props} />; } };
});
vi.mock("./components/ControlPanel", async original => {
  const { default: Panel } = await original<typeof import("./components/ControlPanel")>();
  return { default: (props: ComponentProps<typeof Panel>) => { bridge.controls = props; return <Panel {...props} />; } };
});
vi.mock("./lib/useSmallBodyTrajectories", async original => {
  const { default: raw } = await import("../backend/src/main/resources/trajectories/halley-v1.txt?raw");
  const { eclipticToEquatorial } = await import("./lib/ephemerisApi");
  bridge.trajectories.halley = raw.split("$$SOE")[1].split("$$EOE")[0].trim().split("\n").map(line => {
    const fields = line.split(",").map(field => field.trim());
    return {
      time: new Date(fields[1].replace("A.D. ", "").replaceAll("-", " ") + " UTC"),
      positionAu: eclipticToEquatorial({ x: Number(fields[2]), y: Number(fields[3]), z: Number(fields[4]) }),
    };
  });
  return {
    ...(await original<typeof import("./lib/useSmallBodyTrajectories")>()),
    default: () => ({ trajectories: bridge.trajectories, error: null }),
  };
});
vi.mock("./lib/eventCatalogApi", async original => ({
  ...(await original<typeof import("./lib/eventCatalogApi")>()),
  findNextEvent: vi.fn(), findPreviousEvent: vi.fn(),
}));

let root: Root | undefined, renderer: Awaited<ReturnType<typeof create>> | undefined;
afterEach(async () => {
  bridge.beforePaint = null;
  if (renderer) await renderer.unmount();
  if (root) await act(async () => root!.unmount());
  root = undefined; renderer = undefined;
  document.body.innerHTML = "";
  vi.restoreAllMocks(); vi.unstubAllGlobals();
});

async function mountApp() {
  window.history.replaceState(null, "", "/");
  const frames = new Map<number, FrameRequestCallback>();
  let id = 0;
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => { frames.set(++id, callback); return id; });
  vi.stubGlobal("cancelAnimationFrame", (key: number) => frames.delete(key));
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
  vi.spyOn(window, "matchMedia").mockReturnValue({ matches: false } as MediaQueryList);
  Object.defineProperty(document, "hidden", { configurable: true, value: false });
  const container = document.createElement("div"); document.body.append(container);
  root = createRoot(container);
  await act(async () => root!.render(<App />));
  await act(async () => {
    bridge.controls!.onDateChange(new Date("2026-01-01T00:00:00Z"));
    for (const body of bridge.controls!.visibleBodies)
      if (body !== "earth" && body !== "mars" && body !== "halley") bridge.controls!.onToggleBody(body);
    if (!bridge.controls!.visibleBodies.includes("halley")) bridge.controls!.onToggleBody("halley");
  });
  function BeforePaint() {
    const get = useThree(state => state.get);
    useLayoutEffect(() => { bridge.beforePaint?.(get()); });
    return null;
  }
  let rendered: ReactNode;
  const syncScene = async () => {
    if (rendered === bridge.children) return;
    rendered = bridge.children;
    const scene = <>{rendered}<BeforePaint /></>;
    if (renderer) await renderer.update(scene);
    else renderer = await create(scene, { width: 800, height: 600, camera: { position: [12, 9, 12], fov: 48 } });
  };
  await syncScene();
  const update = async (action: () => void | Promise<void>) => { await act(action); await syncScene(); };
  const tickClock = (now: number) => {
    const queued = [...frames.values()]; frames.clear();
    queued.forEach(callback => callback(now));
  };
  const frame = async (now: number) => {
    await act(async () => tickClock(now));
    await syncScene();
    await renderer!.advanceFrames(1, 1 / 60);
  };
  return { update, frame, tickClock, container };
}

function bodyPoint(body: "earth" | "mars" | "halley") {
  let mesh: THREE.Mesh | undefined;
  renderer!.scene.instance.traverse(object => {
    const candidate = object as THREE.Mesh<THREE.BufferGeometry, THREE.MeshStandardMaterial>;
    if (body === "halley") {
      if (candidate.material?.roughness === 0.65 && candidate.material.color.equals(new THREE.Color(BODY_BY_ID.halley.color)))
        mesh = candidate; // Actual comet nucleus, not the tail or label.
    } else if (candidate.material?.map?.name.includes(body)) mesh = candidate;
  });
  expect(mesh).toBeDefined();
  renderer!.scene.instance.updateMatrixWorld(true);
  return mesh!.getWorldPosition(new THREE.Vector3());
}
function expected(body: "earth" | "mars" | "halley", time: Date) {
  const point = body === "halley" ? interpolateTrajectory(bridge.trajectories.halley!, time, body)!
    : getBodyPositions([body], time)[0].positionAu;
  return new THREE.Vector3(...toScenePoint(point));
}
function assertSceneAt(time: Date, event = false) {
  for (const body of ["earth", "mars", "halley"] as const) {
    expect(bodyPoint(body).distanceTo(expected(body, time))).toBeLessThan(1e-8);
    const anchor = renderer!.scene.instance.getObjectByName("html:" + BODY_BY_ID[body].name)?.parent;
    if (bridge.scene!.focusedBody !== body) expect(anchor).toBeDefined();
    if (anchor) {
      const labelPoint = expected(body, time);
      labelPoint.y += getVisualRadius(BODY_BY_ID[body].radiusKm, event && body !== "halley", body === "halley");
      expect(anchor.position.distanceTo(labelPoint)).toBeLessThan(1e-8);
    }
  }
  if (event) {
    let line: Line2 | undefined;
    renderer!.scene.instance.traverse(object => {
      const candidate = object as Line2;
      if (candidate.isLine2 && candidate.material.color.getHexString() === "93c5fd") line = candidate;
    });
    expect(line).toBeDefined();
    const midpoint = expected("earth", time).add(expected("mars", time)).multiplyScalar(0.5);
    expect(renderer!.scene.instance.getObjectByName("html:pair-distance")!.parent!.position.distanceTo(midpoint))
      .toBeLessThan(1e-8);
    for (const [attribute, body] of [["instanceStart", "earth"], ["instanceEnd", "mars"]] as const) {
      const point = new THREE.Vector3().fromBufferAttribute(line!.geometry.attributes[attribute], 0);
      expect(point.distanceTo(expected(body, time))).toBeLessThan(1e-6);
    }
  }
}
function result(time: Date): CatalogEventResult {
  return { id: "clock-event", type: "closestApproach", bodyA: "earth", bodyB: "mars", time,
    computedTime: time, source: "JPL_HORIZONS", computedSource: "TEST", validationStatus: "validated",
    validatedSource: "JPL_HORIZONS", angleDeg: null, magnitude: null, distanceAu: 0.6, distanceKm: 9e7,
    jplCheckedAtUtc: null, jplDeltaKm: null, jplRawSummary: null };
}

it("real App clock drives the actual scene every frame and synchronizes date edits and Next while playing", async () => {
  const app = await mountApp();
  const edited = new Date("2030-07-04T12:30:00Z");
  await app.update(() => bridge.controls!.onPlayToggle());
  expect(bridge.controls!.isPlaying).toBe(true);
  await app.update(() => bridge.controls!.onDateChange(edited));
  await renderer!.advanceFrames(1, 1 / 60);
  expect(bridge.controls!.currentTime).toEqual(edited);
  expect(app.container.querySelector<HTMLInputElement>('input[type="datetime-local"]')!.value).toBe("2030-07-04T12:30");
  assertSceneAt(edited);
  await app.update(() => { bridge.controls!.onSpeedChange(365); bridge.controls!.onPlayToggle(); });
  await app.frame(1000);
  let previous = bodyPoint("earth");
  for (let i = 1; i <= 6; i++) {
    await app.frame(1000 + i * 50);
    const live = new Date(edited.getTime() + i * 50 * 365 * 86_400);
    expect(bodyPoint("earth").distanceTo(previous)).toBeGreaterThan(0.001);
    assertSceneAt(live);
    if (i % 2 === 0) expect(bridge.controls!.currentTime).toEqual(live);
    previous = bodyPoint("earth");
  }
  const next = new Date("2032-02-20T10:00:00Z");
  vi.mocked(findNextEvent).mockResolvedValue(result(next));
  await app.update(() => bridge.controls!.onNextEvent());
  await renderer!.advanceFrames(1, 1 / 60);
  expect(bridge.controls!.currentTime).toEqual(next);
  expect(bridge.controls!.isPlaying).toBe(false);
  assertSceneAt(next, true);
  await app.update(() => bridge.controls!.onPlayToggle());
  await app.frame(2000);
  for (let i = 1; i <= 4; i++) {
    await app.frame(2000 + i * 25);
    assertSceneAt(new Date(next.getTime() + i * 25 * 365 * 86_400), true);
  }
  const following = new Date("2034-05-01T18:45:00Z");
  vi.mocked(findNextEvent).mockResolvedValue(result(following));
  await app.update(() => bridge.controls!.onNextEvent());
  expect(findNextEvent).toHaveBeenLastCalledWith("closestApproach", "earth", "mars",
    new Date(next.getTime() + 86_400_000), expect.any(AbortSignal), "clock-event");
  await renderer!.advanceFrames(1, 1 / 60);
  expect(bridge.controls!.currentTime).toEqual(following);
  expect(bridge.controls!.isPlaying).toBe(false);
  expect(app.container.querySelector<HTMLInputElement>('input[type="datetime-local"]')!.value).toBe("2034-05-01T18:45");
  assertSceneAt(following, true);
});

it.each(["next", "prev"] as const)("Find %s while playing uses the unpublished live frame time", async direction => {
  const app = await mountApp();
  await app.update(() => { bridge.controls!.onSpeedChange(365); bridge.controls!.onPlayToggle(); });
  await app.frame(1000); await app.frame(1050);
  const live = new Date(Date.parse("2026-01-01T00:00:00Z") + 50 * 365 * 86_400);
  expect(bridge.controls!.currentTime).not.toEqual(live);
  const search = direction === "next" ? findNextEvent : findPreviousEvent;
  vi.mocked(search).mockResolvedValue(null);
  await app.update(() => direction === "next" ? bridge.controls!.onNextEvent() : bridge.controls!.onPrevEvent());
  expect(search).toHaveBeenLastCalledWith("closestApproach", "earth", "mars", live, expect.any(AbortSignal), undefined);
});

it("bodies, labels and a following camera read live playback positions before passive effects", async () => {
  const app = await mountApp();
  await app.update(() => bridge.scene!.onFocusBody("earth"));
  await renderer!.advanceFrames(150, 1 / 60);
  const edited = new Date("2031-06-15T00:00:00Z");
  let checked = false;
  bridge.beforePaint = state => {
    // React layout has committed; passive effects have not. This is the audited frame gap.
    for (const subscriber of state.internal.subscribers) subscriber.ref.current(state, 1 / 60);
    const label = state.scene.getObjectByName("html:Mars")!.parent!;
    const point = expected("mars", edited); point.y += getVisualRadius(BODY_BY_ID.mars.radiusKm);
    expect(label.position.distanceTo(point)).toBeLessThan(1e-8);
    expect((state.controls as OrbitControls).target.distanceTo(expected("earth", edited))).toBeLessThan(1e-8);
    checked = true;
  };
  await app.update(() => bridge.controls!.onDateChange(edited));
  expect(checked).toBe(true);
  bridge.beforePaint = null;
  await app.update(() => { bridge.controls!.onSpeedChange(365); bridge.controls!.onPlayToggle(); });
  await app.frame(1000);
  let playbackChecked = false;
  bridge.beforePaint = state => {
    bridge.beforePaint = null;
    // The 1100ms publication has committed new scene-owned position objects.
    // Tick once more before passive effects: SceneMotion must update those objects,
    // not the previous render's clones. This exercises actual clock/scene wiring.
    app.tickClock(1125);
    const live = bridge.scene!.timeRef.current;
    expect(live).not.toEqual(bridge.controls!.currentTime);
    for (const subscriber of state.internal.subscribers) subscriber.ref.current(state, 1 / 60);
    assertSceneAt(live);
    expect((state.controls as OrbitControls).target.distanceTo(expected("earth", live))).toBeLessThan(1e-8);
    playbackChecked = true;
  };
  await app.frame(1100);
  expect(playbackChecked).toBe(true);
});

it.each(["next", "prev"] as const)("event history %s synchronously pauses the live clock and restores the actual scene", async direction => {
  const app = await mountApp();
  const first = new Date("2032-02-20T10:00:00Z");
  const second = new Date("2034-05-01T18:45:00Z");
  vi.mocked(findNextEvent).mockResolvedValueOnce({ ...result(first), id: "first" });
  await app.update(() => bridge.controls!.onNextEvent());
  vi.mocked(findNextEvent).mockResolvedValueOnce({ ...result(second), id: "second" });
  await app.update(() => bridge.controls!.onNextEvent());
  if (direction === "next") await app.update(() => bridge.controls!.onPrevEvent());
  const target = direction === "next" ? second : first;
  const calls = [vi.mocked(findNextEvent).mock.calls.length, vi.mocked(findPreviousEvent).mock.calls.length];
  await app.update(() => { bridge.controls!.onSpeedChange(365); bridge.controls!.onPlayToggle(); });
  await app.frame(1000); await app.frame(1050);
  await app.update(() => {
    if (direction === "next") bridge.controls!.onNextEvent();
    else bridge.controls!.onPrevEvent();
    // Synthetic queued-frame ordering: defense in depth, not an observed browser race.
    // Replay must stop the live clock synchronously, before React commits the paused UI.
    app.tickClock(1075);
    expect(bridge.scene!.timeRef.current).toEqual(target);
  });
  await renderer!.advanceFrames(1, 1 / 60);
  expect(bridge.controls!.currentTime).toEqual(target);
  expect(bridge.controls!.isPlaying).toBe(false);
  assertSceneAt(target, true);
  expect([vi.mocked(findNextEvent).mock.calls.length, vi.mocked(findPreviousEvent).mock.calls.length]).toEqual(calls);
});

it("date editing synchronously pauses a queued live tick before React commits", async () => {
  const app = await mountApp();
  await app.update(() => { bridge.controls!.onSpeedChange(365); bridge.controls!.onPlayToggle(); });
  await app.frame(1000); await app.frame(1050);
  const edited = new Date("2040-07-04T12:30:00Z");
  await app.update(() => {
    bridge.controls!.onDateChange(edited);
    // Synthetic queued-frame ordering: defense in depth, not an observed browser race.
    app.tickClock(1075);
    expect(bridge.scene!.timeRef.current).toEqual(edited);
  });
  await renderer!.advanceFrames(1, 1 / 60);
  expect(bridge.controls!.currentTime).toEqual(edited);
  expect(bridge.controls!.isPlaying).toBe(false);
  assertSceneAt(edited);
});
