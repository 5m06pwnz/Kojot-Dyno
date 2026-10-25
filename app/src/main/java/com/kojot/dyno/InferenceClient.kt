package com.kojot.dyno

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HttpURLConnection against llama-server on loopback. Cleartext to 127.0.0.1 must be allowed in
 * network_security_config (see SETUP.md) or every call here fails on Android 9+.
 */
object LocalHttp {
    data class Result(val status: Int, val body: String?, val error: String?)

    fun request(port: Int, method: String, path: String, body: String? = null, connectMs: Int = 1000, readMs: Int = 2000): Result {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val st = c.responseCode
            val stream = if (st in 200..299) c.inputStream else c.errorStream
            Result(st, stream?.bufferedReader()?.use { it.readText() }, null)
        } catch (e: IOException) {
            Result(-1, null, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            c?.disconnect()
        }
    }
}

/** Handle that lets another thread (the circuit breaker) tear down an in-flight request. */
class InferenceCall {
    @Volatile var conn: HttpURLConnection? = null
    @Volatile var aborted = false
    fun abort() {
        aborted = true
        try { conn?.disconnect() } catch (_: Throwable) {}
    }
}

class StreamResult(
    val httpStatus: Int,
    val text: String,
    val finishReason: String?,
    val timings: JSONObject?,
    val usage: JSONObject?,
    val chunks: Int,
    val startNs: Long,
    val firstTokenNs: Long,
    val endNs: Long,
    val aborted: Boolean,
    val transportError: String?,
    val errorBody: String?,
)

class InferenceClient {

    private fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else optString(k)

    /**
     * Streams one completion. Works with /v1/chat/completions and /completion. Reasoning deltas are wrapped
     * in <think>...</think> so raw_output reflects what the model actually emitted.
     * The reading thread feeds [breaker]; the breaker aborts the request through [call].
     */
    suspend fun stream(
        port: Int, path: String, body: JSONObject, call: InferenceCall, breaker: CircuitBreaker,
        onText: (String) -> Unit,
    ): StreamResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val sb = StringBuilder()
        var status = -1
        var finish: String? = null
        var timings: JSONObject? = null
        var usage: JSONObject? = null
        var chunks = 0
        var firstNs = 0L
        var transportErr: String? = null
        var errBody: String? = null
        var inThink = false
        try {
            body.put("stream", true)
            if (!body.has("cache_prompt")) body.put("cache_prompt", false)   // reproducible prefill timings
            if (path.contains("chat") && !body.has("stream_options")) body.put("stream_options", JSONObject().put("include_usage", true))
            val bytes = body.toString().toByteArray(Charsets.UTF_8)

            val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 0                      // liveness is the breaker's job (phase-aware), not a socket timeout
            c.doOutput = true
            c.useCaches = false
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.setFixedLengthStreamingMode(bytes.size)
            call.conn = c
            if (call.aborted) { c.disconnect(); throw IOException("aborted before connect") }

            c.outputStream.use { it.write(bytes) }
            status = c.responseCode
            if (status !in 200..299) {
                errBody = c.errorStream?.bufferedReader()?.use { it.readText() }
            } else {
                c.inputStream.bufferedReader().use { r ->
                    for (line in r.lineSequence()) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break
                        val j = try { JSONObject(payload) } catch (_: JSONException) { continue }
                        if (j.has("error")) { errBody = j.opt("error").toString(); continue }

                        var piece: String? = null
                        val choice = j.optJSONArray("choices")?.optJSONObject(0)
                        if (choice != null) {
                            val d = choice.optJSONObject("delta")
                            val rc = d?.strOrNull("reasoning_content")
                            val ct = d?.strOrNull("content")
                            if (!rc.isNullOrEmpty()) {
                                if (!inThink) { piece = "<think>"; inThink = true }
                                piece = (piece ?: "") + rc
                            }
                            if (!ct.isNullOrEmpty()) {
                                if (inThink) { piece = (piece ?: "") + "</think>"; inThink = false }
                                piece = (piece ?: "") + ct
                            }
                            choice.strOrNull("finish_reason")?.let { finish = it }
                        } else {
                            j.strOrNull("content")?.takeIf { it.isNotEmpty() }?.let { piece = it }
                            if (j.optBoolean("stop", false)) finish = j.strOrNull("stop_type") ?: "stop"
                        }
                        j.optJSONObject("timings")?.let { timings = it }
                        j.optJSONObject("usage")?.let { usage = it }

                        val p = piece
                        if (p != null) {
                            if (firstNs == 0L) firstNs = System.nanoTime()
                            chunks++
                            sb.append(p)
                            breaker.onChunk(p)
                            onText(p)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            call.abort(); throw e
        } catch (e: IOException) {
            if (!call.aborted) transportErr = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { call.conn?.disconnect() } catch (_: Throwable) {}
        }
        StreamResult(status, sb.toString(), finish, timings, usage, chunks, startNs, firstNs, System.nanoTime(),
            call.aborted, transportErr, errBody)
    }

    /**
     * Prompt token estimate (+-2: chat templates may add/strip BOS). Chat requests go through
     * /apply-template then /tokenize. Authoritative counts come from the final usage/timings chunk.
     */
    suspend fun estimatePromptTokens(port: Int, endpoint: String, body: JSONObject): Int? = withContext(Dispatchers.IO) {
        try {
            val text: String = if (endpoint.contains("chat")) {
                val msgs = body.optJSONArray("messages") ?: return@withContext null
                val r = LocalHttp.request(port, "POST", "/apply-template", JSONObject().put("messages", msgs).toString(), readMs = 10_000)
                if (r.status != 200 || r.body == null) return@withContext null
                JSONObject(r.body).optString("prompt").takeIf { it.isNotEmpty() } ?: return@withContext null
            } else body.optString("prompt", "")
            val t = LocalHttp.request(port, "POST", "/tokenize",
                JSONObject().put("content", text).put("parse_special", true).toString(), readMs = 20_000)
            if (t.status != 200 || t.body == null) return@withContext null
            JSONObject(t.body).optJSONArray("tokens")?.length()
        } catch (_: JSONException) { null }
    }
}
