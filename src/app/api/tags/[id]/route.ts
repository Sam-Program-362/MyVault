import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

interface Params {
  params: Promise<{ id: string }>;
}

export async function PATCH(request: Request, { params }: Params) {
  const { id } = await params;
  const body = await request.json();

  const allowed: Record<string, unknown> = {};
  for (const field of ["name", "color", "textColor", "reusable"] as const) {
    if (field in body) allowed[field] = body[field];
  }

  const [updated] = await db
    .update(tags)
    .set(allowed)
    .where(eq(tags.id, id))
    .returning();

  if (!updated) {
    return NextResponse.json({ error: "Tag not found" }, { status: 404 });
  }

  return NextResponse.json(updated);
}

export async function DELETE(_request: Request, { params }: Params) {
  const { id } = await params;
  await db.delete(tags).where(eq(tags.id, id));
  return NextResponse.json({ ok: true });
}
