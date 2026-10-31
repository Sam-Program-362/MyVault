import { NextResponse } from "next/server";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { entries, entryTags, settings, spaces, tags } from "@/db/schema";

const SINGLETON_ID = "singleton";

export async function GET() {
  const [settingsRow] = await db.select().from(settings).where(eq(settings.id, SINGLETON_ID));
  const spaceRows = await db.select().from(spaces);
  const tagRows = await db.select().from(tags);
  const entryRows = await db.select().from(entries);
  const joinRows = await db.select().from(entryTags);

  const tagsByEntry = new Map<string, typeof tagRows>();
  for (const join of joinRows) {
    const tag = tagRows.find((t) => t.id === join.tagId);
    if (!tag) continue;
    const list = tagsByEntry.get(join.entryId) ?? [];
    list.push(tag);
    tagsByEntry.set(join.entryId, list);
  }

  const payload = {
    version: 2,
    exportedAt: new Date().toISOString(),
    settings: settingsRow ?? null,
    spaces: spaceRows,
    tags: tagRows,
    entries: entryRows.map((entry) => ({
      ...entry,
      tags: tagsByEntry.get(entry.id) ?? [],
    })),
  };

  return NextResponse.json(payload, {
    headers: {
      "Content-Disposition": `attachment; filename="export-${Date.now()}.json"`,
    },
  });
}
