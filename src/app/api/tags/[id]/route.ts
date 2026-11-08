import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

interface Params { params: Promise<{ id: string }>; }
const IDENTITY_BLOCKER = "Tag name and scope changes are paused until Unicode 15.1 uniqueness is enforced by both Android SQLite and PostgreSQL.";

export async function PATCH(request: Request, { params }: Params) {
  const { id } = await params;
  const body = await request.json();
  if ("name" in body || "scope" in body || "ownerSpaceId" in body) {
    return NextResponse.json({ error: IDENTITY_BLOCKER }, { status: 409 });
  }
  const allowed: Record<string, unknown> = {};
  for (const field of ["color", "textColor", "reusable"] as const) if (field in body) allowed[field] = body[field];
  const [updated] = await db.update(tags).set(allowed).where(eq(tags.id, id)).returning();
  if (!updated) return NextResponse.json({ error: "Tag not found" }, { status: 404 });
  return NextResponse.json(updated);
}

export async function DELETE(_request: Request, { params }: Params) {
  const { id } = await params;
  await db.delete(tags).where(eq(tags.id, id));
  return NextResponse.json({ ok: true });
}
