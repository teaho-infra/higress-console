# Remove Portal Directory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `portal/` while keeping Console quota UI/API and Java demo tools working and preserving private local state.

**Architecture:** Public tool configuration moves to `backend/walmart-quota-tools/config`; historical documentation and evidence move to `docs/walmart-quota`. Private `.env` and `.debug` move to ignored `.walmart-quota`. The obsolete Vite standalone app is deleted.

**Tech Stack:** Java 8 / Maven, React / Ice, shell, Kubernetes demo tooling.

**Spec:** `docs/superpowers/specs/2026-11-26-remove-portal-design.md`

## Global Constraints

- Do not change quota policy, gateway resources, WASM plugin, or Redis data.
- Preserve existing local private credentials without printing them.
- Keep all current Console UI and API routes.

## Review Focus

- A fresh CLI run from the repository root must find public config without `portal/`.
- Existing private credentials must remain unchanged at the new path.
- Historical reports must remain available after directory deletion.
- Console build and Java module tests must work with `portal/` absent.

### Task 1: Move Java tools and local state

**Files:** `backend/walmart-quota-tools/src/main/java/com/alibaba/higress/quota/tools/{QuotaTools,Deployment,Verification}.java`, `backend/walmart-quota-tools/src/test/java/com/alibaba/higress/quota/tools/QuotaToolsTest.java`, `backend/walmart-quota-tools/config/*.json`, `backend/walmart-quota-tools/quota-tools`, `backend/walmart-quota/start-java`, `.gitignore`, ignored `.walmart-quota/`.

- [x] Add a test that asserts private paths are under `.walmart-quota`; observed the expected red failure before implementation.
- [x] Move public configs and private local state; update CLI paths, wrapper, report destinations, and standalone launcher.
- [x] Run `mvn -f backend/walmart-quota-tools/pom.xml test` and `mvn -f backend/walmart-quota/pom.xml test`.

### Task 2: Remove standalone application and preserve docs

**Files:** `portal/**` (remove), `docs/walmart-quota/**` (move from `portal/docs`), `docs/walmart-quota/README.md` (move and update), root docs links, `frontend` and `backend` as required for references.

- [x] Move historical docs and evidence, update active command paths, and remove standalone app/tests/build artifacts. Retain ignored generated artifacts under `.walmart-quota/legacy-standalone` as a local fallback.
- [x] Verify `test ! -e portal`, search for live `portal/` dependencies, run Java tests and Console frontend build.
- [x] Check `git diff --check` and inspect the final status; do not push or mutate the cluster.
