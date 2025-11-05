package com.orchestrator.storage.schema

/**
 * DuckDB schema definition for the Orchestrator.
 *
 * This object exposes a list of SQL statements (in a deterministic order)
 * to create tables, add comments, and create indexes commonly used by queries.
 *
 * Key notes:
 * - Uses DuckDB-specific types: JSON and ARRAY (via type[] syntax)
 * - DuckDB currently blocks UPDATEs on parent rows that are referenced by FKs, so
 *   referential integrity with tasks/decisions is enforced at the application layer
 *   (we retain supporting indexes for quick lookups)
 * - Adds CHECK constraints for value ranges
 * - Includes indexes on frequently queried columns
 */
object Schema {
    /**
     * Ordered list of SQL DDL statements. Execute them sequentially.
     */
    private val baseStatements: List<String> = listOf(
        // --- Tables ---
        // tasks
        """
        CREATE TABLE IF NOT EXISTS tasks (
            id              VARCHAR PRIMARY KEY, -- domain TaskId (string)
            title           TEXT NOT NULL,       -- short human-readable title
            description     TEXT,                -- optional detailed description
            type            VARCHAR NOT NULL,    -- enum TaskType as text
            status          VARCHAR NOT NULL,    -- enum TaskStatus as text
            routing         VARCHAR NOT NULL,    -- enum RoutingStrategy as text
            assignee_ids    VARCHAR[] DEFAULT [],-- set of AgentId values (no FK table defined for agents)
            dependencies    VARCHAR[] DEFAULT [],-- set of TaskId values (self-referential; array cannot have FK)
            complexity      INTEGER NOT NULL CHECK (complexity BETWEEN 1 AND 10),
            risk            INTEGER NOT NULL CHECK (risk BETWEEN 1 AND 10),
            created_at      TIMESTAMP NOT NULL,
            updated_at      TIMESTAMP,
            due_at          TIMESTAMP,
            metadata        JSON                 -- arbitrary key/value metadata (string-to-string)
        );
        """.trimIndent(),

        // proposals
        """
        CREATE TABLE IF NOT EXISTS proposals (
            id              VARCHAR PRIMARY KEY, -- domain ProposalId (string)
            task_id         VARCHAR NOT NULL,    -- references tasks.id (application-enforced)
            agent_id        VARCHAR NOT NULL,    -- origin AgentId (no agents table to reference)
            input_type      VARCHAR NOT NULL,    -- enum InputType as text
            content         JSON,                -- JSON-compatible proposal payload
            confidence      DOUBLE NOT NULL CHECK (confidence >= 0.0 AND confidence <= 1.0),
            token_input     INTEGER NOT NULL DEFAULT 0,  -- TokenUsage.inputTokens
            token_output    INTEGER NOT NULL DEFAULT 0,  -- TokenUsage.outputTokens
            created_at      TIMESTAMP NOT NULL,
            metadata        JSON
        );
        """.trimIndent(),

        // decisions
        """
        CREATE TABLE IF NOT EXISTS decisions (
            id                  VARCHAR PRIMARY KEY, -- domain DecisionId (string)
            task_id             VARCHAR NOT NULL,    -- references tasks.id (application-enforced)
            considered          JSON NOT NULL,       -- JSON array of ProposalRef snapshots
            selected_ids        VARCHAR[] NOT NULL DEFAULT [], -- selected ProposalId values
            winner_proposal_id  VARCHAR,             -- optional single winner
            agreement_rate      DOUBLE,              -- [0.0, 1.0]
            rationale           TEXT,                -- free-form rationale
            decided_at          TIMESTAMP NOT NULL,
            metadata            JSON,
            CONSTRAINT chk_agreement_bounds CHECK (
                agreement_rate IS NULL OR (agreement_rate >= 0.0 AND agreement_rate <= 1.0)
            ),
            CONSTRAINT fk_decisions_winner
                FOREIGN KEY(winner_proposal_id) REFERENCES proposals(id)
        );
        """.trimIndent(),

        // metrics_timeseries
        """
        CREATE TABLE IF NOT EXISTS metrics_timeseries (
            id              BIGINT PRIMARY KEY, -- surrogate key
            task_id         VARCHAR,                 -- optional reference to tasks.id
            agent_id        VARCHAR,                 -- optional AgentId
            metric_name     VARCHAR NOT NULL,        -- e.g., \"latency_ms\", \"tokens\"
            ts              TIMESTAMP NOT NULL,      -- timestamp of the metric sample
            value           DOUBLE,                  -- numeric metric value
            tags            JSON                    -- optional dimensions/tags (JSON object)
        );
        """.trimIndent(),

        // context_snapshots
        """
        CREATE TABLE IF NOT EXISTS context_snapshots (
            id              BIGINT PRIMARY KEY,
            task_id         VARCHAR,                 -- optional reference to tasks.id
            decision_id     VARCHAR,                 -- optional reference to decisions.id
            label           VARCHAR,                 -- optional label for the snapshot (e.g., \"pre-consensus\")
            snapshot        JSON NOT NULL,           -- JSON document capturing context
            created_at      TIMESTAMP NOT NULL       -- when the snapshot was recorded
        );
        """.trimIndent(),

        // conversation_messages
        """
        CREATE TABLE IF NOT EXISTS conversation_messages (
            id          BIGINT PRIMARY KEY,
            task_id     VARCHAR NOT NULL,      -- references tasks.id (application-enforced)
            role        VARCHAR NOT NULL,      -- user, assistant, system, tool, summary
            agent_id    VARCHAR,               -- optional AgentId for assistant/tool
            content     TEXT NOT NULL,         -- raw message content (text)
            tokens      INTEGER NOT NULL DEFAULT 0, -- estimated token count
            ts          TIMESTAMP NOT NULL,    -- message timestamp
            metadata    JSON                   -- optional JSON metadata
        );
        """.trimIndent(),

        // Indexes for conversation_messages
        """
        CREATE INDEX IF NOT EXISTS idx_conv_task_ts ON conversation_messages(task_id, ts);
        """.trimIndent(),

        """
        CREATE INDEX IF NOT EXISTS idx_conv_task_id ON conversation_messages(task_id, id);
        """.trimIndent(),

        // --- Comments ---
        // tasks comments
        """
        COMMENT ON TABLE tasks IS 'Tasks tracked by the orchestrator';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.id IS 'Domain TaskId (string)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.title IS 'Short human-readable title';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.description IS 'Optional detailed description';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.type IS 'TaskType enum as text';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.status IS 'TaskStatus enum as text';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.routing IS 'RoutingStrategy enum as text';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.assignee_ids IS 'Set of AgentId values (array of VARCHAR)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.dependencies IS 'TaskId dependencies (array of VARCHAR; cannot enforce FK on arrays)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.complexity IS 'Relative difficulty 1..10';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.risk IS 'Relative risk 1..10';
        """.trimIndent(),
        """
        COMMENT ON COLUMN tasks.metadata IS 'Arbitrary metadata as JSON object';
        """.trimIndent(),

        // proposals comments
        """
        COMMENT ON TABLE proposals IS 'Agent proposals linked to tasks';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.task_id IS 'References tasks.id (application-enforced)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.agent_id IS 'Origin AgentId (no agents table)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.input_type IS 'InputType enum as text';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.content IS 'JSON-compatible proposal payload';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.confidence IS 'Confidence in [0.0, 1.0]';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.token_input IS 'TokenUsage.inputTokens';
        """.trimIndent(),
        """
        COMMENT ON COLUMN proposals.token_output IS 'TokenUsage.outputTokens';
        """.trimIndent(),

        // decisions comments
        """
        COMMENT ON TABLE decisions IS 'Consensus decisions for tasks';
        """.trimIndent(),
        """
        COMMENT ON COLUMN decisions.task_id IS 'References tasks.id (application-enforced)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN decisions.considered IS 'JSON array of ProposalRef snapshots considered in the decision';
        """.trimIndent(),
        """
        COMMENT ON COLUMN decisions.selected_ids IS 'Set of selected ProposalId values (array)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN decisions.winner_proposal_id IS 'Optional winning ProposalId (FK to proposals.id)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN decisions.agreement_rate IS 'Agreement in [0.0, 1.0]';
        """.trimIndent(),

        // metrics_timeseries comments
        """
        COMMENT ON TABLE metrics_timeseries IS 'Time series metrics (task/agent scoped)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN metrics_timeseries.task_id IS 'Optional reference to tasks.id';
        """.trimIndent(),
        """
        COMMENT ON COLUMN metrics_timeseries.metric_name IS 'Metric name (e.g., latency_ms, tokens)';
        """.trimIndent(),
        """
        COMMENT ON COLUMN metrics_timeseries.ts IS 'Metric timestamp';
        """.trimIndent(),
        """
        COMMENT ON COLUMN metrics_timeseries.value IS 'Metric numeric value';
        """.trimIndent(),
        """
        COMMENT ON COLUMN metrics_timeseries.tags IS 'Optional dimensions/tags as JSON object';
        """.trimIndent(),

        // context_snapshots comments
        """
        COMMENT ON TABLE context_snapshots IS 'Snapshots of execution context for auditing';
        """.trimIndent(),
        """
        COMMENT ON COLUMN context_snapshots.task_id IS 'Optional reference to tasks.id';
        """.trimIndent(),
        """
        COMMENT ON COLUMN context_snapshots.decision_id IS 'Optional reference to decisions.id';
        """.trimIndent(),
        """
        COMMENT ON COLUMN context_snapshots.label IS 'Optional label for the snapshot';
        """.trimIndent(),
        """
        COMMENT ON COLUMN context_snapshots.snapshot IS 'JSON document with captured context';
        """.trimIndent(),

        // --- Indexes ---
        // tasks indexes
        """
        CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_tasks_type ON tasks(type);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_tasks_due_at ON tasks(due_at);
        """.trimIndent(),

        // proposals indexes
        """
        CREATE INDEX IF NOT EXISTS idx_proposals_task ON proposals(task_id);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_proposals_agent ON proposals(agent_id);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_proposals_created_at ON proposals(created_at);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_proposals_input_type ON proposals(input_type);
        """.trimIndent(),

        // decisions indexes
        """
        CREATE INDEX IF NOT EXISTS idx_decisions_task ON decisions(task_id);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_decisions_decided_at ON decisions(decided_at);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_decisions_winner ON decisions(winner_proposal_id);
        """.trimIndent(),

        // metrics_timeseries indexes
        """
        CREATE INDEX IF NOT EXISTS idx_metrics_task_name_time ON metrics_timeseries(task_id, metric_name, ts);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_metrics_ts ON metrics_timeseries(ts);
        """.trimIndent(),

        // context_snapshots indexes
        """
        CREATE INDEX IF NOT EXISTS idx_ctx_task ON context_snapshots(task_id);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_ctx_decision ON context_snapshots(decision_id);
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_ctx_created_at ON context_snapshots(created_at);
        """.trimIndent()
    )

    val statements: List<String> = baseStatements + loadResourceStatements("/context/schema.sql")

    private fun loadResourceStatements(resourcePath: String): List<String> {
        val stream = Schema::class.java.getResourceAsStream(resourcePath) ?: return emptyList()
        val statements = mutableListOf<String>()
        val builder = StringBuilder()
        stream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("--")) return@forEach
                builder.append(rawLine).append(' ')
                if (line.endsWith(';')) {
                    val statement = builder.toString().trim()
                    if (statement.isNotEmpty()) {
                        statements.add(statement)
                    }
                    builder.setLength(0)
                }
            }
        }
        val remainder = builder.toString().trim()
        if (remainder.isNotEmpty()) {
            statements.add(if (remainder.endsWith(';')) remainder else "$remainder;")
        }
        return statements.map { stmt ->
            val trimmed = stmt.trim()
            if (trimmed.endsWith(';')) trimmed else "$trimmed;"
        }
    }
}
