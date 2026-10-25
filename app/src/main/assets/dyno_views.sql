-- ============================================================================
-- Kojot Dyno :: analytical views (dropped and recreated on every writable open)
-- Only judged turns (COMPLETE / RECOVERED_CRASH) feed statistics. STARTED,
-- GENERATED and UNJUDGED rows are excluded so half-finished work never skews
-- failure rates.
-- NOTE: Wilson intervals need sqrt(); SQLite builds on Android may lack math
-- functions, so confidence bounds are computed in DatasetExporter (Kotlin).
-- ============================================================================

DROP VIEW IF EXISTS v_turn_flat;
CREATE VIEW v_turn_flat AS
SELECT t.turn_id, t.run_id, t.turn_index, t.turn_state, t.case_id,
       s.name AS suite_name, s.category AS suite_category,
       m.model_family, m.param_size_b, m.quant_type, m.architecture,
       r.ctx_size, r.threads, r.batch_size, r.rope_freq_base, r.rope_freq_scale,
       r.cache_type_k, r.cache_type_v, r.flash_attn, r.mmap,
       t.endpoint, t.grammar_mode,
       t.temp, t.top_p, t.top_k, t.min_p, t.repeat_penalty, t.seed, t.n_predict,
       t.temp_bucket, t.min_p_bucket, t.ctx_depth_bucket,
       t.prompt_tokens, t.completion_tokens, t.ttft_ms, t.tok_per_sec,
       t.start_rss_mb, t.peak_rss_mb, t.mem_available_mb_min, t.lmk_headroom_mb_min,
       t.battery_temp_c, t.thermal_status, t.thermal_status_max, t.throttling_flag,
       t.is_failure, t.failure_class, t.failure_source, t.loop_period_tokens, t.loop_repeat_count,
       t.trajectory_id, t.agent_step, t.tool_name, t.fault_injected, t.wall_start_ms
  FROM telemetry_turns t
  JOIN test_runs  r ON r.run_id   = t.run_id
  JOIN test_suites s ON s.suite_id = r.suite_id
  JOIN models     m ON m.model_id = r.model_id
 WHERE t.turn_state IN ('COMPLETE','RECOVERED_CRASH');

-- quant x context depth: where does each quant fall over?
DROP VIEW IF EXISTS v_quant_ctx_failure;
CREATE VIEW v_quant_ctx_failure AS
SELECT model_family, param_size_b, quant_type, ctx_depth_bucket,
       COUNT(*)                                   AS n_turns,
       SUM(is_failure)                            AS n_fail,
       ROUND(1.0 * SUM(is_failure) / COUNT(*), 4) AS fail_rate,
       ROUND(AVG(tok_per_sec), 2)                 AS avg_tps,
       ROUND(AVG(ttft_ms), 1)                     AS avg_ttft_ms,
       ROUND(MAX(peak_rss_mb), 0)                 AS max_peak_rss_mb
  FROM v_turn_flat
 GROUP BY model_family, param_size_b, quant_type, ctx_depth_bucket;

-- sampler grid: temperature x min_p x repetition penalty
DROP VIEW IF EXISTS v_sampler_failure;
CREATE VIEW v_sampler_failure AS
SELECT model_family, param_size_b, quant_type, temp_bucket, min_p_bucket,
       ROUND(repeat_penalty, 2)                   AS repeat_penalty,
       COUNT(*)                                   AS n_turns,
       SUM(is_failure)                            AS n_fail,
       ROUND(1.0 * SUM(is_failure) / COUNT(*), 4) AS fail_rate
  FROM v_turn_flat
 GROUP BY model_family, param_size_b, quant_type, temp_bucket, min_p_bucket, ROUND(repeat_penalty, 2);

-- failure class distribution per quant x context depth
DROP VIEW IF EXISTS v_failure_class_matrix;
CREATE VIEW v_failure_class_matrix AS
SELECT model_family, param_size_b, quant_type, ctx_depth_bucket, failure_class,
       COUNT(*) AS n
  FROM v_turn_flat
 WHERE is_failure = 1
 GROUP BY model_family, param_size_b, quant_type, ctx_depth_bucket, failure_class;

