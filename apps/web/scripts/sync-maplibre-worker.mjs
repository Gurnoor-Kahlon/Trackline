// Copies MapLibre's web worker into public/ so the browser can fetch it.
//
// MapLibre works out where its worker lives from `import.meta.url` at runtime.
// Under a bundler that is the URL of the chunk MapLibre was bundled into, so it
// asks for `/_next/static/chunks/maplibre-gl-worker.mjs`, which does not exist.
// The dev server answers a 404 with an HTML page, the browser refuses it on MIME
// grounds, and the map renders no tiles at all while logging only "Worker failed
// to load" — a failure that is easy to mistake for a broken style URL.
//
// Copying rather than committing the file keeps it pinned to whichever version
// of maplibre-gl is installed: it is regenerated before every dev run and build,
// and ignored by git, so it cannot drift from package.json.

import { copyFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const projectRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

// The worker and the module it imports. Copying only the worker leaves it
// loading a relative import that is not there, which fails in exactly the same
// way as not copying it at all.
const FILES = ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"];

// Resolved through package.json rather than assembled as a path, so an upstream
// change to the dist layout fails here instead of producing a silent 404.
const distDirectory = join(dirname(require.resolve("maplibre-gl/package.json")), "dist");
const outputDirectory = join(projectRoot, "public", "vendor");

await mkdir(outputDirectory, { recursive: true });
for (const file of FILES) {
  await copyFile(join(distDirectory, file), join(outputDirectory, file));
}
