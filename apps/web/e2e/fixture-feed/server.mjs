// A stand-in for the two things Trackline reads from outside: the TTC's schedule catalogue and its GTFS-Realtime feeds.
//
// The end-to-end tests need a backend that is seeded and alive without depending on the TTC being reachable, on today's
// date, or on what the fleet happens to be doing. This serves:
//
//   GET  /ckan/package_show     the catalogue entry, pointing at the committed fixture schedule
//   GET  /ttc-subset.zip        that schedule: three real routes (a subway line, a bus and a streetcar) with their stops
//   GET  /gtfsrt/vehicles       a feed generated now: five vehicles on route 501, drifting along its shape
//   GET  /gtfsrt/trips          an empty feed (the pipeline reads it; nothing here needs predictions)
//   GET  /gtfsrt/alerts         one alert on route 501, in the shape the TTC publishes them
//   POST /control/scenario      {"name": "even" | "bunched"} chooses how the five vehicles are spaced
//
// The vehicles are placed along the route's own shape, read from the API, so they are where the application will project
// them whichever of the route's shapes the importer chose as primary. Spacing is fixed and known, so a test can assert on
// what the application makes of it. No dependency: the protobuf wire format is written by hand, since the part of it used
// here is a few dozen lines.
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import path from "node:path";

const PORT = Number(process.env.FIXTURE_PORT ?? 4010);
const API_URL = process.env.API_URL ?? "http://127.0.0.1:8080";
const ZIP = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../../services/api/src/test/resources/gtfs/ttc-subset.zip",
);

/** Where along the route, as a fraction of its length, the five vehicles are. Kept clear of both ends, which are terminals. */
const SCENARIOS = {
  // Evenly spaced: nothing for the detector to find.
  even: [0.1, 0.3, 0.5, 0.7, 0.9],
  // Three together, then a long gap: the case bunching exists for.
  bunched: [0.2, 0.23, 0.26, 0.36, 0.76],
};
let scenario = "even";

// --- protobuf, by hand ------------------------------------------------------------------------------------------

const varint = (value) => {
  const bytes = [];
  let rest = BigInt(value);
  while (rest > 0x7fn) {
    bytes.push(Number(rest & 0x7fn) | 0x80);
    rest >>= 7n;
  }
  bytes.push(Number(rest));
  return Buffer.from(bytes);
};
const tag = (field, wire) => varint((field << 3) | wire);
const field = {
  varint: (number, value) => Buffer.concat([tag(number, 0), varint(value)]),
  string: (number, text) => {
    const bytes = Buffer.from(text, "utf8");
    return Buffer.concat([tag(number, 2), varint(bytes.length), bytes]);
  },
  message: (number, body) => Buffer.concat([tag(number, 2), varint(body.length), body]),
  float: (number, value) => {
    const bytes = Buffer.alloc(4);
    bytes.writeFloatLE(value);
    return Buffer.concat([tag(number, 5), bytes]);
  },
};

const header = (timestamp) =>
  field.message(
    1,
    Buffer.concat([field.string(1, "2.0"), field.varint(2, 0), field.varint(3, timestamp)]),
  );

const feed = (timestamp, entities) => Buffer.concat([header(timestamp), ...entities.map((e) => field.message(2, e))]);

// --- the route's shape ------------------------------------------------------------------------------------------

let line = null;

const METRES_PER_DEGREE_LATITUDE = 111_320;

/** Fetches the primary shape of route 501, direction 0, once the schedule has been imported. */
async function loadLine() {
  if (line !== null) {
    return line;
  }
  try {
    const response = await fetch(`${API_URL}/api/v1/routes/501/shape?direction=0`);
    if (!response.ok) {
      return null;
    }
    const { data } = await response.json();
    const points = data.geometry.coordinates;
    const cumulative = [0];
    for (let index = 1; index < points.length; index++) {
      const [lon0, lat0] = points[index - 1];
      const [lon1, lat1] = points[index];
      const north = (lat1 - lat0) * METRES_PER_DEGREE_LATITUDE;
      const east = (lon1 - lon0) * METRES_PER_DEGREE_LATITUDE * Math.cos(((lat0 + lat1) / 2) * (Math.PI / 180));
      cumulative.push(cumulative[index - 1] + Math.hypot(north, east));
    }
    line = { points, cumulative, length: cumulative[cumulative.length - 1] };
    return line;
  } catch {
    return null;
  }
}

