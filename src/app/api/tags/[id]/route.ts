import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

interface Params { params: Promise<{ id: string }>; }

export async function PATCH(request: Request, { params }: Params) {
  const { id } = await params;
  const body = await request.json();
  if ("name" in body && (typeof body.name !== "string" || !body.name.trim())) return NextResponse.json({ error: "A tag name is required." }, { status: 400 });
  if ("scope" in body && !["global", "local"].includes(body.scope)) return NextResponse.json({ error: "Invalid tag scope." }, { status: 400 });
  const allowed: Record<string, unknown> = {};
  for (const field of ["name", "color", "textColor", "reusable", "scope", "ownerSpaceId"] as const) if (field in body) allowed[field] = field === "name" ? body.name.trim() : body[field];
  try {
    const [updated] = await db.update(tags).set(allowed).where(eq(tags.id, id)).returning();
    if (!updated) return NextResponse.json({ error: "Tag not found" }, { status: 404 });
    return NextResponse.json(updated);
  } catch (error) {
    const code = (error as { cause?: { code?: string }; code?: string }).cause?.code ?? (error as { code?: string }).code;
    if (["23505", "23503", "23514", "22P02"].includes(code ?? "")) return NextResponse.json({ error: "This change conflicts with another tag or its existing entry associations." }, { status: 409 });
    console.error("Update tag failed", error);
    return NextResponse.json({ error: "Could not update tag." }, { status: 500 });
  }
}

export async function DELETE(_request: Request, { params }: Params) {
  const { id } = await params;
  await db.delete(tags).where(eq(tags.id, id));
  return NextResponse.json({ ok: true });
}
