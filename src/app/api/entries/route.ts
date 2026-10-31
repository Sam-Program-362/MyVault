import { NextResponse } from "next/server";
import { and, desc, eq, inArray, or, ilike } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, tags } from "@/db/schema";
import { tagsAllowedInSpace } from "@/lib/tag-scope";

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
    const [tag] = await db.select().from(tags).where(eq(tags.id, tagId)).limit(1);
    if (!tag || !["local", "global"].includes(tag.scope) ||
        (tag.scope === "local" && (!spaceId || tag.ownerSpaceId !== spaceId)) ||
        (tag.scope === "global" && tag.ownerSpaceId !== null)) return NextResponse.json([]);
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

  const tagIds: string[] = Array.isArray(body.tagIds) ? body.tagIds : [];
  if (!tagIds.every((id) => typeof id === "string") || !(await tagsAllowedInSpace(tagIds, body.spaceId))) {
    return NextResponse.json({ error: "Tags must belong to this Space or be global." }, { status: 400 });
  }
  const id = crypto.randomUUID();
  const [created] = await db.transaction(async (tx) => {
    const [entry] = await tx.insert(entries).values({
      id,
      spaceId: body.spaceId,
      title: body.title?.trim() || "Untitled entry",
      content: body.content ?? "",
      coverImage: body.coverImage || null,
      pinned: Boolean(body.pinned),
    }).returning();
    if (tagIds.length > 0) await tx.insert(entryTags).values(tagIds.map((tagId) => ({ entryId: id, tagId })));
    return [entry];
  });

  const [withTags] = await attachTags([created]);
  return NextResponse.json(withTags, { status: 201 });
}

export const dynamic = "force-dynamic";
