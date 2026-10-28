"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Check,
  Code2,
  Columns2,
  Eye,
  FileUp,
  ImagePlus,
  Loader2,
  Pin,
  PinOff,
  Replace,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { api } from "@/lib/api";
import type { Entry, Tag } from "@/lib/types";
import TagPicker from "@/components/TagPicker";
import Markdown from "@/components/Markdown";

type ViewMode = "edit" | "split" | "preview";

function escapeRegExp(str: string) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function escapeHtml(str: string) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

interface Props {
  entryId: string;
  onClose: () => void;
  onSaved: () => void;
  onDeleted: () => void;
}

export default function EntryEditor({ entryId, onClose, onSaved, onDeleted }: Props) {
  const [entry, setEntry] = useState<Entry | null>(null);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [coverImage, setCoverImage] = useState<string | null>(null);
  const [pinned, setPinned] = useState(false);
  const [entryTags, setEntryTags] = useState<Tag[]>([]);
  const [viewMode, setViewMode] = useState<ViewMode>("edit");
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved">("idle");
  const [uploadingCover, setUploadingCover] = useState(false);

  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState("");
  const [replaceQuery, setReplaceQuery] = useState("");
  const [wholeWord, setWholeWord] = useState(false);

  const [pendingImport, setPendingImport] = useState<string | null>(null);
  const [pendingImportName, setPendingImportName] = useState("");

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const coverInputRef = useRef<HTMLInputElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loaded = useRef(false);

  useEffect(() => {
    let active = true;
    api.entries.get(entryId).then((data) => {
      if (!active) return;
      setEntry(data);
      setTitle(data.title);
      setContent(data.content);
      setCoverImage(data.coverImage);
      setPinned(data.pinned);
      setEntryTags(data.tags);
      loaded.current = true;
    });
    return () => {
      active = false;
    };
  }, [entryId]);

  const persist = useCallback(
    async (patch: Partial<Entry> & { tagIds?: string[] }) => {
      setSaveState("saving");
      await api.entries.update(entryId, patch);
      setSaveState("saved");
      onSaved();
    },
    [entryId, onSaved],
  );

  const scheduleSave = useCallback(
    (patch: Partial<Entry> & { tagIds?: string[] }) => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
      saveTimer.current = setTimeout(() => persist(patch), 700);
    },
    [persist],
  );

  useEffect(() => {
    if (!loaded.current) return;
    scheduleSave({ title, content });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, content]);

  useEffect(() => {
    return () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    };
  }, []);

  const handleTagsChange = (next: Tag[]) => {
    setEntryTags(next);
    persist({ tagIds: next.map((t) => t.id) });
  };

  const togglePin = () => {
    setPinned((p) => {
      persist({ pinned: !p });
      return !p;
    });
  };

  const handleCoverUpload = async (file: File) => {
    setUploadingCover(true);
    try {
      const { url } = await api.upload(file);
      setCoverImage(url);
      persist({ coverImage: url });
    } catch (err) {
      alert(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploadingCover(false);
    }
  };

  const removeCover = () => {
    setCoverImage(null);
    persist({ coverImage: null });
  };

  const handleDelete = async () => {
    if (!confirm("Delete this entry permanently?")) return;
    await api.entries.remove(entryId);
    onDeleted();
  };

  // --- Import .txt / .md file into this entry ---
  const handleImportFile = async (file: File) => {
    const ok = /\.(txt|md|markdown)$/i.test(file.name) || file.type.startsWith("text/");
    if (!ok) {
      alert("Only .txt or .md files can be imported into an entry.");
      return;
    }
    const text = await file.text();
    if (!content.trim()) {
      setContent(text);
      if (!title.trim() || title === "Untitled entry") {
        setTitle(file.name.replace(/\.[^.]+$/, ""));
      }
    } else {
      setPendingImport(text);
      setPendingImportName(file.name);
    }
  };

  const resolveImport = (mode: "replace" | "insert" | "cancel") => {
    if (mode === "replace" && pendingImport !== null) {
      setContent(pendingImport);
    } else if (mode === "insert" && pendingImport !== null) {
      const el = textareaRef.current;
      if (el) {
        const start = el.selectionStart ?? content.length;
        const end = el.selectionEnd ?? content.length;
        const next = content.slice(0, start) + pendingImport + content.slice(end);
        setContent(next);
      } else {
        setContent((prev) => `${prev}\n\n${pendingImport}`);
      }
    }
    setPendingImport(null);
    setPendingImportName("");
  };

  // --- Find & replace ---
  const buildRegex = (flags: string) => {
    if (!findQuery) return null;
    const pattern = wholeWord ? `\\b${escapeRegExp(findQuery)}\\b` : escapeRegExp(findQuery);
    try {
      return new RegExp(pattern, flags);
    } catch {
      return null;
    }
  };

  const matchCount = (() => {
    const re = buildRegex("gi");
    if (!re) return 0;
    return (content.match(re) ?? []).length;
  })();

  const findNext = () => {
    const el = textareaRef.current;
    const re = buildRegex("gi");
    if (!el || !re) return;
    re.lastIndex = 0;
    const from = el.selectionEnd ?? 0;
    let match: RegExpExecArray | null;
    let found: RegExpExecArray | null = null;
    while ((match = re.exec(content))) {
      if (match.index >= from) {
        found = match;
        break;
      }
    }
    if (!found) {
      re.lastIndex = 0;
      found = re.exec(content);
    }
    if (found) {
      el.focus();
      el.setSelectionRange(found.index, found.index + found[0].length);
      const lineHeight = 20;
      const linesBefore = content.slice(0, found.index).split("\n").length;
      el.scrollTop = Math.max(0, (linesBefore - 3) * lineHeight);
    }
  };

  const replaceCurrent = () => {
    const el = textareaRef.current;
    if (!el || !findQuery) return;
    const selected = content.slice(el.selectionStart, el.selectionEnd);
    const re = buildRegex("i");
    if (selected && re && re.test(selected)) {
      const next = content.slice(0, el.selectionStart) + replaceQuery + content.slice(el.selectionEnd);
      setContent(next);
      setTimeout(findNext, 0);
    } else {
      findNext();
    }
  };

  const replaceAll = () => {
    const re = buildRegex("gi");
    if (!re) return;
    setContent((prev) => prev.replace(re, replaceQuery));
  };

  const highlightedHtml = (() => {
    if (!findQuery) return escapeHtml(content);
    const re = buildRegex("gi");
    if (!re) return escapeHtml(content);
    return escapeHtml(content).replace(
      new RegExp(wholeWord ? `\\b${escapeRegExp(findQuery)}\\b` : escapeRegExp(findQuery), "gi"),
      (m) => `<mark>${m}</mark>`,
    );
  })();

  if (!entry) {
    return (
      <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 backdrop-blur-sm">
        <Loader2 className="animate-spin text-white" size={28} />
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/40 backdrop-blur-sm">
      <div className="pop-in flex h-full w-full max-w-3xl flex-col overflow-hidden bg-white shadow-2xl dark:bg-slate-900">
        {/* Top bar */}
        <div className="flex items-center gap-2 border-b border-black/10 px-4 py-3 dark:border-white/10">
          <button
            onClick={onClose}
            className="rounded-lg p-2 text-slate-500 hover:bg-black/5 dark:text-slate-300 dark:hover:bg-white/10"
          >
            <X size={18} />
          </button>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            {saveState === "saving" && <Loader2 size={13} className="animate-spin" />}
            {saveState === "saving" ? "Saving…" : saveState === "saved" ? "Saved" : ""}
          </div>
          <div className="ml-auto flex items-center gap-1">
            <button
              onClick={togglePin}
              className={`rounded-lg p-2 ${pinned ? "accent-text" : "text-slate-400"} hover:bg-black/5 dark:hover:bg-white/10`}
              title={pinned ? "Unpin" : "Pin entry"}
            >
              {pinned ? <Pin size={17} /> : <PinOff size={17} />}
            </button>
            <button
              onClick={() => setFindOpen((v) => !v)}
              className={`rounded-lg p-2 ${findOpen ? "accent-bg text-white" : "text-slate-400"} hover:bg-black/5 dark:hover:bg-white/10`}
              title="Find & replace"
            >
              <Search size={17} />
            </button>
            <div className="mx-1 flex items-center rounded-lg border border-black/10 p-0.5 dark:border-white/10">
              <button
                onClick={() => setViewMode("edit")}
                className={`rounded-md p-1.5 ${viewMode === "edit" ? "accent-bg text-white" : "text-slate-400"}`}
                title="Edit"
              >
                <Code2 size={15} />
              </button>
              <button
                onClick={() => setViewMode("split")}
                className={`rounded-md p-1.5 ${viewMode === "split" ? "accent-bg text-white" : "text-slate-400"}`}
                title="Split view"
              >
                <Columns2 size={15} />
              </button>
              <button
                onClick={() => setViewMode("preview")}
                className={`rounded-md p-1.5 ${viewMode === "preview" ? "accent-bg text-white" : "text-slate-400"}`}
                title="Preview"
              >
                <Eye size={15} />
              </button>
            </div>
            <button
              onClick={() => importInputRef.current?.click()}
              className="rounded-lg p-2 text-slate-400 hover:bg-black/5 dark:hover:bg-white/10"
              title="Import .txt / .md file"
            >
              <FileUp size={17} />
            </button>
            <button
              onClick={handleDelete}
              className="rounded-lg p-2 text-red-400 hover:bg-red-50 dark:hover:bg-red-500/10"
              title="Delete entry"
            >
              <Trash2 size={17} />
            </button>
          </div>
        </div>

        {/* Find & replace bar */}
        {findOpen && (
          <div className="flex flex-wrap items-center gap-2 border-b border-black/10 bg-slate-50 px-4 py-2.5 dark:border-white/10 dark:bg-slate-800/60">
            <input
              value={findQuery}
              onChange={(e) => setFindQuery(e.target.value)}
              placeholder="Find word or phrase…"
              className="ui-radius min-w-[140px] flex-1 border border-black/10 bg-white px-3 py-1.5 text-sm outline-none dark:border-white/10 dark:bg-slate-900 dark:text-white"
            />
            <input
              value={replaceQuery}
              onChange={(e) => setReplaceQuery(e.target.value)}
              placeholder="Replace with…"
              className="ui-radius min-w-[140px] flex-1 border border-black/10 bg-white px-3 py-1.5 text-sm outline-none dark:border-white/10 dark:bg-slate-900 dark:text-white"
            />
            <label className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
              <input type="checkbox" checked={wholeWord} onChange={(e) => setWholeWord(e.target.checked)} />
              Whole word
            </label>
            <span className="text-xs text-slate-400">{matchCount} match{matchCount === 1 ? "" : "es"}</span>
            <button onClick={findNext} className="ui-radius border border-black/10 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-black/5 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/10">
              Find next
            </button>
            <button onClick={replaceCurrent} className="ui-radius border border-black/10 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-black/5 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/10">
              Replace
            </button>
            <button
              onClick={replaceAll}
              className="ui-radius accent-bg flex items-center gap-1 px-3 py-1.5 text-xs font-semibold text-white"
            >
              <Replace size={13} /> Replace all
            </button>
          </div>
        )}

        {/* Cover */}
        <div className="relative">
          {coverImage ? (
            <div className="relative h-44 w-full">
              <img src={coverImage} alt="" className="h-full w-full object-cover" />
              <button
                onClick={removeCover}
                className="absolute right-3 top-3 rounded-full bg-black/50 p-1.5 text-white hover:bg-black/70"
              >
                <X size={14} />
              </button>
            </div>
          ) : (
            <button
              onClick={() => coverInputRef.current?.click()}
              className="flex h-20 w-full items-center justify-center gap-2 border-b border-dashed border-black/10 text-sm text-slate-400 hover:bg-black/5 dark:border-white/10 dark:hover:bg-white/5"
            >
              {uploadingCover ? <Loader2 size={16} className="animate-spin" /> : <ImagePlus size={16} />}
              Add cover image (optional)
            </button>
          )}
          <input
            ref={coverInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => e.target.files?.[0] && handleCoverUpload(e.target.files[0])}
          />
          <input
            ref={importInputRef}
            type="file"
            accept=".txt,.md,.markdown,text/plain,text/markdown"
            className="hidden"
            onChange={(e) => {
              if (e.target.files?.[0]) handleImportFile(e.target.files[0]);
              e.target.value = "";
            }}
          />
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Entry title / manual name"
            className="w-full border-none bg-transparent text-2xl font-bold text-slate-900 outline-none dark:text-white"
          />

          <div className="mt-3">
            <TagPicker selected={entryTags} onChange={handleTagsChange} />
          </div>

          <div className="mt-4 grid gap-4" style={{ gridTemplateColumns: viewMode === "split" ? "1fr 1fr" : "1fr" }}>
            {viewMode !== "preview" && (
              <div className="relative">
                {findOpen && findQuery ? (
                  <div
                    className="whitespace-pre-wrap break-words rounded-xl border border-black/10 p-3 font-mono text-sm leading-relaxed text-slate-800 dark:border-white/10 dark:text-slate-100"
                    dangerouslySetInnerHTML={{ __html: `${highlightedHtml}&nbsp;` }}
                  />
                ) : (
                  <textarea
                    ref={textareaRef}
                    value={content}
                    onChange={(e) => setContent(e.target.value)}
                    placeholder="Write in Markdown — # headings, **bold**, - lists, `code`, > quotes…"
                    className="h-[48vh] w-full resize-none rounded-xl border border-black/10 bg-transparent p-3 font-mono text-sm leading-relaxed text-slate-800 outline-none accent-ring dark:border-white/10 dark:text-slate-100"
                  />
                )}
              </div>
            )}
            {viewMode !== "edit" && (
              <div className="h-[48vh] overflow-y-auto rounded-xl border border-black/10 p-3 dark:border-white/10">
                <Markdown content={content} />
              </div>
            )}
          </div>
        </div>
      </div>

      {pendingImport !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="pop-in ui-radius w-full max-w-md bg-white p-5 shadow-2xl dark:bg-slate-800">
            <h3 className="text-base font-bold text-slate-900 dark:text-white">
              Import “{pendingImportName}”?
            </h3>
            <p className="mt-2 text-sm text-slate-500 dark:text-slate-300">
              This entry already has content. Replace it entirely, or insert the file’s content at
              your cursor without removing what’s already here?
            </p>
            <div className="mt-4 flex flex-col gap-2">
              <button
                onClick={() => resolveImport("insert")}
                className="accent-bg ui-radius flex items-center justify-center gap-2 py-2 text-sm font-semibold text-white"
              >
                <Check size={15} /> Insert / merge here (keep existing content)
              </button>
              <button
                onClick={() => resolveImport("replace")}
                className="ui-radius border border-red-300 py-2 text-sm font-semibold text-red-500 hover:bg-red-50 dark:border-red-500/40 dark:hover:bg-red-500/10"
              >
                Replace entire entry content
              </button>
              <button
                onClick={() => resolveImport("cancel")}
                className="py-2 text-sm text-slate-400 hover:text-slate-600"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
