import { NextResponse } from "next/server";
import { asc } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

export async function GET() {
  const rows = await db.select().from(tags).orderBy(asc(tags.name));
  return NextResponse.json(rows);
}

export async function POST(request: Request) {
  const body = await request.json();

  if (!body.name || typeof body.name !== "string") {
    return NextResponse.json({ error: "Tag name is required" }, { status: 400 });
  }

  const name = body.name.trim();
  if (!name) {
    return NextResponse.json({ error: "Tag name is required" }, { status: 400 });
  }

  const existing = await db.select().from(tags);
  const match = existing.find((t) => t.name.toLowerCase() === name.toLowerCase());
  if (match) {
    return NextResponse.json(match);
  }

  const [created] = await db
    .insert(tags)
    .values({
      id: crypto.randomUUID(),
      name,
      color: body.color || "#6366f1",
      textColor: body.textColor || "#ffffff",
      reusable: body.reusable === undefined ? true : Boolean(body.reusable),
    })
    .returning();

  return NextResponse.json(created, { status: 201 });
}
