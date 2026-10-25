package com.kojot.dyno

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Minimal GGUF metadata reader (v2/v3). Reads only the header KV section and stops at the first
 * `tokenizer.*` key (they are written last and hold the huge vocab arrays), so a multi-GB file is
 * identified by reading a few KiB. Zero copies of the model are made.
 */
object GgufMeta {

    data class Info(
        val file: File, val sizeBytes: Long, val mtimeMs: Long, val fingerprint: String,
        val architecture: String?, val modelFamily: String, val paramSizeB: Double?,
        val quantType: String, val ftypeId: Int?,
        val nLayer: Int?, val nEmbd: Int?, val nHead: Int?, val nHeadKv: Int?, val nHeadKvPerLayer: List<Int>?,
        val keyLength: Int?, val valueLength: Int?, val ctxTrain: Int?,
        val ropeFreqBase: Double?, val ropeScalingFactor: Double?,
        val metaJson: String,
    )

    private val FTYPE = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
        16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
        22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
        28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16",
    )
    private val QUANT_FROM_NAME = Regex("(?i)(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|BF16|F16|F32)")
    private val SIZE_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*([BMK])", RegexOption.IGNORE_CASE)

    fun read(file: File): Info {
        require(file.isFile && file.canRead()) { "Cannot read ${file.path}" }
        val kv = LinkedHashMap<String, Any?>()
        LeIn(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { r ->
            val magic = ByteArray(4); r.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "GGUF") { "Not a GGUF file" }
            val version = r.u32()
            require(version in 2..3) { "Unsupported GGUF version $version" }
            r.u64() // tensor count
            val kvCount = r.u64()
            var n = 0L
            while (n < kvCount) {
                val key = r.str()
                val type = r.u32().toInt()
                if (key.startsWith("tokenizer.")) break
                readValue(r, type)?.let { kv[key] = it }
                n++
            }
        }

        val arch = kv["general.architecture"] as? String
        fun k(suffix: String) = arch?.let { "$it.$suffix" }
        fun long(key: String?): Long? = key?.let { kv[it] as? Long }
        fun dbl(key: String?): Double? = when (val v = key?.let { kv[it] }) { is Double -> v; is Long -> v.toDouble(); else -> null }

        val nHeadKvRaw = k("attention.head_count_kv")?.let { kv[it] }
        @Suppress("UNCHECKED_CAST")
        val perLayer = (nHeadKvRaw as? List<Long>)?.map { it.toInt() }
        val nHeadKv = (nHeadKvRaw as? Long)?.toInt() ?: perLayer?.maxOrNull()

        val ftype = long("general.file_type")?.toInt()
        val quant = ftype?.let { FTYPE[it] }
            ?: QUANT_FROM_NAME.find(file.name)?.value?.uppercase()
            ?: "UNKNOWN"

        val familyRaw = (kv["general.basename"] as? String) ?: (kv["general.name"] as? String) ?: arch ?: file.nameWithoutExtension
        val family = familyRaw.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-')

        val sizeLabel = kv["general.size_label"] as? String
        val paramB = parseSize(sizeLabel) ?: parseSize(file.nameWithoutExtension)

        val meta = JSONObject().apply {
            for ((key, v) in kv) put(key, if (v is List<*>) JSONArray(v) else v)
        }

        return Info(
            file = file, sizeBytes = file.length(), mtimeMs = file.lastModified(), fingerprint = fingerprint(file),
            architecture = arch, modelFamily = family, paramSizeB = paramB, quantType = quant, ftypeId = ftype,
            nLayer = long(k("block_count"))?.toInt(), nEmbd = long(k("embedding_length"))?.toInt(),
            nHead = long(k("attention.head_count"))?.toInt(), nHeadKv = nHeadKv, nHeadKvPerLayer = perLayer,
            keyLength = long(k("attention.key_length"))?.toInt(), valueLength = long(k("attention.value_length"))?.toInt(),
            ctxTrain = long(k("context_length"))?.toInt(),
            ropeFreqBase = dbl(k("rope.freq_base")), ropeScalingFactor = dbl(k("rope.scaling.factor")),
            metaJson = meta.toString(),
        )
    }

    private fun parseSize(s: String?): Double? {
        val m = s?.let { SIZE_RE.find(it) } ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) { "B" -> v; "M" -> v / 1000.0; "K" -> v / 1_000_000.0; else -> null }
    }

    /** Cheap identity: SHA-256 over first 4 MiB + last 1 MiB + size. Reads ~5 MiB regardless of model size. */
    fun fingerprint(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val head = ByteArray(minOf(len, 4L shl 20).toInt())
            raf.readFully(head); md.update(head)
            if (len > head.size) {
                val tailLen = minOf(len - head.size, 1L shl 20).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(len - tailLen); raf.readFully(tail); md.update(tail)
            }
            md.update(len.toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- value decoding ---------------------------------------------------------------------------

    private fun scalarSize(type: Int): Int = when (type) {
        0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> -1
    }

    private fun readScalar(r: LeIn, type: Int): Any? = when (type) {
        0 -> r.u8().toLong()
        1 -> r.u8().toByte().toLong()
        2 -> r.u16().toLong()
        3 -> r.u16().toShort().toLong()
        4 -> r.u32()
        5 -> r.u32().toInt().toLong()
        6 -> r.f32().toDouble()
        7 -> r.u8() != 0
        8 -> r.str()
        10, 11 -> r.u64()
        12 -> r.f64()
        else -> throw IllegalStateException("Unknown GGUF value type $type")
    }

    private fun readValue(r: LeIn, type: Int): Any? {
        if (type != 9) return readScalar(r, type)
        val elemType = r.u32().toInt()
        val count = r.u64()
        if (elemType == 8) { repeat(count.toInt()) { r.skip(r.u64()) }; return null }   // string arrays: skip
        val sz = scalarSize(elemType)
        check(sz > 0) { "Bad array element type $elemType" }
        if (count > 4096) { r.skip(count * sz); return null }                           // large numeric arrays: skip
        return List(count.toInt()) { readScalar(r, elemType) }
    }

    private class LeIn(private val ins: InputStream) : Closeable {
        private val arr = ByteArray(8)
        private val bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
        private fun fill(n: Int) {
            var off = 0
            while (off < n) { val c = ins.read(arr, off, n - off); if (c < 0) throw EOFException(); off += c }
        }
        fun readFully(dst: ByteArray) {
            var off = 0
            while (off < dst.size) { val c = ins.read(dst, off, dst.size - off); if (c < 0) throw EOFException(); off += c }
        }
        fun u8(): Int { fill(1); return arr[0].toInt() and 0xFF }
        fun u16(): Int { fill(2); return bb.getShort(0).toInt() and 0xFFFF }
        fun u32(): Long { fill(4); return bb.getInt(0).toLong() and 0xFFFFFFFFL }
        fun u64(): Long { fill(8); return bb.getLong(0) }
        fun f32(): Float { fill(4); return bb.getFloat(0) }
        fun f64(): Double { fill(8); return bb.getDouble(0) }
        fun str(): String {
            val len = u64()
            require(len in 0..(1L shl 24)) { "Implausible GGUF string length $len" }
            val b = ByteArray(len.toInt()); readFully(b)
            return String(b, Charsets.UTF_8)
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = ins.skip(left)
                if (s <= 0) { if (ins.read() < 0) throw EOFException(); left-- } else left -= s
            }
        }
        override fun close() = ins.close()
    }
}

