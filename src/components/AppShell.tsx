"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  LayoutGrid,
  Pin,
  Plus,
  Search,
  Settings as SettingsIcon,
  Sparkles,
  Tag as TagIcon,
  Trash2,
  X,
} from "lucide-react";
import { useAppData } from "@/context/AppDataContext";
import { api } from "@/lib/api";
import type { Entry } from "@/lib/types";
import Wallpaper from "@/components/Wallpaper";
import SplashOverlay from "@/components/SplashOverlay";
import EntryEditor from "@/components/EntryEditor";
import SettingsPanel from "@/components/SettingsPanel";

function timeAgo(dateStr: string) {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

function plainTextPreview(markdown: string) {
  return markdown
    .replace(/!\[.*?\]\(.*?\)/g, "")
    .replace(/\[(.*?)\]\(.*?\)/g, "$1")
    .replace(/[#>*_`~-]/g, "")
    .trim();
}

export default function AppShell() {
  const { loading, settings, spaces, tags, entered, currentSpaceId, setEntered, setCurrentSpaceId, addSpace, renameSpace, deleteSpace } =
    useAppData();

  const [entries, setEntries] = useState<Entry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(true);
  const entriesRequest = useRef(0);
  const [search, setSearch] = useState("");
  const [searchAllSpaces, setSearchAllSpaces] = useState(false);
  const [tagFilter, setTagFilter] = useState<string | null>(null);
  const [selectedEntryId, setSelectedEntryId] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [spaceMenuOpen, setSpaceMenuOpen] = useState<string | null>(null);
  const [editingSpace, setEditingSpace] = useState<{ id: string; name: string } | null>(null);
  const [addingSpace, setAddingSpace] = useState(false);
  const [newSpaceName, setNewSpaceName] = useState("");

  const currentSpace = spaces.find((s) => s.id === currentSpaceId) ?? null;

  const loadEntries = useCallback(async () => {
    const request = ++entriesRequest.current;
    // Defer the loading state so effect-triggered and manual refreshes share one path.
    await Promise.resolve();
    if (request !== entriesRequest.current) return;
    setEntriesLoading(true);
    try {
      const data = await api.entries.list({
        spaceId: searchAllSpaces ? undefined : currentSpaceId ?? undefined,
        q: search.trim() || undefined,
        tagId: tagFilter ?? undefined,
      });
      if (request === entriesRequest.current) setEntries(data);
    } finally {
      if (request === entriesRequest.current) setEntriesLoading(false);
    }
  }, [currentSpaceId, search, searchAllSpaces, tagFilter]);

  useEffect(() => {
    if (!entered) return;
    void loadEntries();
    return () => { entriesRequest.current += 1; };
  }, [entered, loadEntries]);

  if (loading || !settings) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-neutral-950 text-white/60">
        <Sparkles className="glow-pulse mr-2" size={18} /> Loading your vault…
      </div>
    );
  }

  if (!entered) {
    return <SplashOverlay />;
  }

  const handleCreateEntry = async () => {
    if (!currentSpaceId) return;
    const created = await api.entries.create({ spaceId: currentSpaceId, title: "Untitled entry" });
    // A list request may have already included this entry while creation was in flight.
    entriesRequest.current += 1;
    setEntriesLoading(false);
    setEntries((prev) => [created, ...prev.filter((entry) => entry.id !== created.id)]);
    setSelectedEntryId(created.id);
  };

  const handleCreateSpace = async () => {
    if (!newSpaceName.trim()) return;
    const space = await addSpace({ name: newSpaceName.trim() });
    setNewSpaceName("");
    setAddingSpace(false);
    setCurrentSpaceId(space.id);
  };

  const handleDeleteSpace = async (id: string) => {
    if (!confirm("Delete this space and all of its entries? This cannot be undone.")) return;
    await deleteSpace(id);
    setSpaceMenuOpen(null);
    loadEntries();
  };

  return (
    <div className="relative min-h-screen">
      <Wallpaper />
      <div className="relative z-10 flex min-h-screen flex-col">
        {/* Header */}
        <header className="app-header flex flex-wrap items-center gap-2 border-b border-black/5 bg-white/70 px-3 py-2 backdrop-blur-xl dark:border-white/10 dark:bg-neutral-950/60">
          <button
            onClick={() => setEntered(false)}
            className="app-brand flex items-center gap-2 rounded-xl px-2 py-1.5 font-bold text-neutral-900 transition hover:bg-black/5 dark:text-white dark:hover:bg-white/10"
            title="Back to space selector"
          >
            <LayoutGrid size={18} style={{ color: "var(--accent)" }} />
            <span className="hidden sm:inline">{settings.appName}</span>
          </button>

          <div className="relative ml-1 flex min-w-[80px] flex-1 items-center">
            <Search size={16} className="pointer-events-none absolute left-3 text-neutral-400" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={searchAllSpaces ? "Search every space…" : `Search in ${currentSpace?.name ?? "space"}…`}
              className="app-search ui-radius w-full border border-black/10 bg-white/80 py-2 pl-9 pr-3 text-sm outline-none accent-ring dark:border-white/10 dark:bg-white/5 dark:text-white"
            />
          </div>
          <button
            onClick={() => setSearchAllSpaces((v) => !v)}
            className={`app-all-spaces ui-radius border px-3 py-2 text-xs font-medium transition ${
              searchAllSpaces
                ? "accent-bg border-transparent text-white"
                : "border-black/10 text-neutral-600 hover:bg-black/5 dark:border-white/10 dark:text-neutral-300 dark:hover:bg-white/10"
            }`}
          >
            All spaces
          </button>

          {tagFilter && (
            <button
              onClick={() => setTagFilter(null)}
              className="flex items-center gap-1 rounded-full bg-black/5 px-3 py-1.5 text-xs text-neutral-700 dark:bg-white/10 dark:text-neutral-200"
            >
              <TagIcon size={12} /> {tags.find((t) => t.id === tagFilter)?.name ?? "tag"}
              <X size={12} />
            </button>
          )}

          <button
            onClick={() => setSettingsOpen(true)}
            className="app-settings ui-radius border border-black/10 p-2.5 text-neutral-600 transition hover:bg-black/5 dark:border-white/10 dark:text-neutral-300 dark:hover:bg-white/10"
            title="Settings"
          >
            <SettingsIcon size={18} />
          </button>
        </header>

        <div className="flex flex-1 flex-col md:flex-row">
          {/* Sidebar */}
          <aside className="flex shrink-0 gap-2 overflow-x-auto border-b border-black/5 bg-white/50 p-3 backdrop-blur-xl dark:border-white/10 dark:bg-neutral-950/40 md:w-60 md:flex-col md:overflow-y-auto md:border-b-0 md:border-r">
            {spaces.map((space) => (
              <div key={space.id} className="group relative shrink-0">
                {editingSpace?.id === space.id ? (
                  <input
                    autoFocus
                    value={editingSpace.name}
                    onChange={(e) => setEditingSpace({ id: space.id, name: e.target.value })}
                    onBlur={async () => {
                      if (editingSpace.name.trim()) await renameSpace(space.id, { name: editingSpace.name.trim() });
                      setEditingSpace(null);
                    }}
                    onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
                    className="ui-radius w-full border border-black/10 bg-white px-3 py-2 text-sm dark:border-white/20 dark:bg-neutral-800 dark:text-white"
                  />
                ) : (
                  <button
                    onClick={() => setCurrentSpaceId(space.id)}
                    onDoubleClick={() => setEditingSpace({ id: space.id, name: space.name })}
                    className={`ui-radius flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm font-medium transition ${
                      currentSpaceId === space.id
                        ? "text-white"
                        : "text-neutral-700 hover:bg-black/5 dark:text-neutral-200 dark:hover:bg-white/10"
                    }`}
                    style={currentSpaceId === space.id ? { background: space.color } : undefined}
                  >
                    <span>{space.icon}</span>
                    <span className="truncate">{space.name}</span>
                    <span className="ml-auto text-xs opacity-60">{space.entryCount ?? 0}</span>
                  </button>
                )}
                <button
                  onClick={() => setSpaceMenuOpen(spaceMenuOpen === space.id ? null : space.id)}
                  className="absolute -right-1 -top-1 hidden h-5 w-5 items-center justify-center rounded-full bg-red-500 text-[10px] text-white group-hover:flex"
                  title="Delete space"
                >
                  <Trash2
                    size={11}
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDeleteSpace(space.id);
                    }}
                  />
                </button>
              </div>
            ))}

            {addingSpace ? (
              <input
                autoFocus
                value={newSpaceName}
                onChange={(e) => setNewSpaceName(e.target.value)}
                onBlur={handleCreateSpace}
                onKeyDown={(e) => e.key === "Enter" && handleCreateSpace()}
                placeholder="Space name"
                className="ui-radius shrink-0 border border-black/10 bg-white px-3 py-2 text-sm dark:border-white/20 dark:bg-neutral-800 dark:text-white"
              />
            ) : (
              <button
                onClick={() => setAddingSpace(true)}
                className="ui-radius flex shrink-0 items-center gap-2 border border-dashed border-black/15 px-3 py-2.5 text-sm text-neutral-500 transition hover:border-black/30 dark:border-white/20 dark:text-neutral-400"
              >
                <Plus size={15} /> New space
              </button>
            )}
          </aside>

          {/* Main content */}
          <main className="flex-1 p-4 md:p-6">
            {tags.filter((t) => t.reusable).length > 0 && (
              <div className="mb-4 flex flex-wrap gap-2">
                {tags
                  .filter((t) => t.reusable)
                  .map((tag) => (
                    <button
                      key={tag.id}
                      onClick={() => setTagFilter(tagFilter === tag.id ? null : tag.id)}
                      className="rounded-full border px-3 py-1 text-xs font-medium transition"
                      style={{
                        borderColor: tag.color,
                        background: `color-mix(in srgb, ${tag.color} ${tagFilter === tag.id ? 18 : 8}%, transparent)`,
                        color: "inherit",
                      }}
                    >
                      {tag.name}
                    </button>
                  ))}
              </div>
            )}

            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-neutral-900 dark:text-white">
                {searchAllSpaces ? "All spaces" : currentSpace?.name ?? "Select a space"}
              </h2>
              <button
                onClick={handleCreateEntry}
                disabled={!currentSpaceId}
                className="accent-bg ui-radius flex items-center gap-1.5 px-3.5 py-2 text-sm font-semibold text-white shadow-md transition hover:opacity-90 disabled:opacity-40"
              >
                <Plus size={16} /> New entry
              </button>
            </div>

            {entriesLoading ? (
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Loading entries…</p>
            ) : entries.length === 0 ? (
              <div className="ui-radius flex flex-col items-center justify-center gap-2 border border-dashed border-black/10 p-14 text-center text-neutral-400 dark:border-white/10">
                <Sparkles size={22} />
                <p>No entries yet. Create your first one.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                {entries.map((entry) => (
                  <button
                    key={entry.id}
                    onClick={() => setSelectedEntryId(entry.id)}
                    className="ui-radius group flex flex-col overflow-hidden border border-black/10 bg-white/80 text-left shadow-sm transition hover:-translate-y-0.5 hover:shadow-lg dark:border-white/10 dark:bg-white/5"
                  >
                    {entry.coverImage ? (
                      <div
                        className="h-32 w-full bg-cover bg-center"
                        style={{ backgroundImage: `url(${entry.coverImage})` }}
                      />
                    ) : (
                      <div
                        className="flex h-16 w-full items-center justify-center text-xs uppercase tracking-wide text-white/70"
                        style={{ background: `color-mix(in srgb, var(--accent) 55%, #1e293b)` }}
                      >
                        {spaces.find((s) => s.id === entry.spaceId)?.icon ?? "📄"}
                      </div>
                    )}
                    <div className="flex flex-1 flex-col gap-1.5 p-3.5">
                      <div className="flex items-start justify-between gap-2">
                        <h3 className="line-clamp-1 font-semibold text-neutral-900 dark:text-white">
                          {entry.title}
                        </h3>
                        {entry.pinned && <Pin size={14} className="shrink-0 accent-text" />}
                      </div>
                      <p className="line-clamp-3 text-xs text-neutral-500 dark:text-neutral-400">
                        {plainTextPreview(entry.content) || "No content yet."}
                      </p>
                      <div className="mt-auto flex flex-wrap items-center gap-1 pt-2">
                        {entry.tags.slice(0, 3).map((tag) => (
                          <span
                            key={tag.id}
                            className="rounded-full border px-2 py-0.5 text-[10px] font-medium"
                            style={{ borderColor: tag.color, background: `color-mix(in srgb, ${tag.color} 8%, transparent)`, color: "inherit" }}
                          >
                            {tag.name}
                          </span>
                        ))}
                        <span className="ml-auto text-[10px] text-neutral-400">
                          {timeAgo(entry.updatedAt)}
                        </span>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </main>
        </div>
      </div>

      {selectedEntryId && (
        <EntryEditor
          entryId={selectedEntryId}
          onClose={() => {
            setSelectedEntryId(null);
            // A creation can invalidate a list request; always refresh the active filters.
            void loadEntries();
          }}
          onSaved={loadEntries}
          onDeleted={() => {
            setSelectedEntryId(null);
            loadEntries();
          }}
        />
      )}

      {settingsOpen && <SettingsPanel onClose={() => setSettingsOpen(false)} />}
    </div>
  );
}
