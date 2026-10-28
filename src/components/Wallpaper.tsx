"use client";

import { useAppData } from "@/context/AppDataContext";

export default function Wallpaper() {
  const { settings } = useAppData();

  if (!settings?.wallpaperEnabled || !settings.wallpaperUrl) {
    return (
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 overflow-hidden"
        style={{
          background:
            "radial-gradient(circle at 20% 20%, color-mix(in srgb, var(--accent) 35%, transparent), transparent 55%), radial-gradient(circle at 80% 0%, color-mix(in srgb, var(--accent) 25%, transparent), transparent 50%), #0b0d14",
        }}
      />
    );
  }

  const opacity = (settings.wallpaperOpacity ?? 35) / 100;
  const blur = settings.wallpaperBlur ?? 0;

  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
      <div
        className="absolute inset-0 bg-cover bg-center"
        style={{
          backgroundImage: `url(${settings.wallpaperUrl})`,
          opacity,
          filter: blur ? `blur(${blur}px)` : undefined,
          transform: blur ? "scale(1.08)" : undefined,
        }}
      />
      <div className="absolute inset-0 bg-slate-950/40" />
    </div>
  );
}
