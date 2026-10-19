#!/usr/bin/env node
// A smoke test for a running Trackline: the API, and optionally the web application in front of it.
//
//   node scripts/smoke.mjs --api https://api.example.test --web https://app.example.test
//   node scripts/smoke.mjs --api http://localhost:8080
//
// It asks the questions that decide whether a deployment is usable, and nothing more: is the API up, did it import a
// schedule, is it ingesting right now, does the live stream open, is the production profile really in force, does the web
// application answer and may it call the API. It changes nothing on the server. It needs Node 20 or later and no packages.
//
// Exit status is 0 when every check passed and 1 otherwise, so a pipeline can gate on it.

const options = parse(process.argv.slice(2));
if (options.help || options.api === undefined) {
  console.log(
    "usage: node scripts/smoke.mjs --api <api base url> [--web <web base url>] [--max-age <seconds>] [--allow-dev]\n\n" +
      "  --max-age    how old the newest vehicle feed may be, in seconds (default 120)\n" +
      "  --allow-dev  skip the checks that a production profile must pass (admin import closed, metrics not exposed)",
  );
  process.exit(options.help ? 0 : 2);
}

const api = options.api.replace(/\/+$/, "");
const web = options.web?.replace(/\/+$/, "");
const maxAge = Number(options["max-age"] ?? 120);
const production = options["allow-dev"] !== true;

const results = [];

/** Runs one named check; a thrown error is a failed check with the error as its reason. */
async function check(name, run) {
  const started = performance.now();
  try {
    const detail = await run();
    results.push({ name, ok: true, detail: detail ?? "", ms: performance.now() - started });
  } catch (error) {
    results.push({ name, ok: false, detail: error instanceof Error ? error.message : String(error), ms: performance.now() - started });
  }
}

function expect(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function getJson(path, init) {
  const response = await fetch(`${api}${path}`, { signal: AbortSignal.timeout(15_000), ...init });
  expect(response.ok, `${path} answered ${response.status}`);
  return response.json();
}

await check("API health is UP", async () => {
  const body = await getJson("/actuator/health");
  expect(body.status === "UP", `status is ${body.status}`);
});

await check("a schedule has been imported", async () => {
  const body = await getJson("/api/v1/routes?size=1");
  expect(body.data.totalElements > 0, "no routes: the schedule import has not been run");
  return `${body.data.totalElements} routes`;
});

await check("the schedule can be read in depth (route, shape, stops)", async () => {
  const list = await getJson("/api/v1/routes?size=1&q=501");
  const routeId = list.data.items[0]?.routeId;
  expect(routeId !== undefined, "route 501 is not in the imported schedule");
  const detail = await getJson(`/api/v1/routes/${routeId}`);
  expect(detail.data.directions.length > 0, "the route has no directions");
  const shape = await getJson(`/api/v1/routes/${routeId}/shape`);
  expect(JSON.stringify(shape.data).includes("coordinates"), "the route has no geometry");
  const stops = await getJson(`/api/v1/routes/${routeId}/stops`);
  expect(stops.data.length > 0, "the route has no stops");
  return `route ${routeId}: ${stops.data.length} stops`;
});

await check("the live feed is being ingested and is recent", async () => {
  const body = await getJson("/api/v1/network/status");
  expect(body.meta.source === "LIVE", `source is ${body.meta.source}, not LIVE`);
  expect(body.meta.degraded === false, "the response is degraded");
  expect(body.data.vehiclesTracked > 0, "no vehicles are being tracked");
  expect(body.meta.ageSeconds !== null && body.meta.ageSeconds <= maxAge, `the feed is ${body.meta.ageSeconds} s old (limit ${maxAge} s)`);
  return `${body.data.vehiclesTracked} vehicles on ${body.data.routesReporting} routes, feed ${body.meta.ageSeconds} s old`;
});

await check("route health is being computed", async () => {
  const body = await getJson("/api/v1/health/routes");
  const routes = body.data?.routes ?? body.data?.items ?? body.data;
  expect(Array.isArray(routes) && routes.length > 0, "no route has a health verdict");
  return `${routes.length} routes judged`;
});

await check("service alerts are readable", async () => {
  const body = await getJson("/api/v1/alerts?size=1");
  expect(body.data !== undefined, "no alerts document");
});

await check("the live stream opens and sends a snapshot", async () => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(`${api}/api/v1/stream/vehicles?routes=501`, { signal: controller.signal });
    expect(response.ok, `the stream answered ${response.status}`);
    expect(response.headers.get("content-type")?.startsWith("text/event-stream"), "the content type is not text/event-stream");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let received = "";
    while (!received.includes("\n\n")) {
      const { value, done } = await reader.read();
      expect(!done, "the stream closed before sending anything");
      received += decoder.decode(value, { stream: true });
    }
    expect(/^event:\s*snapshot/m.test(received), "the first event is not a snapshot");
    await reader.cancel();
    return "snapshot received";
  } finally {
    clearTimeout(timer);
    controller.abort();
  }
});

