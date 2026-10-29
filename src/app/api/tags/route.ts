import { NextResponse } from "next/server";
import { asc } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

export async function GET() {
  return NextResponse.json(await db.select().from(tags).orderBy(asc(tags.name)));
}

export async function POST(request: Request) {
  const body = await request.json();
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (!name || !["local", "global"].includes(body.scope) ||
      (body.scope === "local" ? typeof body.ownerSpaceId !== "string" || !body.ownerSpaceId : body.ownerSpaceId != null)) {
    return NextResponse.json({ error: "Enter a name and explicitly choose a valid tag scope." }, { status: 400 });
  }
  try {
    const [created] = await db.insert(tags).values({ id: crypto.randomUUID(), name,
      color: body.color || "#6366f1", textColor: body.textColor || "#ffffff",
      reusable: body.reusable === undefined ? true : Boolean(body.reusable),
      scope: body.scope, ownerSpaceId: body.scope === "local" ? body.ownerSpaceId : null }).returning();
    return NextResponse.json(created, { status: 201 });
  } catch (error) {
    const code = (error as { cause?: { code?: string }; code?: string }).cause?.code ?? (error as { code?: string }).code;
    if (["23505", "23503", "23514", "22P02"].includes(code ?? "")) return NextResponse.json({ error: "Tag name or Space conflicts with an existing tag. Choose a different name or Space." }, { status: 409 });
    console.error("Create tag failed", error);
    return NextResponse.json({ error: "Could not create tag." }, { status: 500 });
  }
}