-- cyclic attractor map over the sampler space
DROP VIEW IF EXISTS v_loop_attractors;
CREATE VIEW v_loop_attractors AS
SELECT model_family, param_size_b, quant_type, temp_bucket, min_p_bucket,
       ROUND(repeat_penalty, 2)                                              AS repeat_penalty,
       COUNT(*)                                                              AS n_turns,
       COALESCE(SUM(failure_class = 'CYCLIC_LOOP'), 0)                       AS n_loops,
       ROUND(1.0 * COALESCE(SUM(failure_class = 'CYCLIC_LOOP'), 0) / COUNT(*), 4) AS loop_rate,
       ROUND(AVG(CASE WHEN failure_class = 'CYCLIC_LOOP' THEN loop_period_tokens END), 1) AS avg_period_tokens
  FROM v_turn_flat
 GROUP BY model_family, param_size_b, quant_type, temp_bucket, min_p_bucket, ROUND(repeat_penalty, 2);

-- first ctx depth at which needle/constraint retrieval fails >= 50% (min 3 turns/bucket)
DROP VIEW IF EXISTS v_attention_collapse_threshold;
CREATE VIEW v_attention_collapse_threshold AS
WITH b AS (
    SELECT model_family, param_size_b, quant_type, ctx_depth_bucket,
           COUNT(*) AS n, 1.0 * SUM(is_failure) / COUNT(*) AS fr
      FROM v_turn_flat
     WHERE suite_category = 'CONTEXT_DEGRADATION' AND ctx_depth_bucket IS NOT NULL
     GROUP BY model_family, param_size_b, quant_type, ctx_depth_bucket
)
SELECT model_family, param_size_b, quant_type,
       MIN(CASE WHEN n >= 3 AND fr >= 0.5 THEN ctx_depth_bucket END) AS collapse_ctx,
       MAX(CASE WHEN n >= 3 AND fr <  0.5 THEN ctx_depth_bucket END) AS last_healthy_ctx,
       SUM(n) AS n_turns
  FROM b
 GROUP BY model_family, param_size_b, quant_type;

-- thermal confounds: throughput and failure rate per thermal state
DROP VIEW IF EXISTS v_thermal_impact;
CREATE VIEW v_thermal_impact AS
SELECT model_family, quant_type, COALESCE(thermal_status_max, 0) AS thermal_status_max,
       COUNT(*)                                   AS n_turns,
       ROUND(AVG(tok_per_sec), 2)                 AS avg_tps,
       ROUND(AVG(ttft_ms), 1)                     AS avg_ttft_ms,
       ROUND(AVG(battery_temp_c), 1)              AS avg_batt_c,
       ROUND(1.0 * SUM(is_failure) / COUNT(*), 4) AS fail_rate
  FROM v_turn_flat
 GROUP BY model_family, quant_type, COALESCE(thermal_status_max, 0);

-- memory pressure vs. launch config
DROP VIEW IF EXISTS v_memory_pressure;
CREATE VIEW v_memory_pressure AS
SELECT model_family, param_size_b, quant_type, ctx_size, cache_type_k, mmap,
       COUNT(*)                                          AS n_turns,
       ROUND(AVG(peak_rss_mb), 0)                        AS avg_peak_rss_mb,
       ROUND(MAX(peak_rss_mb), 0)                        AS max_peak_rss_mb,
       ROUND(MIN(lmk_headroom_mb_min), 0)                AS min_lmk_headroom_mb,
       COALESCE(SUM(failure_class = 'LMK_OOM'), 0)       AS n_lmk_oom,
       COALESCE(SUM(failure_class = 'SERVER_CRASH'), 0)  AS n_server_crash
  FROM v_turn_flat
 GROUP BY model_family, param_size_b, quant_type, ctx_size, cache_type_k, mmap;

