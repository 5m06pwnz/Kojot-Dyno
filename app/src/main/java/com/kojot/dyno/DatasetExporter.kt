package com.kojot.dyno

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Flywheel exporter: aggregates failure clusters from dyno.db and emits Unsloth / HF-compatible
 * chat-template JSONL for an agentic systems-debugging SFT set.
 *
 * Each sample is a multi-turn Socratic trace:
 *   User   — vague symptom
 *   Asst   — clarifying questions (runtime / hardware)
 *   User   — supplies telemetry
 *   Asst   — <think> root-cause reasoning </think> + concrete patch
 *
 * Optional ReAct trajectories when trajectory_id groups exist.
 */
class DatasetExporter(private val ctx: Context, private val dao: DynoDao, private val helper: DynoDb) {

    data class ExportResult(val path: String, val samples: Int, val clusters: Int)

    suspend fun exportSocraticJsonl(
        outFile: File = File(ctx.getExternalFilesDir(null), "dyno_sft_${System.currentTimeMillis()}.jsonl"),
        minClusterSize: Int = 3,
    ): ExportResult = withContext(Dispatchers.IO) {
        val clusters = loadClusters(minClusterSize)
        var n = 0
        FileOutputStream(outFile).bufferedWriter().use { w ->
            for (c in clusters) {
                val sample = buildSocratic(c)
                w.appendLine(sample.toString())
                n++
                // Also emit a ReAct-style trajectory when enough linked turns exist
                val react = buildReact(c)
                if (react != null) {
                    w.appendLine(react.toString())
                    n++
                }
            }
        }
        ExportResult(outFile.absolutePath, n, clusters.size)
    }

    // ---- cluster detection ---------------------------------------------------------------------------

    data class Cluster(
        val failureClass: String,
        val quantType: String,
        val ctxSize: Int,
        val minP: Double?,
        val temp: Double?,
        val count: Int,
        val examples: List<TurnEx>,
    )

    data class TurnEx(
        val turnId: Long,
        val rawPrompt: String,
        val rawOutput: String,
        val message: String?,
        val modelFamily: String,
        val quantType: String,
        val ctxSize: Int,
        val temp: Double?,
        val minP: Double?,
        val topP: Double?,
        val peakRssMb: Double?,
        val lmkHeadroomMin: Double?,
        val ttftMs: Double?,
        val tokPerSec: Double?,
        val caseId: String,
        val trajectoryId: String?,
    )

    private fun loadClusters(minSize: Int): List<Cluster> {
        val sql = """
            SELECT t.failure_class,
                   m.quant_type,
                   r.ctx_size,
                   COUNT(*) AS n
            FROM telemetry_turns t
            JOIN test_runs r ON r.run_id = t.run_id
            JOIN models m ON m.model_id = r.model_id
            WHERE t.is_failure = 1
              AND t.turn_state IN ('COMPLETE', 'RECOVERED_CRASH')
              AND t.failure_class IS NOT NULL
            GROUP BY t.failure_class, m.quant_type, r.ctx_size
            HAVING n >= ?
            ORDER BY n DESC
            LIMIT 50
        """.trimIndent()
        val clusters = ArrayList<Cluster>()
        helper.readableDatabase.rawQuery(sql, arrayOf(minSize.toString())).use { c ->
            while (c.moveToNext()) {
                val fc = c.getString(0)
                val quant = c.getString(1)
                val ctx = c.getInt(2)
                val n = c.getInt(3)
                val examples = loadExamples(fc, quant, ctx, limit = 5)
                // Derive sampler stats from case_params / request if present
                val minP = examples.mapNotNull { it.minP }.average().takeIf { !it.isNaN() }
                val temp = examples.mapNotNull { it.temp }.average().takeIf { !it.isNaN() }
                clusters += Cluster(fc, quant, ctx, minP, temp, n, examples)
            }
        }
        return clusters
    }

