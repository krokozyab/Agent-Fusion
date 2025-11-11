-- Surrogate key sequences keep identifiers monotonic without relying on identity columns
CREATE SEQUENCE IF NOT EXISTS file_state_seq START 1;
CREATE SEQUENCE IF NOT EXISTS chunks_seq START 1;
CREATE SEQUENCE IF NOT EXISTS embeddings_seq START 1;
CREATE SEQUENCE IF NOT EXISTS links_seq START 1;
CREATE SEQUENCE IF NOT EXISTS usage_metrics_seq START 1;
CREATE SEQUENCE IF NOT EXISTS bootstrap_progress_seq START 1;
CREATE SEQUENCE IF NOT EXISTS bootstrap_errors_seq START 1;
CREATE SEQUENCE IF NOT EXISTS jobs_seq START 1;
CREATE SEQUENCE IF NOT EXISTS project_config_seq START 1;
CREATE SEQUENCE IF NOT EXISTS context_snapshots_seq START 1;

-- Tracks each file that has been indexed, including its latest fingerprint
CREATE TABLE IF NOT EXISTS file_state (
    -- Column: file_id - surrogate key for referencing files across tables
    file_id BIGINT PRIMARY KEY DEFAULT nextval('file_state_seq'),
    -- Column: rel_path - workspace-relative path used for unique identification
    rel_path TEXT NOT NULL,
    -- Column: content_hash - hash of file contents for change detection
    content_hash TEXT NOT NULL,
    -- Column: size_bytes - size of the file at indexing time
    size_bytes BIGINT NOT NULL,
    -- Column: mtime_ns - last modified timestamp in nanoseconds
    mtime_ns BIGINT NOT NULL,
    -- Column: language - detected language or file type hint
    language TEXT,
    -- Column: kind - high-level category (source, config, doc, etc.)
    kind TEXT,
    -- Column: fingerprint - additional fingerprint metadata for delta indexing
    fingerprint TEXT,
    -- Column: indexed_at - timestamp for when the file was last indexed
    indexed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Column: is_deleted - soft-delete marker for removed files
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (rel_path)
);

-- Stores semantic chunks produced from files for retrieval
CREATE TABLE IF NOT EXISTS chunks (
    -- Column: chunk_id - surrogate key for referencing semantic chunks
    chunk_id BIGINT PRIMARY KEY DEFAULT nextval('chunks_seq'),
    -- Column: file_id - foreign key back to the owning file
    file_id BIGINT NOT NULL,
    -- Column: ordinal - stable ordering of chunks per file
    ordinal INTEGER NOT NULL,
    -- Column: kind - chunk type (code, docstring, comment, etc.)
    kind TEXT NOT NULL,
    -- Column: start_line - first line covered by the chunk (1-indexed)
    start_line INTEGER,
    -- Column: end_line - last line covered by the chunk (inclusive)
    end_line INTEGER,
    -- Column: token_count - token estimate for budgeting
    token_count INTEGER,
    -- Column: content - textual content of the chunk
    content TEXT NOT NULL,
    -- Column: summary - optional short summary for quick previews
    summary TEXT,
    -- Column: created_at - timestamp for chunk creation
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id) REFERENCES file_state(file_id),
    UNIQUE (file_id, ordinal)
);

-- Persists embedding vectors associated with chunks for semantic search
CREATE TABLE IF NOT EXISTS embeddings (
    -- Column: embedding_id - surrogate key for embedding rows
    embedding_id BIGINT PRIMARY KEY DEFAULT nextval('embeddings_seq'),
    -- Column: chunk_id - foreign key to the source chunk
    chunk_id BIGINT NOT NULL,
    -- Column: model - name of embedding model used
    model TEXT NOT NULL,
    -- Column: dimensions - dimensionality of the embedding vector
    dimensions INTEGER NOT NULL,
    -- Column: vector - dense vector serialized as JSON text
    vector TEXT NOT NULL,
    -- Column: created_at - timestamp for embedding generation
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chunk_id) REFERENCES chunks(chunk_id),
    UNIQUE (chunk_id, model)
);

-- Captures explicit cross-file relationships discovered during indexing
CREATE TABLE IF NOT EXISTS links (
    -- Column: link_id - surrogate key for referencing links
    link_id BIGINT PRIMARY KEY DEFAULT nextval('links_seq'),
    -- Column: source_chunk_id - chunk where the reference originated
    source_chunk_id BIGINT NOT NULL,
    -- Column: target_file_id - file being referenced
    target_file_id BIGINT NOT NULL,
    -- Column: target_chunk_id - optional chunk-level target reference
    target_chunk_id BIGINT,
    -- Column: link_type - category of relationship (import, call, doc, etc.)
    link_type TEXT NOT NULL,
    -- Column: label - human-readable label or anchor text
    label TEXT,
    -- Column: score - optional confidence or ranking signal
    score DOUBLE,
    -- Column: created_at - timestamp for when the link was recorded
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (source_chunk_id) REFERENCES chunks(chunk_id),
    FOREIGN KEY (target_file_id) REFERENCES file_state(file_id),
    FOREIGN KEY (target_chunk_id) REFERENCES chunks(chunk_id)
);

