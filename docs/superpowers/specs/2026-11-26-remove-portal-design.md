# Remove the standalone Portal directory

## Intent

Keep Walmart quota management inside Higress Console and remove the standalone `portal/` tree without breaking the Console UI, quota API, Java deployment tools, or local debug workflow. This reduces the duplicated compatibility surface before future upstream Console merges.

## Current dependencies

- Console UI and API live in `frontend/src/custom/walmart-quota/` and `backend/walmart-quota/`.
- Java deployment tools live in `backend/walmart-quota-tools/`, but currently read `portal/scripts/{money-config,plugin-images,quota-config}.json`, default `--root` to `portal`, and write reports under `portal/docs/`.
- The Java tools tests read the same `portal/scripts/` files. The standalone Portal's Vite launcher, wrappers, and browser tests still live under `portal/`.
- Ignored `portal/.env` and `portal/.debug/` contain local credentials and generated state; deleting them would lose access to the current demo configuration. `portal/node_modules/` and `portal/dist/` are regenerable.

## Approaches

1. **Delete the directory directly.** Smallest diff, but breaks Java tooling and loses local credentials. Reject.
2. **Move the small set of live configurations and evidence, then delete the standalone app.** Preserve local private state, update tooling/tests/docs to new paths, verify Console and tools, then remove `portal/`. Recommended.
3. **Keep `portal/` as a compatibility shell.** Least immediate work, but does not satisfy the request to remove the directory. Reject.

## Proposed design

- Put public deployment JSON and the CLI wrapper alongside `backend/walmart-quota-tools/`. Update Java tool defaults and tests to resolve those files there.
- Move the historical Portal documentation and debug evidence to a dedicated `docs/walmart-quota/` subtree, updating links and command examples that are still intended to work. Old evidence stays labeled as historical.
- Move ignored `.env` and `.debug/` into a new ignored local-state directory before any deletion, retaining file permissions and contents. Point the Java CLI and standalone launcher there; avoid printing secrets.
- Remove the standalone Vite app, duplicate frontend source wrappers, standalone tests, generated build directories, and finally `portal/`.
- Verify Maven tests for both quota modules, the Console frontend build, CLI manifest generation without applying to Kubernetes, and live Console quota navigation/API if the local services are available. Do not mutate Redis or Kubernetes as part of this cleanup.

## Scope and limits

No changes to quota policy, WASM plugin, gateway routes, or Redis data. Preserve the Console's current behavior and API paths. This design does not change the existing local demo's storage durability.
