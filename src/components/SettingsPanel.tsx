"use client";

import { useRef, useState } from "react";
import {
  Check,
  Download,
  ImagePlus,
  Loader2,
  Moon,
  Palette,
  Sun,
  Tags,
  Trash2,
  Upload,
  X,
} from "lucide-react";
import { useAppData } from "@/context/AppDataContext";
import { api } from "@/lib/api";
import type { AppSettings } from "@/lib/types";

const ACCENTS = ["#6366f1", "#ec4899", "#f59e0b", "#10b981", "#06b6d4", "#8b5cf6", "#ef4444", "#0ea5e9"];
const FONT_SIZES: { value: AppSettings["fontSize"]; label: string }[] = [
  { value: "sm", label: "Small" },
  { value: "md", label: "Medium" },
  { value: "lg", label: "Large" },
  { value: "xl", label: "Extra large" },
];
const RADII: { value: AppSettings["radius"]; label: string }[] = [
  { value: "none", label: "Square" },
  { value: "sm", label: "Subtle" },
  { value: "md", label: "Soft" },
  { value: "lg", label: "Rounded" },
  { value: "xl", label: "Very round" },
];

export default function SettingsPanel({ onClose }: { onClose: () => void }) {
  const { settings, updateSettings, tags, refreshTags } = useAppData();
  const [appName, setAppName] = useState(settings?.appName ?? "MyVault");
  const [uploadingWallpaper, setUploadingWallpaper] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importSummary, setImportSummary] = useState<string | null>(null);
  const [tab, setTab] = useState<"theme" | "tags" | "data">("theme");
  const wallpaperInputRef = useRef<HTMLInputElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);

  if (!settings) return null;

  const handleWallpaperUpload = async (file: File) => {
    setUploadingWallpaper(true);
    try {
      const { url } = await api.upload(file);
      await updateSettings({ wallpaperUrl: url, wallpaperEnabled: true });
    } catch (err) {
      alert(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploadingWallpaper(false);
    }
  };

  const handleExport = async () => {
    setExporting(true);
    try {
      const data = await api.export();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${(settings.appName || "myvault").toLowerCase().replace(/\s+/g, "-")}-export.json`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setExporting(false);
    }
  };

  const handleImportFile = async (file: File) => {
    setImporting(true);
    setImportSummary(null);
    try {
      const text = await file.text();
      const json = JSON.parse(text);
      const applySettings = confirm(
        "Also restore interface settings (app name, theme, wallpaper) from this file? Click Cancel to only import spaces/entries/tags.",
      );
      const res = await api.import(json, applySettings);
      setImportSummary(
        `Imported ${res.summary.entriesAdded} entries, ${res.summary.spacesAdded} new spaces, ${res.summary.tagsAdded} new tags. Nothing existing was deleted.`,
      );
      await refreshTags();
    } catch (err) {
      alert(err instanceof Error ? err.message : "Import failed. Make sure this is a valid export JSON file.");
    } finally {
      setImporting(false);
    }
  };

  const reusableTags = tags.filter((t) => t.reusable);
  const oneOffTags = tags.filter((t) => !t.reusable);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="pop-in ui-radius flex max-h-[85vh] w-full max-w-xl flex-col overflow-hidden bg-white shadow-2xl dark:bg-neutral-900">
        <div className="flex items-center justify-between border-b border-black/10 px-5 py-4 dark:border-white/10">
          <h2 className="text-lg font-bold text-neutral-900 dark:text-white">Settings</h2>
          <button onClick={onClose} aria-label="Close settings" className="rounded-lg p-1.5 text-neutral-400 hover:bg-black/5 dark:hover:bg-white/10">
            <X size={18} />
          </button>
        </div>

        <div className="flex gap-1 border-b border-black/10 px-5 pt-3 dark:border-white/10">
          {[
            { id: "theme" as const, label: "Appearance", icon: Palette },
            { id: "tags" as const, label: "Tags", icon: Tags },
            { id: "data" as const, label: "Import / Export", icon: Download },
          ].map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => setTab(id)}
              className={`flex items-center gap-1.5 rounded-t-lg px-3 py-2 text-sm font-medium ${
                tab === id
                  ? "accent-text border-b-2"
                  : "text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200"
              }`}
              style={tab === id ? { borderColor: "var(--accent)" } : undefined}
            >
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {tab === "theme" && (
            <div className="flex flex-col gap-6">
              <div>
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">
                  Application name
                </label>
                <div className="mt-1.5 flex gap-2">
                  <input
                    value={appName}
                    onChange={(e) => setAppName(e.target.value)}
                    className="ui-radius flex-1 border border-black/10 bg-white px-3 py-2 text-sm outline-none dark:border-white/10 dark:bg-neutral-800 dark:text-white"
                  />
                  <button
                    onClick={() => updateSettings({ appName: appName.trim() || "MyVault" })}
                    className="accent-bg ui-radius px-4 py-2 text-sm font-semibold text-white"
                  >
                    Save
                  </button>
                </div>
                <p className="mt-1 text-xs text-neutral-400">Shown on the animated start screen.</p>
              </div>

              <div>
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Accent color</label>
                <div className="mt-2 flex flex-wrap gap-2">
                  {ACCENTS.map((c) => (
                    <button
                      key={c}
                      aria-label={`Accent color ${c}`}
                      aria-pressed={settings.accent === c}
                      onClick={() => updateSettings({ accent: c })}
                      className={`h-8 w-8 rounded-full ${settings.accent === c ? "ring-2 ring-offset-2 ring-neutral-500" : ""}`}
                      style={{ background: c }}
                    />
                  ))}
                  <input
                    type="color"
                    aria-label="Custom accent color"
                    value={settings.accent}
                    onChange={(e) => updateSettings({ accent: e.target.value })}
                    className="h-8 w-8 cursor-pointer rounded-full border-none bg-transparent"
                  />
                </div>
              </div>

              <div>
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Theme mode</label>
                <div className="mt-2 flex gap-2">
                  {[
                    { value: "light" as const, label: "Light", icon: Sun },
                    { value: "dark" as const, label: "Dark", icon: Moon },
                    { value: "system" as const, label: "System", icon: Palette },
                  ].map(({ value, label, icon: Icon }) => (
                    <button
                      key={value}
                      onClick={() => updateSettings({ mode: value })}
                      className={`ui-radius flex flex-1 items-center justify-center gap-1.5 border px-3 py-2 text-xs font-medium ${
                        settings.mode === value
                          ? "accent-bg border-transparent text-white"
                          : "border-black/10 text-neutral-500 dark:border-white/10 dark:text-neutral-300"
                      }`}
                    >
                      <Icon size={13} /> {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Text size</label>
                <div className="mt-2 flex flex-wrap gap-2">
                  {FONT_SIZES.map(({ value, label }) => (
                    <button
                      key={value}
                      onClick={() => updateSettings({ fontSize: value })}
                      className={`ui-radius border px-3 py-1.5 text-xs font-medium ${
                        settings.fontSize === value
                          ? "accent-bg border-transparent text-white"
                          : "border-black/10 text-neutral-500 dark:border-white/10 dark:text-neutral-300"
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Corner roundness</label>
                <div className="mt-2 flex flex-wrap gap-2">
                  {RADII.map(({ value, label }) => (
                    <button
                      key={value}
                      onClick={() => updateSettings({ radius: value })}
                      className={`border px-3 py-1.5 text-xs font-medium ${
                        settings.radius === value
                          ? "accent-bg border-transparent text-white"
                          : "border-black/10 text-neutral-500 dark:border-white/10 dark:text-neutral-300"
                      }`}
                      style={{ borderRadius: value === "none" ? 0 : value === "sm" ? 6 : value === "md" ? 10 : value === "lg" ? 14 : 20 }}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="border-t border-black/10 pt-4 dark:border-white/10">
                <label className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Background wallpaper</label>
                <div className="mt-2 flex items-center gap-3">
                  {settings.wallpaperUrl ? (
                    // Wallpaper may be a user-provided URL or local upload.
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={settings.wallpaperUrl} alt="" className="h-16 w-24 rounded-lg object-cover" />
                  ) : (
                    <div className="flex h-16 w-24 items-center justify-center rounded-lg bg-black/5 text-neutral-300 dark:bg-white/10">
                      <ImagePlus size={18} />
                    </div>
                  )}
                  <div className="flex flex-col gap-1.5">
                    <button
                      onClick={() => wallpaperInputRef.current?.click()}
                      className="ui-radius border border-black/10 px-3 py-1.5 text-xs font-medium text-neutral-600 hover:bg-black/5 dark:border-white/10 dark:text-neutral-300 dark:hover:bg-white/10"
                    >
                      {uploadingWallpaper ? "Uploading…" : "Upload image"}
                    </button>
                    {settings.wallpaperUrl && (
                      <button
                        onClick={() => updateSettings({ wallpaperUrl: null, wallpaperEnabled: false })}
                        className="text-xs text-red-400 hover:text-red-500"
                      >
                        Remove wallpaper
                      </button>
                    )}
                  </div>
                  <input
                    ref={wallpaperInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(e) => e.target.files?.[0] && handleWallpaperUpload(e.target.files[0])}
                  />
                </div>

                <label className="mt-3 flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300">
                  <button
                    onClick={() => updateSettings({ wallpaperEnabled: !settings.wallpaperEnabled })}
                    className={`flex h-4 w-4 items-center justify-center rounded border ${
                      settings.wallpaperEnabled ? "accent-bg border-transparent text-white" : "border-neutral-400"
                    }`}
                  >
                    {settings.wallpaperEnabled && <Check size={11} />}
                  </button>
                  Show wallpaper (visibility)
                </label>

                <div className="mt-3">
                  <label className="flex items-center justify-between text-xs text-neutral-500 dark:text-neutral-400">
                    Opacity <span>{settings.wallpaperOpacity}%</span>
                  </label>
                  <input
                    type="range"
                    min={5}
                    max={100}
                    value={settings.wallpaperOpacity}
                    onChange={(e) => updateSettings({ wallpaperOpacity: Number(e.target.value) })}
                    className="w-full accent-[var(--accent)]"
                  />
                </div>
                <div className="mt-2">
                  <label className="flex items-center justify-between text-xs text-neutral-500 dark:text-neutral-400">
                    Blur <span>{settings.wallpaperBlur}px</span>
                  </label>
                  <input
                    type="range"
                    min={0}
                    max={20}
                    value={settings.wallpaperBlur}
                    onChange={(e) => updateSettings({ wallpaperBlur: Number(e.target.value) })}
                    className="w-full accent-[var(--accent)]"
                  />
                </div>
              </div>
            </div>
          )}

          {tab === "tags" && (
            <div className="flex flex-col gap-5">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-neutral-400">Saved reusable tags</p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {reusableTags.length === 0 && <p className="text-xs text-neutral-400">None yet.</p>}
                  {reusableTags.map((tag) => (
                    <span
                      key={tag.id}
                      className="flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium"
                      style={{ background: tag.color, color: tag.textColor }}
                    >
                      {tag.name}
                      <button
                        onClick={async () => {
                          if (confirm(`Delete tag “${tag.name}” from all entries?`)) {
                            await api.tags.remove(tag.id);
                            refreshTags();
                          }
                        }}
                      >
                        <Trash2 size={11} />
                      </button>
                    </span>
                  ))}
                </div>
              </div>
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-neutral-400">One-off tags</p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {oneOffTags.length === 0 && <p className="text-xs text-neutral-400">None yet.</p>}
                  {oneOffTags.map((tag) => (
                    <span
                      key={tag.id}
                      className="flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium opacity-80"
                      style={{ background: tag.color, color: tag.textColor }}
                    >
                      {tag.name}
                      <button
                        onClick={async () => {
                          await api.tags.update(tag.id, { reusable: true });
                          refreshTags();
                        }}
                        title="Make reusable"
                      >
                        <Check size={11} />
                      </button>
                    </span>
                  ))}
                </div>
              </div>
            </div>
          )}

          {tab === "data" && (
            <div className="flex flex-col gap-5">
              <div className="ui-radius border border-black/10 p-4 dark:border-white/10">
                <h3 className="flex items-center gap-2 text-sm font-semibold text-neutral-800 dark:text-white">
                  <Download size={15} /> Export everything
                </h3>
                <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                  Download a complete JSON backup of your spaces, entries, tags and settings.
                </p>
                <button
                  onClick={handleExport}
                  disabled={exporting}
                  className="accent-bg ui-radius mt-3 flex items-center gap-2 px-4 py-2 text-sm font-semibold text-white disabled:opacity-60"
                >
                  {exporting ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
                  Export JSON
                </button>
              </div>

              <div className="ui-radius border border-black/10 p-4 dark:border-white/10">
                <h3 className="flex items-center gap-2 text-sm font-semibold text-neutral-800 dark:text-white">
                  <Upload size={15} /> Import from JSON
                </h3>
                <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                  Restores spaces, tags and entries from a previously exported file. Existing data is
                  never deleted — matching spaces/tags are merged and entries are added alongside
                  what you already have.
                </p>
                <button
                  onClick={() => importInputRef.current?.click()}
                  disabled={importing}
                  className="ui-radius mt-3 flex items-center gap-2 border border-black/10 px-4 py-2 text-sm font-semibold text-neutral-600 hover:bg-black/5 disabled:opacity-60 dark:border-white/10 dark:text-neutral-200 dark:hover:bg-white/10"
                >
                  {importing ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
                  Choose JSON file
                </button>
                <input
                  ref={importInputRef}
                  type="file"
                  accept="application/json"
                  className="hidden"
                  onChange={(e) => {
                    if (e.target.files?.[0]) handleImportFile(e.target.files[0]);
                    e.target.value = "";
                  }}
                />
                {importSummary && (
                  <p className="mt-2 text-xs font-medium text-emerald-500">{importSummary}</p>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
