# Browser regression checks

Install dependencies and a Playwright Chromium browser, then run:

```sh
npm install
npx playwright install chromium
npm run test:e2e
```

The tests start the web app and intercept its API with isolated fixtures, so they do not modify a real vault. They cover the phone and desktop editor, keyboard-sized viewports, preview, find/replace, theme settings, entry actions, and save-on-close (including pending operations and failed saves/deletion). A separate real PostgreSQL smoke test is useful when a database is available.

Set `PLAYWRIGHT_BASE_URL` to use an already-running server, or `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to use a locally installed Chromium binary. The app still requires `DATABASE_URL` for normal operation; the tests use a dummy value if none is provided.
