import { createHash, randomUUID } from "node:crypto";
import { and, eq, isNotNull, isNull, lt, sql } from "drizzle-orm";
import { NextResponse } from "next/server";
import { db } from "@/db";
import { mediaAssets } from "@/db/schema";
import { inspectImage, MAX_IMAGE_BYTES, MAX_STORED_MEDIA_BYTES } from "@/lib/markdown-media";

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_MULTIPART_BYTES = MAX_IMAGE_BYTES + 64 * 1024;

export async function POST(request: Request) {
  try {
    const contentLength = Number(request.headers.get("content-length") ?? 0);
    if (contentLength > MAX_MULTIPART_BYTES) {
      return NextResponse.json({ error: "Image upload exceeds the 2 MB limit." }, { status: 413 });
    }
    const form = await request.formData();
    const file = form.get("file");
    if (!(file instanceof File)) return NextResponse.json({ error: "Choose an image file." }, { status: 400 });
    if (file.size < 1 || file.size > MAX_IMAGE_BYTES) {
      return NextResponse.json({ error: "Images must be 2 MB or smaller." }, { status: 413 });
    }
    const stagingValue = form.get("stagingToken");
    const stagingToken = stagingValue === null ? null : String(stagingValue);
    if (stagingToken !== null && !uuidPattern.test(stagingToken)) {
      return NextResponse.json({ error: "Invalid import staging token." }, { status: 400 });
    }
    const bytes = Buffer.from(await file.arrayBuffer());
    const image = inspectImage(bytes);
    const hintedMime = file.type.toLowerCase() === "image/jpg" ? "image/jpeg" : file.type.toLowerCase();
    if (hintedMime && hintedMime !== "application/octet-stream" && hintedMime !== image.mime) {
      return NextResponse.json({ error: "The image content does not match its declared type." }, { status: 400 });
    }

    const sha256 = createHash("sha256").update(bytes).digest("hex");
    const id = await db.transaction(async (tx) => {
      await tx.execute(sql`SELECT pg_advisory_xact_lock(1836674165)`);
      // Expire only abandoned staged imports. Committed rows are never garbage-collected here.
      await tx.delete(mediaAssets).where(and(isNotNull(mediaAssets.stagingToken), lt(mediaAssets.createdAt, new Date(Date.now() - 24 * 60 * 60 * 1000))));
      const [existing] = await tx.select({ id: mediaAssets.id }).from(mediaAssets)
        .where(and(eq(mediaAssets.sha256, sha256), isNull(mediaAssets.stagingToken))).limit(1);
      if (existing) return existing.id;
      const [storage] = await tx.select({ usedBytes: sql<number>`coalesce(sum(${mediaAssets.byteSize}), 0)` }).from(mediaAssets);
      if (Number(storage?.usedBytes ?? 0) + bytes.byteLength > MAX_STORED_MEDIA_BYTES) {
        throw new Error("Durable image storage quota reached (256 MB). Remove unused images before uploading more.");
      }
      const nextId = randomUUID();
      await tx.insert(mediaAssets).values({
        id: nextId,
        mimeType: image.mime,
        byteSize: bytes.byteLength,
        sha256,
        imageBytes: bytes,
        stagingToken,
      });
      return nextId;
    });
    return NextResponse.json({ id, url: `/api/media/${id}`, mimeType: image.mime, byteSize: bytes.byteLength }, { status: 201 });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Could not store this image.";
    const quota = /storage quota/i.test(message);
    const expected = /image|MIME|base64|dimension|PNG|JPEG|GIF|WebP|SVG|malformed|unsupported/i.test(message);
    return NextResponse.json(
      { error: expected || quota ? message : "Image storage is temporarily unavailable." },
      { status: quota ? 507 : expected ? 400 : 500 },
    );
  }
}

export const runtime = "nodejs";
