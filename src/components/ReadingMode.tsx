"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowLeft, BookOpen, ChevronDown, ChevronUp, Clipboard, Download, Search, X } from "lucide-react";
import type { Entry, Tag } from "@/lib/types";
import Markdown from "@/components/Markdown";

function findTextRanges(root: HTMLElement | null, query: string): Range[] {
  if (!root || !query.trim()) return [];
  const ranges: Range[] = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const needle = query.toLowerCase();
  let node: Node | null;
  while ((node = walker.nextNode())) {
    const text = node.textContent ?? "";
    let haystack = "";
    const starts: number[] = [];
    const ends: number[] = [];
    let originalOffset = 0;
    for (const character of text) {
      const endOffset = originalOffset + character.length;
      const folded = character.toLowerCase();
      haystack += folded;
      for (let index = 0; index < folded.length; index++) {
        starts.push(originalOffset);
        ends.push(endOffset);
      }
      originalOffset = endOffset;
    }
    let offset = 0;
    while (offset <= haystack.length - needle.length) {
      const index = haystack.indexOf(needle, offset);
      if (index < 0) break;
      const range = document.createRange();
      range.setStart(node, starts[index]);
      range.setEnd(node, ends[index + needle.length - 1]);
      ranges.push(range);
      offset = index + Math.max(needle.length, 1);
    }
  }
  return ranges;
}

interface Props {
  entry: Entry;
  tags: Tag[];
  onBack: () => void;
  onExport: () => Promise<string | void>;
}

export default function ReadingMode({ entry, tags, onBack, onExport }: Props) {
  const bodyRef = useRef<HTMLDivElement>(null);
  const matchesRef = useRef<Range[]>([]);
  const [findOpen, setFindOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [matchCount, setMatchCount] = useState(0);
  const [matchIndex, setMatchIndex] = useState(-1);
  const [status, setStatus] = useState("");
  const [exporting, setExporting] = useState(false);
  const [coverFailed, setCoverFailed] = useState(false);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onBack();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onBack]);

  useEffect(() => {
    const ranges = findTextRanges(bodyRef.current, query);
    matchesRef.current = ranges;
    setMatchCount(ranges.length);
    setMatchIndex(-1);
  }, [entry.content, query]);

  const navigateMatch = (direction: -1 | 1) => {
    const ranges = matchesRef.current;
    if (!ranges.length) return;
    const next = matchIndex < 0
      ? (direction > 0 ? 0 : ranges.length - 1)
      : (matchIndex + direction + ranges.length) % ranges.length;
    const range = ranges[next];
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    const element = range.startContainer.parentElement;
    element?.scrollIntoView({ block: "center", behavior: "smooth" });
    setMatchIndex(next);
    setStatus("");
  };

  const copyOriginal = async () => {
    try {
      await navigator.clipboard.writeText(entry.content);
      setStatus("Original Markdown copied.");
    } catch {
      setStatus("Clipboard access was unavailable. Select and copy the Markdown from the editor instead.");
    }
  };

  const exportCurrentEntry = async () => {
    setExporting(true);
    setStatus("");
    try {
      const result = await onExport();
      setStatus(result || "Single-entry export downloaded.");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "Couldn’t export this entry.");
    } finally {
      setExporting(false);
    }
  };

  return (
    <main className="reading-mode" aria-label="Reading mode">
      <header className="reader-toolbar">
        <div className="reader-heading">
          <BookOpen size={19} aria-hidden="true" />
          <span>Reading mode</span>
        </div>
        <div className="reader-actions">
          <button type="button" className="reader-button" onClick={() => { setFindOpen((open) => !open); setStatus(""); }} aria-label="Find in entry" aria-expanded={findOpen}>
            <Search size={17} /><span>Find</span>
          </button>
          <button type="button" className="reader-button" onClick={() => void copyOriginal()} aria-label="Copy original Markdown">
            <Clipboard size={17} /><span>Copy Markdown</span>
          </button>
          <button type="button" className="reader-button" onClick={() => void exportCurrentEntry()} disabled={exporting} aria-label="Export this entry">
            <Download size={17} /><span>{exporting ? "Exporting…" : "Export"}</span>
          </button>
          <button type="button" className="reader-button reader-back" onClick={onBack} aria-label="Return to editor">
            <ArrowLeft size={17} /><span>Editor</span>
          </button>
        </div>
      </header>

      {findOpen && (
        <section className="reader-find" aria-label="Find in entry">
          <label className="sr-only" htmlFor="reader-find-query">Find text in this entry</label>
          <input
            id="reader-find-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Find text in this entry…"
            autoComplete="off"
          />
          <span role="status" aria-live="polite" className="reader-match-count">
            {matchCount ? `${matchIndex < 0 ? 0 : matchIndex + 1} of ${matchCount} matches` : "0 matches"}
          </span>
          <button type="button" className="reader-small-button" aria-label="Previous match" disabled={!matchCount} onClick={() => navigateMatch(-1)}><ChevronUp size={17} /></button>
          <button type="button" className="reader-small-button" aria-label="Next match" disabled={!matchCount} onClick={() => navigateMatch(1)}><ChevronDown size={17} /></button>
          <button type="button" className="reader-small-button" aria-label="Close find" onClick={() => { setFindOpen(false); setQuery(""); }}><X size={16} /></button>
          {!matchCount && query.trim() && <span className="reader-no-matches">No matches found.</span>}
        </section>
      )}

      <article className="reader-scroll">
        <div className="reader-document">
          {entry.coverImage && !coverFailed && (
            // eslint-disable-next-line @next/next/no-img-element
            <img className="reader-cover" src={entry.coverImage} alt="Entry cover" onError={() => setCoverFailed(true)} />
          )}
          <div className="reader-meta-line">
            {entry.pinned && <span className="reader-chip">Pinned</span>}
            {tags.map((tag) => <span key={tag.id} className="reader-chip">{tag.name}{tag.scope === "local" ? " · Local" : ""}</span>)}
          </div>
          <h1 className="reader-title">{entry.title || "Untitled entry"}</h1>
          <p className="reader-dates">Updated {new Date(entry.updatedAt).toLocaleString()}</p>
          <div ref={bodyRef} className="reader-markdown">
            <Markdown content={entry.content} />
          </div>
        </div>
      </article>
      <div className="reader-status" aria-live="polite" role="status">{status}</div>
    </main>
  );
}
