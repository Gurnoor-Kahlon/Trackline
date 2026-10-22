/**
 * The backend the end-to-end tests run against, started and stopped from one place.
 *
 * It is the real application: the packaged API jar, a real PostgreSQL with PostGIS and a real Redis, with the schedule imported
 * through the real importer. Only what lies outside is replaced, by the fixture feed (see fixture-feed/server.mjs): the schedule
 * catalogue and the realtime feeds. So a test exercises ingestion, projection, health, bunching, the stream and the sign-in
 * for real, and nothing it asserts depends on the TTC being up or on what time of day it is.
 *
 * Isolation: its own database in the shared PostgreSQL container, recreated empty on every start, and its own Redis database
 * number, so running the tests never touches the data a developer has in the default ones.
 *
 * Run alone with `node e2e/stack.ts up` and `node e2e/stack.ts down` to look at the stack by hand.
 */
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

// Both Playwright and `node e2e/stack.ts up` run this from apps/web, which is what these paths are relative to. Not
// import.meta: Playwright loads TypeScript as CommonJS here, where it is a syntax error.
const here = path.join(process.cwd(), "e2e");
const repoRoot = path.resolve(here, "../../..");
const apiDir = path.join(repoRoot, "services/api");
const stateFile = path.join(here, ".stack", "pids.json");

export const PORTS = { fixture: 4010, api: 8081, web: 3100 };
export const URLS = {
  api: `http://127.0.0.1:${PORTS.api}`,
  // localhost, not 127.0.0.1: the Next.js development server refuses other origins for its own resources.
  web: `http://localhost:${PORTS.web}`,
  fixture: `http://127.0.0.1:${PORTS.fixture}`,
};

const DATABASE = "trackline_e2e";
const REDIS_DATABASE = "5";

/** Reads the repository's .env if there is one, so a developer's own settings (passwords, ports) are used. */
function dotenv(): Record<string, string> {
  const file = path.join(repoRoot, ".env");
  if (!existsSync(file)) {
    return {};
  }
  const values: Record<string, string> = {};
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (match && match[1] !== undefined && match[2] !== undefined) {
      values[match[1]] = match[2];
    }
  }
  return values;
}

function settings() {
  const env = { ...dotenv(), ...process.env } as Record<string, string | undefined>;
  return {
    pgUser: env.POSTGRES_USER ?? "trackline",
    pgPassword: env.POSTGRES_PASSWORD ?? "",
    pgPort: env.POSTGRES_PORT ?? "5432",
    redisPort: env.REDIS_PORT ?? "6379",
  };
}