    private fun loadExamples(fc: String, quant: String, ctx: Int, limit: Int): List<TurnEx> {
        val sql = """
            SELECT t.turn_id, t.raw_prompt, t.raw_output, t.parsed_error_message,
                   m.model_family, m.quant_type, r.ctx_size,
                   t.peak_rss_mb, t.lmk_headroom_mb_min, t.ttft_ms, t.tok_per_sec,
                   t.case_id, t.trajectory_id, t.case_params_json, t.request_json
            FROM telemetry_turns t
            JOIN test_runs r ON r.run_id = t.run_id
            JOIN models m ON m.model_id = r.model_id
            WHERE t.failure_class = ? AND m.quant_type = ? AND r.ctx_size = ?
              AND t.is_failure = 1
            ORDER BY t.turn_id DESC
            LIMIT ?
        """.trimIndent()
        val out = ArrayList<TurnEx>()
        helper.readableDatabase.rawQuery(sql, arrayOf(fc, quant, ctx.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                val params = c.getString(13)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val req = c.getString(14)?.let { runCatching { JSONObject(it) }.getOrNull() }
                out += TurnEx(
                    turnId = c.getLong(0),
                    rawPrompt = c.getString(1) ?: "",
                    rawOutput = c.getString(2) ?: "",
                    message = c.getString(3),
                    modelFamily = c.getString(4) ?: "?",
                    quantType = c.getString(5) ?: "?",
                    ctxSize = c.getInt(6),
                    temp = req?.optDouble("temperature")?.takeIf { !it.isNaN() }
                        ?: params?.optDouble("temp")?.takeIf { !it.isNaN() },
                    minP = req?.optDouble("min_p")?.takeIf { !it.isNaN() }
                        ?: params?.optDouble("min_p")?.takeIf { !it.isNaN() },
                    topP = req?.optDouble("top_p")?.takeIf { !it.isNaN() },
                    peakRssMb = if (c.isNull(7)) null else c.getDouble(7),
                    lmkHeadroomMin = if (c.isNull(8)) null else c.getDouble(8),
                    ttftMs = if (c.isNull(9)) null else c.getDouble(9),
                    tokPerSec = if (c.isNull(10)) null else c.getDouble(10),
                    caseId = c.getString(11) ?: "",
                    trajectoryId = c.getString(12),
                )
            }
        }
        return out
    }

    // ---- Socratic construction -----------------------------------------------------------------------

    private fun buildSocratic(c: Cluster): JSONObject {
        val ex = c.examples.first()
        val symptom = symptomFor(c.failureClass, ex)
        val questions = clarifyingQuestions(c.failureClass)
        val telemetry = telemetryReply(ex, c)
        val think = thinkBlock(c, ex)
        val patch = patchAdvice(c, ex)

        val messages = JSONArray()
            .put(msg("user", symptom))
            .put(msg("assistant", questions))
            .put(msg("user", telemetry))
            .put(msg("assistant", "<think>\n$think\n</think>\n\n$patch"))

        return JSONObject()
            .put("messages", messages)
            .put("meta", JSONObject()
                .put("failure_class", c.failureClass)
                .put("quant_type", c.quantType)
                .put("ctx_size", c.ctxSize)
                .put("cluster_size", c.count)
                .put("source_turn_id", ex.turnId)
                .put("format", "socratic_v1"))
    }

