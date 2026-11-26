> 历史调试记录：独立 `portal/` 已移除，当前操作命令以 [README](README.md) 和 [Java 工具指南](java-tools.md) 为准。

# Quota frontend integration

The authoritative UI is now `frontend/src/custom/walmart-quota/`: components, API adapter, CSV import/money helpers, scoped styles, and menu metadata. `frontend/src/pages/walmart-quota/index.tsx` mounts the component into Console's existing React tree at `/walmart-quota`. The only edit to an existing Console source file is the menu import/entry in `frontend/src/pages/_defaultProps.tsx`.

The standalone `portal/src` files are compatibility adapters; they contain no second implementation. Its Vite config deduplicates React/ReactDOM because shared source lives alongside Console's dependencies. `portal/src/style.css` contains only the standalone body margin reset. All 251 custom CSS selectors are scoped to `.walmart-quota`; the animation name is also namespaced. Embedded mode contains drawers and provides an independently scrolling main area inside Console, preserving the quota navigation and forms.

## Authentication and API routing

Quota retains its own password, session, CSRF and `/api/login`, `/api/session`, `/api/logout`, `/api/quota/*` requests. Console's global data loader separately requests `/user/info` and `/system/*`, using its existing Console session and redirect behavior. Login to Console does not grant quota access. The frontend does not use the Console axios client for quota calls.

Production requires the Java backend to expose those `/api/*` paths unchanged. Console's existing development proxy strips `/api` and targets the upstream demo server; for local integrated development, use a local reverse proxy or override the quota-specific routes to the local Java server without stripping `/api`. No upstream default proxy or authentication behavior was changed here.

## Validation (2026-11-25)

- `npm --prefix frontend run build`: production webpack build passes; generated file route includes `/walmart-quota`.
- `npm --prefix portal run build`: standalone Vite build passes using shared source.
- `node --test portal/tests/import.test.mjs`: six CSV/import contract tests pass.
- `node portal/tests/browser-console-migration.mjs`: Chromium smoke test against the built Console with intercepted synthetic API fixtures, covering independent quota login, CSV preview/import, string ID preservation, CSRF, CSS sibling isolation, and no page errors. Set `CONSOLE_TEST_URL` to the origin serving `frontend/build` with SPA fallback. This test makes no live gateway mutations.
- `docs/walmart-quota/evidence/quota-console-migration.png`: actual Console shell with completed synthetic CSV import.

Build warnings about the existing direct webpack configuration, webpack deprecated API and outdated Browserslist data are unrelated to this migration. Real Java/gateway integration is validated separately by the integration task.
