import { NextResponse } from "next/server";
import { asc } from "drizzle-orm";
import { db } from "@/db";
import { tags } from "@/db/schema";

const IDENTITY_BLOCKER = "Tag creation is paused until the Unicode 15.1 name identity can be enforced by both Android SQLite and PostgreSQL. Existing tags remain available.";

export async function GET() {
  return NextResponse.json(await db.select().from(tags).orderBy(asc(tags.name)));
}

export async function POST(request: Request) {
  const body = await request.json();
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (!name || !["local", "global"].includes(body.scope) ||
      (body.scope === "local" ? typeof body.ownerSpaceId !== "string" || !body.ownerSpaceId : body.ownerSpaceId != null)) {
    return NextResponse.json({ error: "Enter a name and explicitly choose a valid tag scope." }, { status: 400 });
  }
  return NextResponse.json({ error: IDENTITY_BLOCKER }, { status: 409 });
}
