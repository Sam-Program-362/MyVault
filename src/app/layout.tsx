import type { Metadata } from "next";
import type { ReactNode } from "react";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { settings } from "@/db/schema";
import { AppDataProvider } from "@/context/AppDataContext";
import "./globals.css";

async function getSettingsSafe() {
  try {
    const rows = await db.select().from(settings).where(eq(settings.id, "singleton")).limit(1);
    return rows[0] ?? null;
  } catch {
    return null;
  }
}

export async function generateMetadata(): Promise<Metadata> {
  const row = await getSettingsSafe();
  const name = row?.appName || "MyVault";
  return {
    title: name,
    description: "Organize prompts, notes and media into searchable spaces.",
  };
}

const FONT_SCALES: Record<string, number> = { sm: 0.9, md: 1, lg: 1.12, xl: 1.26 };
const RADIUS_SCALES: Record<string, string> = {
  none: "0px",
  sm: "0.4rem",
  md: "0.75rem",
  lg: "1.1rem",
  xl: "1.6rem",
};

export default async function RootLayout({ children }: { children: ReactNode }) {
  const row = await getSettingsSafe();
  const accent = row?.accent || "#6366f1";
  const fontScale = FONT_SCALES[row?.fontSize || "md"] ?? 1;
  const radius = RADIUS_SCALES[row?.radius || "lg"] ?? "1.1rem";
  const isDark = row?.mode === "dark";

  return (
    <html lang="en" className={isDark ? "dark" : undefined} style={{ ["--accent" as string]: accent, ["--font-scale" as string]: fontScale, ["--radius-ui" as string]: radius }}>
      <body className="antialiased">
        <AppDataProvider>{children}</AppDataProvider>
      </body>
    </html>
  );
}
