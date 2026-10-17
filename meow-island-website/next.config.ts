import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // three / drei ship ESM that Next should transpile for the client bundle.
  transpilePackages: ["three"],
};

export default nextConfig;
