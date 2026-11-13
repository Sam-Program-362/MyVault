import { expect, test } from "@playwright/test";
import { isTagStagingEnabled } from "@/lib/tag-staging";

test.describe("Temporary Tags feature gate", () => {
  test("defaults off when TAG_STAGING_ENABLED is missing", () => {
    const previous = process.env.TAG_STAGING_ENABLED;
    try {
      delete process.env.TAG_STAGING_ENABLED;
      expect(isTagStagingEnabled()).toBe(false);
    } finally {
      if (previous === undefined) delete process.env.TAG_STAGING_ENABLED;
      else process.env.TAG_STAGING_ENABLED = previous;
    }
  });

  test("enables only for the exact string true and fails closed otherwise", () => {
    for (const value of ["", "false", "TRUE", "True", "1", "yes", " true", "true "]) {
      expect(isTagStagingEnabled(value), `value ${JSON.stringify(value)} must stay disabled`).toBe(false);
    }
    expect(isTagStagingEnabled("true")).toBe(true);
  });
});