await check("visitors are refused what needs an account (401)", async () => {
  const response = await fetch(`${api}/api/v1/favourites`, { signal: AbortSignal.timeout(15_000) });
  expect(response.status === 401, `/favourites answered ${response.status}, not 401`);
});

if (production) {
  await check("the manual import endpoint is closed", async () => {
    const response = await fetch(`${api}/api/v1/admin/gtfs/import`, { method: "POST", signal: AbortSignal.timeout(15_000) });
    expect([401, 403, 404, 405].includes(response.status), `POST /admin/gtfs/import answered ${response.status}: anyone can start an import`);
  });

  await check("only the health endpoint is exposed", async () => {
    const response = await fetch(`${api}/actuator/metrics`, { signal: AbortSignal.timeout(15_000) });
    expect(response.status === 404 || response.status === 401 || response.status === 403, `/actuator/metrics answered ${response.status}`);
  });
}

if (web !== undefined) {
  await check("the home page answers", async () => {
    const response = await fetch(`${web}/`, { signal: AbortSignal.timeout(20_000) });
    expect(response.ok, `/ answered ${response.status}`);
    const html = await response.text();
    expect(html.includes("Trackline"), "the page does not mention Trackline");
  });

  for (const path of ["/map", "/alerts", "/login"]) {
    await check(`${path} answers`, async () => {
      const response = await fetch(`${web}${path}`, { signal: AbortSignal.timeout(20_000) });
      expect(response.ok, `${path} answered ${response.status}`);
    });
  }

  await check("the API allows the web application's origin, and not a stranger's", async () => {
    const ask = (origin) =>
      fetch(`${api}/api/v1/network/status`, { headers: { Origin: origin }, signal: AbortSignal.timeout(15_000) });
    const own = await ask(web);
    expect(own.headers.get("access-control-allow-origin") === web, `the web origin ${web} is not allowed`);
    const stranger = await ask("https://not-trackline.example");
    expect(stranger.headers.get("access-control-allow-origin") === null, "a stranger's origin is allowed");
  });
}

const widest = Math.max(...results.map((result) => result.name.length));
for (const result of results) {
  const mark = result.ok ? "PASS" : "FAIL";
  console.log(`${mark}  ${result.name.padEnd(widest)}  ${String(Math.round(result.ms)).padStart(5)} ms  ${result.detail}`);
}
const failed = results.filter((result) => !result.ok).length;
console.log(`\n${results.length - failed} of ${results.length} checks passed against ${api}${web === undefined ? "" : ` and ${web}`}`);
process.exit(failed === 0 ? 0 : 1);

function parse(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      parsed.help = true;
    } else if (argument === "--allow-dev") {
      parsed["allow-dev"] = true;
    } else if (argument.startsWith("--")) {
      parsed[argument.slice(2)] = argv[++index];
    }
  }
  return parsed;
}
