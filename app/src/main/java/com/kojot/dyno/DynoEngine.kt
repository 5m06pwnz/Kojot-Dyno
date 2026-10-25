package com.kojot.dyno

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Orchestrator: owns llama-server process, vitals windows, circuit breakers, and the single-writer
 * [DynoDao]. One instance lives inside [DynoService] for the lifetime of the foreground service.
 *
 * Turn lifecycle is deliberate:
 *   1. Insert STARTED row (durable) before the HTTP request leaves the device.
 *   2. Stream tokens; breaker may abort.
 *   3. Write GENERATED metrics.
 *   4. JS validator (or autoComplete) → COMPLETE via [applyVerdict].
 * If the process dies between 1 and 4, the next boot's [recover] reclassifies the row.
 */
class DynoEngine(
    private val ctx: Context,
    private val scope: CoroutineScope,
) {
    private val helper = DynoDb(ctx)
    private val writer = DbWriter(helper, scope)
    val dao = DynoDao(helper, writer)

    private val process = LlamaServerProcess(ctx, scope)
    private val client = InferenceClient()
    private val sampler = VitalsSampler(ctx)

    private val runMutex = Mutex()
    @Volatile private var activeRunId: Long? = null
    @Volatile private var activeCfg: LaunchConfig? = null
    @Volatile private var turnIndex = 0
    @Volatile private var policy = EnginePolicy()
    private var vitalsWindow: VitalsWindow? = null
    private var exitWatchJob: Job? = null

    private val _events = MutableSharedFlow<DynoEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DynoEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            process.logs.collect { ev ->
                _events.tryEmit(DynoEvent.EngineLog(ev.line, ev.signal?.name))
            }
        }
        scope.launch {
            process.state.collect { st ->
                _events.tryEmit(DynoEvent.EngineState(st.name, process.pid))
            }
        }
    }

    // ---- boot recovery -------------------------------------------------------------------------------

    suspend fun recover(): RecoverySummary = withContext(Dispatchers.IO) {
        val orphans = process.reapOrphans(exceptPid = null).size
        val summary = dao.recoverAfterCrash(orphans)
        if (summary.any) _events.tryEmit(DynoEvent.Recovery(summary))
        summary
    }

    // ---- server lifecycle ----------------------------------------------------------------------------

    suspend fun startRun(cfg: LaunchConfig, suiteName: String? = null): RunStart = runMutex.withLock {
        if (process.state.value == LlamaServerProcess.State.READY ||
            process.state.value == LlamaServerProcess.State.STARTING
        ) {
            return RunStart.Failed(null, "ALREADY_RUNNING", "Stop the current run first")
        }
        closeVitalsWindow()
        val snap = sampler.snapshot(null)
        val meta = runCatching { GgufMeta.read(File(cfg.modelPath)) }.getOrNull()
            ?: return RunStart.Failed(null, "GGUF_UNREADABLE", cfg.modelPath)
        val pf = Preflight.assess(meta, cfg, snap)
        if (cfg.preflight == PreflightMode.ENFORCE && pf.verdict == Preflight.Verdict.REFUSE) {
            return RunStart.Failed(null, "PREFLIGHT_REFUSE", pf.note)
        }

        val modelId = dao.upsertModel(meta)
        val suiteId = dao.ensureSuite(suiteName ?: "adhoc", "CUSTOM")
        val runUuid = UUID.randomUUID().toString()
        val runId = dao.insertRun(
            cvOf(
                "run_uuid" to runUuid,
                "suite_id" to suiteId,
                "model_id" to modelId,
                "status" to "STARTING",
                "started_at_ms" to System.currentTimeMillis(),
                "ctx_size" to cfg.ctxSize,
                "threads" to cfg.threads,
                "threads_batch" to cfg.threadsBatch,
                "batch_size" to cfg.batchSize,
                "ubatch_size" to cfg.ubatchSize,
                "rope_freq_base" to cfg.ropeFreqBase,
                "rope_freq_scale" to cfg.ropeFreqScale,
                "flash_attn" to cfg.flashAttn,
                "cache_type_k" to cfg.cacheTypeK,
                "cache_type_v" to cfg.cacheTypeV,
                "n_gpu_layers" to cfg.nGpuLayers,
                "mlock" to if (cfg.mlock) 1 else 0,
                "mmap" to if (cfg.mmap) 1 else 0,
                "port" to cfg.port,
                "server_args_json" to cfg.toJson().toString(),
                "preflight_verdict" to pf.verdict.name,
                "predicted_resident_mb" to pf.residentNeedMb,
                "predicted_anon_mb" to pf.anonNeedMb,
                "predicted_budget_mb" to pf.budgetMb,
                "device_model" to Build.MODEL,
                "soc" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE),
                "android_sdk" to Build.VERSION.SDK_INT,
                // Device physical RAM (not JVM heap maxMemory, which is only the ART heap ceiling)
                "total_ram_mb" to deviceTotalRamMb(),
            )
        )
        activeRunId = runId
        activeCfg = cfg
        turnIndex = 0

        val start = process.start(cfg)
        when (start) {
            is LlamaServerProcess.StartResult.Ready -> {
                dao.updateRunSync(
                    runId,
                    cvOf(
                        "status" to "RUNNING",
                        "server_pid" to start.pid,
                        "load_ms" to start.loadMs,
                        "llama_build" to start.buildInfo,
                    )
                )
                openVitalsWindow(runId)
                watchExits(runId)
                _events.tryEmit(DynoEvent.RunStatus(runId, "READY", null))
                RunStart.Started(runId, modelId, start.pid, start.loadMs, start.buildInfo, pf)
            }
            is LlamaServerProcess.StartResult.Failed -> {
                val fc = classifyStartFailure(start)
                dao.updateRunSync(
                    runId,
                    cvOf(
                        "status" to "FAILED_TO_START",
                        "abort_reason" to "${start.reason}: ${start.detail ?: ""}".take(500),
                        "ended_at_ms" to System.currentTimeMillis(),
                    )
                )
                dao.logSystemEvent("START_FAILED", runId, null,
                    JSONObject().put("reason", start.reason).put("detail", start.detail)
                        .put("failure_class", fc).put("log_tail", start.logTail.take(2000)).toString())
                activeRunId = null
                activeCfg = null
                _events.tryEmit(DynoEvent.RunStatus(runId, "FAILED", start.reason))
                RunStart.Failed(runId, start.reason, start.detail)
            }
        }
    }

    suspend fun stopRun(reason: String = "USER_STOP") = runMutex.withLock {
        val runId = activeRunId
        closeVitalsWindow()
        exitWatchJob?.cancel()
        process.stop()
        if (runId != null) {
            dao.updateRunSync(
                runId,
                cvOf(
                    "status" to "ABORTED",
                    "abort_reason" to reason,
                    "ended_at_ms" to System.currentTimeMillis(),
                )
            )
            _events.tryEmit(DynoEvent.RunStatus(runId, "STOPPED", reason))
        }
        activeRunId = null
        activeCfg = null
    }

    fun setPolicy(p: EnginePolicy) {
        policy = p
    }

    fun killNow() {
        process.killNow()
    }

    // ---- turns ---------------------------------------------------------------------------------------

    suspend fun runTurn(spec: TurnSpec): TurnOutcome = withContext(Dispatchers.IO) {
        val runId = activeRunId ?: error("No active run")
        val cfg = activeCfg ?: error("No active config")
        if (process.state.value != LlamaServerProcess.State.READY) error("Server not READY")

        maybeCooldown()

        val idx = synchronized(this@DynoEngine) { turnIndex++ }
        val body = JSONObject(spec.body.toString())
        body.put("stream", true)

        val tStartUs = SystemClock.elapsedRealtimeNanos() / 1000
        val turnId = dao.insertTurn(
            cvOf(
                "run_id" to runId,
                "turn_index" to idx,
                "turn_state" to "STARTED",
                "case_id" to spec.caseId,
                "case_params_json" to spec.caseParams?.toString(),
                "trajectory_id" to spec.trajectoryId,
                "parent_turn_id" to spec.parentTurnId,
                "agent_step" to spec.agentStep,
                "tool_name" to spec.toolName,
                "fault_injected" to spec.faultInjected,
                "endpoint" to spec.endpoint,
                "grammar_mode" to spec.grammarMode,
                "raw_prompt" to (spec.rawPrompt ?: extractPrompt(body)),
                "request_params_json" to body.toString(),
                "wall_start_ms" to System.currentTimeMillis(),
                "t_start_us" to tStartUs,
                "temp" to body.optDouble("temperature").takeIf { !it.isNaN() },
                "top_p" to body.optDouble("top_p").takeIf { !it.isNaN() },
                "min_p" to body.optDouble("min_p").takeIf { !it.isNaN() },
                "n_predict" to body.optInt("max_tokens").takeIf { it > 0 },
            )
        )
        _events.tryEmit(DynoEvent.TurnStarted(turnId, idx, spec.caseId))

        val turnWindow = sampler.window(
            pidProvider = { process.pid },
            periodMs = policy.vitalsPeriodMs,
            scope = scope,
            onSample = { v ->
                val headroom = policy.preemptiveKillHeadroomMb
                if (headroom > 0f && v.lmkHeadroomMb < headroom && process.isAlive()) {
                    Log.w(TAG, "Pre-emptive kill: headroom=${v.lmkHeadroomMb} < $headroom")
                    _events.tryEmit(DynoEvent.Notice("warn", "Pre-emptive kill (LMK headroom ${v.lmkHeadroomMb.toInt()} MB)"))
                    scope.launch { process.stop() }
                }
            },
        )

        val call = InferenceCall()
        val breakerCfg = BreakerConfig(
            ttftDeadlineMs = spec.ttftDeadlineMs ?: 60_000L,
            stallMs = spec.stallMs,
            wallDeadlineMs = spec.wallDeadlineMs,
            maxChunks = 8_192,
            loopDetection = spec.loopDetection,
        )
        val breaker = CircuitBreaker(breakerCfg) { trip ->
            call.abort()
            _events.tryEmit(DynoEvent.BreakerTrip(turnId, trip.kind.name, trip.detail))
        }
        val tickJob = scope.launch {
            while (isActive && breaker.trip == null) {
                delay(250)
                breaker.tick()
            }
        }

        val outcome = try {
            val stream = client.stream(
                port = cfg.port,
                path = spec.endpoint,
                body = body,
                call = call,
                breaker = breaker,
                onText = { t ->
                    _events.tryEmit(DynoEvent.Token(turnId, t))
                    breaker.onChunk(t)
                },
            )
            tickJob.cancel()
            val agg = turnWindow.stop()
            val trip = breaker.trip
            val classified = classifyStream(stream, trip)
            val ttftMs = if (stream.firstTokenNs > 0) (stream.firstTokenNs - stream.startNs) / 1e6 else null
            val tps = computeTps(stream)
            val finalState = if (spec.autoComplete || classified.isFailure) "COMPLETE" else "GENERATED"
            val endUs = SystemClock.elapsedRealtimeNanos() / 1000
            val cv = cvOf(
                "turn_state" to finalState,
                "raw_output" to stream.text,
                "prompt_tokens" to stream.usage?.optInt("prompt_tokens"),
                "completion_tokens" to stream.usage?.optInt("completion_tokens"),
                "ttft_us" to ttftMs?.let { (it * 1000).toLong() },
                "duration_us" to (endUs - tStartUs),
                "tok_per_sec" to tps,
                "finish_reason" to stream.finishReason,
                "http_status" to stream.httpStatus,
                "stream_chunks" to stream.chunks,
                "is_failure" to if (classified.isFailure) 1 else 0,
                "failure_class" to classified.failureClass,
                "failure_source" to if (classified.isFailure) "ENGINE" else null,
                "parsed_error_message" to classified.message,
                "loop_period_tokens" to breaker.loopPeriod,
                "loop_repeat_count" to breaker.loopRepeats,
            )
            cv.putAll(agg.toContentValues())
            dao.finalizeTurn(turnId, cv)
            TurnOutcome(
                turnId = turnId,
                turnIndex = idx,
                state = finalState,
                isFailure = classified.isFailure,
                failureClass = classified.failureClass,
                message = classified.message,
                output = stream.text,
                promptTokens = stream.usage?.optInt("prompt_tokens"),
                completionTokens = stream.usage?.optInt("completion_tokens"),
                ttftMs = ttftMs,
                tokPerSec = tps,
                finishReason = stream.finishReason,
            )
        } catch (e: CancellationException) {
            tickJob.cancel()
            turnWindow.stop()
            throw e
        } catch (e: Exception) {
            tickJob.cancel()
            val agg = turnWindow.stop()
            val msg = e.message ?: e.javaClass.simpleName
            val endUs = SystemClock.elapsedRealtimeNanos() / 1000
            val cv = cvOf(
                "turn_state" to "COMPLETE",
                "raw_output" to "",
                "is_failure" to 1,
                "failure_class" to "SERVER_TIMEOUT",
                "failure_source" to "ENGINE",
                "parsed_error_message" to msg,
                "duration_us" to (endUs - tStartUs),
            )
            cv.putAll(agg.toContentValues())
            dao.finalizeTurn(turnId, cv)
            TurnOutcome(turnId, idx, "COMPLETE", true, "SERVER_TIMEOUT", msg, "", null, null, null, null, null)
        }
        _events.tryEmit(DynoEvent.TurnFinished(outcome))
        outcome
    }

    /** JS validator path: locks a GENERATED turn into COMPLETE. */
    suspend fun applyVerdict(
        turnId: Long,
        isFailure: Boolean,
        failureClass: String?,
        message: String?,
        detailJson: String? = null,
    ): Boolean = dao.applyVerdict(turnId, isFailure, failureClass, message, detailJson)

    // ---- queries for the bridge ----------------------------------------------------------------------

    suspend fun listModels(dir: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val root = File(dir)
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()
        val files = ArrayList<File>()
        // Direct children
        root.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true)) files += f
            // One level of subdirs (e.g. Download/Local_Models/qwen/*.gguf)
            if (f.isDirectory) {
                f.listFiles()?.forEach { c ->
                    if (c.isFile && c.name.endsWith(".gguf", ignoreCase = true)) files += c
                }
            }
        }
        files.sortedBy { it.name.lowercase() }.map { f ->
            val info = runCatching { GgufMeta.read(f) }.getOrNull()
            JSONObject().apply {
                put("path", f.absolutePath)
                put("name", f.name)
                put("sizeBytes", f.length())
                put("mtimeMs", f.lastModified())
                if (info != null) {
                    put("architecture", info.architecture)
                    put("family", info.modelFamily)
                    put("paramSizeB", info.paramSizeB)
                    put("quant", info.quantType)
                    put("nCtxTrain", info.ctxTrain)
                    put("nLayer", info.nLayer)
                    put("nEmbd", info.nEmbd)
                }
            }
        }
    }

    suspend fun query(sql: String, args: Array<String>? = null): List<JSONObject> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(sql, args).use { c ->
                val cols = c.columnNames
                val out = ArrayList<JSONObject>(c.count)
                while (c.moveToNext()) {
                    val j = JSONObject()
                    for (i in cols.indices) {
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> j.put(cols[i], JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> j.put(cols[i], c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> j.put(cols[i], c.getDouble(i))
                            else -> j.put(cols[i], c.getString(i))
                        }
                    }
                    out += j
                }
                out
            }
        }

    // ---- internals -----------------------------------------------------------------------------------

    private fun openVitalsWindow(runId: Long) {
        closeVitalsWindow()
        vitalsWindow = sampler.window(
            pidProvider = { process.pid },
            periodMs = policy.heartbeatMs,
            scope = scope,
            onSample = { v -> _events.tryEmit(DynoEvent.VitalsTick(v)) },
        )
    }

    private fun closeVitalsWindow() {
        vitalsWindow?.stop()
        vitalsWindow = null
    }

    private fun watchExits(runId: Long) {
        exitWatchJob?.cancel()
        exitWatchJob = scope.launch {
            process.exits.collect { exit ->
                if (exit.expected) return@collect
                closeVitalsWindow()
                val signals = process.drainSignals()
                val fc = when {
                    LlamaServerProcess.LogSignal.ALLOC_FAIL in signals -> "LMK_OOM"
                    LlamaServerProcess.LogSignal.GGML_ASSERT in signals ||
                        LlamaServerProcess.LogSignal.ABORT in signals -> "SERVER_CRASH"
                    else -> "SERVER_CRASH"
                }
                dao.updateRunSync(
                    runId,
                    cvOf(
                        "status" to "CRASHED",
                        "abort_reason" to fc,
                        "ended_at_ms" to exit.atWallMs,
                    )
                )
                dao.logSystemEvent(
                    "SERVER_EXIT",
                    runId,
                    null,
                    JSONObject()
                        .put("exit_code", exit.exitCode)
                        .put("signal", exit.signal)
                        .put("uptime_ms", exit.uptimeMs)
                        .put("failure_class", fc)
                        .put("log_tail", process.logTail(60))
                        .toString(),
                )
                _events.tryEmit(DynoEvent.RunStatus(runId, "CRASHED", fc))
                activeRunId = null
                activeCfg = null
            }
        }
    }

    private suspend fun maybeCooldown() {
        val p = policy
        val v = sampler.snapshot(process.pid)
        val hot = (v.batteryTempC != null && v.batteryTempC >= p.cooldownBatteryC) ||
            v.thermalStatus > p.cooldownMaxThermal
        if (!hot) return
        val start = SystemClock.elapsedRealtime()
        _events.tryEmit(DynoEvent.Cooldown(v.batteryTempC, v.thermalStatus, 0, false))
        while (SystemClock.elapsedRealtime() - start < p.cooldownTimeoutMs) {
            delay(5_000)
            val now = sampler.snapshot(process.pid)
            val still = (now.batteryTempC != null && now.batteryTempC >= p.cooldownBatteryC) ||
                now.thermalStatus > p.cooldownMaxThermal
            val waited = SystemClock.elapsedRealtime() - start
            if (!still) {
                _events.tryEmit(DynoEvent.Cooldown(now.batteryTempC, now.thermalStatus, waited, true))
                return
            }
            _events.tryEmit(DynoEvent.Cooldown(now.batteryTempC, now.thermalStatus, waited, false))
        }
        _events.tryEmit(DynoEvent.Notice("warn", "Cooldown timeout; continuing under thermal pressure"))
    }

    private data class Classified(val isFailure: Boolean, val failureClass: String?, val message: String?)

    private fun classifyStream(s: StreamResult, trip: Trip?): Classified {
        if (trip != null) {
            val fc = when (trip.kind) {
                TripKind.LOOP -> "CYCLIC_LOOP"
                TripKind.RUNAWAY -> "RUNAWAY_GENERATION"
                TripKind.TTFT_DEADLINE, TripKind.STALL, TripKind.WALL_DEADLINE -> "SERVER_TIMEOUT"
                TripKind.MEMORY_CRITICAL -> "LMK_OOM"
                TripKind.ENGINE_DIED -> "SERVER_CRASH"
                TripKind.USER_ABORT -> null
            }
            if (fc == null) return Classified(false, null, "user abort")
            return Classified(true, fc, trip.detail.toString())
        }
        if (s.aborted) return Classified(true, "SERVER_TIMEOUT", s.transportError ?: "aborted")
        if (s.transportError != null) return Classified(true, "HTTP_ERROR", s.transportError)
        if (s.httpStatus !in 200..299) {
            return Classified(true, "HTTP_ERROR", "HTTP ${s.httpStatus}: ${s.errorBody?.take(200)}")
        }
        if (s.text.isEmpty()) return Classified(true, "EMPTY_OUTPUT", "zero tokens")
        return Classified(false, null, null)
    }

    private fun classifyStartFailure(f: LlamaServerProcess.StartResult.Failed): String {
        val sigs = process.drainSignals()
        return when {
            LlamaServerProcess.LogSignal.ALLOC_FAIL in sigs -> "LMK_OOM"
            f.reason == "BINARY_MISSING" || f.reason == "PORT_BUSY" -> "SERVER_CRASH"
            else -> "SERVER_CRASH"
        }
    }

    private fun computeTps(s: StreamResult): Double? {
        val tok = s.usage?.optInt("completion_tokens") ?: return null
        if (tok <= 0 || s.firstTokenNs <= 0) return null
        val sec = (s.endNs - s.firstTokenNs) / 1e9
        if (sec <= 0) return null
        return tok / sec
    }

    private fun extractPrompt(body: JSONObject): String {
        body.optJSONArray("messages")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                sb.append(m.optString("role")).append(": ").append(m.optString("content")).append('\n')
            }
            return sb.toString().trim()
        }
        return body.optString("prompt", body.toString().take(500))
    }

    /** Physical device RAM in MiB via ActivityManager; falls back to 0 if unavailable. */
    private fun deviceTotalRamMb(): Int {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return 0
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024L * 1024L)).toInt()
        } catch (_: Throwable) {
            0
        }
    }

    companion object {
        private const val TAG = "DynoEngine"
    }
}
