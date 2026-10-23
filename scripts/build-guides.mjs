import { mkdirSync, writeFileSync } from "node:fs";

const origin = "https://solar.andreyg.com";
const guides = [
  {
    slug: "opposition", title: "What is planetary opposition?",
    description: "Understand opposition, why Mars is opposite the Sun in our sky, and how it differs from closest approach.",
    event: "opposition", target: "mars",
    paragraphs: [
      "Opposition describes a direction as seen from Earth: an outer planet lies opposite the Sun in the sky. In the Sun-centered picture, Earth is roughly between the Sun and that planet. Mercury and Venus orbit inside Earth’s orbit, so they do not reach opposition.",
      "Opposition and closest approach are not the same calculation. One concerns direction; the other minimizes distance. Because orbits are not perfect concentric circles, their dates need not coincide.",
      "This explorer searches a geometric, geocentric catalog. A provisional result is an approximate candidate; a validated result has passed the app’s JPL geometry checks. The displayed refinement grid is not an observational accuracy guarantee. Local visibility also depends on your location, horizon, weather, and time of night.",
    ],
    exercise: "Open the fixed Mars example below, then compare Opposition with Closest approach using Find. Use Explain this date to inspect the Earth-view track. Rotate the Sun-centered scene and look for Earth between the Sun and Mars. The scene exaggerates body sizes, so apparent disk sizes there are not to scale.",
    source: "https://science.nasa.gov/resource/the-opposition-of-mars/", sourceTitle: "NASA: The Opposition of Mars",
  },
  {
    slug: "retrograde", title: "Why do planets appear to move backward?",
    description: "Explore apparent retrograde motion from Earth and compare it with the same planet’s Sun-centered orbit.",
    event: "retrogradeStart", target: "mars",
    paragraphs: [
      "Mars does not reverse its orbit when it appears to move backward among the stars. The apparent reversal comes from observing a moving planet from a moving Earth. As Earth overtakes an outer planet, our line of sight changes in a way that can temporarily reverse its usual eastward drift.",
      "The turning dates are called stationary points. They bracket a retrograde interval, but the planet is not physically standing still. The answer depends on the coordinate being tracked: this catalog uses geocentric ecliptic longitude; the explanation plot uses J2000 right ascension and declination, so its turning dates can differ.",
      "A Sun-centered orbit and an Earth-view track answer different questions. The orbit shows where the planet is in space. The sky track shows which direction we look to see it. The explanation chart is geometric, not a prediction of where to point a telescope from your backyard.",
    ],
    exercise: "Open the Mars example and select Explain this date. Follow the points from −120 days through the selected date to +120 days. Compare the turn in the sky track with Mars’s continuing orbit in the main view. Search Retrograde start and Retrograde end separately; share each paused view with someone and ask them to explain the difference.",
    source: "https://pwg.gsfc.nasa.gov/stargaze/Splanets.htm", sourceTitle: "NASA Goddard: The Planets",
  },
  {
    slug: "elongation", title: "What is greatest elongation?",
    description: "See why Mercury and Venus stay near the Sun in Earth’s sky and what greatest elongation measures.",
    event: "greatestElongation", target: "venus",
    paragraphs: [
      "Elongation is a planet’s angular separation from the Sun as seen from Earth. Mercury and Venus stay inside Earth’s orbit, so they never wander to the opposite side of our sky from the Sun. Their greatest elongations are local maxima of that separation.",
      "An eastern elongation places the planet east of the Sun; a western elongation places it west. These configurations help explain evening and morning appearances, but an angle alone cannot tell you whether a planet is visible from your location.",
      "Greatest elongation, greatest brightness, and closest approach are different events. Brightness also depends on illuminated phase and distance. In this app, brightness is a photometric model even when its underlying geometry is JPL-validated.",
    ],
    exercise: "Open the fixed Venus example, choose Greatest elongation, then Find. Keep Earth and Venus visible and rotate the 3D scene to inspect the viewing geometry. Compare with Brightest approach. Read each result’s validation status and remember that the enlarged rendered planet is not a prediction of its apparent size.",
    source: "https://science.nasa.gov/learn/basics-of-space-flight/chapter1-2/", sourceTitle: "NASA: Solar System configurations",
  },
];
const escape = value => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
mkdirSync("dist/learn", { recursive: true });
for (const guide of guides) {
  const path = "/learn/" + guide.slug + ".html";
  const params = new URLSearchParams({ v: "1", time: "2027-02-19T15:45:00.000Z", event: guide.event, a: "earth", b: guide.target, bodies: "earth," + guide.target });
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(guide.title)} | Solar System Explorer</title>
<meta name="description" content="${escape(guide.description)}">
<link rel="canonical" href="${origin}${path}">
<meta property="og:type" content="article"><meta property="og:title" content="${escape(guide.title)}">
<meta property="og:description" content="${escape(guide.description)}"><meta property="og:url" content="${origin}${path}">
<meta property="og:image" content="${origin}/apple-touch-icon.png"><meta name="twitter:card" content="summary">
<link rel="icon" href="/favicon.ico"><link rel="stylesheet" href="/learn.css">
</head><body><header><a href="/">Solar System Explorer</a><span> / Field notes</span></header>
<main><p class="eyebrow">A different point of view</p><h1>${escape(guide.title)}</h1>
<p class="lead">${escape(guide.description)}</p>
${guide.paragraphs.map(p => "<p>" + escape(p) + "</p>").join("\n")}
<section><h2>Try it in 3D</h2><p>${escape(guide.exercise)}</p>
<a class="cta" href="/?${escape(params.toString())}">Open the example in 3D →</a>
<p class="note">A fixed illustrative date, not a claim that this event occurs at that instant. Use Find to query an event. No account required.</p></section>
<p class="note">Further reading: <a href="${guide.source}">${escape(guide.sourceTitle)}</a>. Independent educational project; not affiliated with NASA or JPL.</p>
<nav aria-label="More astronomy guides">${guides.filter(g => g !== guide).map(g => `<a href="/learn/${g.slug}.html">${escape(g.title)}</a>`).join(" · ")}</nav>
</main><footer>Geometry is global. Visibility is local. Never look at the Sun through unfiltered optics.</footer></body></html>`;
  writeFileSync("dist" + path, html);
}
writeFileSync("dist/sitemap.xml", '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' +
  ["/", ...guides.map(g => "/learn/" + g.slug + ".html")].map(path => "<url><loc>" + origin + path + "</loc></url>").join("") + "</urlset>\n");