function run(command: string, args: string[], cwd = repoRoot): string {
  return execFileSync(command, args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

async function waitFor(what: string, check: () => Promise<boolean>, seconds: number): Promise<void> {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try {
      if (await check()) {
        return;
      }
    } catch {
      // Not up yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out after ${seconds}s waiting for ${what}`);
}

const reachable = (url: string, accept: (body: unknown) => boolean = () => true) => async () => {
  const response = await fetch(url);
  return response.ok && accept(await response.json());
};

function jarPath(): string {
  const jar = path.join(apiDir, "target", "trackline-api-0.1.0-SNAPSHOT.jar");
  if (!existsSync(jar)) {
    throw new Error(`${jar} does not exist. Build it first: (cd services/api && ./mvnw -DskipTests package)`);
  }
  return jar;
}

function apiEnvironment(): NodeJS.ProcessEnv {
  const { pgUser, pgPassword, pgPort, redisPort } = settings();
  return {
    ...process.env,
    SPRING_PROFILES_ACTIVE: "prod",
    POSTGRES_HOST: "127.0.0.1",
    POSTGRES_PORT: pgPort,
    POSTGRES_DB: DATABASE,
    POSTGRES_USER: pgUser,
    POSTGRES_PASSWORD: pgPassword,
    REDIS_HOST: "127.0.0.1",
    REDIS_PORT: redisPort,
    SPRING_DATA_REDIS_DATABASE: REDIS_DATABASE,
    API_PORT: String(PORTS.api),
    TRACKLINE_CORS_ALLOWED_ORIGINS: URLS.web,
    // Fixed for the tests, and meaningless outside them.
    TRACKLINE_AUTH_JWT_SECRET: "end-to-end-tests-only-secret-0123456789abcdef",
    TRACKLINE_AUTH_BCRYPT_COST: "4",
    // The fixture stands in for the TTC.
    TRACKLINE_GTFS_STATIC_CATALOGUE_URL: `${URLS.fixture}/ckan/package_show`,
    TRACKLINE_GTFS_REALTIME_BASE_URL: `${URLS.fixture}/gtfsrt`,
    // Fast polling so a test is not waiting twenty seconds for the next cycle.
    TRACKLINE_GTFS_REALTIME_VEHICLE_INTERVAL: "2s",
    TRACKLINE_GTFS_REALTIME_TRIP_UPDATE_INTERVAL: "5s",
    TRACKLINE_GTFS_REALTIME_ALERT_INTERVAL: "5s",
    TRACKLINE_HISTORY_ENABLED: "false",
    // The tests are one visitor making hundreds of requests a minute, which is what the rate limit exists to refuse. It has its own
    // tests; here it would only make the others fail for a reason that is not what they are about.
    TRACKLINE_RATE_LIMIT_ENABLED: "false",
  };
}

function remember(processes: Record<string, number | undefined>): void {
  mkdirSync(path.dirname(stateFile), { recursive: true });
  writeFileSync(stateFile, JSON.stringify(processes));
}

/**
 * Starts a process that outlives this one, with its output in a file under .stack/ for when a test fails and the question
 * is what the application said. Detached, so stopping the tests (or running `up` by hand) does not take it down with them.
 */
function spawnLogged(name: string, command: string, args: string[], env: NodeJS.ProcessEnv, cwd: string): ChildProcess {
  const logDir = path.join(here, ".stack");
  mkdirSync(logDir, { recursive: true });
  const log = openSync(path.join(logDir, `${name}.log`), "w");
  const child = spawn(command, args, { cwd, env, detached: true, stdio: ["ignore", log, log] });
  closeSync(log);
  child.unref();
  return child;
}

/** Starts everything and returns once the application is serving live data. */
export async function startStack(): Promise<() => Promise<void>> {
  const { pgUser } = settings();

  // 1. Infrastructure. The containers are the ones the project's compose file defines.
  run("docker", ["compose", "up", "-d", "--wait", "postgres", "redis"]);
  const psql = (sql: string) =>
    run("docker", ["compose", "exec", "-T", "postgres", "psql", "-U", pgUser, "-d", "postgres", "-c", sql]);
  psql(`DROP DATABASE IF EXISTS ${DATABASE} WITH (FORCE)`);
  psql(`CREATE DATABASE ${DATABASE}`);

  // 2. The stand-in for the TTC.
  const fixture = spawnLogged(
    "fixture",
    process.execPath,
    [path.join(here, "fixture-feed", "server.mjs")],
    { ...process.env, FIXTURE_PORT: String(PORTS.fixture), API_URL: URLS.api },
    here,
  );
  remember({ fixture: fixture.pid });
  await waitFor("the fixture feed", reachable(`${URLS.fixture}/health`), 20);

  // 3. The schedule, through the real importer, which exits when it is done.
  const jar = jarPath();
  try {
    execFileSync("java", ["-jar", jar, "--import-gtfs"], {
      cwd: apiDir,
      env: apiEnvironment(),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 180_000,
    });
  } catch (cause) {
    stopRemembered();
    const output = cause as { stdout?: string; stderr?: string };
    throw new Error(`The schedule import failed:
${output.stdout ?? ""}
${output.stderr ?? String(cause)}`);
  }

  // 4. The application.
  const api = spawnLogged("api", "java", ["-jar", jar], apiEnvironment(), apiDir);
  remember({ fixture: fixture.pid, api: api.pid });
  await waitFor("the API", reachable(`${URLS.api}/actuator/health`, (body) => (body as { status?: string }).status === "UP"), 90);

  // 5. Wait until a vehicle cycle has run and the fleet is in, so no test starts against an empty map.
  await waitFor(
    "live vehicles",
    reachable(`${URLS.api}/api/v1/network/status`, (body) => {
      return ((body as { data?: { vehiclesTracked?: number } }).data?.vehiclesTracked ?? 0) > 0;
    }),
    60,
  );

  return async () => {
    stopRemembered();
  };
}

/** Stops what a previous `up` left running. */
export function stopRemembered(): void {
  if (!existsSync(stateFile)) {
    return;
  }
  const processes = JSON.parse(readFileSync(stateFile, "utf8")) as Record<string, number | undefined>;
  for (const pid of Object.values(processes)) {
    if (pid !== undefined) {
      try {
        process.kill(pid);
      } catch {
        // Already gone.
      }
    }
  }
  rmSync(stateFile, { force: true });
}

async function main(command: string | undefined): Promise<void> {
  if (command === "up") {
    stopRemembered();
    await startStack();
    console.log(`up: api ${URLS.api}, fixture ${URLS.fixture}. Stop with: node e2e/stack.ts down`);
    // The processes were started by this one and are detached; leave them running after it exits.
    process.exit(0);
  } else if (command === "down") {
    stopRemembered();
  } else {
    console.error("usage (from apps/web): node e2e/stack.ts up | down");
    process.exit(2);
  }
}

if (process.argv[1] !== undefined && path.basename(process.argv[1]) === "stack.ts") {
  void main(process.argv[2]);
}
