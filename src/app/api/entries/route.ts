import { NextResponse } from "next/server";
import { and, desc, eq, inArray, or, ilike } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, tags } from "@/db/schema";

type TagRow = typeof tags.$inferSelect;

async function attachTags<T extends { id: string }>(rows: T[]) {
  if (rows.length === 0) return rows.map((r) => ({ ...r, tags: [] as TagRow[] }));

  const ids = rows.map((r) => r.id);
  const joined = await db
    .select({
      entryId: entryTags.entryId,
      tag: tags,
    })
    .from(entryTags)
    .innerJoin(tags, eq(tags.id, entryTags.tagId))
    .where(inArray(entryTags.entryId, ids));

  const map = new Map<string, TagRow[]>();
  for (const row of joined) {
    const list = map.get(row.entryId) ?? [];
    list.push(row.tag);
    map.set(row.entryId, list);
  }

  return rows.map((r) => ({ ...r, tags: map.get(r.id) ?? [] }));
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const spaceId = searchParams.get("spaceId");
  const search = searchParams.get("q")?.trim();
  const tagId = searchParams.get("tagId");

  const conditions = [];
  if (spaceId) conditions.push(eq(entries.spaceId, spaceId));
  if (search) {
    conditions.push(
      or(ilike(entries.title, `%${search}%`), ilike(entries.content, `%${search}%`)),
    );
  }

  let entryIdsWithTag: string[] | null = null;
  if (tagId) {
    const rows = await db
      .select({ entryId: entryTags.entryId })
      .from(entryTags)
      .where(eq(entryTags.tagId, tagId));
    entryIdsWithTag = rows.map((r) => r.entryId);
    if (entryIdsWithTag.length === 0) return NextResponse.json([]);
    conditions.push(inArray(entries.id, entryIdsWithTag));
  }

  const rows = await db
    .select()
    .from(entries)
    .where(conditions.length ? and(...conditions) : undefined)
    .orderBy(desc(entries.pinned), desc(entries.updatedAt));

  const withTags = await attachTags(rows);
  return NextResponse.json(withTags);
}

export async function POST(request: Request) {
  const body = await request.json();

  if (!body.spaceId) {
    return NextResponse.json({ error: "spaceId is required" }, { status: 400 });
  }

  const id = crypto.randomUUID();
  const [created] = await db
    .insert(entries)
    .values({
      id,
      spaceId: body.spaceId,
      title: body.title?.trim() || "Untitled entry",
      content: body.content ?? "",
      coverImage: body.coverImage || null,
      pinned: Boolean(body.pinned),
    })
    .returning();

  const tagIds: string[] = Array.isArray(body.tagIds) ? body.tagIds : [];
  if (tagIds.length > 0) {
    await db.insert(entryTags).values(tagIds.map((tagId) => ({ entryId: id, tagId })));
  }

  const [withTags] = await attachTags([created]);
  return NextResponse.json(withTags, { status: 201 });
}

export const dynamic = "force-dynamic";
