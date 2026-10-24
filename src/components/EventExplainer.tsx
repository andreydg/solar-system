import { useMemo } from "react";
import { BODY_BY_ID, type BodyId } from "../domain/solarSystem";
import { EVENT_TYPE_BY_ID, type CatalogEventType } from "../domain/eventTypes";
import { skyTrack } from "../lib/skyTrack";

export default function EventExplainer({ type, body, time }: { type: CatalogEventType; body: BodyId; time: Date }) {
  const points = useMemo(() => skyTrack(body, time), [body, time]);
  const ra = points.map(p => p.ra), dec = points.map(p => p.dec);
  const maxX = Math.max(...ra), width = Math.max(1, maxX-Math.min(...ra));
  const minY = Math.min(...dec), height = Math.max(1, Math.max(...dec)-minY);
  const xy = (p: typeof points[number]) => ({ x: 25+(maxX-p.ra)/width*250, y: 130-(p.dec-minY)/height*100 });
  return <section className="event-explainer" aria-label="Event explanation">
    <h3>What am I seeing?</h3>
    <p className="explanation-note">Snapshot: {time.toISOString().slice(0, 16).replace("T", " ")} UTC. Choose About This Date again to refresh.</p>
    <p>{EVENT_TYPE_BY_ID[type].description}</p>
    {points.length > 0 ? <>
      <h4>{BODY_BY_ID[body].name} from Earth</h4>
      <svg viewBox="0 0 300 165" role="img" aria-label="Approximate Earth-view sky track, 120 days before and after the selected date">
        <path d="M20 20V140H285" fill="none" stroke="#666" />
        <polyline points={points.map(p => { const v=xy(p); return `${v.x},${v.y}`; }).join(" ")} fill="none" stroke="#d8a44f" strokeWidth="2" />
        {[0,30,60].map(i => { const p=xy(points[i]); return <g key={i}>
          <circle cx={p.x} cy={p.y} r={i===30 ? 4 : 2} fill={i===30 ? "#8bc3ef" : "#d8a44f"} />
          <text x={p.x} y={p.y-8} textAnchor="middle" fill="#e8e8e8" fontSize="10">{i===30 ? "Selected date" : `${i===0 ? "−" : "+"}120d`}</text>
        </g>; })}
        <text x="150" y="158" textAnchor="middle" fill="#aaa" fontSize="10">← increasing RA (east)</text>
      </svg>
      <p className="explanation-note">Geometric J2000 direction; vertical axis is declination. Each axis is scaled independently. A turn in the track is a change of viewing direction, not an orbit reversal. Not a local sky or visibility forecast.</p>
    </> : null}
    <nav aria-label="Astronomy guides">
      <a href="/learn/opposition.html">Opposition</a>{" · "}
      <a href="/learn/retrograde.html">Retrograde motion</a>{" · "}
      <a href="/learn/elongation.html">Elongation</a>
    </nav>
  </section>;
}
