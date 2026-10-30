import { expect, test, type Page } from "@playwright/test";

const now = "2026-10-28T00:00:00.000Z";
const reusableTag = { id: "tag-1", name: "Ideas", color: "#8b5cf6", textColor: "#ffffff", reusable: true, createdAt: now };

type TestEntry = {
  id: string;
  spaceId: string;
  title: string;
  content: string;
  coverImage: string | null;
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
  tags: typeof reusableTag[];
};

async function mockVault(page: Page, mode: "light" | "dark" = "dark", entriesListDelay = 0) {
  const settings = {
    id: "singleton", appName: "MyVault", accent: "#6366f1", mode, fontSize: "md", radius: "lg",
    wallpaperUrl: null, wallpaperEnabled: false, wallpaperOpacity: 35, wallpaperBlur: 0, updatedAt: now,
  };
  const spaces = [{ id: "space-1", name: "Notes", description: null, icon: "📝", color: "#6366f1", sortOrder: 0, createdAt: now, entryCount: 0 }];
  const entries: TestEntry[] = [];
  const tags = [reusableTag];
  const patches: Record<string, unknown>[] = [];
  let draftFailuresRemaining = 0;
  let failedField: string | null = null;
  let fieldFailuresRemaining = 0;
  let uploadDelay = 0;
  let deleteFailuresRemaining = 0;
  let patchDelay = 80;

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value });
    if (pathname === "/api/settings") {
      if (method === "PATCH") Object.assign(settings, request.postDataJSON());
      return json(settings);
    }
    if (pathname === "/api/spaces") return json(spaces);
    if (pathname === "/api/tags") {
      if (method === "POST") return json({ error: "Unicode 15.1 tag identity is not yet enforceable on both databases." }, 409);
      return json(tags);
    }
    if (pathname === "/api/entries") {
      if (method === "POST") {
        const entry: TestEntry = {
          id: `entry-${entries.length + 1}`, spaceId: "space-1", title: "Untitled entry", content: "",
          coverImage: null, pinned: false, createdAt: now, updatedAt: now, tags: [],
          ...request.postDataJSON(),
        };
        entries.unshift(entry);
        return json(entry, 201);
      }
      if (entriesListDelay) await new Promise((resolve) => setTimeout(resolve, entriesListDelay));
      return json(entries);
    }
    if (pathname.startsWith("/api/entries/")) {
      const entry = entries.find((item) => item.id === pathname.split("/").at(-1));
      if (!entry) return json({ error: "Not found" }, 404);
      if (method === "PATCH") {
        const patch = request.postDataJSON() as Record<string, unknown>;
        patches.push(patch);
        // Exercise closing while a save is still in flight.
        await new Promise((resolve) => setTimeout(resolve, patchDelay));
        if (draftFailuresRemaining > 0 && "content" in patch) {
          draftFailuresRemaining -= 1;
          return json({ error: "Temporary save failure" }, 500);
        }
        if (failedField && fieldFailuresRemaining > 0 && failedField in patch) {
          fieldFailuresRemaining -= 1;
          return json({ error: "Temporary save failure" }, 500);
        }
        if (Array.isArray(patch.tagIds)) entry.tags = tags.filter((tag) => (patch.tagIds as string[]).includes(tag.id));
        const { tagIds: _tagIds, ...fields } = patch;
        Object.assign(entry, fields);
      }
      if (method === "DELETE") {
        if (deleteFailuresRemaining > 0) {
          deleteFailuresRemaining -= 1;
          return json({ error: "Temporary delete failure" }, 500);
        }
        entries.splice(entries.indexOf(entry), 1);
        return json({ ok: true });
      }
      return json(entry);
    }
    if (pathname === "/api/upload") {
      if (uploadDelay) await new Promise((resolve) => setTimeout(resolve, uploadDelay));
      return json({ url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL6WQAAAABJRU5ErkJggg==" }, 201);
    }
    return json({ error: `Unexpected ${method} ${pathname}` }, 404);
  });
  return {
    entries, patches, settings, tags,
    failDrafts: (count: number) => { draftFailuresRemaining = count; },
    failField: (field: string, count: number) => { failedField = field; fieldFailuresRemaining = count; },
    delayUpload: (milliseconds: number) => { uploadDelay = milliseconds; },
    failDeletes: (count: number) => { deleteFailuresRemaining = count; },
    delayPatches: (milliseconds: number) => { patchDelay = milliseconds; },
  };
}

