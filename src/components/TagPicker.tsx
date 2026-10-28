"use client";

import { useMemo, useState } from "react";
import { Check, Plus, X } from "lucide-react";
import { useAppData } from "@/context/AppDataContext";
import { api } from "@/lib/api";
import type { Tag } from "@/lib/types";

const COLOR_CHOICES = [
  "#6366f1",
  "#ec4899",
  "#f59e0b",
  "#10b981",
  "#06b6d4",
  "#8b5cf6",
  "#ef4444",
  "#64748b",
  "#0ea5e9",
  "#84cc16",
];

interface Props {
  selected: Tag[];
  onChange: (tags: Tag[]) => void;
  onPendingChange: (pending: boolean) => void;
}

export default function TagPicker({ selected, onChange, onPendingChange }: Props) {
  const { tags, refreshTags } = useAppData();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [newColor, setNewColor] = useState(COLOR_CHOICES[0]);
  const [saveForFuture, setSaveForFuture] = useState(true);
  const [creating, setCreating] = useState(false);

  const suggestions = useMemo(() => {
    const selectedIds = new Set(selected.map((t) => t.id));
    const q = query.trim().toLowerCase();
    return tags
      .filter((t) => !selectedIds.has(t.id))
      .filter((t) => (q ? t.name.toLowerCase().includes(q) : t.reusable))
      .slice(0, 8);
  }, [tags, query, selected]);

  const exactMatch = tags.some((t) => t.name.toLowerCase() === query.trim().toLowerCase());

  const addExisting = (tag: Tag) => {
    onChange([...selected, tag]);
    setQuery("");
  };

  const removeTag = (id: string) => {
    onChange(selected.filter((t) => t.id !== id));
  };

  const createTag = async () => {
    const name = query.trim();
    if (!name || creating) return;
    setCreating(true);
    onPendingChange(true);
    try {
      const created = await api.tags.create({ name, color: newColor, textColor: "#ffffff", reusable: saveForFuture });
      // Attach the new tag before refreshing the shared tag list.
      onChange([...selected, created]);
      setQuery("");
      await refreshTags();
    } catch (error) {
      alert(error instanceof Error ? error.message : "Couldn’t create the tag.");
    } finally {
      setCreating(false);
      onPendingChange(false);
    }
  };

  return (
    <div className="relative" aria-busy={creating} inert={creating}>
      <div className="tag-picker-row">
        {selected.map((tag) => (
          <span
            key={tag.id}
            className="flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium"
            style={{ background: tag.color, color: tag.textColor }}
          >
            {tag.name}
            <button type="button" onClick={() => removeTag(tag.id)} aria-label={`Remove ${tag.name} tag`} className="opacity-80 hover:opacity-100">
              <X size={11} />
            </button>
          </span>
        ))}
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => setOpen(true)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && query.trim() && !exactMatch) {
              e.preventDefault();
              createTag();
            }
          }}
          placeholder="Add tag…"
          aria-label="Add tag"
          className="tag-picker-input"
        />
      </div>

      {open && (query.trim() || suggestions.length > 0) && (
        <div className="tag-picker-menu ui-radius absolute z-20 mt-2 w-72 border border-black/10 bg-white p-3 shadow-xl dark:border-white/10 dark:bg-neutral-800">
          <div className="flex items-center justify-between">
            <p className="text-[11px] font-semibold uppercase tracking-wide text-neutral-400">Tags</p>
            <button type="button" onClick={() => setOpen(false)} aria-label="Close tags" className="text-neutral-400 hover:text-neutral-600">
              <X size={13} />
            </button>
          </div>

          <div className="mt-2 flex max-h-40 flex-col gap-1 overflow-y-auto">
            {suggestions.map((tag) => (
              <button
                key={tag.id}
                onClick={() => addExisting(tag)}
                className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-black/5 dark:hover:bg-white/10"
              >
                <span className="h-2.5 w-2.5 rounded-full" style={{ background: tag.color }} />
                <span className="text-neutral-700 dark:text-neutral-200">{tag.name}</span>
                {!tag.reusable && (
                  <span className="ml-auto text-[10px] text-neutral-400">one-off</span>
                )}
              </button>
            ))}
            {suggestions.length === 0 && (
              <p className="px-2 py-1 text-xs text-neutral-400">No matching tags yet.</p>
            )}
          </div>

          {query.trim() && !exactMatch && (
            <div className="mt-3 border-t border-black/10 pt-3 dark:border-white/10">
              <p className="mb-1.5 text-xs text-neutral-500 dark:text-neutral-400">
                Create new tag “{query.trim()}”
              </p>
              <div className="flex flex-wrap gap-1.5">
                {COLOR_CHOICES.map((c) => (
                  <button
                    key={c}
                    aria-label={`Tag color ${c}`}
                    aria-pressed={newColor === c}
                    onClick={() => setNewColor(c)}
                    className={`h-5 w-5 rounded-full ${newColor === c ? "ring-2 ring-offset-1 ring-neutral-500" : ""}`}
                    style={{ background: c }}
                  />
                ))}
              </div>
              <label className="mt-2 flex items-center gap-2 text-xs text-neutral-600 dark:text-neutral-300">
                <button
                  type="button"
                  aria-label="Save this tag for future entries"
                  aria-pressed={saveForFuture}
                  onClick={() => setSaveForFuture((v) => !v)}
                  className={`flex h-4 w-4 items-center justify-center rounded border ${
                    saveForFuture ? "accent-bg border-transparent text-white" : "border-neutral-400"
                  }`}
                >
                  {saveForFuture && <Check size={11} />}
                </button>
                Save this tag (color &amp; style) for future entries
              </label>
              <button
                onClick={createTag}
                className="accent-bg mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs font-semibold text-white"
              >
                <Plus size={13} /> Create &amp; add tag
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
