import { expect, test } from "@playwright/test";
import { makePortableBackup } from "@/lib/api";
import type { ExportPayload } from "@/lib/types";
import {
  dataUriFromBytes,
  escapeMarkdownAlt,
  findMarkdownImages,
  imageAltFromFilename,
  inspectImage,
  parseDataImageUri,
  replaceMarkdownImages,
} from "@/lib/markdown-media";

const validPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";

 test("validates PNG bytes and rejects checksum, trailing-data, MIME, and SVG mismatches", () => {
  const valid = Uint8Array.from(Buffer.from(validPngBase64, "base64"));
  expect(inspectImage(valid)).toEqual({ mime: "image/png", width: 1, height: 1 });

  const badChecksum = valid.slice();
  badChecksum[42] ^= 1;
  expect(() => inspectImage(badChecksum)).toThrow(/checksum/i);
  expect(() => inspectImage(Uint8Array.from([...valid, 0]))).toThrow(/malformed/i);
  expect(() => parseDataImageUri(`data:image/jpeg;base64,${validPngBase64}`)).toThrow(/MIME/i);
  expect(() => inspectImage(new TextEncoder().encode("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"))).toThrow(/SVG/i);
});

test("portable image bytes round-trip without changing unrelated Markdown references", () => {
  const bytes = Uint8Array.from(Buffer.from(validPngBase64, "base64"));
  const uri = dataUriFromBytes(bytes, "image/png");
  expect(parseDataImageUri(uri)).toEqual({ bytes, mime: "image/png" });

  const markdown = `before ![local](/api/media/11111111-1111-4111-8111-111111111111) after ![remote](https://example.org/photo.png)\n\`![code](/api/media/11111111-1111-4111-8111-111111111111)\`\n\`\`\`md\n![fenced](/api/media/11111111-1111-4111-8111-111111111111)\n\`\`\``;
  const refs = findMarkdownImages(markdown);
  expect(refs).toHaveLength(1);
  expect(replaceMarkdownImages(markdown, () => uri)).toBe(markdown.replace("/api/media/11111111-1111-4111-8111-111111111111", uri));
  expect(markdown).toContain("![remote](https://example.org/photo.png)");
});

test("Web backup export embeds durable media bytes and preserves external references", async () => {
  const bytes = Uint8Array.from(Buffer.from(validPngBase64, "base64"));
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(new Blob([bytes]), { status: 200, headers: { "content-type": "image/png" } });
  try {
    const payload = {
      version: 2,
      exportedAt: "2026-11-06T00:00:00.000Z",
      settings: { wallpaperUrl: null },
      spaces: [], tags: [],
      entries: [{
        id: "entry", spaceId: "space", title: "Note",
        content: "before ![saved](/api/media/11111111-1111-4111-8111-111111111111) after ![remote](https://example.org/photo.png)",
        coverImage: null, pinned: false, tags: [],
      }],
    } as unknown as ExportPayload;
    const exported = await makePortableBackup(payload);
    expect(exported.entries[0].content).toContain(`![saved](data:image/png;base64,${validPngBase64})`);
    expect(exported.entries[0].content).toContain("![remote](https://example.org/photo.png)");
    expect(exported.entries[0].content).toContain("before ");
    expect(exported.entries[0].content).toContain(" after ");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("filename-derived alt text is readable and escaped for Markdown", () => {
  expect(imageAltFromFilename("/photos/trip_photo-01.final.jpg")).toBe("trip photo 01 final");
  expect(escapeMarkdownAlt("a[b]\\c\nnext")).toBe("a\\[b\\]\\\\c next");
  expect(imageAltFromFilename(".jpg")).toBe("Image");
});
