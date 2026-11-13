import { expect, test } from "@playwright/test";
import { coalescibleInputKey, EditorHistory, type EditorSnapshot } from "@/lib/editor-history";

const initial: EditorSnapshot = {
  title: "",
  content: "",
  selection: { field: "content", start: 0, end: 0 },
};

function snapshot(title: string, content: string, field: "title" | "content", start: number, end = start): EditorSnapshot {
  return { title, content, selection: { field, start, end } };
}

test("undo and redo span title and body and restore selection", () => {
  const history = new EditorHistory(initial);
  history.record(snapshot("Title", "", "title", 5), { field: "title" });
  history.record(snapshot("Title", "body", "content", 4), { field: "content" });

  expect(history.undo()).toEqual(snapshot("Title", "", "title", 5));
  expect(history.undo()).toEqual(initial);
  expect(history.canUndo).toBe(false);
  expect(history.redo()).toEqual(snapshot("Title", "", "title", 5));
  expect(history.redo()).toEqual(snapshot("Title", "body", "content", 4));
  expect(history.canRedo).toBe(false);
});

test("rapid typing coalesces, while an imported block is one atomic operation", () => {
  const history = new EditorHistory(initial);
  const key = coalescibleInputKey("content", "insertText");
  history.record(snapshot("", "h", "content", 1), { field: "content", coalesceKey: key, at: 10 });
  history.record(snapshot("", "he", "content", 2), { field: "content", coalesceKey: key, at: 20 });
  history.record(snapshot("", "hello", "content", 5), { field: "content", coalesceKey: key, at: 30 });
  expect(history.undo()).toEqual(initial);
  expect(history.redo()).toEqual(snapshot("", "hello", "content", 5));

  history.record(snapshot("", "imported\nmarkdown", "content", 16), { field: "content", coalesceKey: null, at: 40 });
  expect(history.undo()).toEqual(snapshot("", "hello", "content", 5));
});

test("moving the caret ends the current typing group", () => {
  const history = new EditorHistory(initial);
  const key = coalescibleInputKey("content", "insertText");
  history.record(snapshot("", "a", "content", 1), { field: "content", coalesceKey: key, at: 10 });
  history.updateSelection({ field: "content", start: 0, end: 0 });
  history.record(snapshot("", "ba", "content", 1), { field: "content", coalesceKey: key, at: 20 });
  expect(history.undo()).toEqual(snapshot("", "a", "content", 0));
});

test("typing after the coalescing window and history limits create safe boundaries", () => {
  const history = new EditorHistory(initial, 3, 750);
  const key = coalescibleInputKey("content", "insertText");
  history.record(snapshot("", "a", "content", 1), { field: "content", coalesceKey: key, at: 0 });
  history.record(snapshot("", "ab", "content", 2), { field: "content", coalesceKey: key, at: 900 });
  history.record(snapshot("", "abc", "content", 3), { field: "content", at: 1000 });
  history.record(snapshot("", "abcd", "content", 4), { field: "content", at: 1100 });

  expect(history.undo()).toEqual(snapshot("", "abc", "content", 3));
  expect(history.undo()).toEqual(snapshot("", "ab", "content", 2));
  expect(history.undo()).toBeNull(); // the initial state was evicted by the bounded history
});

test("each entry gets an independent history and new edits discard the redo branch", () => {
  const firstEntry = new EditorHistory(initial);
  firstEntry.record(snapshot("First", "", "title", 5), { field: "title" });
  const secondEntry = new EditorHistory(snapshot("Other", "", "title", 5));
  expect(secondEntry.canUndo).toBe(false);
  expect(secondEntry.undo()).toBeNull();

  firstEntry.undo();
  firstEntry.record(snapshot("Replacement", "", "title", 11), { field: "title" });
  expect(firstEntry.canRedo).toBe(false);
});
