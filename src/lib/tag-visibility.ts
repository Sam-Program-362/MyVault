import type { Tag } from "@/lib/types";

/** Legacy rows without scope are global. Local tags are usable only in their owner Space. */
export function tagVisibleInSpace(tag: Tag, spaceId: string | null, allSpaces = false): boolean {
  const scope = tag.scope ?? "global";
  if (scope === "global") return tag.ownerSpaceId == null;
  return scope === "local" && !allSpaces && spaceId !== null && tag.ownerSpaceId === spaceId;
}

export function tagsVisibleInSpace(tags: Tag[], spaceId: string | null, allSpaces = false): Tag[] {
  return tags.filter((tag) => tagVisibleInSpace(tag, spaceId, allSpaces));
}
