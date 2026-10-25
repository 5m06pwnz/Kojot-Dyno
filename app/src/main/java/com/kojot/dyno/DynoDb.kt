package com.kojot.dyno

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * dyno.db opener. WAL is on (readers never block the single writer); foreign keys are on.
 * Schema is idempotent and re-applied on create/upgrade; views are re-created on every writable open
 * so editing dyno_views.sql never needs a version bump.
 */
class DynoDb(private val appContext: Context) : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = applyAsset(db, SCHEMA_ASSET)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Forward-only. Add ALTER TABLE migrations here keyed on oldVersion; IF NOT EXISTS DDL self-heals new tables.
        applyAsset(db, SCHEMA_ASSET)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) applyAsset(db, VIEWS_ASSET)
    }

    private fun applyAsset(db: SQLiteDatabase, asset: String) {
        val script = appContext.assets.open(asset).bufferedReader().use { it.readText() }
        db.beginTransaction()
        try {
            for (stmt in splitSql(script)) db.execSQL(stmt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "dyno.db"
        const val DB_VERSION = 1
        private const val SCHEMA_ASSET = "dyno_schema.sql"
        private const val VIEWS_ASSET = "dyno_views.sql"
    }
}

/**
 * Statement splitter that understands CREATE TRIGGER ... BEGIN ... END; blocks.
 * Constraints on the .sql files: comments on their own line, statements end with ';' at end of line.
 */
internal fun splitSql(script: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var inTrigger = false
    for (raw in script.lineSequence()) {
        val line = raw.trimEnd()
        val t = line.trim()
        if (cur.isEmpty() && (t.isEmpty() || t.startsWith("--"))) continue
        if (cur.isEmpty()) inTrigger = t.uppercase().startsWith("CREATE TRIGGER")
        cur.append(line).append('\n')
        val end = if (inTrigger) t.equals("END;", ignoreCase = true) else t.endsWith(";")
        if (end) {
            out += cur.toString().trim().removeSuffix(";")
            cur.clear()
            inTrigger = false
        }
    }
    require(cur.isBlank()) { "Unterminated SQL statement: ${cur.take(80)}" }
    return out
}

/**
 * Single-threaded write-behind queue. All writes funnel through one thread and are committed in batches.
 *
 * Durability model: committed WAL frames live in the kernel page cache, so they survive an LMK kill of
 * this process (only a kernel panic / power loss can lose them). The only exposure is the in-memory queue,
 * which is drained continuously; [call] waits for the commit and is used for rows that must exist before
 * risky work begins (turn STARTED rows, run rows, finalised failures).
 */
class DbWriter(private val helper: DynoDb, scope: CoroutineScope) {

    private class Op(val block: (SQLiteDatabase) -> Any?, val done: CompletableDeferred<Any?>?)

    private val queue = Channel<Op>(Channel.UNLIMITED)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dyno-db-writer").apply { isDaemon = true } }

    init {
        scope.launch(executor.asCoroutineDispatcher()) { drainLoop() }
    }

    fun post(block: (SQLiteDatabase) -> Unit) {
        queue.trySend(Op(block, null))
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> call(block: (SQLiteDatabase) -> T): T {
        val done = CompletableDeferred<Any?>()
        queue.trySend(Op(block, done))
        return done.await() as T
    }

    suspend fun flush() = call { }

    fun shutdown() {
        queue.close()
        executor.shutdown()
    }

    private suspend fun drainLoop() {
        val db = helper.writableDatabase
        for (first in queue) {
            val batch = ArrayList<Op>(32).apply { add(first) }
            while (batch.size < 128) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch += next
            }
            val results = arrayOfNulls<Any>(batch.size)
            val errors = arrayOfNulls<Throwable>(batch.size)
            var commitError: Throwable? = null
            db.beginTransaction()
            try {
                batch.forEachIndexed { i, op ->
                    try { results[i] = op.block(db) } catch (t: Throwable) {
                        errors[i] = t
                        if (op.done == null) Log.w(TAG, "async write failed", t)
                    }
                }
                db.setTransactionSuccessful()
            } catch (t: Throwable) {
                commitError = t
            } finally {
                try { db.endTransaction() } catch (t: Throwable) { commitError = commitError ?: t }
            }
            batch.forEachIndexed { i, op ->
                val err = errors[i] ?: commitError
                if (err != null) op.done?.completeExceptionally(err) else op.done?.complete(results[i])
            }
        }
    }

    private companion object { const val TAG = "DbWriter" }
}

private fun SQLiteDatabase.exec(sql: String, vararg args: Any?): Int {
    val st = compileStatement(sql)
    try {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                is Double -> st.bindDouble(i + 1, a)
                is Float -> st.bindDouble(i + 1, a.toDouble())
                else -> st.bindString(i + 1, a.toString())
            }
        }
        return st.executeUpdateDelete()
    } finally {
        st.close()
    }
}

/** Typed access to dyno.db. Writes go through [DbWriter]; reads use the WAL reader pool. */
class DynoDao(private val helper: DynoDb, private val w: DbWriter) {

    suspend fun flush() = w.flush()

