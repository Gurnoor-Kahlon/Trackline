#!/usr/bin/env node
// Checks how a running API counts live-stream connections per address, which decides what the stream's per-address limit means
// behind a load balancer.
//
//   node scripts/streams-per-address-check.mjs --api https://api.example.test [--connections 25]
//
// It opens that many streams at once, first each claiming to be a different visitor (X-Forwarded-For), then all claiming
// nothing, and reports how many were admitted each time. Run against a deployment, the answer to the first should be all of
// them, and the answer to the second the API's per-address limit (20 by default). If the first is also capped at 20, the API
// is counting its proxy's address and not its visitors', and the whole site is sharing one allowance: the proxy is not one the API
// trusts to say who a visitor is.
//
// It opens and closes streams, which is all it does, and it is meant for a deployment you run. It is not a load test.

const args = parse(process.argv.slice(2));
if (args.api === undefined) {
  console.log("usage: node scripts/streams-per-address-check.mjs --api <api base url> [--connections 25]");
  process.exit(2);
}
const api = args.api.replace(/\/+$/, "");
const count = Number(args.connections ?? 25);

async function open(forwardedFor) {
  const controller = new AbortController();
  const headers = { Accept: "text/event-stream" };
  if (forwardedFor !== undefined) {
    headers["X-Forwarded-For"] = forwardedFor;
  }
  try {
    const response = await fetch(`${api}/api/v1/stream/network`, { headers, signal: controller.signal });
    return { status: response.status, close: () => controller.abort() };
  } catch (error) {
    return { status: `error: ${error.message}`, close: () => controller.abort() };
  }
}

async function round(label, forwardedFor) {
  // One after another, so each is admitted or refused against the ones already open.
  const opened = [];
  for (let index = 1; index <= count; index++) {
    opened.push(await open(forwardedFor?.(index)));
  }
  const admitted = opened.filter((connection) => connection.status === 200).length;
  const refused = opened.filter((connection) => connection.status === 429).length;
  const other = opened.length - admitted - refused;
  console.log(`${label}: ${count} requested, ${admitted} admitted, ${refused} refused with 429${other > 0 ? `, ${other} other` : ""}`);
  opened.forEach((connection) => connection.close());
  // Closing is noticed by the server on its next write to the stream; give it the time of a heartbeat before the next round.
  await new Promise((resolve) => setTimeout(resolve, 17_000));
  return { admitted, refused };
}

const different = await round("each claiming a different address", (index) => `203.0.113.${index}`);
const same = await round("claiming nothing (counted by where they came from)", undefined);

console.log();
if (different.admitted === count) {
  console.log("PASS  visitors a proxy names are counted separately.");
} else {
  console.log(`FAIL  only ${different.admitted} of ${count} distinct visitors were admitted: the API is counting its proxy's address.`);
}
process.exit(different.admitted === count ? 0 : 1);

function parse(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index++) {
    if (argv[index].startsWith("--")) {
      parsed[argv[index].slice(2)] = argv[++index];
    }
  }
  return parsed;
}
