package com.situ.aichat.prompt.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device sentence embedder backed by bge-small-zh-v1.5 (int8 ONNX) via ONNX Runtime.
 *
 * Replaces iOS `NLContextualEmbedding`. GMS-free, fully local. Produces a 512-dim, L2-normalized vector
 * using **CLS pooling** (verified against the real model in Python: related Chinese pairs ≥0.65, unrelated
 * <0.55 — the 0.65 retrieval threshold separates cleanly).
 *
 * Graceful degradation: if the model/vocab asset is missing or the session fails to load, [embed] returns
 * null and the vector-memory layer simply no-ops (mirrors iOS falling back to nil when assets unavailable).
 */
@Singleton
class TextEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private class Loaded(
        val env: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: BertTokenizer,
    )

    @Volatile private var loaded: Loaded? = null

    /**
     * 批3 3-8：加载失败计数（旧实现单布尔闩=第一次失败即整进程放弃向量记忆——低内存等瞬态失败被永久化）。
     * 连续失败满 [MAX_LOAD_FAILURES] 次才闩死；期间每次调用重试加载，成功即清零。
     *
     * #2 瞬态/永久分流：只有**永久失败**（asset 缺失/损坏、模型不兼容、原生库缺失）计入本计数；
     * **瞬态失败**（内存不足 [OutOfMemoryError]）不计入 → 不因一次内存打盹把本进程的向量记忆永久关掉，
     * 下次调用自然重试（判定见 [isTransientFailure] / [nextLoadFailureCount]）。
     */
    @Volatile internal var loadFailures = 0
        private set
    private val lock = Any()

    /** 加载器最近一次结果（被动记录·**读取绝不触发加载**）——给诊断/状态展示用，绕开 [isAvailable] 会触发懒加载的副作用。 */
    enum class LoadState { NOT_ATTEMPTED, LOADED, FAILED }

    private val _loadState = MutableStateFlow(LoadState.NOT_ATTEMPTED)
    /** #3：可观测的加载状态。`NOT_ATTEMPTED`=尚未成功加载（还没被用到，或失败后仍会重试）·`LOADED`=已就绪·`FAILED`=连续永久失败已放弃。 */
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    val isAvailable: Boolean
        get() = ensureLoaded() != null

    /** 嵌入结果三态（图纸件④）：永久不可嵌（[NoContent]）与瞬态失败（[Failed]）必须分开，前者才允许写哨兵。 */
    sealed interface EmbedOutcome {
        class Ok(val vector: FloatArray) : EmbedOutcome
        /** ≤2 token 无实义内容——永久不可嵌，允许写哨兵。 */
        data object NoContent : EmbedOutcome
        /** 嵌入器未加载 / 推理异常——瞬态，绝不写哨兵。 */
        data object Failed : EmbedOutcome
    }

    /**
     * Embed [text] → 512-dim L2-normalized vector, or null if the model is unavailable.
     * Blocking ONNX inference — call from a background dispatcher.
     *
     * 只关心「有没有向量」的调用方用它；要区分**瞬态失败**与**永久不可嵌**（决定能不能写哨兵）的
     * 走 [embedDetailed]（图纸 2026-09-01 件④）。
     */
    fun embed(text: String): FloatArray? = (embedDetailed(text) as? EmbedOutcome.Ok)?.vector

    /**
     * 带失败归因的嵌入（图纸 2026-09-01 件④）：回填路必须据此决定「写永久哨兵」还是「留 NULL 待下轮」——
     * 二者混为一谈时，一次瞬态推理失败会把那条消息永久钉成「不可嵌入」，向量检索对它永远哑巴。
     */
    fun embedDetailed(text: String): EmbedOutcome {
        val l = ensureLoaded() ?: return EmbedOutcome.Failed
        val ids = l.tokenizer.encode(text, MAX_SEQUENCE_LENGTH)
        if (ids.size <= 2) return EmbedOutcome.NoContent // 只有 [CLS][SEP]，无实义内容

        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val idBuf = LongBuffer.wrap(LongArray(n) { ids[it].toLong() })
        val maskBuf = LongBuffer.wrap(LongArray(n) { 1L })
        val typeBuf = LongBuffer.wrap(LongArray(n) { 0L })

        var idTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var typeTensor: OnnxTensor? = null
        return try {
            idTensor = OnnxTensor.createTensor(l.env, idBuf, shape)
            maskTensor = OnnxTensor.createTensor(l.env, maskBuf, shape)
            typeTensor = OnnxTensor.createTensor(l.env, typeBuf, shape)
            val inputs = mapOf(
                "input_ids" to idTensor,
                "attention_mask" to maskTensor,
                "token_type_ids" to typeTensor,
            )
            l.session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val lastHidden = result[0].value as Array<Array<FloatArray>> // [1, seq, 512]
                val cls = lastHidden[0][0]                                   // CLS pooling
                EmbedOutcome.Ok(l2Normalize(cls))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "embed failed", t)
            EmbedOutcome.Failed // 推理异常 = 瞬态，绝不据此写永久哨兵
        } finally {
            idTensor?.close()
            maskTensor?.close()
            typeTensor?.close()
        }
    }

    private fun ensureLoaded(): Loaded? {
        loaded?.let { return it }
        if (loadFailures >= MAX_LOAD_FAILURES) return null
        synchronized(lock) {
            loaded?.let { return it }
            if (loadFailures >= MAX_LOAD_FAILURES) return null
            try {
                val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
                val vocab = context.assets.open(VOCAB_PATH).bufferedReader().use { reader ->
                    BertTokenizer.parseVocab(reader.lineSequence())
                }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                val session = env.createSession(modelBytes, opts)
                loaded = Loaded(env, session, BertTokenizer(vocab))
                loadFailures = 0
                _loadState.value = LoadState.LOADED
                Log.i(TAG, "bge-small-zh-v1.5 embedder loaded (vocab=${vocab.size})")
            } catch (t: Throwable) {
                // #2 瞬态失败（内存不足）不计入永久放弃闩；永久失败才累加，满 MAX 即置 FAILED（本进程放弃、冷启动重试）。
                loadFailures = nextLoadFailureCount(loadFailures, t)
                if (loadFailures >= MAX_LOAD_FAILURES) _loadState.value = LoadState.FAILED
                val kind = if (isTransientFailure(t)) "transient·不计永久闩" else "permanent"
                Log.w(TAG, "embedder load failed [$kind] ($loadFailures/$MAX_LOAD_FAILURES) — ${t.message}")
            }
        }
        return loaded
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    companion object {
        private const val TAG = "TextEmbedder"
        private const val MODEL_PATH = "models/bge-small-zh-v1.5/model_quantized.onnx"
        private const val VOCAB_PATH = "models/bge-small-zh-v1.5/vocab.txt"
        const val MAX_SEQUENCE_LENGTH = 512

        /** 批3 3-8：连续**永久**加载失败满此数才本进程放弃（防瞬态失败永久化；下次冷启动自然重试）。 */
        private const val MAX_LOAD_FAILURES = 3

        /**
         * #2：本次加载异常是否**瞬态**（内存不足）——瞬态不计入永久放弃闩，下次调用自然重试；
         * 其余（asset 缺失/损坏 `IOException`、模型不兼容 `OrtException`、原生库缺失 `UnsatisfiedLinkError`）视为永久。
         * 纯函数（无副作用/无 Android 依赖）便于 T1 断言。
         */
        internal fun isTransientFailure(t: Throwable): Boolean =
            t is OutOfMemoryError || t.cause is OutOfMemoryError

        /** #2：给定当前永久失败计数与本次异常，算出新计数（瞬态失败不变·永久失败 +1）。纯函数便于 T1 断言。 */
        internal fun nextLoadFailureCount(current: Int, t: Throwable): Int =
            if (isTransientFailure(t)) current else current + 1
    }
}
