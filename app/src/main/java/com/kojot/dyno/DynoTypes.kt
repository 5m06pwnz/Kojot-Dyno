package com.kojot.dyno

import android.content.ContentValues
import android.os.PowerManager
import org.json.JSONObject

/** ENFORCE refuses launches the memory model says cannot fit; RECORD_ONLY launches anyway (stress mode). */
enum class PreflightMode { ENFORCE, RECORD_ONLY }

/** What to do when prompt_tokens + n_predict would exceed ctx_size. ALLOW lets the server's behaviour be the data. */
enum class OverflowPolicy { ALLOW, CLAMP, REFUSE }

/**
 * llama-server launch configuration. Fixed for the lifetime of one server process (and therefore one test run).
 * Flag spellings target recent llama.cpp builds; older builds may need [extraArgs] tweaks (e.g. `-fa`).
 */
data class LaunchConfig(
    val modelPath: String,
    val ctxSize: Int,
    val threads: Int,
    val threadsBatch: Int = threads,
    val batchSize: Int = 512,
    val ubatchSize: Int = 512,
    val parallel: Int = 1,
    val port: Int = 8080,
    /** "on" | "off" | "auto" | null (omit flag). */
    val flashAttn: String? = null,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    val nGpuLayers: Int = 0,
    val ropeFreqBase: Float? = null,
    val ropeFreqScale: Float? = null,
    val extraArgs: List<String> = emptyList(),
    val preflight: PreflightMode = PreflightMode.ENFORCE,
    val readyTimeoutMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("modelPath", modelPath); put("ctxSize", ctxSize); put("threads", threads)
        put("threadsBatch", threadsBatch); put("batchSize", batchSize); put("ubatchSize", ubatchSize)
        put("parallel", parallel); put("port", port); put("flashAttn", flashAttn ?: JSONObject.NULL)
        put("cacheTypeK", cacheTypeK); put("cacheTypeV", cacheTypeV); put("mmap", mmap); put("mlock", mlock)
        put("nGpuLayers", nGpuLayers); put("ropeFreqBase", ropeFreqBase?.toDouble() ?: JSONObject.NULL)
        put("ropeFreqScale", ropeFreqScale?.toDouble() ?: JSONObject.NULL)
        put("extraArgs", org.json.JSONArray(extraArgs)); put("preflight", preflight.name)
    }

    companion object {
        fun fromJson(j: JSONObject): LaunchConfig {
            val threads = j.optInt("threads", 4)
            return LaunchConfig(
                modelPath = j.getString("modelPath"),
                ctxSize = j.getInt("ctxSize"),
                threads = threads,
                threadsBatch = j.optInt("threadsBatch", threads),
                batchSize = j.optInt("batchSize", 512),
                ubatchSize = j.optInt("ubatchSize", 512),
                parallel = j.optInt("parallel", 1),
                port = j.optInt("port", 8080),
                flashAttn = if (j.isNull("flashAttn")) null else j.optString("flashAttn"),
                cacheTypeK = j.optString("cacheTypeK", "f16"),
                cacheTypeV = j.optString("cacheTypeV", "f16"),
                mmap = j.optBoolean("mmap", true),
                mlock = j.optBoolean("mlock", false),
                nGpuLayers = j.optInt("nGpuLayers", 0),
                ropeFreqBase = if (j.isNull("ropeFreqBase")) null else j.optDouble("ropeFreqBase").toFloat(),
                ropeFreqScale = if (j.isNull("ropeFreqScale")) null else j.optDouble("ropeFreqScale").toFloat(),
                extraArgs = j.optJSONArray("extraArgs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                preflight = runCatching { PreflightMode.valueOf(j.optString("preflight", "ENFORCE")) }.getOrDefault(PreflightMode.ENFORCE),
                readyTimeoutMs = if (j.has("readyTimeoutMs") && !j.isNull("readyTimeoutMs")) j.getLong("readyTimeoutMs") else null,
            )
        }
    }
}

/** Watchdog / supervision knobs. Set preemptiveKillHeadroomMb = 0 to let the real LMK have its way (stress mode). */
data class EnginePolicy(
    /** Kill llama-server ourselves when LMK headroom drops below this (MiB). 0 disables. */
    val preemptiveKillHeadroomMb: Float = 200f,
    val autoRespawn: Boolean = true,
    val maxRespawns: Int = 3,
    val respawnBackoffMs: Long = 2_000,
    val vitalsPeriodMs: Long = 200,
    val heartbeatMs: Long = 1_000,
    val cooldownBatteryC: Float = 37f,
    val cooldownMaxThermal: Int = PowerManager.THERMAL_STATUS_LIGHT,
    val cooldownTimeoutMs: Long = 600_000,
)