async function openNewEntry(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: /Notes/ }).first().click();
  await page.getByRole("button", { name: "New entry" }).click();
  await expect(page.getByLabel("Entry content")).toBeVisible();
}

test.describe("phone writing experience", () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test("keeps the light dashboard readable and its header compact", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await mockVault(page, "light");
    await page.goto("/");
    await page.getByRole("button", { name: /Notes/ }).first().click();
    await expect(page.locator(".default-wallpaper")).toHaveCSS("background-color", "rgb(246, 245, 242)");
    const header = page.locator(".app-header");
    expect((await header.boundingBox())!.height).toBeLessThan(70);
    for (const control of await header.locator(".app-brand, .app-search, .app-all-spaces, .app-settings").all()) {
      const box = await control.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(320);
      expect(box!.height).toBeGreaterThanOrEqual(44);
    }
  });

  test("uses the whole page and opens a right-hand preview without losing the draft", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    const editor = page.locator(".entry-editor");
    const content = page.getByLabel("Entry content");
    const editorBox = await editor.boundingBox();
    const contentBox = await content.boundingBox();
    expect(editorBox).toMatchObject({ x: 0, y: 0, width: 390, height: 844 });
    expect(contentBox?.width).toBe(390);
    expect(contentBox?.height).toBeGreaterThan(500);
    await expect(editor).toHaveCSS("background-color", "rgb(25, 25, 24)");
    await expect(content).toHaveCSS("border-top-width", "0px");

    const toolbarButtons = page.locator(".editor-toolbar button");
    for (const button of await toolbarButtons.all()) {
      const box = await button.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
      expect(box!.width).toBeGreaterThanOrEqual(44);
      expect(box!.height).toBeGreaterThanOrEqual(44);
    }

    await page.getByLabel("Entry title").fill("A new idea");
    await content.fill("# Heading\n\nA **bold** idea.");
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    const preview = page.getByRole("complementary", { name: "Markdown preview" });
    await expect(preview.getByRole("heading", { name: "Heading" })).toBeVisible();
    await expect(preview.getByText("bold", { exact: true })).toBeVisible();
    // Wait for the slide-in transition before checking its final position.
    await expect.poll(async () => {
      const box = await preview.boundingBox();
      return Math.round(box!.x + box!.width);
    }).toBe(390);
    expect((await preview.boundingBox())!.x).toBeGreaterThan(0);
    await page.getByRole("button", { name: "Close preview" }).click();
    await expect(preview).toBeHidden();
    await expect(content).toHaveValue("# Heading\n\nA **bold** idea.");

    // Close immediately rather than waiting for the debounce.
    await content.fill("Saved even when closing immediately");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(editor).toBeHidden();
    expect(vault.entries[0].title).toBe("A new idea");
    expect(vault.entries[0].content).toBe("Saved even when closing immediately");
    await page.getByRole("button", { name: /A new idea/ }).click();
    await expect(page.getByLabel("Entry content")).toHaveValue("Saved even when closing immediately");
  });

  test("the writing page adapts when the mobile viewport becomes shorter", async ({ page }) => {
    await mockVault(page);
    await openNewEntry(page);
    await expect(page.locator('meta[name="viewport"]')).toHaveAttribute("content", /interactive-widget=resizes-content/);
    await page.getByLabel("Entry content").focus();
    await page.setViewportSize({ width: 390, height: 460 });
    const editor = await page.locator(".entry-editor").boundingBox();
    const content = await page.getByLabel("Entry content").boundingBox();
    expect(editor!.height).toBe(460);
    expect(content!.height).toBeGreaterThan(200);
    expect(content!.y).toBeLessThan(460);
  });

  test("keeps controls reachable on a narrow screen", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await mockVault(page, "light");
    await openNewEntry(page);
    await expect(page.locator(".entry-editor")).toHaveCSS("background-color", "rgb(251, 250, 248)");
    let previousRight = 0;
    for (const button of await page.locator(".editor-toolbar button").all()) {
      const box = await button.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(previousRight);
      expect(box!.x + box!.width).toBeLessThanOrEqual(320);
      expect(box!.width).toBeGreaterThanOrEqual(44);
      previousRight = box!.x + box!.width;
    }
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    await expect(page.getByRole("complementary", { name: "Markdown preview" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("complementary", { name: "Markdown preview" })).toBeHidden();
  });

  test("large text settings do not make the narrow editor toolbar overlap", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await mockVault(page);
    await page.goto("/");
    await page.getByRole("button", { name: /Notes/ }).first().click();
    await page.getByTitle("Settings").click();
    await page.getByRole("button", { name: "Extra large", exact: true }).click();
    await page.getByRole("button", { name: "Close settings" }).click();
    await page.getByRole("button", { name: "New entry" }).click();
    await page.getByLabel("Entry content").waitFor();
    let previousRight = 0;
    for (const button of await page.locator(".editor-toolbar button").all()) {
      const box = await button.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(previousRight);
      expect(box!.x + box!.width).toBeLessThanOrEqual(320);
      previousRight = box!.x + box!.width;
    }
  });
});

