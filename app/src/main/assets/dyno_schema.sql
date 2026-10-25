-- ============================================================================
-- Kojot Dyno :: dyno.db schema            PRAGMA user_version = 1
-- ----------------------------------------------------------------------------
-- Applied by DynoDb.onCreate / onUpgrade. Every statement is idempotent
-- (IF NOT EXISTS / INSERT OR IGNORE), so re-applying on upgrade is safe.
-- Views live in dyno_views.sql and are dropped/recreated on every open.
--
-- Requirements: SQLite >= 3.31 (generated columns) and JSON1 (json_valid,
-- json_object). Both ship with Android 14. WAL + foreign_keys are enabled by
-- DynoDb, not here (PRAGMAs are per-connection).
--
-- Conventions
--   *_ms  wall-clock epoch milliseconds
--   *_us  monotonic microseconds (SystemClock.elapsedRealtimeNanos() / 1000)
--   *_mb  MiB (1024*1024 bytes)
--
-- Turn lifecycle (telemetry_turns.turn_state)
--   STARTED          row inserted BEFORE the request is sent; survives a kill
--   GENERATED        inference finished, awaiting the JS validator's verdict
--   COMPLETE         verdict applied (or engine-level failure locked it in)
--   RECOVERED_CRASH  app process died mid-turn; reclassified at next boot
--   UNJUDGED         generated but never judged (excluded from statistics)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Failure taxonomy (lookup, FK target for telemetry_turns.failure_class)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS failure_classes (
    failure_class TEXT PRIMARY KEY,
    category      TEXT    NOT NULL CHECK (category IN ('SYNTAX','SAMPLER','CONTEXT','RESOURCE','AGENTIC','ENGINE','TRANSPORT')),
    severity      INTEGER NOT NULL DEFAULT 2 CHECK (severity BETWEEN 1 AND 5),
    description   TEXT    NOT NULL
) WITHOUT ROWID;

INSERT OR IGNORE INTO failure_classes (failure_class, category, severity, description) VALUES
 ('SCHEMA_SYNTAX_ERROR',        'SYNTAX',    3, 'Output is not parseable JSON (unterminated string, bad escape, trailing garbage)'),
 ('SCHEMA_VIOLATION',           'SYNTAX',    2, 'Output parses but violates the target JSON schema (missing key, wrong type, bad enum)'),
 ('TRUNCATED_OUTPUT',          'SYNTAX',    2, 'Generation hit n_predict or EOS before the structure closed'),
 ('GRAMMAR_ABORT',              'ENGINE',    4, 'Grammar sampler dead-ended (empty grammar stack / no candidates)'),
 ('CYCLIC_LOOP',                'SAMPLER',   3, 'Repetition attractor: token n-gram cycle detected by the circuit breaker'),
 ('RUNAWAY_GENERATION',         'SAMPLER',   3, 'Non-cyclic generation exceeded the chunk budget without terminating'),
 ('EMPTY_OUTPUT',               'SAMPLER',   2, 'Server completed with zero generated tokens'),
 ('CONTEXT_ATTENTION_COLLAPSE', 'CONTEXT',   4, 'Needle / constraint retrieval failed at depth; attention degraded'),
 ('CONTEXT_OVERFLOW_REJECTED',  'CONTEXT',   3, 'Prompt + generation exceeded n_ctx and was rejected or clamped'),
 ('LMK_OOM',                    'RESOURCE',  5, 'Memory exhaustion: LMK SIGKILL, allocator failure, or watchdog pre-emptive kill'),
 ('THERMAL_COLLAPSE',           'RESOURCE',  3, 'Throughput collapse attributable to thermal throttling'),
 ('SERVER_CRASH',               'ENGINE',    5, 'llama-server died (SIGSEGV, SIGABRT/GGML_ASSERT, non-LMK SIGKILL, bad exit)'),
 ('SERVER_TIMEOUT',             'TRANSPORT', 4, 'TTFT deadline, inter-token stall or wall-clock deadline tripped'),
 ('HTTP_ERROR',                 'TRANSPORT', 3, 'Non-2xx response not otherwise classified'),
 ('TOOL_ARG_DRIFT',             'AGENTIC',   3, 'Tool-call arguments drift from the schema or from prior turns'),
 ('TOOL_CALL_MALFORMED',        'AGENTIC',   3, 'Tool call cannot be parsed or names a nonexistent tool'),
 ('TOOL_ERROR_MISHANDLED',      'AGENTIC',   3, 'Model ignored, hallucinated around, or looped on an injected tool error'),
 ('TOOL_LOOP',                  'AGENTIC',   3, 'Recursive tool calling without progress'),
 ('UNKNOWN',                    'ENGINE',    1, 'Unclassified failure');

