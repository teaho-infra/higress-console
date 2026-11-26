# Console quota Java migration implementation plan

Goal: move existing Portal pages into Console, preserve quota APIs and plugin, replace Node backend and Python tooling with Java.
Architecture: isolated frontend/src/custom/walmart-quota with a thin Console route/menu entry; isolated backend/walmart-quota Spring Boot 2.7.18 module, integrated by a Maven profile and auto-configuration; Java deployment/debug CLI in backend/walmart-quota-tools. Keep original portal assets as compatibility launcher only, no second implementation.
Spec: user-approved migration in this conversation. React UI and /api/quota/* contract remain; plugin and gateway image unchanged. Existing Portal password/session contract remains independent of Console login for compatibility and to avoid widening quota admin privileges.

## Constraints and review focus
- No edits to ai-quota-walmart, unrelated user changes or private credentials.
- API origin, CSRF, session expiration, method/query allowlists, size/time limits and error behavior must survive migration.
- Scope UI CSS so Console layout is unaffected. No iframe or duplicate React root in Console.
- Java tools preserve credentials and never automatically reseed users. No credentials in logs or committed resources.
- Upstream Console build stays possible without custom backend profile; document exact merge touchpoints.

## Tasks
- [x] Frontend: relocate React components to custom directory; thin route, isolated menu item, scoped styles; reuse source for standalone compatibility; build and browser checks.
- [x] Backend: Java module with tested session and proxy service, Servlet endpoints for existing API contract, optional standalone Boot launcher, profile dependency and Spring auto-configuration; run HTTP contract tests.
- [x] Tools: port deploy, install, mock/static server, seed and gateway/money checks to Java Maven CLI; replace Python commands/docs; test manifest and mock behavior.
- [x] Integration: build Console, start locally with existing credentials, verify navigation, API and bulk import; save sanitized evidence and merge guide.

Ruling: use existing Spring Boot 2.7.18/Java 8-compatible bytecode for integration; local JDK 21 builds. Retain separate quota admin login to preserve existing permission semantics. Use independent subagents for frontend and Java tooling per subagent-driven-development skill; root implements backend and integration. No commit/push until requested.

Completed: real Console deployed on 18001, Java standalone on 18890, fixture service migrated without restarting Redis. Review fixes applied: strict JSON, query normalization, static security headers, credential validation, valid query-auth check. Discovered route-driven Redis cluster pruning; independent DNS registry fixes it. API18 + tools10 + HTTP/CSV18 + browser20 + gateway108 checks pass. No commits or pushes.