/** The point at a distance along the line, and the compass bearing of the line there. */
function pointAt(distance) {
  const { points, cumulative } = line;
  let index = 1;
  while (index < points.length - 1 && cumulative[index] < distance) {
    index++;
  }
  const [lon0, lat0] = points[index - 1];
  const [lon1, lat1] = points[index];
  const span = cumulative[index] - cumulative[index - 1] || 1;
  const along = Math.min(1, Math.max(0, (distance - cumulative[index - 1]) / span));
  const north = (lat1 - lat0) * METRES_PER_DEGREE_LATITUDE;
  const east = (lon1 - lon0) * METRES_PER_DEGREE_LATITUDE * Math.cos(lat0 * (Math.PI / 180));
  const bearing = ((Math.atan2(east, north) * 180) / Math.PI + 360) % 360;
  return { lat: lat0 + (lat1 - lat0) * along, lon: lon0 + (lon1 - lon0) * along, bearing };
}

// --- the feeds --------------------------------------------------------------------------------------------------

async function vehicles(timestamp) {
  if ((await loadLine()) === null) {
    return feed(timestamp, []);
  }
  // Every vehicle drifts together by up to 400 m, so a marker visibly moves while the spacing stays exactly as set.
  const drift = Math.sin(timestamp / 25) * 400;
  const entities = SCENARIOS[scenario].map((fraction, index) => {
    const { lat, lon, bearing } = pointAt(fraction * line.length + drift);
    const id = `e2e-${index + 1}`;
    const vehicle = Buffer.concat([
      field.message(1, Buffer.concat([field.string(1, `trip-${id}`), field.string(5, "501")])),
      field.message(2, Buffer.concat([field.float(1, lat), field.float(2, lon), field.float(3, bearing)])),
      field.varint(5, timestamp - 5),
      field.message(8, field.string(1, id)),
    ]);
    return Buffer.concat([field.string(1, id), field.message(4, vehicle)]);
  });
  return feed(timestamp, entities);
}

// A TranslatedString is a list of translations, each a text and its language.
const translated = (text) => field.message(1, Buffer.concat([field.string(1, text), field.string(2, "en")]));

function alerts(timestamp) {
  const alert = Buffer.concat([
    field.message(5, field.string(2, "501")),
    // As the TTC publishes them: an unknown cause and effect on every alert.
    field.varint(6, 1),
    field.varint(7, 8),
    field.message(10, translated("501 Queen: diversion at Bay")),
    field.message(11, translated("501 Queen streetcars are diverting around construction at Queen St and Bay St. This is a fixture for the end-to-end tests.")),
  ]);
  return feed(timestamp, [Buffer.concat([field.string(1, "e2e-alert-1"), field.message(5, alert)])]);
}

// --- the server -------------------------------------------------------------------------------------------------

const send = (response, status, type, body) => {
  response.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(body), "Cache-Control": "no-store" });
  response.end(body);
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", `http://127.0.0.1:${PORT}`);
  const now = Math.floor(Date.now() / 1000);

  if (request.method === "GET" && url.pathname === "/ckan/package_show") {
    return send(
      response,
      200,
      "application/json",
      JSON.stringify({
        success: true,
        result: {
          resources: [
            {
              name: "ttc-routes-and-schedules (end-to-end fixture)",
              format: "ZIP",
              url: `http://127.0.0.1:${PORT}/ttc-subset.zip`,
              last_modified: "2026-09-30T00:00:00",
            },
          ],
        },
      }),
    );
  }
  if (request.method === "GET" && url.pathname === "/ttc-subset.zip") {
    return send(response, 200, "application/zip", await readFile(ZIP));
  }
  if (request.method === "GET" && url.pathname === "/gtfsrt/vehicles") {
    return send(response, 200, "application/x-google-protobuf", await vehicles(now));
  }
  if (request.method === "GET" && url.pathname === "/gtfsrt/trips") {
    return send(response, 200, "application/x-google-protobuf", feed(now, []));
  }
  if (request.method === "GET" && url.pathname === "/gtfsrt/alerts") {
    return send(response, 200, "application/x-google-protobuf", alerts(now));
  }
  if (request.method === "POST" && url.pathname === "/control/scenario") {
    let body = "";
    for await (const chunk of request) {
      body += chunk;
    }
    const name = JSON.parse(body || "{}").name;
    if (!(name in SCENARIOS)) {
      return send(response, 400, "application/json", JSON.stringify({ error: `unknown scenario ${name}` }));
    }
    scenario = name;
    return send(response, 200, "application/json", JSON.stringify({ scenario }));
  }
  if (request.method === "GET" && url.pathname === "/health") {
    return send(response, 200, "application/json", JSON.stringify({ scenario, shapeLoaded: line !== null }));
  }
  return send(response, 404, "application/json", JSON.stringify({ error: "not found" }));
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`fixture feed listening on http://127.0.0.1:${PORT}`);
});