-- ---------------------------------------------------------------------------
-- Model registry (one row per distinct GGUF, keyed by cheap content fingerprint)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS models (
    model_id            INTEGER PRIMARY KEY,
    file_path           TEXT    NOT NULL,
    file_name           TEXT    NOT NULL,
    size_bytes          INTEGER NOT NULL,
    mtime_ms            INTEGER NOT NULL,
    fingerprint         TEXT    NOT NULL UNIQUE,
    architecture        TEXT,
    model_family        TEXT    NOT NULL,
    param_size_b        REAL,
    quant_type          TEXT    NOT NULL DEFAULT 'UNKNOWN',
    ftype_id            INTEGER,
    n_layer             INTEGER,
    n_embd              INTEGER,
    n_head              INTEGER,
    n_head_kv           INTEGER,
    ctx_train           INTEGER,
    key_length          INTEGER,
    value_length        INTEGER,
    rope_freq_base      REAL,
    rope_scaling_factor REAL,
    gguf_meta_json      TEXT CHECK (gguf_meta_json IS NULL OR json_valid(gguf_meta_json)),
    first_seen_ms       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_models_family_quant ON models (model_family, param_size_b, quant_type);

-- ---------------------------------------------------------------------------
-- test_suites: versioned battery definitions
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS test_suites (
    suite_id      INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    category      TEXT    NOT NULL CHECK (category IN ('SCHEMA_GRAMMAR','SAMPLER_SWEEP','CONTEXT_DEGRADATION','AGENTIC_TOOL','MEMORY_PRESSURE','CUSTOM')),
    description   TEXT,
    config_json   TEXT    NOT NULL DEFAULT '{}' CHECK (json_valid(config_json)),
    created_at_ms INTEGER NOT NULL,
    UNIQUE (name, version)
);

INSERT OR IGNORE INTO test_suites (name, version, category, description, created_at_ms) VALUES
 ('schema_gauntlet',   1, 'SCHEMA_GRAMMAR',      'Nested objects, arrays of objects, union types, unterminated strings under JSON-schema grammar and free generation', CAST(strftime('%s','now') AS INTEGER) * 1000),
 ('sampler_sweep',     1, 'SAMPLER_SWEEP',       'Temperature x Top-P x Min-P x repetition-penalty grid to map cyclic attractors',                                      CAST(strftime('%s','now') AS INTEGER) * 1000),
 ('context_ladder',    1, 'CONTEXT_DEGRADATION', 'Needle-in-haystack and multi-constraint checks at 1k/2k/4k/8k/16k depths',                                           CAST(strftime('%s','now') AS INTEGER) * 1000),
 ('agent_tool_chaos',  1, 'AGENTIC_TOOL',        'Recursive tool calls with ambiguous args, dropped sockets and malformed tool returns',                                CAST(strftime('%s','now') AS INTEGER) * 1000),
 ('memory_squeeze',    1, 'MEMORY_PRESSURE',     'Oversized ctx / KV types / no-mmap launches to probe LMK and allocator failure boundaries',                           CAST(strftime('%s','now') AS INTEGER) * 1000);

-- ---------------------------------------------------------------------------
-- test_runs: one row per (suite, model, launch configuration)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS test_runs (
    run_id                   INTEGER PRIMARY KEY,
    run_uuid                 TEXT    NOT NULL UNIQUE,
    suite_id                 INTEGER NOT NULL REFERENCES test_suites (suite_id) ON DELETE RESTRICT,
    model_id                 INTEGER NOT NULL REFERENCES models (model_id)      ON DELETE RESTRICT,
    status                   TEXT    NOT NULL DEFAULT 'QUEUED'
                             CHECK (status IN ('QUEUED','STARTING','RUNNING','COMPLETED','ABORTED','CRASHED','FAILED_TO_START')),
    abort_reason             TEXT,
    started_at_ms            INTEGER NOT NULL,
    ended_at_ms              INTEGER,
    load_ms                  INTEGER,
    -- launch configuration (fixed for the lifetime of the server process)
    ctx_size                 INTEGER NOT NULL,
    threads                  INTEGER NOT NULL,
    threads_batch            INTEGER,
    batch_size               INTEGER NOT NULL,
    ubatch_size              INTEGER,
    rope_freq_base           REAL,
    rope_freq_scale          REAL,
    flash_attn               TEXT,
    cache_type_k             TEXT,
    cache_type_v             TEXT,
    n_gpu_layers             INTEGER,
    mlock                    INTEGER CHECK (mlock IN (0,1)),
    mmap                     INTEGER CHECK (mmap IN (0,1)),
    port                     INTEGER,
    server_pid               INTEGER,
    server_args_json         TEXT CHECK (server_args_json IS NULL OR json_valid(server_args_json)),
    llama_build              TEXT,
    -- pre-flight memory model (heuristic; see Preflight in GgufMeta.kt)
    preflight_verdict        TEXT CHECK (preflight_verdict IN ('OK','RISKY_THRASH','REFUSE','UNKNOWN')),
    predicted_resident_mb    REAL,
    predicted_anon_mb        REAL,
    predicted_budget_mb      REAL,
    model_cache_resident_pct REAL,
    -- device fingerprint
    device_model             TEXT,
    soc                      TEXT,
    android_sdk              INTEGER,
    total_ram_mb             INTEGER,
    -- counters maintained by triggers
    server_restarts          INTEGER NOT NULL DEFAULT 0,
    n_turns                  INTEGER NOT NULL DEFAULT 0,
    n_failures               INTEGER NOT NULL DEFAULT 0,
    notes                    TEXT
);
CREATE INDEX IF NOT EXISTS ix_runs_suite_time ON test_runs (suite_id, started_at_ms);
CREATE INDEX IF NOT EXISTS ix_runs_model_cfg  ON test_runs (model_id, ctx_size, cache_type_k);
CREATE INDEX IF NOT EXISTS ix_runs_live       ON test_runs (status) WHERE status IN ('STARTING','RUNNING');

-- ---------------------------------------------------------------------------
-- telemetry_turns: one row per request/response turn
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS telemetry_turns (
    turn_id               INTEGER PRIMARY KEY,
    run_id                INTEGER NOT NULL REFERENCES test_runs (run_id) ON DELETE CASCADE,
    turn_index            INTEGER NOT NULL,
    turn_state            TEXT    NOT NULL DEFAULT 'STARTED'
                          CHECK (turn_state IN ('STARTED','GENERATED','COMPLETE','RECOVERED_CRASH','UNJUDGED')),
    case_id               TEXT    NOT NULL,
    case_params_json      TEXT CHECK (case_params_json IS NULL OR json_valid(case_params_json)),

    -- agentic linkage
    trajectory_id         TEXT,
    parent_turn_id        INTEGER REFERENCES telemetry_turns (turn_id) ON DELETE SET NULL,
    agent_step            INTEGER,
    tool_name             TEXT,
    fault_injected        TEXT,

    endpoint              TEXT    NOT NULL DEFAULT '/v1/chat/completions',
    grammar_mode          TEXT    NOT NULL DEFAULT 'NONE' CHECK (grammar_mode IN ('NONE','JSON_SCHEMA','GBNF')),

    -- sampler parameters (per turn: sweeps vary these without a server restart)
    temp                  REAL,
    top_p                 REAL,
    top_k                 INTEGER,
    min_p                 REAL,
    repeat_penalty        REAL,
    repeat_last_n         INTEGER,
    seed                  INTEGER,
    n_predict             INTEGER,
    request_params_json   TEXT CHECK (request_params_json IS NULL OR json_valid(request_params_json)),

    -- timing
    wall_start_ms         INTEGER NOT NULL,
    t_start_us            INTEGER NOT NULL,
    ttft_us               INTEGER,
    duration_us           INTEGER,
    heartbeat_ms          INTEGER,
    ttft_ms               REAL GENERATED ALWAYS AS (ttft_us / 1000.0) VIRTUAL,

    -- inference metrics
    tok_per_sec           REAL,
    prompt_tok_per_sec    REAL,
    prompt_tokens         INTEGER,
    completion_tokens     INTEGER,
    cached_tokens         INTEGER,
    stream_chunks         INTEGER,

    -- hardware vitals (aggregated over the turn window)
    start_rss_mb          REAL,
    peak_rss_mb           REAL,
    end_rss_mb            REAL,
    rss_anon_peak_mb      REAL,
    rss_file_peak_mb      REAL,
    mem_available_mb_start REAL,
    mem_available_mb_min  REAL,
    lmk_headroom_mb_min   REAL,
    psi_some_max          REAL,
    psi_full_max          REAL,
    battery_temp_c        REAL,
    battery_temp_c_end    REAL,
    battery_temp_c_max    REAL,
    thermal_status        INTEGER,
    thermal_status_max    INTEGER,
    throttling_flag       INTEGER NOT NULL DEFAULT 0 CHECK (throttling_flag IN (0,1)),
    thermal_headroom_max  REAL,
    cpu_freq_cap_ratio_min REAL,
    vitals_samples        INTEGER,

    -- failure taxonomy
    is_failure            INTEGER NOT NULL DEFAULT 0 CHECK (is_failure IN (0,1)),
    failure_class         TEXT REFERENCES failure_classes (failure_class),
    failure_source        TEXT CHECK (failure_source IS NULL OR failure_source IN ('ENGINE','VALIDATOR','RECOVERY')),
    parsed_error_message  TEXT,
    failure_detail_json   TEXT CHECK (failure_detail_json IS NULL OR json_valid(failure_detail_json)),
    finish_reason         TEXT,
    http_status           INTEGER,
    loop_period_tokens    INTEGER,
    loop_repeat_count     INTEGER,

    raw_prompt            TEXT,
    raw_output            TEXT,

    -- analytical buckets (virtual; indexable)
    ctx_depth_bucket      INTEGER GENERATED ALWAYS AS (
        CASE WHEN prompt_tokens IS NULL THEN NULL
             WHEN prompt_tokens <  1536 THEN 1024
             WHEN prompt_tokens <  3072 THEN 2048
             WHEN prompt_tokens <  6144 THEN 4096
             WHEN prompt_tokens < 12288 THEN 8192
             ELSE 16384 END) VIRTUAL,
    min_p_bucket          REAL GENERATED ALWAYS AS (ROUND(min_p, 2)) VIRTUAL,
    temp_bucket           REAL GENERATED ALWAYS AS (ROUND(temp, 1)) VIRTUAL,

    CHECK (is_failure = 0 OR failure_class IS NOT NULL),
    UNIQUE (run_id, turn_index)
);
CREATE INDEX IF NOT EXISTS ix_turns_failure ON telemetry_turns (failure_class, run_id)                 WHERE is_failure = 1;
CREATE INDEX IF NOT EXISTS ix_turns_cell    ON telemetry_turns (ctx_depth_bucket, min_p_bucket, temp_bucket, is_failure);
CREATE INDEX IF NOT EXISTS ix_turns_case    ON telemetry_turns (case_id, run_id);
CREATE INDEX IF NOT EXISTS ix_turns_traj    ON telemetry_turns (trajectory_id, agent_step)             WHERE trajectory_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_turns_parent  ON telemetry_turns (parent_turn_id)                        WHERE parent_turn_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_turns_open    ON telemetry_turns (turn_state)                            WHERE turn_state IN ('STARTED','GENERATED');
CREATE INDEX IF NOT EXISTS ix_turns_wall    ON telemetry_turns (wall_start_ms);

-- run counters (cheap to maintain, invaluable for crash recovery and dashboards)
CREATE TRIGGER IF NOT EXISTS trg_turns_ai AFTER INSERT ON telemetry_turns
BEGIN
    UPDATE test_runs
       SET n_turns = n_turns + 1,
           n_failures = n_failures + NEW.is_failure
     WHERE run_id = NEW.run_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_turns_au AFTER UPDATE OF is_failure ON telemetry_turns
WHEN OLD.is_failure <> NEW.is_failure
BEGIN
    UPDATE test_runs
       SET n_failures = n_failures + NEW.is_failure - OLD.is_failure
     WHERE run_id = NEW.run_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_turns_ad AFTER DELETE ON telemetry_turns
BEGIN
    UPDATE test_runs
       SET n_turns = n_turns - 1,
           n_failures = n_failures - OLD.is_failure
     WHERE run_id = OLD.run_id;
END;

-- ---------------------------------------------------------------------------
-- system_events: device/engine events for post-hoc correlation
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS system_events (
    event_id    INTEGER PRIMARY KEY,
    ts_ms       INTEGER NOT NULL,
    mono_us     INTEGER NOT NULL,
    run_id      INTEGER REFERENCES test_runs (run_id) ON DELETE SET NULL,
    kind        TEXT    NOT NULL CHECK (kind IN ('SERVICE_START','SERVICE_STOP','TRIM_MEMORY','LOW_MEMORY','THERMAL_STATUS',
                                                  'ENGINE_EXIT','ENGINE_RESPAWN','BREAKER_TRIP','COOLDOWN','RECOVERY','PREFLIGHT')),
    value       REAL,
    detail_json TEXT CHECK (detail_json IS NULL OR json_valid(detail_json))
);
CREATE INDEX IF NOT EXISTS ix_sysev_run  ON system_events (run_id, ts_ms);
CREATE INDEX IF NOT EXISTS ix_sysev_kind ON system_events (kind, ts_ms);

-- ---------------------------------------------------------------------------
-- Flywheel: detected clusters, datasets, samples, lineage
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS failure_clusters (
    cluster_id         INTEGER PRIMARY KEY,
    cluster_key        TEXT    NOT NULL UNIQUE,
    model_family       TEXT,
    param_size_b       REAL,
    quant_type         TEXT,
    failure_class      TEXT    REFERENCES failure_classes (failure_class),
    ctx_depth_bucket   INTEGER,
    min_p_bucket       REAL,
    temp_bucket        REAL,
    conditions_json    TEXT CHECK (conditions_json IS NULL OR json_valid(conditions_json)),
    n_total            INTEGER NOT NULL,
    n_failures         INTEGER NOT NULL,
    fail_rate          REAL    NOT NULL,
    baseline_fail_rate REAL,
    lift               REAL,
    wilson_lower       REAL,
    detected_at_ms     INTEGER NOT NULL,
    last_seen_ms       INTEGER NOT NULL,
    status             TEXT    NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','EXPORTED','STALE'))
);
CREATE INDEX IF NOT EXISTS ix_clusters_rank ON failure_clusters (status, lift DESC, wilson_lower DESC);

CREATE TABLE IF NOT EXISTS diagnostic_datasets (
    dataset_id       INTEGER PRIMARY KEY,
    name             TEXT    NOT NULL,
    version          INTEGER NOT NULL DEFAULT 1,
    exporter_version TEXT    NOT NULL,
    format           TEXT    NOT NULL DEFAULT 'UNSLOTH_CHATML' CHECK (format IN ('UNSLOTH_CHATML','HF_MESSAGES','SHAREGPT')),
    status           TEXT    NOT NULL DEFAULT 'BUILDING' CHECK (status IN ('BUILDING','READY','EXPORTED','FAILED')),
    filter_json      TEXT CHECK (filter_json IS NULL OR json_valid(filter_json)),
    created_at_ms    INTEGER NOT NULL,
    finished_at_ms   INTEGER,
    n_clusters       INTEGER NOT NULL DEFAULT 0,
    n_samples        INTEGER NOT NULL DEFAULT 0,
    n_socratic       INTEGER NOT NULL DEFAULT 0,
    n_react          INTEGER NOT NULL DEFAULT 0,
    file_path        TEXT,
    file_sha256      TEXT,
    notes            TEXT,
    UNIQUE (name, version)
);

CREATE TABLE IF NOT EXISTS diagnostic_samples (
    sample_id      INTEGER PRIMARY KEY,
    dataset_id     INTEGER NOT NULL REFERENCES diagnostic_datasets (dataset_id) ON DELETE CASCADE,
    cluster_id     INTEGER REFERENCES failure_clusters (cluster_id) ON DELETE SET NULL,
    kind           TEXT    NOT NULL CHECK (kind IN ('SOCRATIC','REACT_REFLEXION')),
    split          TEXT    NOT NULL DEFAULT 'train' CHECK (split IN ('train','val','test')),
    messages_json  TEXT    NOT NULL CHECK (json_valid(messages_json)),
    tools_json     TEXT CHECK (tools_json IS NULL OR json_valid(tools_json)),
    token_estimate INTEGER,
    quality_score  REAL,
    content_hash   TEXT    NOT NULL,
    created_at_ms  INTEGER NOT NULL,
    UNIQUE (dataset_id, content_hash)
);
CREATE INDEX IF NOT EXISTS ix_samples_dataset ON diagnostic_samples (dataset_id, kind, split);
CREATE INDEX IF NOT EXISTS ix_samples_cluster ON diagnostic_samples (cluster_id);

-- lineage: which raw turns produced which sample (enables dedup + provenance audits)
CREATE TABLE IF NOT EXISTS sample_sources (
    sample_id INTEGER NOT NULL REFERENCES diagnostic_samples (sample_id) ON DELETE CASCADE,
    turn_id   INTEGER NOT NULL REFERENCES telemetry_turns (turn_id)      ON DELETE CASCADE,
    role      TEXT    NOT NULL DEFAULT 'EVIDENCE',
    PRIMARY KEY (sample_id, turn_id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS ix_sources_turn ON sample_sources (turn_id);
