import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { settings } from "@/db/schema";

const SINGLETON_ID = "singleton";

async function ensureSettings() {
  const existing = await db
    .select()
    .from(settings)
    .where(eq(settings.id, SINGLETON_ID))
    .limit(1);

  if (existing.length > 0) return existing[0];

  const [created] = await db
    .insert(settings)
    .values({ id: SINGLETON_ID })
    .returning();
  return created;
}

export async function GET() {
  const row = await ensureSettings();
  return NextResponse.json(row);
}

export async function PATCH(request: Request) {
  const body = await request.json();
  await ensureSettings();

  const allowed: Record<string, unknown> = {};
  const fields = [
    "appName",
    "accent",
    "mode",
    "fontSize",
    "radius",
    "wallpaperUrl",
    "wallpaperEnabled",
    "wallpaperOpacity",
    "wallpaperBlur",
  ] as const;

  for (const field of fields) {
    if (field in body) allowed[field] = body[field];
  }

  const [updated] = await db
    .update(settings)
    .set({ ...allowed, updatedAt: new Date() })
    .where(eq(settings.id, SINGLETON_ID))
    .returning();

  return NextResponse.json(updated);
}