test.describe("desktop editor", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("creating an entry while the list is loading never duplicates it", async ({ page }) => {
    const vault = await mockVault(page, "dark", 300);
    vault.entries.push({
      id: "existing-entry", spaceId: "space-1", title: "Existing entry", content: "Still here",
      coverImage: null, pinned: false, createdAt: now, updatedAt: now, tags: [],
    });
    const duplicateWarnings: string[] = [];
    page.on("console", (message) => {
      if (message.text().includes("same key")) duplicateWarnings.push(message.text());
    });
    await openNewEntry(page);
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    await page.waitForTimeout(400); // Allow the deliberately delayed list request to finish.
    await expect(page.getByRole("button", { name: /Untitled entry/ })).toHaveCount(1);
    await expect(page.getByRole("button", { name: /Existing entry/ })).toHaveCount(1);
    expect(duplicateWarnings).toEqual([]);
  });

  test("shows loading feedback when the search changes", async ({ page }) => {
    const vault = await mockVault(page, "dark", 300);
    vault.entries.push({
      id: "existing-entry", spaceId: "space-1", title: "Existing entry", content: "Searchable",
      coverImage: null, pinned: false, createdAt: now, updatedAt: now, tags: [],
    });
    await page.goto("/");
    await page.getByRole("button", { name: /Notes/ }).first().click();
    await expect(page.getByRole("button", { name: /Existing entry/ })).toBeVisible();
    await page.getByPlaceholder(/Search in/).fill("Searchable");
    await expect(page.getByText("Loading entries…")).toBeVisible();
    await expect(page.getByRole("button", { name: /Existing entry/ })).toBeVisible();
  });

  test("slides preview over the page and keeps it live while writing", async ({ page }) => {
    await mockVault(page);
    await openNewEntry(page);
    const content = page.getByLabel("Entry content");
    await content.fill("# First heading");
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    const preview = page.getByRole("complementary", { name: "Markdown preview" });
    await expect(preview.getByRole("heading", { name: "First heading" })).toBeVisible();
    expect((await content.boundingBox())?.width).toBe(1440);
    expect((await preview.boundingBox())!.width).toBeLessThan(600);
    await content.click({ position: { x: 100, y: 20 } });
    await content.fill("# Updated heading\n\n- One\n- Two");
    await expect(preview.getByRole("heading", { name: "Updated heading" })).toBeVisible();
    await expect(preview.getByRole("listitem")).toHaveCount(2);
    await page.keyboard.press("Escape");
    await expect(preview).toBeHidden();
  });

  test("renders representative GFM content in preview", async ({ page }) => {
    await mockVault(page);
    await openNewEntry(page);
    await page.getByLabel("Entry content").fill([
      "# Heading", "", "- Parent", "  - Child", "- [x] Done", "", "> Quote", "",
      "| Name | Value |", "| --- | --- |", "| One | Two |", "",
      "```js", "const answer = 42;", "```", "", "[Safe link](https://example.com)",
      "", "![Example image](https://example.com/image.png)",
    ].join("\n"));
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    const preview = page.getByRole("complementary", { name: "Markdown preview" });
    await expect(preview.getByRole("heading", { name: "Heading" })).toBeVisible();
    await expect(preview.locator("li ul li")).toHaveText("Child");
    await expect(preview.getByRole("checkbox")).toBeChecked();
    await expect(preview.locator("blockquote")).toContainText("Quote");
    await expect(preview.getByRole("cell", { name: "Two" })).toBeVisible();
    await expect(preview.locator("pre code")).toContainText("const answer = 42;");
    await expect(preview.getByRole("link", { name: "Safe link" })).toHaveAttribute("href", "https://example.com");
    await expect(preview.getByRole("img", { name: "Example image" })).toHaveAttribute("src", "https://example.com/image.png");
  });

  test("find and replace stays editable and treats special characters literally", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    const content = page.getByLabel("Entry content");
    await content.fill("cat catalog CAT and a+b a+b");
    await page.getByRole("button", { name: "Find and replace" }).click();
    await page.getByLabel("Find word or phrase").fill("cat");
    await expect(page.getByText("3 matches")).toBeVisible();
    await expect(page.locator(".editor-highlight mark")).toHaveCount(3);
    await page.getByLabel("Whole word").check();
    await expect(page.getByText("2 matches")).toBeVisible();
    await page.getByLabel("Replace with").fill("dog");
    await page.getByRole("button", { name: "Find next" }).click();
    await expect.poll(() => content.evaluate((element: HTMLTextAreaElement) => [element.selectionStart, element.selectionEnd])).toEqual([0, 3]);
    await page.getByRole("button", { name: "Replace", exact: true }).click();
    await expect(content).toHaveValue("dog catalog CAT and a+b a+b");
    await page.getByRole("button", { name: "Replace all" }).click();
    await expect(content).toHaveValue("dog catalog dog and a+b a+b");
    await page.getByLabel("Whole word").uncheck();
    await page.getByLabel("Find word or phrase").fill("a+b");
    await expect(page.getByText("2 matches")).toBeVisible();
    await page.getByLabel("Replace with").fill("[$&]");
    await page.getByRole("button", { name: "Replace all" }).click();
    await expect(content).toHaveValue("dog catalog dog and [$&] [$&]");
    // The textarea must remain editable with find highlighting enabled.
    await content.fill("An edited draft");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].content).toBe("An edited draft");
  });

  test("preserves pin, tags, cover, import, and delete actions", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    await page.getByRole("button", { name: "Pin entry" }).click();
    await expect(page.getByRole("button", { name: "Unpin entry" })).toBeVisible();
    await page.getByLabel("Add tag").focus();
    await page.locator(".tag-picker-menu").getByRole("button", { name: "Ideas" }).click();
    await expect(page.getByRole("button", { name: "Remove Ideas tag" })).toBeVisible();
    const pill = page.getByRole("button", { name: "Remove Ideas tag" }).locator("..");
    await expect(pill).toHaveCSS("border-top-width", "1px");
    await expect(pill).toHaveCSS("border-top-color", "rgb(139, 92, 246)");

    await page.locator('input[accept="image/*"]').setInputFiles({
      name: "cover.png", mimeType: "image/png", buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL6WQAAAABJRU5ErkJggg==", "base64"),
    });
    await expect(page.getByRole("button", { name: "Remove cover image" })).toBeVisible();
    await page.getByRole("button", { name: "Remove cover image" }).click();
    await expect(page.getByRole("button", { name: /Add cover image/ })).toBeVisible();

    const content = page.getByLabel("Entry content");
    await content.fill("Existing content");
    const chooserPromise = page.waitForEvent("filechooser");
    await page.getByRole("button", { name: "Import text or Markdown file" }).click();
    await (await chooserPromise).setFiles({ name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("Imported text") });
    await expect(page.getByRole("dialog", { name: /Import/ })).toBeVisible();
    await page.getByRole("button", { name: "Replace entire entry content" }).click();
    await expect(content).toHaveValue("Imported text");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].pinned).toBe(true);
    expect(vault.entries[0].tags.map((tag) => tag.name)).toEqual(["Ideas"]);
    expect(vault.entries[0].coverImage).toBeNull();
    expect(vault.entries[0].content).toBe("Imported text");

    await page.getByRole("button", { name: /Untitled entry/ }).click();
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "Delete entry" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries).toHaveLength(0);
  });


  test("waits for a cover upload before closing or deleting", async ({ page }) => {
    const vault = await mockVault(page);
    vault.delayUpload(700);
    await openNewEntry(page);
    await page.locator('input[accept="image/*"]').setInputFiles({
      name: "cover.png", mimeType: "image/png", buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL6WQAAAABJRU5ErkJggg==", "base64"),
    });
    await expect(page.getByRole("button", { name: "Close editor" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Delete entry" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Remove cover image" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Close editor" })).toBeEnabled();
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].coverImage).toMatch(/^data:image/);
  });

  test("scope-qualified suggestions stay in the entry Space and creation fails closed", async ({ page }) => {
    const vault = await mockVault(page);
    vault.tags.push({ ...reusableTag, id: "local-a", name: "Local A", scope: "local", ownerSpaceId: "space-1" } as typeof reusableTag);
    vault.tags.push({ ...reusableTag, id: "local-b", name: "Local B", scope: "local", ownerSpaceId: "space-2" } as typeof reusableTag);
    await openNewEntry(page);
    await page.getByLabel("Add tag").focus();
    const menu = page.locator(".tag-picker-menu");
    await expect(menu.getByRole("button", { name: /Local A/ })).toBeVisible();
    await expect(menu.getByRole("button", { name: /Local B/ })).toHaveCount(0);
    await page.getByLabel("Add tag").fill("New local");
    const create = page.getByRole("button", { name: /Create & add tag/ });
    await expect(page.getByLabel("Local to this Space")).not.toBeChecked();
    await expect(page.getByLabel("Global", { exact: true })).not.toBeChecked();
    await expect(create).toBeDisabled();
    await page.getByLabel("Local to this Space").check();
    await expect(create).toBeDisabled();
    await expect(menu.getByText(/Creation is paused until both databases/)).toBeVisible();
    expect(vault.tags.some((tag) => tag.name === "New local")).toBe(false);
    expect(vault.entries[0].tags).toEqual([]);
  });

  test("Global and applicable Local tags remain attachable; reusable is independent", async ({ page }) => {
    const vault = await mockVault(page);
    vault.tags[0].reusable = false;
    vault.tags.push({ ...reusableTag, id: "local-a", name: "Local A", scope: "local", ownerSpaceId: "space-1", reusable: true } as typeof reusableTag);
    vault.tags.push({ ...reusableTag, id: "local-b", name: "Local B", scope: "local", ownerSpaceId: "space-2", reusable: true } as typeof reusableTag);
    await openNewEntry(page);
    await page.getByLabel("Add tag").focus();
    const menu = page.locator(".tag-picker-menu");
    await expect(menu.getByRole("button", { name: /Ideas.*Global/ })).toBeVisible();
    await expect(menu.getByRole("button", { name: /Local A.*Local/ })).toBeVisible();
    await expect(menu.getByRole("button", { name: /Local B.*Local/ })).toHaveCount(0);
    await menu.getByRole("button", { name: /Local A.*Local/ }).click();
    await expect(page.getByRole("button", { name: "Remove Local A tag" })).toBeVisible();
    expect(vault.tags.find((tag) => tag.id === "tag-1")?.reusable).toBe(false);
  });

  test("a failed delete does not discard an unsaved draft", async ({ page }) => {
    const vault = await mockVault(page);
    vault.failDeletes(1);
    await openNewEntry(page);
    await page.getByLabel("Entry content").fill("Keep this even if delete fails");
    page.on("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "Delete entry" }).click();
    await expect(page.getByLabel("Entry content")).toHaveValue("Keep this even if delete fails");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].content).toBe("Keep this even if delete fails");
  });

  test("locks editing controls until the final save finishes", async ({ page }) => {
    const vault = await mockVault(page);
    vault.delayPatches(700);
    await openNewEntry(page);
    await page.getByLabel("Entry content").fill("Final draft");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".editor-document")).toHaveAttribute("inert", "");
    await expect(page.locator(".editor-toolbar-actions")).toHaveAttribute("inert", "");
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].content).toBe("Final draft");
  });

  test("retries an in-flight draft save before closing", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    vault.failDrafts(1);
    const firstSave = page.waitForRequest((request) => request.method() === "PATCH" && request.url().includes("/api/entries/"));
    await page.getByLabel("Entry content").fill("Do not lose this draft");
    await firstSave;
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].content).toBe("Do not lose this draft");
    expect(vault.patches.filter((patch) => patch.content === "Do not lose this draft")).toHaveLength(2);
  });

  test("keeps the draft open if saving still fails", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    vault.failDrafts(2);
    const firstSave = page.waitForRequest((request) => request.method() === "PATCH" && request.url().includes("/api/entries/"));
    await page.getByLabel("Entry content").fill("Keep this draft safe");
    await firstSave;
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.getByRole("status")).toHaveText("Couldn’t save");
    await expect(page.getByLabel("Entry content")).toHaveValue("Keep this draft safe");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].content).toBe("Keep this draft safe");
  });

  test("does not discard a failed pin change on close", async ({ page }) => {
    const vault = await mockVault(page);
    await openNewEntry(page);
    vault.failField("pinned", 2);
    const firstSave = page.waitForRequest((request) => request.method() === "PATCH" && request.url().includes("/api/entries/"));
    await page.getByRole("button", { name: "Pin entry" }).click();
    await firstSave;
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.getByRole("status")).toHaveText("Couldn’t save");
    await expect(page.getByRole("button", { name: "Unpin entry" })).toBeVisible();
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.entries[0].pinned).toBe(true);
  });

  test("preserves a custom accent, wallpaper, text size, and corner setting", async ({ page }) => {
    const vault = await mockVault(page, "light");
    Object.assign(vault.settings, {
      accent: "#10b981", fontSize: "lg", radius: "none", wallpaperEnabled: true,
      wallpaperUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL6WQAAAABJRU5ErkJggg==",
      wallpaperOpacity: 68, wallpaperBlur: 3,
    });
    await page.goto("/");
    await page.getByRole("button", { name: /Notes/ }).first().click();
    const wallpaper = page.locator('.bg-cover.bg-center[style*="background-image"]');
    await expect(wallpaper).toHaveCSS("opacity", "0.68");
    await expect(wallpaper).toHaveCSS("filter", "blur(3px)");
    await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--accent").trim())).toBe("#10b981");
    await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--radius-ui").trim())).toBe("0px");
    await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe("16.8px");
    await page.getByTitle("Settings").click();
    await page.getByRole("button", { name: "Accent color #ec4899" }).click();
    await expect.poll(() => vault.settings.accent).toBe("#ec4899");
    await page.getByRole("button", { name: "Close settings" }).click();
    await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--accent").trim())).toBe("#ec4899");
    await page.getByRole("button", { name: "New entry" }).click();
    await expect(page.getByRole("button", { name: "Close editor" })).toHaveCSS("border-radius", "0px");
    await page.getByLabel("Entry content").fill("Custom settings stay intact");
    await page.getByRole("button", { name: "Close editor" }).click();
    await expect(page.locator(".entry-editor")).toBeHidden();
    expect(vault.settings).toMatchObject({ accent: "#ec4899", fontSize: "lg", radius: "none", wallpaperEnabled: true, wallpaperOpacity: 68, wallpaperBlur: 3 });
  });

  test("the existing text-size setting scales the interface", async ({ page }) => {
    await mockVault(page, "light");
    await page.goto("/");
    await page.getByRole("button", { name: /Notes/ }).first().click();
    await page.getByTitle("Settings").click();
    const before = await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize));
    await page.getByRole("button", { name: "Large", exact: true }).click();
    await expect.poll(() => page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize))).toBeGreaterThan(before);
  });
});