-- Records token usage metrics for analytics and budgeting
CREATE TABLE IF NOT EXISTS usage_metrics (
    -- Column: metric_id - surrogate key for metric entries
    metric_id BIGINT PRIMARY KEY DEFAULT nextval('usage_metrics_seq'),
    -- Column: chunk_id - optional reference to a specific chunk
    chunk_id BIGINT,
    -- Column: file_id - optional reference to a file context
    file_id BIGINT,
    -- Column: context_scope - scope identifier (workflow, agent, etc.)
    context_scope TEXT NOT NULL,
    -- Column: usage_type - classification of the token usage event
    usage_type TEXT NOT NULL,
    -- Column: token_count - number of tokens consumed or produced
    token_count INTEGER NOT NULL,
    -- Column: recorded_at - timestamp for when usage was recorded
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chunk_id) REFERENCES chunks(chunk_id),
    FOREIGN KEY (file_id) REFERENCES file_state(file_id)
);

-- Tracks bootstrap progress for each phase and scope
CREATE TABLE IF NOT EXISTS bootstrap_progress (
    -- Column: progress_id - surrogate key for progress entries
    progress_id BIGINT PRIMARY KEY DEFAULT nextval('bootstrap_progress_seq'),
    -- Column: scope - identifier for the bootstrap scope (e.g., repo, directory)
    scope TEXT NOT NULL,
    -- Column: phase - current phase name (discovery, chunking, etc.)
    phase TEXT NOT NULL,
    -- Column: total_items - total work items discovered for the phase
    total_items BIGINT,
    -- Column: completed_items - work items completed so far
    completed_items BIGINT,
    -- Column: last_checkpoint_at - last checkpoint timestamp for resumable workflows
    last_checkpoint_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Column: metadata - optional JSON metadata for custom state
    metadata JSON
);

-- Captures bootstrap failures for auditing and retries
CREATE TABLE IF NOT EXISTS bootstrap_errors (
    -- Column: error_id - surrogate key for error entries
    error_id BIGINT PRIMARY KEY DEFAULT nextval('bootstrap_errors_seq'),
    -- Column: scope - identifier matching bootstrap_progress scope
    scope TEXT NOT NULL,
    -- Column: phase - phase in which the error occurred
    phase TEXT NOT NULL,
    -- Column: encountered_at - timestamp when error was recorded
    encountered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Column: message - human-readable error description
    message TEXT NOT NULL,
    -- Column: details - structured payload for stack traces or diagnostics
    details JSON
);

-- Queue of asynchronous bootstrap and rebuild jobs
CREATE TABLE IF NOT EXISTS jobs (
    -- Column: job_id - surrogate key for job tracking
    job_id BIGINT PRIMARY KEY DEFAULT nextval('jobs_seq'),
    -- Column: job_type - task category (bootstrap, reindex, refresh, etc.)
    job_type TEXT NOT NULL,
    -- Column: status - lifecycle state (queued, running, succeeded, failed)
    status TEXT NOT NULL,
    -- Column: scope - optional scope identifier for targeted jobs
    scope TEXT,
    -- Column: payload - JSON payload describing the job request
    payload JSON,
    -- Column: created_at - job creation time
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Column: started_at - timestamp when execution began
    started_at TIMESTAMP,
    -- Column: completed_at - timestamp when execution finished
    completed_at TIMESTAMP,
    -- Column: last_error - last error message captured (if any)
    last_error TEXT
);

-- Stores compressed task/decision state snapshots for restoration
CREATE TABLE IF NOT EXISTS context_snapshots (
    id BIGINT PRIMARY KEY,
    task_id TEXT,
    decision_id TEXT,
    label TEXT,
    snapshot JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Stores project boundary configuration discovered during bootstrap
CREATE TABLE IF NOT EXISTS project_config (
    -- Column: config_id - surrogate key for configuration rows
    config_id BIGINT PRIMARY KEY DEFAULT nextval('project_config_seq'),
    -- Column: scope - identifier for the configuration boundary
    scope TEXT NOT NULL,
    -- Column: include_globs - JSON array of include glob patterns
    include_globs JSON,
    -- Column: exclude_globs - JSON array of exclude glob patterns
    exclude_globs JSON,
    -- Column: root_paths - JSON array of project root paths
    root_paths JSON,
    -- Column: created_at - timestamp for initial capture
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Column: updated_at - timestamp for last modification
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Dedicated indexes to accelerate common lookup paths
CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_chunks_kind ON chunks(kind);
CREATE INDEX IF NOT EXISTS idx_embeddings_model ON embeddings(model);
CREATE INDEX IF NOT EXISTS idx_file_state_path ON file_state(rel_path);
CREATE INDEX IF NOT EXISTS idx_file_state_mtime ON file_state(mtime_ns);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at);

-- NOTE: DuckDB 1.4.1 parses but does not yet implement foreign key cascade actions.
-- Cascade semantics will be enforced in application logic until native support lands.
