"use client";

import { useMemo, useState } from "react";
import { Check, Plus, X } from "lucide-react";
import { useAppData } from "@/context/AppDataContext";
import type { Tag } from "@/lib/types";
import { tagVisibleInSpace } from "@/lib/tag-visibility";

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
  spaceId: string;
  onChange: (tags: Tag[]) => void;
}

export default function TagPicker({ selected, spaceId, onChange }: Props) {
  const { tags } = useAppData();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [newColor, setNewColor] = useState(COLOR_CHOICES[0]);
  const [saveForFuture, setSaveForFuture] = useState(true);
  const [scope, setScope] = useState<"local" | "global" | "">("");

  const suggestions = useMemo(() => {
    const selectedIds = new Set(selected.map((t) => t.id));
    const q = query.trim().toLowerCase();
    return tags
      .filter((tag) => tagVisibleInSpace(tag, spaceId))
      .filter((t) => !selectedIds.has(t.id))
      .filter((t) => (q ? t.name.toLowerCase().includes(q) : t.reusable))
      .slice(0, 8);
  }, [tags, query, selected, spaceId]);

  const possibleMatch = scope !== "" && tags.some((t) => (t.scope ?? "global") === scope && (scope === "global" || t.ownerSpaceId === spaceId) && t.name.toLowerCase() === query.trim().toLowerCase());

  const addExisting = (tag: Tag) => {
    onChange([...selected, tag]);
    setQuery("");
  };

  const removeTag = (id: string) => {
    onChange(selected.filter((t) => t.id !== id));
  };

  return (
    <div className="relative" >
      <div className="tag-picker-row">
        {selected.map((tag) => (
          <span
            key={tag.id}
            className="flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-medium"
            style={{ borderColor: tag.color, backgroundColor: `color-mix(in srgb, ${tag.color} 8%, transparent)`, color: "inherit" }}
          >
            {tag.name}
            <span className="text-[10px] opacity-60">{tag.scope === "local" ? "Local" : "Global"}</span>
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
            if (e.key === "Enter") e.preventDefault();
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
                <span className="text-[10px] text-neutral-400">{tag.scope === "local" ? "Local" : "Global"}</span>
                {!tag.reusable && (
                  <span className="ml-auto text-[10px] text-neutral-400">one-off</span>
                )}
              </button>
            ))}
            {suggestions.length === 0 && (
              <p className="px-2 py-1 text-xs text-neutral-400">No matching tags yet.</p>
            )}
          </div>

          {query.trim() && (
            <div className="mt-3 border-t border-black/10 pt-3 dark:border-white/10">
              <p className="mb-1.5 text-xs text-neutral-500 dark:text-neutral-400">
                New tag “{query.trim()}” (creation paused)
              </p>
              <fieldset className="mb-2 flex gap-3 text-xs"><legend>Tag scope (required)</legend>
                <label><input type="radio" name="tag-scope" value="local" checked={scope === "local"} onChange={() => setScope("local")} /> Local to this Space</label>
                <label><input type="radio" name="tag-scope" value="global" checked={scope === "global"} onChange={() => setScope("global")} /> Global</label>
              </fieldset>
              {possibleMatch && <p role="status" className="text-xs">Possible match only; Unicode-equivalent names may differ. Select an existing tag when applicable.</p>}
              <p role="status" className="text-xs text-amber-700 dark:text-amber-300">Creation is paused until both databases can enforce the Unicode 15.1 name policy.</p>
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
                onClick={() => {}}
                disabled
                className="accent-bg disabled:opacity-40 mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs font-semibold text-white"
              >
                <Plus size={13} /> Create &amp; add tag (paused)
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
