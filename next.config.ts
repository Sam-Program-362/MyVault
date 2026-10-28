import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Allow the local browser and Arena's HTTPS preview hosts to load dev assets/HMR.
  allowedDevOrigins: ["127.0.0.1", "*.e2b.app"],
  devIndicators: false,
};

export default nextConfig;
