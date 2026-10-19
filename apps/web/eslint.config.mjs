import coreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

/**
 * eslint-config-next brings the React, hooks and jsx-a11y rule sets, so an
 * accessibility mistake such as a missing label or a click handler on a
 * non-interactive element fails lint rather than waiting for review.
 *
 * These entry points are already flat configs, so no eslintrc compatibility
 * shim is involved.
 */
const config = [
  ...coreWebVitals,
  ...nextTypescript,
  {
    // public/vendor holds MapLibre's worker, copied verbatim from
    // node_modules before each run. It is third-party build output, so
    // linting it reports hundreds of warnings about code we do not own.
    ignores: [".next/**", "node_modules/**", "next-env.d.ts", "public/vendor/**"],
  },
  {
    rules: {
      "no-console": ["warn", { allow: ["warn", "error"] }],
    },
  },
  {
    // The end-to-end stack and the fixture feed are command-line programs: what they print is their interface.
    files: ["e2e/**"],
    rules: { "no-console": "off" },
  },
];

export default config;
