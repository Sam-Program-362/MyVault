import { inArray } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

/** Reject unknown IDs and local tags from another Space before modifying an entry. */
export async function tagsAllowedInSpace(ids: string[], spaceId: string): Promise<boolean> {
  const unique = [...new Set(ids)];
  if (!unique.length) return true;
  const found = await db.select().from(tags).where(inArray(tags.id, unique));
  return found.length === unique.length && found.every((tag) => tag.scope === "global" || (tag.scope === "local" && tag.ownerSpaceId === spaceId));
}
