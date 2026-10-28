"use client";

import { useAppData } from "@/context/AppDataContext";

export default function Wallpaper({ splash = false }: { splash?: boolean }) {
  const { settings } = useAppData();

  if (!settings?.wallpaperEnabled || !settings.wallpaperUrl) {
    return (
      <div
        aria-hidden
        className={`default-wallpaper pointer-events-none absolute inset-0 overflow-hidden ${splash ? "default-wallpaper-splash" : ""}`}
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
      <div className={`absolute inset-0 ${splash ? "bg-neutral-950/40" : "bg-white/30 dark:bg-neutral-950/40"}`} />
    </div>
  );
}
