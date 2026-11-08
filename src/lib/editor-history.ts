export type EditorField = "title" | "content";

export type EditorSelection = {
  field: EditorField;
  start: number;
  end: number;
};

export type EditorSnapshot = {
  title: string;
  content: string;
  selection: EditorSelection;
};

export type HistoryEdit = {
  field: EditorField;
  coalesceKey?: string | null;
  at?: number;
};

/** A bounded, per-editor sequence of title/body snapshots. */
export class EditorHistory {
  private snapshots: EditorSnapshot[];
  private index = 0;
  private lastEdit: { field: EditorField; key: string; at: number } | null = null;

  constructor(
    initial: EditorSnapshot,
    private readonly limit = 100,
    private readonly coalesceWindowMs = 750,
  ) {
    this.snapshots = [cloneSnapshot(initial)];
  }

  get canUndo() { return this.index > 0; }
  get canRedo() { return this.index < this.snapshots.length - 1; }

  updateSelection(selection: EditorSelection) {
    const current = this.snapshots[this.index];
    if (current.selection.field !== selection.field || current.selection.start !== selection.start || current.selection.end !== selection.end) {
      this.lastEdit = null;
    }
    this.snapshots[this.index] = { ...current, selection: { ...selection } };
  }

  record(snapshot: EditorSnapshot, edit: HistoryEdit) {
    const current = this.snapshots[this.index];
    if (current.title === snapshot.title && current.content === snapshot.content) {
      this.updateSelection(snapshot.selection);
      return;
    }

    if (this.index < this.snapshots.length - 1) {
      this.snapshots = this.snapshots.slice(0, this.index + 1);
      this.lastEdit = null;
    }

    const key = edit.coalesceKey ?? null;
    const at = edit.at ?? Date.now();
    const canCoalesce = key !== null && this.lastEdit !== null &&
      this.lastEdit.field === edit.field && this.lastEdit.key === key &&
      at >= this.lastEdit.at && at - this.lastEdit.at <= this.coalesceWindowMs;

    if (canCoalesce) {
      this.snapshots[this.index] = cloneSnapshot(snapshot);
    } else {
      this.snapshots.push(cloneSnapshot(snapshot));
      this.index++;
      if (this.snapshots.length > this.limit) {
        const excess = this.snapshots.length - this.limit;
        this.snapshots.splice(0, excess);
        this.index -= excess;
      }
    }
    this.lastEdit = key === null ? null : { field: edit.field, key, at };
  }

  undo(): EditorSnapshot | null {
    if (!this.canUndo) return null;
    this.index--;
    this.lastEdit = null;
    return cloneSnapshot(this.snapshots[this.index]);
  }

  redo(): EditorSnapshot | null {
    if (!this.canRedo) return null;
    this.index++;
    this.lastEdit = null;
    return cloneSnapshot(this.snapshots[this.index]);
  }
}

function cloneSnapshot(snapshot: EditorSnapshot): EditorSnapshot {
  return { ...snapshot, selection: { ...snapshot.selection } };
}

export function coalescibleInputKey(field: EditorField, inputType: string): string | null {
  if (inputType === "insertText" || inputType === "insertCompositionText") return `${field}:typing`;
  if (inputType === "deleteContentBackward" || inputType === "deleteContentForward") return `${field}:deleting`;
  return null;
}
