# Cross-platform feature audit and plan

Baseline: main at ef1aea8, before changes in this branch. This is a work in progress, not a feature-completion claim.

## Current architecture and compatibility

- Android is native Compose, with Room database version 1 (no registered migrations), DataStore settings and private local media files. The release app intentionally has no internet permission. Compose's blur is available only on Android 12 and later. Android uses `MarkdownText` in the editor preview.
- Web uses Next.js, PostgreSQL/Drizzle (`settings`, `spaces`, `tags`, `entries`, `entry_tags`); tags currently have a globally unique name. There is no tracked Drizzle migration directory in the baseline. The web preview uses react-markdown with remark-gfm.
- Both platforms currently export JSON version 1, with tag objects embedded in entries as well as the top-level tag list. Android embeds private cover images and wallpaper as data URIs in backups; web's upload endpoint writes into `public/uploads`, which is ephemeral on serverless deployments. Existing external and server-relative references are not portable. Inline attachment management does not exist.
- Existing tags have `reusable` suggestion semantics, not scope. Entries have many-to-many tag associations. Both editors have file import and preview; neither provides the requested complete reading mode, portable Markdown bundle, selective backup, scoped tags or explicit bounded editor undo/redo.
- Android's root uses `VaultBackdrop` outside the editor but forces the spaces screen to splash-dark; the editor explicitly replaces the backdrop with an opaque palette background. The default light backdrop is near-white. Settings/theme state and wallpaper, screen transitions and API-specific blur require actual device screenshots before identifying the reported Android 10 defect or changing intended appearance. No physical device is available here.
- The workflow tests API 30 and 34 and has separate guarded release signing. Main run 40777862856 reports API 34 success and API 30 failure. The artifact and full log download hosts are inaccessible from this sandbox (EOF), but the actual job annotations expose the failure: 31 instrumentation tests passed; a separate preview-migration picker step could not activate the synthetic JSON document, so its import confirmation was never reached. The offline upgrade/restart check passed. This points to picker/fixture automation as the immediate failure, but does not establish why the row could not be activated or rule out a product defect. The user's successful Android 10 tag and import/export tests are not evidence of a regression.

## Dependency plan (not yet implemented)

1. Design a shared version-2 contract and migration fixtures first. Legacy tags must remain globally available independent of `reusable`; define case-insensitive uniqueness separately for global and each space. Add tracked PostgreSQL and Room migrations without rebuilding tables or changing existing joins. Test against isolated databases with legacy data.
2. Implement scope-aware validation, queries, attachment, filtering, editing and creation on both platforms before exporting the new fields. Preserve v1 imports and avoid ambiguous name-only mapping.
3. Agree a durable media design: existing PostgreSQL storage could be evaluated for bounded binary assets and its deployment limits; no new paid service or secret should be assumed. Define portable asset identifiers and bounded archive/backup handling shared with Android private storage. Existing filesystem uploads are not durable. Add transactional import validation and failure cleanup.
4. Implement editor history and input behavior, shared Markdown semantics, dedicated read-only reading mode, then selective v2 backups and portable Markdown bundles. Cross-platform fixtures must cover every scope, media, conflicts, invalid payloads and interrupted imports.
5. Capture actual API 29/30/34 screenshots and investigate the background across themes, wallpaper, navigation and restart. Diagnose API 30 from the actual test reports, distinguish automation from product failures, and fix without skipping behavior. Verify signed-release protections remain unchanged.

## Parity status

| Feature | Android | Web |
| --- | --- | --- |
| Local/global tags and scope-aware UI | Missing | Missing |
| Compact outlined tag styling | Partial | Partial |
| Undo/redo | Missing | Missing |
| Inline images and portable media | Missing | Missing |
| Keyboard assistance | Needs verification | Needs verification |
| Equivalent GFM preview | Partial | Partial |
| Dedicated Reading Mode | Missing | Missing |
| Selective JSON export and v2 import | Missing | Missing |
| Markdown export with attachments | Missing | Missing |
| Markdown import | Existing | Existing |
| Android 10 background / Android 11 | Unverified | N/A |

This audit does not claim that pre-existing functionality is broken. In particular, v1 backup and tag behavior should not be rewritten without regression fixtures and cross-platform validation.

## Branch checks and remaining validation

- Web lint, TypeScript and production build passed locally (build used a dummy `DATABASE_URL`, not a production database). The Playwright suite was attempted, but all 19 tests failed at browser launch because Chromium is not installed; no application assertions ran.
- Android CI helper tests and source preflight passed. Android Gradle unit tests could not start because Java is absent; no SDK/emulator is available locally. Branch run 40848244982 passed API 29 and 34 and failed API 30 at the same picker step; all three reported 31 instrumentation tests with zero failures and passed the offline upgrade/restart check. The debug build and unit test job passed. The API 29 real-window evidence was uploaded but cannot be downloaded here because the artifact storage host is blocked. These checks do not establish full background coverage or a physical-device result.
- This branch only adds input-method hints and outlined editor tags on both platforms, plus the API 29 matrix entry. Hints depend on the keyboard/browser and have not been verified with Gboard or a physical device. Other tag surfaces remain unchanged. No database migration, JSON format, import/export or upload behavior has been changed.
- Remaining work is the dependency plan above, including real report access, actual API screenshots and background diagnosis, cross-platform tests, GFM equivalence and all outstanding user-facing features. Do not treat this branch as satisfying the acceptance criteria.

- Web CI now runs lint, typecheck, build and the Playwright suite on GitHub-hosted runners; it uses intercepted browser API fixtures and a dummy database URL. A representative web GFM rendering fixture was added. Its CI result is pending; it does not demonstrate Android rendering parity.

## Scope foundation increment

An additive Room 1→2 migration and PostgreSQL SQL migration add reserved scope
and owning-space columns; all old tags default to global independently of
`reusable`, and existing records/links and case-variant legacy names are left
intact. Android includes a real version-1 database migration test; web CI runs
an isolated transactional PostgreSQL fixture. These tests are pending CI at
this commit. Local scope is deliberately not exposed yet: creating local tags
before scoped uniqueness, deletion and portable cross-platform backups are in
place would risk data loss. PostgreSQL deployment requires applying the reviewed
migration first; it is not run by CI against production.

Outlined compact tag presentation now also covers additional dashboard,
picker and settings surfaces on both platforms. This is not scope-aware UI.
