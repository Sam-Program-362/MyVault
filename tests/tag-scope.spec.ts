import { expect, test } from "@playwright/test";
import type { Tag } from "@/lib/types";
import { tagVisibleInSpace, tagsVisibleInSpace } from "@/lib/tag-visibility";

const tag = (id: string, name: string, scope: "global" | "local", ownerSpaceId: string | null, reusable: boolean): Tag => ({
  id,
  name,
  scope,
  ownerSpaceId,
  color: "#6366f1",
  textColor: "#ffffff",
  reusable,
  createdAt: "2026-10-31T00:00:00.000Z",
});

test("scope visibility includes Global and only current-Space Local tags; reusable is independent", () => {
  const global = tag("global", "Ideas", "global", null, false);
  const localA = tag("local-a", "Ideas", "local", "space-a", true);
  const localB = tag("local-b", "Ideas", "local", "space-b", false);
  const tags = [global, localA, localB];

  expect(tagsVisibleInSpace(tags, "space-a").map(({ id }) => id)).toEqual(["global", "local-a"]);
  expect(tagsVisibleInSpace(tags, "space-b").map(({ id }) => id)).toEqual(["global", "local-b"]);
  expect(tagsVisibleInSpace(tags, null).map(({ id }) => id)).toEqual(["global"]);
  expect(tagsVisibleInSpace(tags, "space-a", true).map(({ id }) => id)).toEqual(["global"]);
  expect(tagVisibleInSpace(localA, "space-a")).toBe(true);
});
