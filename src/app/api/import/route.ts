import { NextResponse } from "next/server";
import { eq, sql } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, settings, spaces, tags } from "@/db/schema";

type ImportTag = { id: string; name: string; color?: string; textColor?: string; reusable?: boolean; scope?: string; ownerSpaceId?: string | null };
type ImportSpace = { id: string; name: string; description?: string | null; icon?: string; color?: string; sortOrder?: number };
type ImportEntry = { id: string; spaceId: string; title?: string; content?: string; coverImage?: string | null; pinned?: boolean; tags?: ImportTag[] };
type ImportPayload = { version?: number; settings?: Record<string, unknown> | null; spaces?: ImportSpace[]; tags?: ImportTag[]; entries?: ImportEntry[]; applySettings?: boolean };

class InvalidBackup extends Error {}
const fail = (message: string): never => { throw new InvalidBackup(message); };
const normalizeTag = (tag: ImportTag) => ({
  ...tag, scope: tag.scope ?? "global", ownerSpaceId: tag.ownerSpaceId ?? null,
  color: tag.color ?? "#6366f1", textColor: tag.textColor ?? "#ffffff", reusable: tag.reusable ?? true,
});

export async function POST(request: Request) {
  try {
    const raw = await request.text();
    if (raw.length > 128 * 1024 * 1024) fail("Backup exceeds the 128 MB limit.");
    let body: ImportPayload;
    try { body = JSON.parse(raw); } catch { return NextResponse.json({ error: "Invalid JSON file" }, { status: 400 }); }
    if (!body || typeof body !== "object" || Array.isArray(body)) fail("Invalid import payload.");
    const version = body.version ?? 1;
    if (version !== 1 && version !== 2) fail("Unsupported backup version.");
    if (version === 1) {
      // Older web backups allowed missing IDs. Resolve missing tag IDs by name,
      // as the legacy importer did, without inventing a Space attachment.
      if (Array.isArray(body.spaces)) body.spaces = body.spaces.map((space, index) => ({ ...space, id: space?.id || `legacy-space-${index}` }));
      if (Array.isArray(body.entries)) body.entries = body.entries.map((entry, index) => ({ ...entry, id: entry?.id || `legacy-entry-${index}`, spaceId: entry?.spaceId || "" }));
      const all = [...(Array.isArray(body.tags) ? body.tags : []), ...(Array.isArray(body.entries) ? body.entries.flatMap((e) => Array.isArray(e.tags) ? e.tags : []) : [])];
      const ids = new Map<string, Set<string>>();
      for (const tag of all) if (tag && typeof tag.name === "string" && tag.id) {
        const key = tag.name.toLowerCase();
        const matches = ids.get(key) ?? new Set<string>();
        matches.add(tag.id);
        ids.set(key, matches);
      }
      const fix = (tag: ImportTag) => {
        if (!tag || typeof tag.name !== "string" || tag.id) return tag;
        const matches = ids.get(tag.name.toLowerCase());
        if (matches && matches.size > 1) fail(`Ambiguous legacy tag ${tag.name} has no ID; cannot identify its relationship.`);
        return { ...tag, id: matches?.values().next().value || `legacy-name:${tag.name.toLowerCase()}` };
      };
      if (Array.isArray(body.tags)) body.tags = body.tags.map(fix);
      if (Array.isArray(body.entries)) body.entries = body.entries.map((entry) => ({ ...entry, tags: Array.isArray(entry.tags) ? entry.tags.map(fix) : entry.tags }));
    }
    if (![body.spaces, body.tags, body.entries].every((v) => v === undefined || Array.isArray(v))) fail("Invalid record lists.");
    const inputSpaces = body.spaces ?? [];
    const inputEntries = body.entries ?? [];
    const definitions = new Map<string, ReturnType<typeof normalizeTag>>();
    const sourceSpaces = new Set<string>();
    for (const space of inputSpaces) {
      if (!space || typeof space.id !== "string" || !space.id || typeof space.name !== "string" || !space.name.trim() || sourceSpaces.has(space.id)) fail("Invalid or duplicate Space.");
      sourceSpaces.add(space.id);
    }
    if (new Set(inputSpaces.map((s) => s.name.toLowerCase())).size !== inputSpaces.length) fail("Ambiguous Space names.");
    for (const entry of inputEntries) {
      if (!entry || typeof entry.id !== "string" || typeof entry.spaceId !== "string" || (entry.tags !== undefined && !Array.isArray(entry.tags))) fail("Invalid entry.");
    }
    for (const original of [...(body.tags ?? []), ...inputEntries.flatMap((entry) => entry.tags ?? [])]) {
      if (!original || typeof original.id !== "string" || !original.id || typeof original.name !== "string" || !original.name.trim()) fail("Invalid tag.");
      const tag = normalizeTag(original);
      if ((tag.scope !== "global" && tag.scope !== "local") || (version === 1 && tag.scope !== "global")) fail("Invalid tag scope.");
      if (tag.scope === "global" ? tag.ownerSpaceId !== null : !tag.ownerSpaceId || !sourceSpaces.has(tag.ownerSpaceId)) fail(`Invalid owner for tag ${tag.name}.`);
      const previous = definitions.get(tag.id);
      if (previous && (previous.name !== tag.name || previous.scope !== tag.scope || previous.ownerSpaceId !== tag.ownerSpaceId || previous.color !== tag.color || previous.textColor !== tag.textColor || previous.reusable !== tag.reusable)) fail(`Conflicting definitions for tag ${tag.name}.`);
      definitions.set(tag.id, tag);
    }
    for (const entry of inputEntries) for (const sourceTag of entry.tags ?? []) {
      const tag = definitions.get(sourceTag.id);
      if (!tag) throw new InvalidBackup(`Entry refers to undeclared tag ID ${sourceTag.id}.`);
      if (tag.scope === "local" && tag.ownerSpaceId !== entry.spaceId) fail(`Tag ${tag.name} belongs to another Space.`);
    }
    const summary = await db.transaction(async (tx) => {
      // Serialize concurrent imports; existing user records are never overwritten.
      await tx.execute(sql`SELECT pg_advisory_xact_lock(1836674164)`);
      const result = { spacesAdded: 0, tagsAdded: 0, entriesAdded: 0, settingsApplied: false };
      const existingSpaces = await tx.select().from(spaces);
      const spaceMap = new Map<string, string>();
      let nextOrder = existingSpaces.reduce((max, space) => Math.max(max, space.sortOrder), -1) + 1;
      for (const space of inputSpaces) {
        const matches = existingSpaces.filter((s) => s.name.toLowerCase() === space.name.toLowerCase());
        if (matches.length > 1) fail(`Ambiguous existing Space ${space.name}.`);
        let existing = matches[0];
        if (!existing) {
          [existing] = await tx.insert(spaces).values({ id: crypto.randomUUID(), name: space.name, description: space.description ?? null, icon: space.icon || "📁", color: space.color || "#6366f1", sortOrder: nextOrder++ }).returning();
          existingSpaces.push(existing);
          result.spacesAdded++;
        }
        spaceMap.set(space.id, existing.id);
      }
      const currentTags = await tx.select().from(tags);
      const tagMap = new Map<string, string>();
      for (const tag of definitions.values()) {
        const match = currentTags.find((candidate) => candidate.id === tag.id);
        if (!match) throw new InvalidBackup("Import of new tag identities is paused until Unicode 15.1 database enforcement is available.");
        const owner = tag.scope === "local" ? spaceMap.get(tag.ownerSpaceId!) : null;
        if (owner !== tag.ownerSpaceId || match.name !== tag.name || match.scope !== tag.scope ||
            match.ownerSpaceId !== tag.ownerSpaceId || match.color !== tag.color ||
            match.textColor !== tag.textColor || match.reusable !== tag.reusable) {
          fail(`Imported tag ${tag.name} conflicts with existing ID ${tag.id}; no tag was changed.`);
        }
        tagMap.set(tag.id, tag.id);
      }
      let fallback: string | undefined;
      for (const entry of inputEntries) {
        let owner = spaceMap.get(entry.spaceId);
        if (!owner) {
          if (version === 2) fail("An entry refers to a missing Space.");
          if (!fallback) {
            fallback = existingSpaces[0]?.id;
            if (!fallback) {
              fallback = crypto.randomUUID();
              await tx.insert(spaces).values({ id: fallback, name: "Imported", icon: "📥", sortOrder: nextOrder++ });
              result.spacesAdded++;
            }
          }
          owner = fallback;
        }
        const id = crypto.randomUUID();
        await tx.insert(entries).values({ id, spaceId: owner, title: entry.title?.trim() || "Untitled entry", content: entry.content ?? "", coverImage: entry.coverImage ?? null, pinned: Boolean(entry.pinned) });
        const ids = [...new Set((entry.tags ?? []).map((tag) => tagMap.get(tag.id)).filter((id): id is string => !!id))];
        if (ids.length) await tx.insert(entryTags).values(ids.map((tagId) => ({ entryId: id, tagId })));
        result.entriesAdded++;
      }
      if (body.applySettings && body.settings) {
        const allowed: Record<string, unknown> = {};
        for (const key of ["appName", "accent", "mode", "fontSize", "radius", "wallpaperUrl", "wallpaperEnabled", "wallpaperOpacity", "wallpaperBlur"]) if (key in body.settings) allowed[key] = body.settings[key];
        await tx.update(settings).set({ ...allowed, updatedAt: new Date() }).where(eq(settings.id, "singleton"));
        result.settingsApplied = true;
      }
      return result;
    });
    return NextResponse.json({ ok: true, summary });
  } catch (error) {
    if (error instanceof InvalidBackup) return NextResponse.json({ error: error.message }, { status: 400 });
    console.error("Backup import failed", error);
    return NextResponse.json({ error: "Import failed; no database changes were committed." }, { status: 500 });
  }
}
