import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, tags } from "@/db/schema";
import { tagsAllowedInSpace } from "@/lib/tag-scope";

interface Params {
  params: Promise<{ id: string }>;
}

async function getEntryWithTags(id: string) {
  const [entry] = await db.select().from(entries).where(eq(entries.id, id)).limit(1);
  if (!entry) return null;

  const joined = await db
    .select({ tag: tags })
    .from(entryTags)
    .innerJoin(tags, eq(tags.id, entryTags.tagId))
    .where(eq(entryTags.entryId, id));

  return { ...entry, tags: joined.map((j) => j.tag) };
}

export async function GET(_request: Request, { params }: Params) {
  const { id } = await params;
  const entry = await getEntryWithTags(id);
  if (!entry) return NextResponse.json({ error: "Entry not found" }, { status: 404 });
  return NextResponse.json(entry);
}

export async function PATCH(request: Request, { params }: Params) {
  const { id } = await params;
  const body = await request.json();

  const current = await getEntryWithTags(id);
  if (!current) return NextResponse.json({ error: "Entry not found" }, { status: 404 });
  const spaceId = body.spaceId ?? current.spaceId;
  const tagIds = Array.isArray(body.tagIds) ? body.tagIds : current.tags.map((tag) => tag.id);
  if (typeof spaceId !== "string" || !tagIds.every((tagId: unknown) => typeof tagId === "string") || !(await tagsAllowedInSpace(tagIds, spaceId))) {
    return NextResponse.json({ error: "Tags must belong to this Space or be global." }, { status: 400 });
  }
  const allowed: Record<string, unknown> = {};
  for (const field of ["title", "content", "coverImage", "pinned", "spaceId"] as const) {
    if (field in body) allowed[field] = body[field];
  }
  allowed.updatedAt = new Date();

  const [updated] = await db
    .update(entries)
    .set(allowed)
    .where(eq(entries.id, id))
    .returning();

  if (!updated) {
    return NextResponse.json({ error: "Entry not found" }, { status: 404 });
  }

  if (Array.isArray(body.tagIds)) {
    await db.delete(entryTags).where(eq(entryTags.entryId, id));
    if (body.tagIds.length > 0) {
      await db
        .insert(entryTags)
        .values(body.tagIds.map((tagId: string) => ({ entryId: id, tagId })));
    }
  }

  const entry = await getEntryWithTags(id);
  return NextResponse.json(entry);
}

export async function DELETE(_request: Request, { params }: Params) {
  const { id } = await params;
  await db.delete(entries).where(eq(entries.id, id));
  return NextResponse.json({ ok: true });
}
