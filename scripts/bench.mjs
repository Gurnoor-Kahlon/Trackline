#!/usr/bin/env node
// Measures how long Trackline's read endpoints take to answer, from the caller's side.
//
//   node scripts/bench.mjs --api http://localhost:8080 [--requests 200] [--warmup 20] [--json]
//
// Each endpoint is requested one at a time over a reused connection (a fresh connection per request would mostly measure
// connection setup, and a fresh process per request, as `curl` in a loop does, would mostly measure the process), after a
// short warm-up that is not counted. It reports the median, 95th percentile and slowest of the counted requests, and the size
// of the response body. It reads only public endpoints and changes nothing.
//
// What it does not do: concurrent load. These are the numbers for one client at a time, which is what `docs/performance.md`
// says they are. Run it on the machine the server runs on and say so when quoting it; across a network it also measures the
// network.

const args = parse(process.argv.slice(2));
const api = (args.api ?? "http://localhost:8080").replace(/\/+$/, "");
const requests = Number(args.requests ?? 200);
const warmup = Number(args.warmup ?? 20);

const ENDPOINTS = [
  ["network status", "/api/v1/network/status"],
  ["routes (page of 50)", "/api/v1/routes?size=50"],
  ["route 501", "/api/v1/routes/501"],
  ["route 501 shape (simplified 20 m)", "/api/v1/routes/501/shape?simplifyMetres=20"],
  ["route 501 stops", "/api/v1/routes/501/stops"],
  ["stops near downtown (1 km)", "/api/v1/stops/nearby?lat=43.6532&lon=-79.3832&radiusMetres=1000"],
  ["search 'Kennedy'", "/api/v1/search?q=Kennedy"],
  ["vehicles (whole fleet)", "/api/v1/vehicles"],
  ["route 501 vehicles", "/api/v1/routes/501/vehicles"],
  ["route 501 health", "/api/v1/routes/501/health"],
  ["health of every route", "/api/v1/health/routes"],
  ["route 501 bunching", "/api/v1/routes/501/bunching"],
  ["alerts", "/api/v1/alerts?size=100"],
  ["route 501 reliability (7 d)", "/api/v1/routes/501/reliability?window=7d"],
];

const rows = [];
for (const [name, path] of ENDPOINTS) {
  const times = [];
  let bytes = 0;
  let status = 0;
  const sources = new Map();
  for (let index = 0; index < warmup + requests; index++) {
    const started = performance.now();
    const response = await fetch(`${api}${path}`, { headers: { Accept: "application/json" } });
    const body = await response.text();
    const elapsed = performance.now() - started;
    status = response.status;
    if (index >= warmup) {
      times.push(elapsed);
      bytes = Buffer.byteLength(body);
      const source = /"source":"([A-Z_]+)"/.exec(body)?.[1];
      if (source !== undefined) {
        sources.set(source, (sources.get(source) ?? 0) + 1);
      }
    }
  }
  times.sort((first, second) => first - second);
  rows.push({
    name,
    path,
    status,
    bytes,
    p50: percentile(times, 0.5),
    p95: percentile(times, 0.95),
    max: times[times.length - 1],
    sources: Object.fromEntries(sources),
  });
}

if (args.json === true) {
  console.log(JSON.stringify({ api, requests, warmup, measuredAt: new Date().toISOString(), rows }, null, 2));
} else {
  const widest = Math.max(...rows.map((row) => row.name.length));
  console.log(`${requests} requests per endpoint after ${warmup} warm-up, one at a time, ${new Date().toISOString()}\n`);
  console.log(`${"endpoint".padEnd(widest)}  status    bytes     p50     p95     max  sources`);
  for (const row of rows) {
    console.log(
      `${row.name.padEnd(widest)}  ${String(row.status).padStart(6)}  ${String(row.bytes).padStart(7)}  ${fixed(row.p50)}  ${fixed(row.p95)}  ${fixed(row.max)}  ${JSON.stringify(row.sources)}`,
    );
  }
}

function percentile(sorted, fraction) {
  // Nearest rank: the smallest value that at least `fraction` of the sample is at or below.
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(fraction * sorted.length) - 1));
  return sorted[index];
}

function fixed(milliseconds) {
  return `${milliseconds.toFixed(1)}`.padStart(6) + "ms";
}

function parse(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === "--json") {
      parsed.json = true;
    } else if (argument.startsWith("--")) {
      parsed[argument.slice(2)] = argv[++index];
    }
  }
  return parsed;
}
