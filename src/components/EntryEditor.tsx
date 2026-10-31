"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import {
  AlertCircle,
  Check,
  Code2,
  Eye,
  FileUp,
  ImagePlus,
  Loader2,
  Pin,
  PinOff,
  Replace,
  Search,
  Trash2,
  Undo2,
  Redo2,
  X,
} from "lucide-react";
import { api } from "@/lib/api";
import type { Entry, Tag } from "@/lib/types";
import TagPicker from "@/components/TagPicker";
import Markdown from "@/components/Markdown";
import { coalescibleInputKey, EditorHistory, type EditorField, type EditorSelection, type EditorSnapshot } from "@/lib/editor-history";

function searchRegex(query: string, wholeWord: boolean, flags: string) {
  if (!query) return null;
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(wholeWord ? `\\b${escaped}\\b` : escaped, flags);
}

interface Props {
  entryId: string;
  onClose: () => void;
  onSaved: () => void;
  onDeleted: () => void;
}

type Draft = { title: string; content: string };
type EntryPatch = Partial<Entry> & { tagIds?: string[] };

type SaveState = "saving" | "saved" | "error";

export default function EntryEditor({ entryId, onClose, onSaved, onDeleted }: Props) {
  const [entry, setEntry] = useState<Entry | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [coverImage, setCoverImage] = useState<string | null>(null);
  const [pinned, setPinned] = useState(false);
  const [entryTags, setEntryTags] = useState<Tag[]>([]);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [closing, setClosing] = useState(false);
  const [uploadingCover, setUploadingCover] = useState(false);
  const [importingFile, setImportingFile] = useState(false);

  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState("");
  const [replaceQuery, setReplaceQuery] = useState("");
  const [wholeWord, setWholeWord] = useState(false);

  const [pendingImport, setPendingImport] = useState<string | null>(null);
  const [pendingImportName, setPendingImportName] = useState("");

  const titleRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);
  const historyRef = useRef<EditorHistory | null>(null);
  const selectionRef = useRef<EditorSelection>({ field: "content", start: 0, end: 0 });
  const [historyRestore, setHistoryRestore] = useState<{ selection: EditorSelection; token: number } | null>(null);
  const historyRestoreToken = useRef(0);
  const handledRestoreToken = useRef(0);
  const [historyAvailability, setHistoryAvailability] = useState({ canUndo: false, canRedo: false });
  const coverInputRef = useRef<HTMLInputElement>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const previewButtonRef = useRef<HTMLButtonElement>(null);
  const previewCloseRef = useRef<HTMLButtonElement>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const draftRef = useRef<Draft>({ title: "", content: "" });
  const dirtyDraft = useRef(false);
  const saveQueue = useRef<Promise<void>>(Promise.resolve());
  const draftSave = useRef<Promise<void> | null>(null);
  const pendingSaves = useRef(0);
  const failedPatches = useRef<EntryPatch>({});
  const onSavedRef = useRef(onSaved);
  const syncHistoryAvailability = () => {
    const history = historyRef.current;
    setHistoryAvailability({ canUndo: history?.canUndo ?? false, canRedo: history?.canRedo ?? false });
  };

  useEffect(() => {
    onSavedRef.current = onSaved;
  }, [onSaved]);

  useEffect(() => {
    let active = true;
    historyRef.current = null;
    selectionRef.current = { field: "content", start: 0, end: 0 };
    api.entries.get(entryId).then((data) => {
      if (!active) return;
      const initialSelection = { field: "content" as const, start: 0, end: 0 };
      historyRef.current = new EditorHistory({ title: data.title, content: data.content, selection: initialSelection });
      setHistoryAvailability({ canUndo: false, canRedo: false });
      selectionRef.current = initialSelection;
      setEntry(data);
      setTitle(data.title);
      setContent(data.content);
      draftRef.current = { title: data.title, content: data.content };
      setCoverImage(data.coverImage);
      setPinned(data.pinned);
      setEntryTags(data.tags);
    }).catch(() => {
      if (active) setLoadError(true);
    });
    return () => {
      active = false;
    };
  }, [entryId]);

  useEffect(() => () => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
  }, []);

  useEffect(() => {
    if (!previewOpen) return;
    previewCloseRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setPreviewOpen(false);
        previewButtonRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [previewOpen]);

  // Serialize patches so a slower earlier save can never overwrite a later one.
  const persist = useCallback((patch: EntryPatch) => {
    setSaveState("saving");
    pendingSaves.current += 1;
    const request = saveQueue.current.then(async () => {
      await api.entries.update(entryId, patch);
    });
    saveQueue.current = request.then(() => undefined, () => undefined);
    return request.then(() => {
      pendingSaves.current -= 1;
      // A later successful patch supersedes an earlier failed value for the same field.
      const remaining = { ...failedPatches.current };
      for (const key of Object.keys(patch) as (keyof EntryPatch)[]) delete remaining[key];
      failedPatches.current = remaining;
      if (!dirtyDraft.current && pendingSaves.current === 0 && Object.keys(remaining).length === 0) setSaveState("saved");
      onSavedRef.current();
    }, (error: unknown) => {
      pendingSaves.current -= 1;
      failedPatches.current = { ...failedPatches.current, ...patch };
      setSaveState("error");
      throw error;
    });
  }, [entryId]);

  const scheduleDraftSave = (draft: Draft) => {
    dirtyDraft.current = true;
    setSaveState("saving");
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      saveTimer.current = null;
      dirtyDraft.current = false;
      draftSave.current = persist(draft);
      void draftSave.current.catch(() => {
        dirtyDraft.current = true;
      });
    }, 700);
  };

  const applyDraft = (
    nextDraft: Draft,
    field: EditorField,
    selection: EditorSelection,
    coalesceKey: string | null = null,
  ) => {
    const history = historyRef.current;
    history?.updateSelection(selectionRef.current);
    history?.record({ ...nextDraft, selection }, { field, coalesceKey });
    syncHistoryAvailability();
    selectionRef.current = selection;
    setTitle(nextDraft.title);
    setContent(nextDraft.content);
    draftRef.current = nextDraft;
    scheduleDraftSave(nextDraft);
  };

  const rememberSelection = (field: EditorField, element: HTMLInputElement | HTMLTextAreaElement) => {
    const selection = {
      field,
      start: element.selectionStart ?? 0,
      end: element.selectionEnd ?? element.selectionStart ?? 0,
    };
    selectionRef.current = selection;
    historyRef.current?.updateSelection(selection);
  };

  const restoreHistorySnapshot = (snapshot: EditorSnapshot | null) => {
    if (!snapshot) return;
    setTitle(snapshot.title);
    setContent(snapshot.content);
    draftRef.current = { title: snapshot.title, content: snapshot.content };
    selectionRef.current = snapshot.selection;
    scheduleDraftSave(draftRef.current);
    historyRestoreToken.current += 1;
    setHistoryRestore({ selection: { ...snapshot.selection }, token: historyRestoreToken.current });
    syncHistoryAvailability();
  };

  const undo = () => restoreHistorySnapshot(historyRef.current?.undo() ?? null);
  const redo = () => restoreHistorySnapshot(historyRef.current?.redo() ?? null);

  useLayoutEffect(() => {
    if (!historyRestore || handledRestoreToken.current === historyRestore.token) return;
    handledRestoreToken.current = historyRestore.token;
    const { selection } = historyRestore;
    const element = selection.field === "title" ? titleRef.current : textareaRef.current;
    if (element) {
      element.focus();
      const end = Math.min(selection.end, element.value.length);
      element.setSelectionRange(Math.min(selection.start, end), end);
      selectionRef.current = { ...selection, start: Math.min(selection.start, end), end };
      historyRef.current?.updateSelection(selectionRef.current);
    }
  }, [historyRestore, title, content]);

  const handleHistoryKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const target = event.target;
    if ((target instanceof HTMLInputElement && target !== titleRef.current) ||
        (target instanceof HTMLTextAreaElement && target !== textareaRef.current)) return;
    if (event.nativeEvent.isComposing || event.altKey || !(event.ctrlKey || event.metaKey)) return;
    const key = event.key.toLowerCase();
    if (key === "z" && event.shiftKey) {
      event.preventDefault();
      redo();
    } else if (key === "z") {
      event.preventDefault();
      undo();
    } else if (key === "y") {
      event.preventDefault();
      redo();
    }
  };

  const handleClose = async () => {
    if (closing || uploadingCover || importingFile) return;
    setClosing(true);
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
      saveTimer.current = null;
    }
    // Wait for in-flight changes, then retry the latest draft or other failed patches.
    await draftSave.current?.catch(() => undefined);
    await saveQueue.current;
    if (dirtyDraft.current) {
      dirtyDraft.current = false;
      try {
        await persist(draftRef.current);
      } catch {
        dirtyDraft.current = true;
        setClosing(false);
        return;
      }
    }
    await saveQueue.current;
    if (Object.keys(failedPatches.current).length > 0) {
      try {
        await persist({ ...failedPatches.current });
      } catch {
        setClosing(false);
        return;
      }
    }
    onClose();
  };

  const handleTagsChange = (next: Tag[]) => {
    setEntryTags(next);
    void persist({ tagIds: next.map((tag) => tag.id) }).catch(() => undefined);
  };

  const togglePin = () => {
    const next = !pinned;
    setPinned(next);
    void persist({ pinned: next }).catch(() => undefined);
  };

  const handleCoverUpload = async (file: File) => {
    setUploadingCover(true);
    try {
      const { url } = await api.upload(file);
      setCoverImage(url);
      await persist({ coverImage: url });
    } catch (err) {
      alert(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploadingCover(false);
    }
  };

  const removeCover = () => {
    setCoverImage(null);
    void persist({ coverImage: null }).catch(() => undefined);
  };

  const handleDelete = async () => {
    if (closing || uploadingCover || importingFile) return;
    if (!confirm("Delete this entry permanently?")) return;
    setClosing(true);
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    try {
      await saveQueue.current;
      await api.entries.remove(entryId);
      dirtyDraft.current = false;
      onDeleted();
    } catch (error) {
      // A failed deletion must not silently cancel a pending draft save.
      setClosing(false);
      if (dirtyDraft.current) scheduleDraftSave(draftRef.current);
      alert(error instanceof Error ? error.message : "Couldn’t delete this entry.");
    }
  };

  const handleImportFile = async (file: File) => {
    const ok = /\.(txt|md|markdown)$/i.test(file.name) || file.type.startsWith("text/");
    if (!ok) {
      alert("Only .txt or .md files can be imported into an entry.");
      return;
    }
    setImportingFile(true);
    try {
      const text = await file.text();
      if (!content.trim()) {
        const importedTitle = !title.trim() || title === "Untitled entry"
          ? file.name.replace(/\.[^.]+$/, "")
          : title;
        applyDraft(
          { title: importedTitle, content: text },
          "content",
          { field: "content", start: text.length, end: text.length },
        );
        requestAnimationFrame(() => textareaRef.current?.focus());
      } else {
        setPendingImport(text);
        setPendingImportName(file.name);
      }
    } catch (error) {
      alert(error instanceof Error ? error.message : "Couldn’t read this file.");
    } finally {
      setImportingFile(false);
    }
  };

  const resolveImport = (mode: "replace" | "insert" | "cancel") => {
    if (mode === "replace" && pendingImport !== null) {
      applyDraft(
        { ...draftRef.current, content: pendingImport },
        "content",
        { field: "content", start: pendingImport.length, end: pendingImport.length },
      );
    } else if (mode === "insert" && pendingImport !== null) {
      const el = textareaRef.current;
      const currentContent = draftRef.current.content;
      if (el) {
        const start = el.selectionStart ?? currentContent.length;
        const end = el.selectionEnd ?? currentContent.length;
        const nextContent = currentContent.slice(0, start) + pendingImport + currentContent.slice(end);
        const cursor = start + pendingImport.length;
        applyDraft(
          { ...draftRef.current, content: nextContent },
          "content",
          { field: "content", start: cursor, end: cursor },
        );
      } else {
        const nextContent = `${currentContent}\n\n${pendingImport}`;
        applyDraft(
          { ...draftRef.current, content: nextContent },
          "content",
          { field: "content", start: nextContent.length, end: nextContent.length },
        );
      }
    }
    setPendingImport(null);
    setPendingImportName("");
  };

  const matchCount = useMemo(() => {
    const re = searchRegex(findQuery, wholeWord, "gi");
    return re ? (content.match(re) ?? []).length : 0;
  }, [content, findQuery, wholeWord]);

  const highlightedContent = useMemo(() => {
    const re = findOpen ? searchRegex(findQuery, wholeWord, "gi") : null;
    if (!re || !content) return null;
    const parts: ReactNode[] = [];
    let lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = re.exec(content))) {
      parts.push(content.slice(lastIndex, match.index));
      parts.push(<mark key={match.index}>{match[0]}</mark>);
      lastIndex = match.index + match[0].length;
    }
    parts.push(content.slice(lastIndex), "\u200b");
    return parts;
  }, [content, findOpen, findQuery, wholeWord]);

  useEffect(() => {
    if (highlightRef.current && textareaRef.current) {
      highlightRef.current.scrollTop = textareaRef.current.scrollTop;
      highlightRef.current.scrollLeft = textareaRef.current.scrollLeft;
    }
  }, [content, findOpen, findQuery, wholeWord]);

  const selectNextMatch = (text: string, from: number) => {
    const el = textareaRef.current;
    const re = searchRegex(findQuery, wholeWord, "gi");
    if (!el || !re) return;
    re.lastIndex = from;
    let match = re.exec(text);
    if (!match) {
      re.lastIndex = 0;
      match = re.exec(text);
    }
    if (match) {
      el.focus();
      el.setSelectionRange(match.index, match.index + match[0].length);
      rememberSelection("content", el);
      const lineHeight = parseFloat(window.getComputedStyle(el).lineHeight) || 24;
      const linesBefore = text.slice(0, match.index).split("\n").length;
      el.scrollTop = Math.max(0, (linesBefore - 3) * lineHeight);
      if (highlightRef.current) highlightRef.current.scrollTop = el.scrollTop;
    }
  };

  const findNext = () => selectNextMatch(content, textareaRef.current?.selectionEnd ?? 0);

  const replaceCurrent = () => {
    const el = textareaRef.current;
    if (!el || !findQuery) return;
    const selected = content.slice(el.selectionStart, el.selectionEnd);
    const match = searchRegex(findQuery, wholeWord, "i")?.exec(selected);
    if (selected && match?.[0] === selected) {
      const start = el.selectionStart;
      const next = content.slice(0, start) + replaceQuery + content.slice(el.selectionEnd);
      const cursor = start + replaceQuery.length;
      applyDraft(
        { ...draftRef.current, content: next },
        "content",
        { field: "content", start: cursor, end: cursor },
      );
      requestAnimationFrame(() => selectNextMatch(next, cursor));
    } else {
      findNext();
    }
  };

  const replaceAll = () => {
    const re = searchRegex(findQuery, wholeWord, "gi");
    if (!re) return;
    const next = content.replace(re, () => replaceQuery);
    applyDraft(
      { ...draftRef.current, content: next },
      "content",
      { field: "content", start: next.length, end: next.length },
    );
  };

  if (!entry) {
    return (
      <div className="entry-editor items-center justify-center">
        {loadError ? (
          <div className="flex flex-col items-center gap-3 text-sm">
            <p>Couldn’t open this entry.</p>
            <button type="button" onClick={onClose} className="editor-find-action">Go back</button>
          </div>
        ) : <Loader2 className="animate-spin" size={24} aria-label="Loading entry" />}
      </div>
    );
  }

  const closePreview = () => {
    setPreviewOpen(false);
    previewButtonRef.current?.focus();
  };
  const processing = uploadingCover || importingFile;
  const displayedSaveState = processing && saveState !== "error" ? "saving" : saveState;

  return (
    <div className="entry-editor" aria-label="Entry editor" onKeyDown={handleHistoryKeyDown}>
      <header className="editor-toolbar">
        <div className="editor-toolbar-start">
          <button type="button" onClick={handleClose} disabled={closing || processing} className="editor-icon-button" aria-label="Close editor" title="Close editor">
            <X size={19} />
          </button>
          <div className={`editor-save-status ${displayedSaveState === "error" ? "is-error" : ""}`} role="status" aria-live="polite" title={displayedSaveState === "error" ? "Couldn’t save. Try closing again." : undefined}>
            {displayedSaveState === "saving" ? <Loader2 size={13} className="animate-spin" /> : displayedSaveState === "saved" ? <Check size={13} /> : <AlertCircle size={14} />}
            <span>{displayedSaveState === "saving" ? "Saving…" : displayedSaveState === "saved" ? "Saved" : "Couldn’t save"}</span>
          </div>
        </div>
        <div className="editor-toolbar-actions" inert={closing}>
          <button type="button" onClick={togglePin} className={`editor-icon-button ${pinned ? "is-pinned" : ""}`} aria-label={pinned ? "Unpin entry" : "Pin entry"} title={pinned ? "Unpin entry" : "Pin entry"}>
            {pinned ? <Pin size={17} /> : <PinOff size={17} />}
          </button>
          <button type="button" onClick={() => setFindOpen((open) => !open)} className={`editor-icon-button ${findOpen ? "is-active" : ""}`} aria-label="Find and replace" aria-pressed={findOpen} title="Find & replace">
            <Search size={17} />
          </button>
          <div className="editor-view-toggle" role="group" aria-label="Editor view">
            <button type="button" onClick={() => setPreviewOpen(false)} className={`editor-icon-button ${!previewOpen ? "is-active" : ""}`} aria-label="Write" aria-pressed={!previewOpen} title="Write">
              <Code2 size={17} />
            </button>
            <button ref={previewButtonRef} type="button" onClick={() => setPreviewOpen((open) => !open)} className={`editor-icon-button ${previewOpen ? "is-active" : ""}`} aria-label="Preview" aria-pressed={previewOpen} aria-expanded={previewOpen} aria-controls="entry-preview" title="Preview">
              <Eye size={17} />
            </button>
          </div>
          <button type="button" onClick={() => importInputRef.current?.click()} disabled={importingFile} className="editor-icon-button" aria-label="Import text or Markdown file" title="Import .txt / .md file">
            <FileUp size={17} />
          </button>
          <button type="button" onClick={handleDelete} disabled={processing} className="editor-icon-button is-danger" aria-label="Delete entry" title="Delete entry">
            <Trash2 size={17} />
          </button>
        </div>
      </header>

      {findOpen && (
        <div className="editor-find-bar" inert={closing}>
          <input value={findQuery} onChange={(event) => setFindQuery(event.target.value)} placeholder="Find word or phrase…" aria-label="Find word or phrase" className="editor-find-input" />
          <input value={replaceQuery} onChange={(event) => setReplaceQuery(event.target.value)} placeholder="Replace with…" aria-label="Replace with" className="editor-find-input" />
          <label className="editor-find-whole flex items-center gap-1.5 whitespace-nowrap">
            <input type="checkbox" checked={wholeWord} onChange={(event) => setWholeWord(event.target.checked)} />
            Whole word
          </label>
          <span aria-live="polite">{matchCount} match{matchCount === 1 ? "" : "es"}</span>
          <button type="button" onClick={findNext} className="editor-find-action">Find next</button>
          <button type="button" onClick={replaceCurrent} className="editor-find-action">Replace</button>
          <button type="button" onClick={replaceAll} className="editor-find-action editor-find-action-primary flex items-center gap-1">
            <Replace size={13} /> Replace all
          </button>
        </div>
      )}

      <div className="editor-document" inert={closing}>
        {coverImage ? (
          <div className="editor-cover-image">
            {/* User-provided uploads can be local paths or remote URLs. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={coverImage} alt="Entry cover" />
            <button type="button" onClick={removeCover} className="editor-cover-remove" aria-label="Remove cover image" title="Remove cover image"><X size={16} /></button>
          </div>
        ) : (
          <button type="button" onClick={() => coverInputRef.current?.click()} disabled={uploadingCover} className="editor-cover-add">
            {uploadingCover ? <Loader2 size={16} className="animate-spin" /> : <ImagePlus size={16} />}
            Add cover image <span className="opacity-70">(optional)</span>
          </button>
        )}
        <input ref={coverInputRef} type="file" accept="image/*" className="hidden" onChange={(event) => {
          if (event.target.files?.[0]) void handleCoverUpload(event.target.files[0]);
          event.target.value = "";
        }} />
        <input ref={importInputRef} type="file" accept=".txt,.md,.markdown,text/plain,text/markdown" className="hidden" onChange={(event) => {
          if (event.target.files?.[0]) void handleImportFile(event.target.files[0]);
          event.target.value = "";
        }} />

        <div className="editor-paper">
          <div className="editor-meta">
            <div className="editor-history-controls" role="group" aria-label="Editing history">
              <button type="button" className="editor-icon-button" aria-label="Undo" title="Undo" disabled={!historyAvailability.canUndo} onClick={undo}><Undo2 size={17} /></button>
              <button type="button" className="editor-icon-button" aria-label="Redo" title="Redo" disabled={!historyAvailability.canRedo} onClick={redo}><Redo2 size={17} /></button>
            </div>
            <input
              ref={titleRef}
              autoCapitalize="sentences"
              autoCorrect="on"
              value={title}
              disabled={closing}
              onFocus={(event) => rememberSelection("title", event.currentTarget)}
              onSelect={(event) => rememberSelection("title", event.currentTarget)}
              onChange={(event) => {
                const element = event.currentTarget;
                const selection = { field: "title" as const, start: element.selectionStart ?? 0, end: element.selectionEnd ?? element.selectionStart ?? 0 };
                const inputType = (event.nativeEvent as InputEvent).inputType;
                applyDraft(
                  { ...draftRef.current, title: element.value },
                  "title",
                  selection,
                  coalescibleInputKey("title", inputType),
                );
              }}
              placeholder="Entry title / manual name"
              aria-label="Entry title"
              className="editor-title"
            />
            <div className="editor-tags"><TagPicker spaceId={entry.spaceId} selected={entryTags} onChange={handleTagsChange} /></div>
          </div>
          <div className="editor-writing">
            {highlightedContent && <div ref={highlightRef} className="editor-highlight" aria-hidden="true">{highlightedContent}</div>}
            <textarea
              ref={textareaRef}
              autoCapitalize="sentences"
              autoCorrect="on"
              value={content}
              disabled={closing}
              onFocus={(event) => rememberSelection("content", event.currentTarget)}
              onSelect={(event) => rememberSelection("content", event.currentTarget)}
              onChange={(event) => {
                const element = event.currentTarget;
                const selection = { field: "content" as const, start: element.selectionStart, end: element.selectionEnd };
                const inputType = (event.nativeEvent as InputEvent).inputType;
                applyDraft(
                  { ...draftRef.current, content: element.value },
                  "content",
                  selection,
                  coalescibleInputKey("content", inputType),
                );
              }}
              onScroll={(event) => {
                if (highlightRef.current) {
                  highlightRef.current.scrollTop = event.currentTarget.scrollTop;
                  highlightRef.current.scrollLeft = event.currentTarget.scrollLeft;
                }
              }}
              placeholder="Write in Markdown — # headings, **bold**, - lists, `code`, > quotes…"
              aria-label="Entry content"
              className={`editor-textarea ${highlightedContent ? "has-highlights" : ""}`}
            />
          </div>
        </div>
      </div>

      <button type="button" className="editor-preview-backdrop" data-open={previewOpen} onClick={closePreview} tabIndex={-1} aria-hidden="true" />
      <aside id="entry-preview" className="editor-preview" data-open={previewOpen} aria-label="Markdown preview" aria-hidden={!previewOpen} inert={!previewOpen}>
        <div className="editor-preview-header">
          <div>
            <h2 className="editor-preview-heading">Preview</h2>
            <p className="editor-preview-caption">Markdown</p>
          </div>
          <button ref={previewCloseRef} type="button" className="editor-icon-button editor-preview-close" onClick={closePreview} aria-label="Close preview" title="Close preview"><X size={18} /></button>
        </div>
        <div className="editor-preview-content">
          <h3 className="editor-preview-title">{title || "Untitled entry"}</h3>
          <Markdown content={content} />
        </div>
      </aside>

      {pendingImport !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="import-title">
          <div className="pop-in ui-radius w-full max-w-md bg-white p-5 shadow-2xl dark:bg-neutral-800">
            <h3 id="import-title" className="text-base font-bold text-neutral-900 dark:text-white">Import “{pendingImportName}”?</h3>
            <p className="mt-2 text-sm text-neutral-500 dark:text-neutral-300">
              This entry already has content. Replace it entirely, or insert the file’s content at
              your cursor without removing what’s already here?
            </p>
            <div className="mt-4 flex flex-col gap-2">
              <button type="button" onClick={() => resolveImport("insert")} className="accent-bg ui-radius flex items-center justify-center gap-2 py-2 text-sm font-semibold text-white"><Check size={15} /> Insert / merge here (keep existing content)</button>
              <button type="button" onClick={() => resolveImport("replace")} className="ui-radius border border-red-300 py-2 text-sm font-semibold text-red-500 hover:bg-red-50 dark:border-red-500/40 dark:hover:bg-red-500/10">Replace entire entry content</button>
              <button type="button" onClick={() => resolveImport("cancel")} className="py-2 text-sm text-neutral-400 hover:text-neutral-600">Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