-- agent degradation by injected fault and step depth
DROP VIEW IF EXISTS v_tool_degradation;
CREATE VIEW v_tool_degradation AS
SELECT model_family, param_size_b, quant_type,
       COALESCE(fault_injected, 'NONE') AS fault_injected,
       COALESCE(agent_step, 0)          AS agent_step,
       COUNT(*)                                   AS n_turns,
       SUM(is_failure)                            AS n_fail,
       ROUND(1.0 * SUM(is_failure) / COUNT(*), 4) AS fail_rate,
       COALESCE(SUM(failure_class = 'TOOL_ARG_DRIFT'), 0)        AS n_arg_drift,
       COALESCE(SUM(failure_class = 'TOOL_CALL_MALFORMED'), 0)   AS n_malformed,
       COALESCE(SUM(failure_class = 'TOOL_ERROR_MISHANDLED'), 0) AS n_err_mishandled,
       COALESCE(SUM(failure_class = 'TOOL_LOOP'), 0)             AS n_tool_loop
  FROM v_turn_flat
 WHERE suite_category = 'AGENTIC_TOOL'
 GROUP BY model_family, param_size_b, quant_type, COALESCE(fault_injected, 'NONE'), COALESCE(agent_step, 0);

-- cluster candidates: cells whose failure rate sits well above the model+quant baseline.
-- The exporter applies n / lift / Wilson thresholds and persists winners to failure_clusters.
DROP VIEW IF EXISTS v_cluster_candidates;
CREATE VIEW v_cluster_candidates AS
WITH base AS (
    SELECT model_family, param_size_b, quant_type,
           COUNT(*) AS n, SUM(is_failure) AS f
      FROM v_turn_flat
     GROUP BY model_family, param_size_b, quant_type
),
cell AS (
    SELECT model_family, param_size_b, quant_type, ctx_depth_bucket, min_p_bucket, temp_bucket,
           COUNT(*) AS n, SUM(is_failure) AS f
      FROM v_turn_flat
     GROUP BY model_family, param_size_b, quant_type, ctx_depth_bucket, min_p_bucket, temp_bucket
)
SELECT c.model_family, c.param_size_b, c.quant_type, c.ctx_depth_bucket, c.min_p_bucket, c.temp_bucket,
       c.n AS n_total, c.f AS n_failures,
       ROUND(1.0 * c.f / c.n, 4)                                 AS fail_rate,
       ROUND(1.0 * b.f / b.n, 4)                                 AS baseline_fail_rate,
       ROUND((1.0 * c.f / c.n) / NULLIF(1.0 * b.f / b.n, 0), 3)  AS lift,
       (SELECT x.failure_class
          FROM v_turn_flat x
         WHERE x.model_family IS c.model_family AND x.param_size_b IS c.param_size_b
           AND x.quant_type IS c.quant_type AND x.ctx_depth_bucket IS c.ctx_depth_bucket
           AND x.min_p_bucket IS c.min_p_bucket AND x.temp_bucket IS c.temp_bucket
           AND x.is_failure = 1
         GROUP BY x.failure_class ORDER BY COUNT(*) DESC LIMIT 1) AS dominant_failure_class
  FROM cell c
  JOIN base b ON b.model_family IS c.model_family AND b.param_size_b IS c.param_size_b AND b.quant_type IS c.quant_type
 WHERE c.f > 0;

-- run roll-up for the dashboard
DROP VIEW IF EXISTS v_run_summary;
CREATE VIEW v_run_summary AS
SELECT r.run_id, r.status, r.abort_reason, s.name AS suite_name,
       m.model_family, m.param_size_b, m.quant_type,
       r.ctx_size, r.cache_type_k, r.preflight_verdict,
       r.n_turns, r.n_failures,
       ROUND(1.0 * r.n_failures / NULLIF(r.n_turns, 0), 4) AS fail_rate,
       r.server_restarts, r.load_ms, r.started_at_ms, r.ended_at_ms,
       ROUND((COALESCE(r.ended_at_ms, CAST(strftime('%s','now') AS INTEGER) * 1000) - r.started_at_ms) / 1000.0, 1) AS duration_s
  FROM test_runs r
  JOIN test_suites s ON s.suite_id = r.suite_id
  JOIN models m ON m.model_id = r.model_id;

-- failures not yet consumed by any dataset sample (exporter work queue)
DROP VIEW IF EXISTS v_exporter_ready_failures;
CREATE VIEW v_exporter_ready_failures AS
SELECT f.*
  FROM v_turn_flat f
 WHERE f.is_failure = 1
   AND NOT EXISTS (SELECT 1 FROM sample_sources ss WHERE ss.turn_id = f.turn_id);
