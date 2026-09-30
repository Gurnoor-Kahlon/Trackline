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
    ignores: [".next/**", "node_modules/**", "next-env.d.ts"],
  },
  {
    rules: {
      "no-console": ["warn", { allow: ["warn", "error"] }],
    },
  },
];

export default config;
