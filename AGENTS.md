# Repository Guidelines

## Project Structure & Module Organization
Core orchestrator code lives in `src/main/kotlin/com/orchestrator/...`, with HTTP plugins, context tools, and DuckDB access in clear packages. Static assets and templates sit under `src/main/resources`. Tests mirror the tree in `src/test/kotlin`, while canned inputs live in `test-data/`. Configuration templates (`fusionagent.toml`, `config/agents.toml.example`, `config/context.example.toml`) document how to wire agents and context roots. Long-form specs, routing notes, and install docs are in `docs/`, and the composite MCP Kotlin SDK lives under `external/kotlin-sdk`, pulled in via `includeBuild` for local iteration.

## Build, Test, and Development Commands
- `./gradlew run` – launches the Ktor server on port 8081 with hot reloading of config.
- `./gradlew build` – compiles, runs tests, and assembles artifacts.
- `./gradlew shadowJar` – emits `build/libs/orchestrator-all.jar` for distribution.
- `./gradlew test` – executes the orchestrator unit/integration suite (JUnit 5 + MockK).
- `./gradlew :external:kotlin-sdk:check` – verifies the vendored SDK before promoting changes.

## Coding Style & Naming Conventions
Follow idiomatic Kotlin style: four-space indentation, 120-column soft limit, `UpperCamelCase` for types, `lowerCamelCase` for functions/props, and `SCREAMING_SNAKE_CASE` for constants. Keep packages aligned with the directory layout (`com.orchestrator.context...`). Prefer data classes and sealed hierarchies for DTOs, extension functions for adapter glue, and avoid wildcard imports. Run the IDE's Kotlin formatter before pushing; keep loggers as `private val logger = KotlinLogging.logger { }` so they remain consistent.

## Testing Guidelines
Store test fixtures beside specs in `src/test` and name files `*Test.kt` (e.g., `ProjectRootDetectorTest`). Use JUnit 5 annotations, MockK for doubles, and `ktor-server-test-host` for HTTP routes. New endpoints require both happy-path tests and failure-mode coverage. High-risk filesystem or DuckDB changes need regression tests plus sample data in `test-data/`. Run `./gradlew test` locally; CI blocks merges on failures.

## Commit & Pull Request Guidelines
Match the existing imperative, one-line commit style (`Fix path filtering...`). Reference linked issues in the body where relevant. PRs should summarize scope, list testing evidence (`./gradlew test` output), call out docs or config updates, and include screenshots for UI tweaks. Keep changes focused—large rewrites should be split into reviewable chunks.

## Security & Configuration Tips
Do not commit real fusionagent credentials; edit `fusionagent.toml.example` instead and add deltas to docs. Treat DuckDB files (`context.duckdb*`) as generated artifacts. When touching TOML configs, document new keys in `docs/fusionagent_config_docs.md` so agents remain in sync.
