import { NextResponse } from "next/server";
import { asc, count, eq } from "drizzle-orm";
import { db } from "@/db";
import { entries, spaces } from "@/db/schema";

export async function GET() {
  const rows = await db
    .select({
      id: spaces.id,
      name: spaces.name,
      description: spaces.description,
      icon: spaces.icon,
      color: spaces.color,
      sortOrder: spaces.sortOrder,
      createdAt: spaces.createdAt,
      entryCount: count(entries.id),
    })
    .from(spaces)
    .leftJoin(entries, eq(entries.spaceId, spaces.id))
    .groupBy(spaces.id)
    .orderBy(asc(spaces.sortOrder), asc(spaces.createdAt));

  return NextResponse.json(rows);
}

export async function POST(request: Request) {
  const body = await request.json();

  if (!body.name || typeof body.name !== "string") {
    return NextResponse.json({ error: "Space name is required" }, { status: 400 });
  }

  const maxOrderRows = await db.select().from(spaces);
  const maxOrder = maxOrderRows.reduce((max, s) => Math.max(max, s.sortOrder), -1);

  const [created] = await db
    .insert(spaces)
    .values({
      id: crypto.randomUUID(),
      name: body.name.trim(),
      description: body.description?.trim() || null,
      icon: body.icon || "📁",
      color: body.color || "#6366f1",
      sortOrder: maxOrder + 1,
    })
    .returning();

  return NextResponse.json({ ...created, entryCount: 0 }, { status: 201 });
}
