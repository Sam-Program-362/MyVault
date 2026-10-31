import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { spaces, tags } from "@/db/schema";

interface Params {
  params: Promise<{ id: string }>;
}

export async function PATCH(request: Request, { params }: Params) {
  const { id } = await params;
  const body = await request.json();

  const allowed: Record<string, unknown> = {};
  for (const field of ["name", "description", "icon", "color", "sortOrder"] as const) {
    if (field in body) allowed[field] = body[field];
  }

  const [updated] = await db
    .update(spaces)
    .set(allowed)
    .where(eq(spaces.id, id))
    .returning();

  if (!updated) {
    return NextResponse.json({ error: "Space not found" }, { status: 404 });
  }

  return NextResponse.json(updated);
}

export async function DELETE(_request: Request, { params }: Params) {
  const { id } = await params;
  await db.transaction(async (tx) => {
    await tx.delete(tags).where(eq(tags.ownerSpaceId, id));
    await tx.delete(spaces).where(eq(spaces.id, id));
  });
  return NextResponse.json({ ok: true });
}
