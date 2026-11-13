import type { AppSettings, Entry, ExportPayload, SingleEntryExport, Space, Tag } from "./types";
import {
  dataUriFromBytes,
  findMarkdownImages,
  inspectImage,
  MAX_BACKUP_MEDIA_BYTES,
  MAX_IMAGE_BYTES,
  parseDataImageUri,
  replaceMarkdownImages,
} from "./markdown-media";

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || "Request failed");
  }
  return res.json();
}

const mediaUrlPattern = /^\/?api\/media\/([0-9a-f-]{36})$/i;
const dataImagePattern = /^data:image\//i;

type UploadResult = { id: string; url: string; mimeType: string; byteSize: number };

async function uploadMedia(bytes: Uint8Array, mime: string, stagingToken?: string): Promise<UploadResult> {
  const file = new File([new Uint8Array(bytes).buffer], `inline-image.${mime.split("/")[1]}`, { type: mime });
  const form = new FormData();
  form.append("file", file);
  if (stagingToken) form.append("stagingToken", stagingToken);
  const response = await fetch("/api/media", { method: "POST", body: form });
  const body = await response.json().catch(() => ({ error: response.statusText }));
  if (!response.ok) throw new Error(body.error || "Image upload failed.");
  return body as UploadResult;
}

function contentRecords(payload: unknown) {
  const value = payload as { entries?: Array<Record<string, unknown>>; settings?: Record<string, unknown> };
  return { entries: Array.isArray(value?.entries) ? value.entries : [], settings: value?.settings };
}

/** Converts portable bytes to separately uploaded, hidden staging records before the bounded JSON API call. */
async function stagePortableImages(payload: unknown) {
  const cloned = structuredClone(payload) as Record<string, unknown>;
  const { entries, settings } = contentRecords(cloned);
  const stagingToken = crypto.randomUUID();
  const uploads = new Map<string, UploadResult>();
  const uploadIds: string[] = [];
  let aggregateBytes = 0;

  const mapReference = async (reference: string): Promise<string> => {
    if (!dataImagePattern.test(reference)) return reference;
    const cached = uploads.get(reference);
    if (cached) return cached.url;
    const { bytes, mime } = parseDataImageUri(reference);
    if (aggregateBytes + bytes.byteLength > MAX_BACKUP_MEDIA_BYTES) {
      throw new Error("Backup inline images exceed the 16 MB total limit.");
    }
    aggregateBytes += bytes.byteLength;
    const result = await uploadMedia(bytes, mime, stagingToken);
    uploads.set(reference, result);
    uploadIds.push(result.id);
    return result.url;
  };

  try {
    for (const entry of entries) {
      if (typeof entry.content === "string") {
        const refs = findMarkdownImages(entry.content);
        let next = entry.content;
        for (const ref of refs.reverse()) {
          const url = await mapReference(ref.destination);
          next = next.slice(0, ref.start) + url + next.slice(ref.end);
        }
        entry.content = next;
      }
      if (typeof entry.coverImage === "string") entry.coverImage = await mapReference(entry.coverImage);
    }
    if (settings && typeof settings.wallpaperUrl === "string") {
      settings.wallpaperUrl = await mapReference(settings.wallpaperUrl);
    }
    const json = JSON.stringify({ ...cloned, mediaStagingToken: uploadIds.length ? stagingToken : undefined });
    if (new TextEncoder().encode(json).byteLength > 4 * 1024 * 1024) {
      throw new Error("Backup JSON exceeds the 4 MB import limit after images are staged.");
    }
    return { json, uploadIds, stagingToken };
  } catch (error) {
    await Promise.all(uploadIds.map((id) => fetch(`/api/media/${id}`, {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ stagingToken }),
    }).catch(() => undefined)));
    throw error;
  }
}

/** Adds image bytes to a downloaded backup in the browser, avoiding Vercel's 4.5 MB response ceiling. */
export async function makePortableBackup(payload: ExportPayload): Promise<ExportPayload> {
  const cloned = structuredClone(payload) as ExportPayload;
  const entries = (cloned.entries ?? []) as unknown as Array<Record<string, unknown>>;
  const settings = (cloned.settings ?? {}) as unknown as Record<string, unknown>;
  const seen = new Map<string, string>();
  let aggregateBytes = 0;
  let missingCount = 0;

  const portableReference = async (reference: string): Promise<string> => {
    if (dataImagePattern.test(reference)) {
      const { bytes } = parseDataImageUri(reference);
      const key = `data:${reference}`;
      if (!seen.has(key)) {
        aggregateBytes += bytes.byteLength;
        if (aggregateBytes > MAX_BACKUP_MEDIA_BYTES) throw new Error("Backup inline images exceed the 16 MB total limit.");
        seen.set(key, reference);
      }
      return reference;
    }
    const match = mediaUrlPattern.exec(reference);
    if (!match) return reference;
    const key = `id:${match[1].toLowerCase()}`;
    const existing = seen.get(key);
    if (existing) return existing;
    try {
      const response = await fetch(`/api/media/${match[1]}`);
      if (!response.ok) throw new Error("The saved image is missing.");
      const blob = await response.blob();
      if (blob.size > MAX_IMAGE_BYTES) throw new Error("The saved image exceeds the 2 MB limit.");
      const bytes = new Uint8Array(await blob.arrayBuffer());
      const { mime } = inspectImage(bytes);
      if (aggregateBytes + bytes.byteLength > MAX_BACKUP_MEDIA_BYTES) {
        throw new Error("Backup inline images exceed the 16 MB total limit.");
      }
      aggregateBytes += bytes.byteLength;
      const dataUri = dataUriFromBytes(bytes, mime);
      seen.set(key, dataUri);
      return dataUri;
    } catch (error) {
      if (error instanceof Error && error.message.includes("16 MB total limit")) throw error;
      missingCount++;
      return reference;
    }
  };

  for (const entry of entries) {
    if (typeof entry.content === "string") {
      const refs = findMarkdownImages(entry.content);
      let next = entry.content;
      for (const ref of refs.reverse()) {
        const destination = await portableReference(ref.destination);
        next = next.slice(0, ref.start) + destination + next.slice(ref.end);
      }
      entry.content = next;
    }
    if (typeof entry.coverImage === "string") entry.coverImage = await portableReference(entry.coverImage);
  }
  if (typeof settings.wallpaperUrl === "string") settings.wallpaperUrl = await portableReference(settings.wallpaperUrl);
  if (missingCount) console.warn(`${missingCount} missing image reference(s) were preserved in the backup.`);
  return cloned;
}

