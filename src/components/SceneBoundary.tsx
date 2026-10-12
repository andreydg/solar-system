import { Component, type ReactNode } from "react";

export default class SceneBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <div className="scene-loading" role="alert">
      <p>The 3D view could not load. Event search and date controls are still available.</p>
      <button type="button" onClick={() => window.location.reload()}>Reload 3D view</button>
    </div> : this.props.children;
  }
}
