// Keep the historical upload URL working, but route bytes to durable PostgreSQL storage.
import { POST as storeMedia } from "../media/route";

export async function POST(request: Request) {
  return storeMedia(request);
}

export const runtime = "nodejs";