/** Build a one-entry archival document while reusing the backup media portability rules. */
export async function makeSingleEntryExport(entry: Entry, space: Space | null): Promise<SingleEntryExport> {
  const portable = await makePortableBackup({
    version: 2,
    exportedAt: new Date().toISOString(),
    settings: { wallpaperUrl: null } as AppSettings,
    spaces: space ? [space] : [],
    tags: entry.tags,
    entries: [entry],
  });
  return {
    format: "myvault-entry",
    formatVersion: 1,
    exportedAt: portable.exportedAt,
    space: space ? {
      id: space.id,
      name: space.name,
      description: space.description,
      icon: space.icon,
      color: space.color,
      sortOrder: space.sortOrder,
      createdAt: space.createdAt,
    } : null,
    entry: portable.entries[0],
  };
}

export const api = {
  settings: {
    get: () => request<AppSettings>("/api/settings"),
    update: (patch: Partial<AppSettings>) =>
      request<AppSettings>("/api/settings", { method: "PATCH", body: JSON.stringify(patch) }),
  },
  spaces: {
    list: () => request<Space[]>("/api/spaces"),
    create: (data: Partial<Space>) =>
      request<Space>("/api/spaces", { method: "POST", body: JSON.stringify(data) }),
    update: (id: string, patch: Partial<Space>) =>
      request<Space>(`/api/spaces/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
    remove: (id: string) => request<{ ok: true }>(`/api/spaces/${id}`, { method: "DELETE" }),
  },
  tags: {
    list: () => request<Tag[]>("/api/tags"),
    create: (data: { name: string; scope: "local" | "global"; ownerSpaceId: string | null; color?: string; textColor?: string; reusable?: boolean }) =>
      request<Tag>("/api/tags", { method: "POST", body: JSON.stringify(data) }),
    update: (id: string, patch: Partial<Tag>) =>
      request<Tag>(`/api/tags/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
    remove: (id: string) => request<{ ok: true }>(`/api/tags/${id}`, { method: "DELETE" }),
  },
  entries: {
    list: (params: { spaceId?: string; q?: string; tagId?: string } = {}) => {
      const search = new URLSearchParams();
      if (params.spaceId) search.set("spaceId", params.spaceId);
      if (params.q) search.set("q", params.q);
      if (params.tagId) search.set("tagId", params.tagId);
      const qs = search.toString();
      return request<Entry[]>(`/api/entries${qs ? `?${qs}` : ""}`);
    },
    get: (id: string) => request<Entry>(`/api/entries/${id}`),
    create: (data: Partial<Entry> & { spaceId: string; tagIds?: string[] }) =>
      request<Entry>("/api/entries", { method: "POST", body: JSON.stringify(data) }),
    update: (id: string, patch: Partial<Entry> & { tagIds?: string[] }) =>
      request<Entry>(`/api/entries/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
    remove: (id: string) => request<{ ok: true }>(`/api/entries/${id}`, { method: "DELETE" }),
  },
  upload: async (file: File): Promise<{ url: string }> => {
    const formData = new FormData();
    formData.append("file", file);
    const res = await fetch("/api/upload", { method: "POST", body: formData });
    if (!res.ok) {
      const body = await res.json().catch(() => ({ error: res.statusText }));
      throw new Error(body.error || "Upload failed");
    }
    return res.json();
  },
  inlineImage: async (file: File): Promise<{ url: string }> => {
    if (file.size > MAX_IMAGE_BYTES) throw new Error("Images must be 2 MB or smaller.");
    const bytes = new Uint8Array(await file.arrayBuffer());
    const image = inspectImage(bytes);
    if (file.type && file.type.toLowerCase() !== "application/octet-stream" && file.type.toLowerCase() !== image.mime && !(file.type.toLowerCase() === "image/jpg" && image.mime === "image/jpeg")) {
      throw new Error("The image content does not match its declared type.");
    }
    const result = await uploadMedia(bytes, image.mime);
    return { url: result.url };
  },
  export: async () => makePortableBackup(await request<ExportPayload>("/api/export")),
  import: async (payload: unknown, applySettings: boolean) => {
    const staged = await stagePortableImages(payload);
    try {
      return await request<{ ok: true; summary: Record<string, number | boolean> }>("/api/import", {
        method: "POST",
        body: JSON.stringify({ ...JSON.parse(staged.json), applySettings }),
      });
    } catch (error) {
      await Promise.all(staged.uploadIds.map((id) => fetch(`/api/media/${id}`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stagingToken: staged.stagingToken }),
      }).catch(() => undefined)));
      throw error;
    }
  },
};