/**
 * Heuristic launch-time memory model. The key Android insight: with mmap the weights are file-backed
 * and reclaimable, so what the LMK can actually be provoked by is the ANONYMOUS demand (KV cache +
 * compute buffers). Weights that do not fit in the page-cache budget do not crash; they thrash
 * (major-fault storm -> TTFT / tok/s collapse). Hence three verdicts.
 */
object Preflight {

    enum class Verdict { OK, RISKY_THRASH, REFUSE, UNKNOWN }

    data class Result(
        val verdict: Verdict, val modelMb: Double, val kvMb: Double?, val computeMb: Double,
        val anonNeedMb: Double, val residentNeedMb: Double, val budgetMb: Double, val note: String,
    )

    private const val MB = 1024.0 * 1024.0

    fun bytesPerElem(cacheType: String): Double = when (cacheType.lowercase()) {
        "f32" -> 4.0; "f16", "bf16" -> 2.0; "q8_0" -> 34.0 / 32; "q5_1" -> 24.0 / 32; "q5_0" -> 22.0 / 32
        "q4_1" -> 20.0 / 32; "q4_0", "iq4_nl" -> 18.0 / 32; else -> 2.0
    }

    /** KV cache bytes for a context of [ctx] tokens, or null if metadata is incomplete. */
    fun kvBytes(i: GgufMeta.Info, ctx: Int, typeK: String, typeV: String): Double? {
        val nl = i.nLayer ?: return null
        val nHead = i.nHead ?: return null
        val nEmbd = i.nEmbd ?: return null
        val dK = i.keyLength ?: (nEmbd / nHead)
        val dV = i.valueLength ?: (nEmbd / nHead)
        val sumKvHeads = i.nHeadKvPerLayer?.sum() ?: ((i.nHeadKv ?: nHead) * nl)
        return ctx.toDouble() * sumKvHeads * (dK * bytesPerElem(typeK) + dV * bytesPerElem(typeV))
    }

    fun assess(i: GgufMeta.Info, cfg: LaunchConfig, v: Vitals): Result {
        val modelMb = i.sizeBytes / MB
        val kvMb = kvBytes(i, cfg.ctxSize, cfg.cacheTypeK, cfg.cacheTypeV)?.div(MB)
        // Compute scratch: fixed overhead + (without flash-attn) the KQ matrix n_kv * n_ubatch * n_head * 4B.
        val fa = cfg.flashAttn == "on"
        val kqMb = if (!fa && i.nHead != null) cfg.ctxSize.toDouble() * cfg.ubatchSize * i.nHead * 4 / MB else 0.0
        val computeMb = 128.0 + kqMb + (cfg.ubatchSize / 512.0) * 96.0
        val budget = maxOf(0.0, minOf(v.lmkHeadroomMb.toDouble(), v.memAvailMb - 256.0))

        if (kvMb == null) {
            return Result(Verdict.UNKNOWN, modelMb, null, computeMb, Double.NaN, Double.NaN, budget,
                "GGUF metadata incomplete; KV size unknown")
        }
        val weightsAnon = !cfg.mmap || cfg.mlock
        val anon = kvMb + computeMb + if (weightsAnon) modelMb else 0.0
        val resident = kvMb + computeMb + modelMb
        val verdict = when {
            anon > budget * 0.95 -> Verdict.REFUSE
            resident > budget * 0.90 -> Verdict.RISKY_THRASH
            else -> Verdict.OK
        }
        val note = "anon=%.0fMB resident=%.0fMB budget=%.0fMB (kv=%.0f compute=%.0f weights=%.0f, mmap=%s)"
            .format(anon, resident, budget, kvMb, computeMb, modelMb, cfg.mmap)
        return Result(verdict, modelMb, kvMb, computeMb, anon, resident, budget, note)
    }
}
