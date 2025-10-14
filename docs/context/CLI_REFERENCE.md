# Context CLI & Configuration Reference

The context subsystem ships with a command-line entry point named `contextd`. It wraps the orchestration utilities for validating configuration, inspecting indexing scope, and rebuilding the DuckDB store. This guide lists every available command and summarizes the supported options in `context.toml`.

## Running the CLI
- From the repo root you can execute the CLI via the shaded jar:\
  `java -jar build/libs/orchestrator-all.jar contextd <command> [options]`
- During development you can invoke it without packaging:\
  `./gradlew run --args "contextd <command> [options]"`
- Global flags:
  - `--help`/`-h` – shows the top-level help with registered subcommands.
  - `--version`/`-v` – prints the CLI version (falls back to `dev` when running from source).

## Subcommands
- **validate-config** – Parse and validate a `context.toml` file using `ProjectConfigValidator`.
  - Options: `--path/-p` (config location, default `config/context.toml`), `--scope/-s` (validator scope, default `default`), `--help/-h`.
  - Exit codes: `0` success; `1` invalid configuration or load failure.
  - Example: `contextd validate-config --path /workspace/context.toml`.
- **list-indexable** – List every file that would be indexed according to the current configuration.
  - Options: `--path/-p` (config file), `--limit/-l` (cap printed entries), `--help/-h`.
  - Emits warnings for missing watch paths and prints summary statistics.
  - Example: `contextd list-indexable --limit 50`.
- **check-file** – Report whether a specific file would be indexed.
  - Options: `--file/-f` (required), `--path/-p`, `--scope/-s`, `--help/-h`.
  - Returns `0` when the target is included; `1` when excluded or configuration errors occur.
  - Example: `contextd check-file --file src/main/kotlin/App.kt`.
- **project-stats** – Produce high-level statistics about indexable files per watch root.
  - Options: `--path/-p`, `--scope/-s`, `--help/-h`.
  - Prints totals, top extensions, and exclusion reasons to aid tuning ignore patterns.
  - Example: `contextd project-stats --scope ci`.
- **rebuild** – Run the rebuild workflow backed by `RebuildContextTool`.
  - Options: `--path/-p`, `--paths` (comma-separated subset to reindex), `--async`, `--dry-run`, `--force`, `--no-vacuum`, `--help/-h`.
  - Without `--force` or `--dry-run` the command prompts for confirmation; declining returns exit code `2`.
  - Example: `contextd rebuild --paths src/main,kotlin-sdk --force`.

All subcommands rely on `config/context.toml` by default; pass `--path` to test alternate configurations.

## `context.toml` Configuration Options
The CLI and orchestrator load the same schema defined in `ContextConfig`. Settings below map directly to TOML keys (snake_case).

- **Top-Level**
  - `enabled` (`true`) – master switch for the context subsystem.
  - `mode` (`"embedded" | "standalone" | "hybrid"`, default `embedded`) – determines where the retrieval engine runs.
  - `fallback_enabled` (`true`) – allow workflows to continue when retrieval fails.
- **engine** – Connection settings used in standalone/hybrid deployments.
  - `host` (`"localhost"`), `port` (`9090`), `timeout_ms` (`10_000`), `retry_attempts` (`3`).
- **storage**
  - `db_path` (`"./context.duckdb"`), `backup_enabled` (`false`), `backup_interval_hours` (`24`).
- **watcher**
  - `enabled` (`true`), `debounce_ms` (`500`), `watch_paths` (`["auto"]`), `ignore_patterns` (defaults include `.git`, `node_modules`, etc.).
  - `max_file_size_mb` (`5`), `use_gitignore` (`true`), `use_contextignore` (`true`).
- **indexing**
  - `allowed_extensions` (language whitelist), `blocked_extensions` (binary/large resources).
  - `max_file_size_mb` (`5`), `warn_file_size_mb` (`2`), `size_exceptions` (`[]`).
  - `follow_symlinks` (`false`), `max_symlink_depth` (`3`).
  - `binary_detection` (`"extension" | "mime" | "content" | "all"`, default `all`), `binary_threshold` (`30`).
- **embedding**
  - `model` (`"sentence-transformers/all-MiniLM-L6-v2"`), `dimension` (`384`), `batch_size` (`128`).
  - `normalize` (`true`), `cache_enabled` (`true`).
- **chunking** – Per-language controls for snippet boundaries.
  - `markdown`, `python`, `kotlin`, `typescript` sections expose `max_tokens` and heuristics (e.g., `split_by_headings`, `preserve_docstrings`).
- **query**
  - `default_k` (`12`), `mmr_lambda` (`0.5`), `min_score_threshold` (`0.3`), `rerank_enabled` (`true`).
- **budget**
  - `default_max_tokens` (`1500`), `reserve_for_prompt` (`500`), `warn_threshold_percent` (`80`).
- **prompt**
  - `enabled` (`true`), `max_tokens` (`null` uses derived budgets).
- **providers** – Map keyed by provider id (`semantic`, `symbol`, `git_history`, `hybrid`, etc.).
  - Per-provider fields: `enabled`, `weight`, `index_ast`, `max_commits`, `combines`, `fusion_strategy`.
- **metrics**
  - `enabled` (`true`), `track_latency` (`true`), `track_token_usage` (`true`), `track_cache_hits` (`true`), `export_interval_minutes` (`5`).
- **bootstrap**
  - `enabled` (`true`), `parallel_workers` (`7`), `batch_size` (`128`), `priority_extensions` (default language list), `max_initial_files` (`0` for unlimited), `fail_fast` (`false`), `show_progress` (`true`), `progress_interval_seconds` (`30`).
- **security**
  - `scrub_secrets` (`true`), `secret_patterns` (regex defaults for common credentials), `encrypt_db` (`false`).

### Provider Defaults
If the `providers` table is omitted, the system installs:
- `semantic` (enabled, weight `0.6`)
- `symbol` (enabled, weight `0.3`, `index_ast = true`)
- `git_history` (enabled, weight `0.2`, `max_commits = 100`)
- `full_text` (disabled, weight `0.1`)
- `hybrid` (enabled, weight `0.5`, `combines = ["semantic","symbol","git_history"]`, `fusion_strategy = "rrf"`)

Override individual providers to tune weights or disable sources entirely.

## Tips
- Always run `contextd validate-config` in CI before shipping configuration changes.
- For large monorepos, start with `project-stats` to understand exclusion ratios, then drill down with `check-file`.
- Use `rebuild --dry-run` to verify settings without modifying the DuckDB database.