/** One request/response turn. [body] is the exact JSON sent to llama-server (stream is forced on). */
data class TurnSpec(
    val caseId: String,
    val body: JSONObject,
    val endpoint: String = "/v1/chat/completions",
    /** Human-readable prompt persisted as raw_prompt; defaults to messages/prompt from [body]. */
    val rawPrompt: String? = null,
    val grammarMode: String = "NONE",
    val caseParams: JSONObject? = null,
    val ttftDeadlineMs: Long? = null,
    val stallMs: Long = 30_000,
    val wallDeadlineMs: Long = 600_000,
    val overflowPolicy: OverflowPolicy = OverflowPolicy.ALLOW,
    val loopDetection: Boolean = true,
    /** true: no validator will follow, so the turn is COMPLETE as soon as generation ends. */
    val autoComplete: Boolean = false,
    val trajectoryId: String? = null,
    val parentTurnId: Long? = null,
    val agentStep: Int? = null,
    val toolName: String? = null,
    val faultInjected: String? = null,
) {
    companion object {
        fun fromJson(j: JSONObject) = TurnSpec(
            caseId = j.getString("caseId"),
            body = j.getJSONObject("body"),
            endpoint = j.optString("endpoint", "/v1/chat/completions"),
            rawPrompt = if (j.isNull("rawPrompt")) null else j.optString("rawPrompt"),
            grammarMode = j.optString("grammarMode", "NONE"),
            caseParams = j.optJSONObject("caseParams"),
            ttftDeadlineMs = if (j.has("ttftDeadlineMs") && !j.isNull("ttftDeadlineMs")) j.getLong("ttftDeadlineMs") else null,
            stallMs = j.optLong("stallMs", 30_000),
            wallDeadlineMs = j.optLong("wallDeadlineMs", 600_000),
            overflowPolicy = runCatching { OverflowPolicy.valueOf(j.optString("overflowPolicy", "ALLOW")) }.getOrDefault(OverflowPolicy.ALLOW),
            loopDetection = j.optBoolean("loopDetection", true),
            autoComplete = j.optBoolean("autoComplete", false),
            trajectoryId = if (j.isNull("trajectoryId")) null else j.optString("trajectoryId"),
            parentTurnId = if (j.has("parentTurnId") && !j.isNull("parentTurnId")) j.getLong("parentTurnId") else null,
            agentStep = if (j.has("agentStep") && !j.isNull("agentStep")) j.getInt("agentStep") else null,
            toolName = if (j.isNull("toolName")) null else j.optString("toolName"),
            faultInjected = if (j.isNull("faultInjected")) null else j.optString("faultInjected"),
        )
    }
}

data class TurnOutcome(
    val turnId: Long,
    val turnIndex: Int,
    val state: String,
    val isFailure: Boolean,
    val failureClass: String?,
    val message: String?,
    val output: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val ttftMs: Double?,
    val tokPerSec: Double?,
    val finishReason: String?,
) {
    fun toJson() = JSONObject().apply {
        put("turnId", turnId); put("turnIndex", turnIndex); put("state", state); put("isFailure", isFailure)
        put("failureClass", failureClass ?: JSONObject.NULL); put("message", message ?: JSONObject.NULL)
        put("output", output); put("promptTokens", promptTokens ?: JSONObject.NULL)
        put("completionTokens", completionTokens ?: JSONObject.NULL); put("ttftMs", ttftMs ?: JSONObject.NULL)
        put("tokPerSec", tokPerSec ?: JSONObject.NULL); put("finishReason", finishReason ?: JSONObject.NULL)
    }
}

sealed interface RunStart {
    data class Started(
        val runId: Long, val modelId: Long, val pid: Int, val loadMs: Long,
        val buildInfo: String?, val preflight: Preflight.Result,
    ) : RunStart

    data class Failed(val runId: Long?, val reason: String, val detail: String?) : RunStart
}

data class RecoverySummary(val runsMarkedCrashed: Int, val turnsRecovered: Int, val turnsUnjudged: Int, val orphansKilled: Int) {
    val any get() = runsMarkedCrashed + turnsRecovered + turnsUnjudged + orphansKilled > 0
}

/** Everything the UI layer can observe. NativeBridge serialises these onto the WebView message channel. */
sealed interface DynoEvent {
    data class EngineState(val state: String, val pid: Int?) : DynoEvent
    data class EngineLog(val line: String, val signal: String?) : DynoEvent
    data class RunStatus(val runId: Long, val status: String, val detail: String?) : DynoEvent
    data class TurnStarted(val turnId: Long, val turnIndex: Int, val caseId: String) : DynoEvent
    data class Token(val turnId: Long, val text: String) : DynoEvent
    data class VitalsTick(val v: Vitals) : DynoEvent
    data class BreakerTrip(val turnId: Long, val kind: String, val detail: JSONObject) : DynoEvent
    data class TurnFinished(val outcome: TurnOutcome) : DynoEvent
    data class Cooldown(val batteryC: Float?, val thermalStatus: Int, val waitedMs: Long, val done: Boolean) : DynoEvent
    data class Recovery(val summary: RecoverySummary) : DynoEvent
    data class Notice(val level: String, val message: String) : DynoEvent
}

internal fun cvOf(vararg p: Pair<String, Any?>): ContentValues = ContentValues().apply {
    for ((k, v) in p) when (v) {
        null -> putNull(k)
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Double -> put(k, v)
        is Float -> put(k, v)
        is Boolean -> put(k, if (v) 1 else 0)
        else -> put(k, v.toString())
    }
}
