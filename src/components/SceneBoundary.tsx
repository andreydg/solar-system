import { Component, lazy, Suspense } from "react";
import type { SolarSystemSceneProps } from "./SolarSystemScene";

function webglAvailable() {
  if (typeof document === "undefined") return false;
  try {
    const context = document.createElement("canvas").getContext("webgl2");
    context?.getExtension("WEBGL_lose_context")?.loseContext();
    return context !== null;
  } catch { return false; }
}

/** Retry only the scene subtree: dates, event results and toggles live above this boundary. */
export default class SceneBoundary extends Component<SolarSystemSceneProps, { failed: boolean }> {
  state = { failed: !webglAvailable() };
  private resetResources?: () => void;
  private loadScene = async () => {
    const module = await import("./SolarSystemScene");
    this.resetResources = module.resetSceneResources;
    return module;
  };
  private Scene = lazy(this.loadScene);
  static getDerivedStateFromError() { return { failed: true }; }
  private retry = () => {
    this.resetResources?.();
    this.Scene = lazy(this.loadScene);
    this.setState({ failed: !webglAvailable() });
  };
  render() {
    const Scene = this.Scene;
    return this.state.failed ? <div className="scene-loading" role="alert">
      <p>The 3D view could not load. Event search and date controls are still available.</p>
      <button type="button" onClick={this.retry}>Retry 3D view</button>
    </div> : <Suspense fallback={<div className="scene-loading">Loading 3D view…</div>}>
      <Scene {...this.props} />
    </Suspense>;
  }
}
