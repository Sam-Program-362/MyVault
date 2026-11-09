# Markdown compatibility and Reading Mode

## Shared fixture and common syntax

The shared source fixture is `tests/fixtures/markdown-compatibility.md`. Web Playwright tests read that file directly; Android instrumentation packages that same file as an `androidTest` asset. It covers headings, paragraphs and soft breaks, strong/emphasis/strikethrough, inline and fenced code, links, unordered/ordered/nested lists, GFM task lists and tables, blockquotes, and a deliberately missing app-local image surrounded by text. The reader tests also exercise long scrolling content, search, and image failure behavior.

The syntax exercised on both renderers is the intended supported interchange subset: CommonMark-style headings, paragraphs, emphasis, code, blockquotes and lists, plus GFM tables, task lists and strikethrough. Links and raster inline images are rendered where the device can access their destination. The fixture and tests are an executable baseline, not a claim that every edge case has identical pixels or parsing.

## Renderer assessment and practical differences

- **Web:** `react-markdown` with `remark-gfm` (declared package ranges `^10.1.0` and `^4.0.1`). Raw HTML is not enabled. Image load failures become a visible `[Image unavailable: …]` fallback while surrounding text remains.
- **Android:** the existing `com.github.jeziellago:compose-markdown:0.5.4` dependency wraps Markwon. Its 0.5.4 source configures HTML, Coil image loading, strikethrough, tables, linkification and task-list plugins. It is MIT-licensed. Invalid app-managed local image references are replaced with visible fallback text before rendering; valid private files continue to use Coil and remain offline-capable. No Markdown dependency was added or upgraded for this phase.
- **Not identical:** the Android dependency enables HTML while the web renderer intentionally does not; web GFM parsing and Android Markwon/linkification need not recognize the same extensions. Typeface metrics, tables, soft breaks, links and line wrapping vary by platform. Android's release app has no general internet permission, so a remote image URL may not load there; its reference and surrounding Markdown remain. Malformed input is left to each parser's tolerant behavior, not normalized into a common AST.
- **Media:** app-managed web `/api/media/<id>` images and Android-private `file://` images are converted to validated `data:image/...;base64` references for a single-entry export when readable. External URLs are kept as references. Missing managed media is preserved rather than silently removed; the export UI warns that such links could not be embedded. An image destination can remain device/app-specific when the source bytes are gone.

This phase keeps the established Android renderer rather than replacing it with a WebView, adding a competing Markdown stack, or broadening dependencies. The shared fixture tests the common user-facing subset; source inspection plus the limitations above describe the non-equivalent areas.

## Reading Mode behavior

Reading Mode is a read-only overlay on the already-mounted editor, not a new entry or a route that discards editor state. Both platforms flush pending editor writes and read the saved entry before opening the reader. Returning therefore keeps the existing Undo/Redo history and does not create a duplicate or reset it.

Both readers render the saved title and Markdown body, support long scrolling, and expose Copy original Markdown, Find, single-entry Export and Return to editor. Copy writes the original body source verbatim (without adding the title). Find never changes the body:

- Web searches visible rendered text nodes, reports the count/no-match state, and uses a browser text selection plus `scrollIntoView` to navigate visible matches. A phrase split across separate rendered text nodes (for example, across differently formatted inline nodes) is not treated as one continuous match.
- Android searches the original Markdown source and presents the current match in a highlighted, navigable snippet. The renderer does not expose source offsets for reliable scrolling to the exact formatted glyph, so Android navigation is by match/snippet rather than synchronized scrolling within Markwon's rendered view.

The independent export is JSON with `format: "myvault-entry"` and `formatVersion: 1`; it contains the selected entry (including the original Markdown source except for app-managed image URLs made portable), its tags and entry metadata, and its Space metadata. It is intentionally a distinct, export-only document—not a version-1/2 whole-vault backup and not a change to either platform's existing import or backup schema.
