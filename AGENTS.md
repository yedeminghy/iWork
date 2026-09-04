# Repository Guidelines

## Project Structure & Module Organization

This local-first desktop agent has a Java 21 backend and Electron/React frontend. Maven modules are `agent-persistence` (SQLite/Flyway), `agent-mcp` (MCP transports), `agent-runtime` (model, tool, and Skill runtime), and `agent-host` (Spring Boot host). Frontend code lives in `desktop-shell/src` for Electron and `desktop-ui/src` for React. Java code follows `src/main` and `src/test`. Keep migrations in `agent-persistence/src/main/resources/db/migration`, built-in Skills in `skills/<kebab-name>/`, scripts in `scripts/`, and designs in `docs/phase-1/`.

## Build, Test, and Development Commands

- `mvn clean verify` — compile all Java modules, run migrations in tests, and execute the JUnit suite.
- `npm install` — install root npm-workspace dependencies.
- `npm run typecheck` — type-check both UI and Electron projects.
- `npm run build` — produce the Vite UI and Electron shell builds.
- `npm run dev` — start the Vite renderer for local development.
- `powershell -ExecutionPolicy Bypass -File .\scripts\verify-week4.ps1` — run the complete release gate, including Electron/Renderer smoke tests and Week 3 regression.

Package the Host before launching Electron manually: `mvn -pl agent-host -am package`.

## Coding Style & Naming Conventions

Use four-space indentation in Java and two spaces in TypeScript/TSX/CSS. Prefer immutable records, constructor injection, boundary validation, and application-owned SDK adapters. Use `PascalCase` for classes/components, `camelCase` for methods/variables, `UPPER_SNAKE_CASE` for constants, and `kebab-case` for Skill directories. Flyway files follow `V<number>__description.sql`. No formatter or linter is configured; match adjacent code and keep type-checking clean.

## Testing Guidelines

Java tests use JUnit 5 with AssertJ or JUnit assertions and are named `*Test.java`; integration scenarios may use `*IntegrationTest.java`. Place tests in the matching module/package. Add regression coverage for security boundaries, persistence, structured events, and restart behavior. Run targeted Maven tests while iterating, then the full Week 4 verification before handoff.

## Commit & Pull Request Guidelines

This checkout contains no Git history, so no repository-specific commit convention can be inferred. Use concise imperative subjects, preferably Conventional Commit style, such as `fix(runtime): reject stale skill resources`. Keep commits scoped. Pull requests should explain behavior and risks, link the relevant design/issue, list verification commands, and include screenshots for visible UI changes.

## Security & Configuration

Never commit Provider keys, bearer tokens, fixture secrets, absolute user paths, reasoning, or Skill/resource bodies. Preserve loopback authentication, restricted preload IPC, fixed Skill roots, read-only AgentScope adapters, and the rule that Skill scripts and `allowed-tools` never grant execution authority.
