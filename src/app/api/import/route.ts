import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, settings, spaces, tags } from "@/db/schema";

const SINGLETON_ID = "singleton";

interface ImportTag {
  id?: string;
  name: string;
  color?: string;
  textColor?: string;
}

interface ImportSpace {
  id?: string;
  name: string;
  description?: string | null;
  icon?: string;
  color?: string;
  sortOrder?: number;
}

interface ImportEntry {
  id?: string;
  spaceId?: string;
  title?: string;
  content?: string;
  coverImage?: string | null;
  pinned?: boolean;
  tags?: ImportTag[];
}

interface ImportPayload {
  settings?: Record<string, unknown> | null;
  spaces?: ImportSpace[];
  tags?: ImportTag[];
  entries?: ImportEntry[];
  applySettings?: boolean;
}

export async function POST(request: Request) {
  let body: ImportPayload;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON file" }, { status: 400 });
  }

  if (!body || typeof body !== "object") {
    return NextResponse.json({ error: "Invalid import payload" }, { status: 400 });
  }

  const summary = { spacesAdded: 0, tagsAdded: 0, entriesAdded: 0, settingsApplied: false };

  // --- Tags: match by name (case-insensitive), otherwise create new ---
  const existingTags = await db.select().from(tags);
  const tagIdMap = new Map<string, string>();
  const tagNameToId = new Map(existingTags.map((t) => [t.name.toLowerCase(), t.id]));

  const allImportedTags = [
    ...(body.tags ?? []),
    ...(body.entries ?? []).flatMap((e) => e.tags ?? []),
  ];

  for (const tag of allImportedTags) {
    if (!tag?.name) continue;
    const key = tag.name.toLowerCase();
    if (tagIdMap.has(tag.id ?? tag.name)) continue;
    let targetId = tagNameToId.get(key);
    if (!targetId) {
      targetId = crypto.randomUUID();
      await db.insert(tags).values({
        id: targetId,
        name: tag.name,
        color: tag.color || "#6366f1",
        textColor: tag.textColor || "#ffffff",
      });
      tagNameToId.set(key, targetId);
      summary.tagsAdded += 1;
    }
    if (tag.id) tagIdMap.set(tag.id, targetId);
    tagIdMap.set(tag.name, targetId);
  }

  // --- Spaces: match by name, otherwise create new ---
  const existingSpaces = await db.select().from(spaces);
  const spaceIdMap = new Map<string, string>();
  const spaceNameToId = new Map(existingSpaces.map((s) => [s.name.toLowerCase(), s.id]));
  const maxOrder = existingSpaces.reduce((max, s) => Math.max(max, s.sortOrder), -1);
  let nextOrder = maxOrder + 1;

  for (const space of body.spaces ?? []) {
    if (!space?.name) continue;
    const key = space.name.toLowerCase();
    let targetId = spaceNameToId.get(key);
    if (!targetId) {
      targetId = crypto.randomUUID();
      await db.insert(spaces).values({
        id: targetId,
        name: space.name,
        description: space.description ?? null,
        icon: space.icon || "📁",
        color: space.color || "#6366f1",
        sortOrder: nextOrder++,
      });
      spaceNameToId.set(key, targetId);
      summary.spacesAdded += 1;
    }
    if (space.id) spaceIdMap.set(space.id, targetId);
  }

  // --- Entries: always inserted as new records so nothing is overwritten ---
  let fallbackSpaceId: string | null = null;
  for (const entry of body.entries ?? []) {
    let targetSpaceId = entry.spaceId ? spaceIdMap.get(entry.spaceId) : undefined;
    if (!targetSpaceId) {
      if (!fallbackSpaceId) {
        const [existingFallback] = await db.select().from(spaces).limit(1);
        if (existingFallback) {
          fallbackSpaceId = existingFallback.id;
        } else {
          fallbackSpaceId = crypto.randomUUID();
          await db.insert(spaces).values({
            id: fallbackSpaceId,
            name: "Imported",
            icon: "📥",
            color: "#6366f1",
            sortOrder: nextOrder++,
          });
          summary.spacesAdded += 1;
        }
      }
      targetSpaceId = fallbackSpaceId;
    }

    const newEntryId = crypto.randomUUID();
    await db.insert(entries).values({
      id: newEntryId,
      spaceId: targetSpaceId,
      title: entry.title?.trim() || "Untitled entry",
      content: entry.content ?? "",
      coverImage: entry.coverImage ?? null,
      pinned: Boolean(entry.pinned),
    });
    summary.entriesAdded += 1;

    const entryTagIds = new Set<string>();
    for (const tag of entry.tags ?? []) {
      const mapped = (tag.id && tagIdMap.get(tag.id)) || tagIdMap.get(tag.name);
      if (mapped) entryTagIds.add(mapped);
    }
    if (entryTagIds.size > 0) {
      await db
        .insert(entryTags)
        .values([...entryTagIds].map((tagId) => ({ entryId: newEntryId, tagId })));
    }
  }

  // --- Settings: only applied if explicitly requested ---
  if (body.applySettings && body.settings) {
    const allowed: Record<string, unknown> = {};
    for (const field of [
      "appName",
      "accent",
      "mode",
      "fontSize",
      "radius",
      "wallpaperUrl",
      "wallpaperEnabled",
      "wallpaperOpacity",
      "wallpaperBlur",
    ] as const) {
      if (field in body.settings) allowed[field] = body.settings[field];
    }
    await db
      .update(settings)
      .set({ ...allowed, updatedAt: new Date() })
      .where(eq(settings.id, SINGLETON_ID));
    summary.settingsApplied = true;
  }

  return NextResponse.json({ ok: true, summary });
}
