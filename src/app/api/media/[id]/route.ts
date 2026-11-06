import { and, eq, isNull } from "drizzle-orm";
import { db } from "@/db";
import { mediaAssets } from "@/db/schema";
import { NextResponse } from "next/server";

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type RouteContext = { params: Promise<{ id: string }> };

export async function GET(_request: Request, { params }: RouteContext) {
  const { id } = await params;
  if (!uuidPattern.test(id)) return new NextResponse("Not found", { status: 404 });
  const [asset] = await db.select({ mimeType: mediaAssets.mimeType, byteSize: mediaAssets.byteSize, imageBytes: mediaAssets.imageBytes }).from(mediaAssets).where(and(eq(mediaAssets.id, id), isNull(mediaAssets.stagingToken))).limit(1);
  if (!asset) return new NextResponse("Not found", { status: 404 });
  return new Response(new Uint8Array(asset.imageBytes), {
    headers: {
      "Content-Type": asset.mimeType,
      "Content-Length": String(asset.byteSize),
      "Cache-Control": "private, max-age=31536000, immutable",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

/** Marks all blobs uploaded with this import token as committed, within the DB import transaction. */
export async function PATCH(request: Request, { params }: RouteContext) {
  const { id } = await params;
  const body = await request.json().catch(() => null);
  const token = body?.stagingToken;
  if (!uuidPattern.test(id) || typeof token !== "string" || !uuidPattern.test(token)) {
    return NextResponse.json({ error: "Invalid media commit request." }, { status: 400 });
  }
  await db.update(mediaAssets).set({ stagingToken: null }).where(and(eq(mediaAssets.id, id), eq(mediaAssets.stagingToken, token)));
  return NextResponse.json({ ok: true });
}

/** Removes only an uncommitted blob belonging to the supplied import token. */
export async function DELETE(request: Request, { params }: RouteContext) {
  const { id } = await params;
  const body = await request.json().catch(() => null);
  const token = body?.stagingToken;
  if (!uuidPattern.test(id) || typeof token !== "string" || !uuidPattern.test(token)) {
    return NextResponse.json({ error: "Invalid media cleanup request." }, { status: 400 });
  }
  await db.delete(mediaAssets).where(and(eq(mediaAssets.id, id), eq(mediaAssets.stagingToken, token)));
  return NextResponse.json({ ok: true });
}

export const runtime = "nodejs";
