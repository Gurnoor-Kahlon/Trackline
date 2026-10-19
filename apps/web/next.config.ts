import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  typedRoutes: true,
  // A self-contained server with only the files it needs, which is what the container image runs: a few tens of
  // megabytes instead of the whole dependency tree.
  output: "standalone",
};

export default nextConfig;