    suspend fun upsertModel(i: GgufMeta.Info): Long = w.call { db ->
        val cv = cvOf(
            "file_path" to i.file.absolutePath, "file_name" to i.file.name,
            "size_bytes" to i.sizeBytes, "mtime_ms" to i.mtimeMs, "fingerprint" to i.fingerprint,
            "architecture" to i.architecture, "model_family" to i.modelFamily, "param_size_b" to i.paramSizeB,
            "quant_type" to i.quantType, "ftype_id" to i.ftypeId, "n_layer" to i.nLayer, "n_embd" to i.nEmbd,
            "n_head" to i.nHead, "n_head_kv" to i.nHeadKv, "ctx_train" to i.ctxTrain,
            "key_length" to i.keyLength, "value_length" to i.valueLength,
            "rope_freq_base" to i.ropeFreqBase, "rope_scaling_factor" to i.ropeScalingFactor,
            "gguf_meta_json" to i.metaJson, "first_seen_ms" to System.currentTimeMillis(),
        )
        db.insertWithOnConflict("models", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        // A moved/renamed file keeps its identity via the fingerprint.
        db.exec("UPDATE models SET file_path=?, file_name=?, mtime_ms=? WHERE fingerprint=?",
            i.file.absolutePath, i.file.name, i.mtimeMs, i.fingerprint)
        db.rawQuery("SELECT model_id FROM models WHERE fingerprint=?", arrayOf(i.fingerprint)).use { c ->
            check(c.moveToFirst()) { "model row missing after upsert" }
            c.getLong(0)
        }
    }

    suspend fun ensureSuite(name: String, category: String = "CUSTOM"): Long = w.call { db ->
        db.rawQuery("SELECT suite_id FROM test_suites WHERE name=? ORDER BY version DESC LIMIT 1", arrayOf(name)).use { c ->
            if (c.moveToFirst()) return@call c.getLong(0)
        }
        db.insertOrThrow("test_suites", null,
            cvOf("name" to name, "version" to 1, "category" to category, "created_at_ms" to System.currentTimeMillis()))
    }

    suspend fun insertRun(cv: ContentValues): Long = w.call { db -> db.insertOrThrow("test_runs", null, cv) }

    fun postRunUpdate(runId: Long, cv: ContentValues) =
        w.post { db -> db.update("test_runs", cv, "run_id=?", arrayOf(runId.toString())) }

    suspend fun updateRunSync(runId: Long, cv: ContentValues) =
        w.call { db -> db.update("test_runs", cv, "run_id=?", arrayOf(runId.toString())) }

    suspend fun insertTurn(cv: ContentValues): Long = w.call { db -> db.insertOrThrow("telemetry_turns", null, cv) }

    fun postHeartbeat(turnId: Long, cv: ContentValues) =
        w.post { db -> db.update("telemetry_turns", cv, "turn_id=? AND turn_state='STARTED'", arrayOf(turnId.toString())) }

    suspend fun finalizeTurn(turnId: Long, cv: ContentValues) =
        w.call { db -> db.update("telemetry_turns", cv, "turn_id=?", arrayOf(turnId.toString())) }

    /**
     * Applies the validator's verdict. Engine-level failures lock the row: only turns still in GENERATED
     * with is_failure=0 accept a verdict. Returns true if applied.
     */
    suspend fun applyVerdict(turnId: Long, isFailure: Boolean, failureClass: String?, message: String?, detailJson: String?): Boolean =
        w.call { db ->
            fun apply(cls: String?): Int = db.exec(
                """UPDATE telemetry_turns
                      SET turn_state='COMPLETE', is_failure=?, failure_class=?, failure_source=?,
                          parsed_error_message=?, failure_detail_json=?
                    WHERE turn_id=? AND turn_state='GENERATED' AND is_failure=0""",
                if (isFailure) 1 else 0, if (isFailure) cls else null, if (isFailure) "VALIDATOR" else null,
                message, detailJson, turnId)
            val cls = if (isFailure) (failureClass ?: "UNKNOWN") else null
            val n = try { apply(cls) } catch (e: SQLiteConstraintException) { apply("UNKNOWN") }  // FK: unknown class name
            n > 0
        }

    fun logSystemEvent(kind: String, runId: Long?, value: Float?, detailJson: String?) =
        w.post { db ->
            db.insert("system_events", null, cvOf(
                "ts_ms" to System.currentTimeMillis(), "mono_us" to SystemClock.elapsedRealtimeNanos() / 1000,
                "run_id" to runId, "kind" to kind, "value" to value, "detail_json" to detailJson))
        }

    /**
     * Boot-time reconciliation after an unclean death (LMK, crash, swipe-away):
     *  - live runs become CRASHED
     *  - STARTED turns become RECOVERED_CRASH failures, classified from the last heartbeat
     *    (LMK_OOM if the last sample showed little headroom, else SERVER_CRASH)
     *  - GENERATED turns that never got a verdict become UNJUDGED (excluded from statistics)
     */
    suspend fun recoverAfterCrash(orphansKilled: Int): RecoverySummary = w.call { db ->
        val now = System.currentTimeMillis()
        val turns = db.exec(
            """UPDATE telemetry_turns
                  SET turn_state='RECOVERED_CRASH', is_failure=1, failure_source='RECOVERY',
                      failure_class=CASE WHEN COALESCE(lmk_headroom_mb_min, 1e9) < 400
                                           OR COALESCE(mem_available_mb_min, 1e9) < 600
                                         THEN 'LMK_OOM' ELSE 'SERVER_CRASH' END,
                      parsed_error_message='Process died mid-turn; reconstructed from last heartbeat',
                      failure_detail_json=json_object('recovered', 1, 'last_heartbeat_ms', heartbeat_ms,
                                                      'lmk_headroom_mb_min', lmk_headroom_mb_min,
                                                      'mem_available_mb_min', mem_available_mb_min)
                WHERE turn_state='STARTED'""")
        val unjudged = db.exec("UPDATE telemetry_turns SET turn_state='UNJUDGED' WHERE turn_state='GENERATED'")
        val runs = db.exec(
            "UPDATE test_runs SET status='CRASHED', ended_at_ms=?, abort_reason='APP_PROCESS_DIED' WHERE status IN ('STARTING','RUNNING')", now)
        RecoverySummary(runs, turns, unjudged, orphansKilled)
    }
}
