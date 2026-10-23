import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import EventExplainer from "./EventExplainer";
import { skyTrack } from "../lib/skyTrack";

it("places increasing right ascension to the left, matching east-left sky charts", () => {
  const time = new Date("2027-02-19T15:45Z");
  const track = skyTrack("mars", time);
  const html = renderToStaticMarkup(<EventExplainer type="opposition" body="mars" time={time} />);
  const coordinates = html.match(/<polyline points="([^"]+)"/)![1].split(" ").map(point => Number(point.split(",")[0]));
  const min = track.reduce((index, point, i) => point.ra < track[index].ra ? i : index, 0);
  const max = track.reduce((index, point, i) => point.ra > track[index].ra ? i : index, 0);
  expect(coordinates[max]).toBeLessThan(coordinates[min]);
  expect(html).toContain("← increasing RA (east)");
});