    private fun buildReact(c: Cluster): JSONObject? {
        val withTraj = c.examples.filter { !it.trajectoryId.isNullOrBlank() }
        if (withTraj.isEmpty()) return null
        val ex = withTraj.first()
        val messages = JSONArray()
            .put(msg("user", "Debug this recurring ${c.failureClass} on ${ex.modelFamily} ${c.quantType} @ ctx=${c.ctxSize}."))
            .put(msg("assistant",
                "I'll inspect the telemetry cluster, write a minimal repro, and propose a fix.\n" +
                    "tool call query_dyno with sql=\"SELECT failure_class, COUNT(*) n FROM telemetry_turns WHERE is_failure=1 GROUP BY 1 ORDER BY n DESC LIMIT 10\""))
            .put(msg("user", "tool result query_dyno → ${c.failureClass}: ${c.count} hits (dominant)"))
            .put(msg("assistant",
                "<think>\n${thinkBlock(c, ex)}\n</think>\n\n" +
                    "Minimal repro:\n```\n${reproSnippet(c, ex)}\n```\n\n${patchAdvice(c, ex)}"))
        return JSONObject()
            .put("messages", messages)
            .put("meta", JSONObject()
                .put("failure_class", c.failureClass)
                .put("format", "react_v1")
                .put("trajectory_id", ex.trajectoryId))
    }

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    private fun symptomFor(fc: String, ex: TurnEx): String = when (fc) {
        "CYCLIC_LOOP" -> "My tool calls keep repeating the same arguments / the model loops the same paragraph."
        "SCHEMA_SYNTAX_ERROR" -> "The model keeps emitting invalid JSON — unterminated strings or trailing garbage."
        "SCHEMA_VIOLATION" -> "JSON parses but required keys are missing or types are wrong."
        "CONTEXT_ATTENTION_COLLAPSE" -> "Needle-in-haystack fails past a few thousand tokens; the model forgets constraints."
        "LMK_OOM" -> "The app dies mid-generation with no stack trace. Looks like the system killed us."
        "SERVER_CRASH" -> "llama-server exits abruptly (segfault / assert) during inference."
        "SERVER_TIMEOUT" -> "Generation stalls: either no first token or tokens stop arriving."
        "TOOL_ARG_DRIFT", "TOOL_LOOP", "TOOL_CALL_MALFORMED" ->
            "Agentic tool use is drifting — wrong args, malformed calls, or tool loops."
        else -> "I'm seeing repeated ${fc} failures on ${ex.modelFamily} during local inference."
    }

    private fun clarifyingQuestions(fc: String): String = buildString {
        appendLine("I need a few runtime details before diagnosing:")
        appendLine("1. Model family, parameter size, and quantization (e.g. Q4_K_S vs Q8_0)?")
        appendLine("2. Context length (`n_ctx`), cache type K/V, mmap/mlock?")
        appendLine("3. Sampler: temperature, min_p, top_p, repetition penalty?")
        when (fc) {
            "LMK_OOM", "SERVER_CRASH" ->
                appendLine("4. Peak RSS / LMK headroom just before the death, and whether the weights were mmap'd?")
            "CYCLIC_LOOP" ->
                appendLine("4. Approximate loop period (tokens) and whether grammar/schema was enabled?")
            "CONTEXT_ATTENTION_COLLAPSE" ->
                appendLine("4. At which context depth did retrieval fail, and was RoPE scaled?")
            else ->
                appendLine("4. Any grammar, tool schema, or fault-injection active?")
        }
        append("5. Paste a short excerpt of the failing prompt/output if available.")
    }

    private fun telemetryReply(ex: TurnEx, c: Cluster): String = buildString {
        appendLine("Model: ${ex.modelFamily} · quant=${ex.quantType} · ctx=${ex.ctxSize}")
        appendLine("Sampler: temp=${ex.temp ?: "?"} min_p=${ex.minP ?: "?"} top_p=${ex.topP ?: "?"}")
        appendLine("Vitals: peak_rss=${ex.peakRssMb ?: "?"} MB · lmk_headroom_min=${ex.lmkHeadroomMin ?: "?"} MB")
        appendLine("Perf: ttft=${ex.ttftMs ?: "?"} ms · tps=${ex.tokPerSec ?: "?"}")
        appendLine("Case: ${ex.caseId} · cluster_size=${c.count}")
        if (!ex.message.isNullOrBlank()) appendLine("Engine message: ${ex.message}")
        appendLine("--- prompt (truncated) ---")
        appendLine(ex.rawPrompt.take(1200))
        appendLine("--- output (truncated) ---")
        append(ex.rawOutput.take(1200))
    }

