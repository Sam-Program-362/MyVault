import type { AppSettings, Entry, ExportPayload, Space, Tag } from "./types";

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
  export: () => request<ExportPayload>("/api/export"),
  import: (payload: unknown, applySettings: boolean) =>
    request<{ ok: true; summary: Record<string, number | boolean> }>("/api/import", {
      method: "POST",
      body: JSON.stringify({ ...(payload as object), applySettings }),
    }),
};
