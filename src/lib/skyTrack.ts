import { isSmallBody, type BodyId } from "../domain/solarSystem";
import { getBodyPosition } from "./ephemeris";
import { addDays } from "./timeUtils";

/** Geometric J2000 equatorial directions, not a horizon/visibility prediction. */
export function skyTrack(body: BodyId, time: Date) {
  if (body === "earth" || isSmallBody(body)) return [];
  let previousRa: number | null = null;
  return Array.from({ length: 61 }, (_, i) => {
    const day = (i-30)*4, date = addDays(time, day);
    const earth = getBodyPosition("earth", date).positionAu, target = getBodyPosition(body, date).positionAu;
    const x=target.x-earth.x, y=target.y-earth.y, z=target.z-earth.z;
    let ra = Math.atan2(y,x)*180/Math.PI;
    if (previousRa !== null) {
      while (ra-previousRa > 180) ra -= 360;
      while (ra-previousRa < -180) ra += 360;
    }
    previousRa = ra;
    return { day, ra, dec: Math.atan2(z,Math.hypot(x,y))*180/Math.PI };
  });
}