    private fun thinkBlock(c: Cluster, ex: TurnEx): String = buildString {
        appendLine("Failure class: ${c.failureClass} (${c.count} correlated rows).")
        appendLine("Axes: quant=${c.quantType}, ctx=${c.ctxSize}, min_p≈${c.minP}, temp≈${c.temp}.")
        when (c.failureClass) {
            "CYCLIC_LOOP" -> append(
                "Small quant models with low min_p put non-negligible mass on already-emitted n-grams. " +
                    "Without a repetition penalty or higher min_p, the sampler falls into a periodic attractor. " +
                    "Circuit breaker trips on period detection; the fix is sampler-side, not architecture."
            )
            "SCHEMA_SYNTAX_ERROR" -> append(
                "Q4/Q5 quant noise + unconstrained decoding → broken escapes / unclosed braces. " +
                    "Grammar (GBNF) or JSON-mode sampling collapses the path to valid terminals."
            )
            "CONTEXT_ATTENTION_COLLAPSE" -> append(
                "At ctx≥${c.ctxSize} with this quant, attention sinks and RoPE scaling dilute the needle. " +
                    "Retrieval failure is expected without position interpolation tuned to the training ctx."
            )
            "LMK_OOM" -> append(
                "Anonymous memory (KV + scratch + optional mlock weights) exceeded LMK headroom. " +
                    "mmap keeps weights file-backed (evictable); mlock or no-mmap makes them anonymous and lethal. " +
                    "Pre-flight anonNeedMb vs budget should have refused this launch under ENFORCE."
            )
            "SERVER_CRASH" -> append(
                "Non-LMK death: GGML assert, bad alloc path that didn't return, or signal not correlated with low headroom. " +
                    "Check server log tail for ggml_assert / empty grammar stack."
            )
            else -> append("Correlate quant × ctx × sampler against the failure taxonomy; prefer the cheapest lever first (sampler → grammar → ctx → quant).")
        }
    }

    private fun patchAdvice(c: Cluster, ex: TurnEx): String = when (c.failureClass) {
        "CYCLIC_LOOP" ->
            "Raise `min_p` to ≥0.05–0.1, add `repeat_penalty` 1.08–1.15, or enable the circuit-breaker loop detector " +
                "and treat LOOP trips as hard failures in the suite."
        "SCHEMA_SYNTAX_ERROR", "SCHEMA_VIOLATION" ->
            "Attach a GBNF/JSON schema grammar to the request (`grammar` field) or switch to a higher quant (Q5_K_M / Q8_0) " +
                "for structured outputs. Keep `temperature` ≤0.7 for schema-critical turns."
        "CONTEXT_ATTENTION_COLLAPSE" ->
            "Reduce working ctx, enable YaRN/linear RoPE scale to match train length, or move the needle closer to the end. " +
                "For eval, record the exact depth of first failure per quant."
        "LMK_OOM" ->
            "Keep `mmap=true`, `mlock=false`, lower `n_ctx` or switch cache types to `q8_0`/`q4_0`, and leave " +
                "`preflight=ENFORCE` so launches that exceed anon budget are refused before spawn."
        "SERVER_TIMEOUT" ->
            "Tighten TTFT/stall deadlines only after ruling out thermal throttling; if `cpuFreqCapRatio` is low, " +
                "insert a cooldown gate (battery °C / thermal status) between turns."
        else ->
            "Log the cluster keys (quant, ctx, sampler) and add a targeted fuzz case that reproduces with minimal tokens."
    }

    private fun reproSnippet(c: Cluster, ex: TurnEx): String = """
        // minimal Dyno turn
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject()
                .put("role","user").put("content", ${JSONObject.quote(ex.rawPrompt.take(200))})))
            .put("temperature", ${ex.temp ?: 0.7})
            .put("min_p", ${ex.minP ?: 0.05})
            .put("max_tokens", 256)
        // expect failure_class=${c.failureClass} on ${c.quantType} @ ctx=${c.ctxSize}
    """.trimIndent()
}
